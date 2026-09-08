package chat.stoat.composables.voice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.stoat.R
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.track.DataPublishReliability
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import io.livekit.android.compose.types.TrackReference
import io.livekit.android.room.track.VideoTrack
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink

import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs

/**
 * Be On It: draw / laser-pointer overlay for a screen-share tile in a call.
 *
 * Mirrors the web `AnnotationOverlay` — strokes and laser positions are
 * broadcast to everyone else in the call over LiveKit's data channel
 * (topic "annotate") as JSON, with no server state and nothing persisted.
 * The wire format matches the web client exactly, so annotations drawn on
 * Android show up for web viewers and vice-versa.
 */

private const val ANNOTATE_TOPIC = "annotate"
private const val LASER_FADE_MS = 800L
private val COLORS = listOf("#ef4444", "#f97316", "#eab308", "#22c55e", "#3b82f6")

private enum class Tool { None, Pen, Laser }

private data class Pt(val x: Float, val y: Float, val t: Long)
private data class PenStroke(val id: String, val color: String, val points: List<Pt>)
private data class Laser(val color: String, val points: List<Pt>)

private fun parseColor(hex: String): Color =
    try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (e: Exception) {
        Color.Red
    }

private data class VideoContentRect(val left: Float, val top: Float, val width: Float, val height: Float)

private fun calculateVideoContentRect(cw: Float, ch: Float, vw: Int, vh: Int): VideoContentRect {
    if (cw <= 0f || ch <= 0f) return VideoContentRect(0f, 0f, cw, ch)
    val vWidth = if (vw > 0) vw.toFloat() else 16f
    val vHeight = if (vh > 0) vh.toFloat() else 9f
    val containerAspect = cw / ch
    val videoAspect = vWidth / vHeight

    return if (videoAspect > containerAspect) {
        // Video is wider than container -> letterbox on top & bottom
        val w = cw
        val h = cw / videoAspect
        val top = (ch - h) / 2f
        VideoContentRect(0f, top, w, h)
    } else {
        // Video is taller than container -> pillarbox on left & right
        val h = ch
        val w = ch * videoAspect
        val left = (cw - w) / 2f
        VideoContentRect(left, 0f, w, h)
    }
}

@Composable
fun ScreenShareAnnotationOverlay(
    room: Room,
    modifier: Modifier = Modifier,
    trackRef: TrackReference? = null,
) {
    val scope = rememberCoroutineScope()

    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }

    val videoTrack = trackRef?.publication?.track as? VideoTrack
    DisposableEffect(videoTrack) {
        if (videoTrack == null) return@DisposableEffect onDispose { }
        val sink = object : VideoSink {
            override fun onFrame(frame: VideoFrame) {
                val w = frame.rotatedWidth
                val h = frame.rotatedHeight
                if (w > 0 && h > 0 && (w != videoWidth || h != videoHeight)) {
                    videoWidth = w
                    videoHeight = h
                }
            }
        }
        videoTrack.addRenderer(sink)
        onDispose {
            videoTrack.removeRenderer(sink)
        }
    }

    var strokes by remember { mutableStateOf<List<PenStroke>>(emptyList()) }
    var lasers by remember { mutableStateOf<Map<String, Laser>>(emptyMap()) }
    var tool by remember { mutableStateOf(Tool.None) }
    var colorHex by remember { mutableStateOf(COLORS[0]) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    val myId = room.localParticipant.identity?.value ?: "me"

    fun send(event: JSONObject, reliable: Boolean) {
        val bytes = event.toString().toByteArray(Charsets.UTF_8)
        scope.launch {
            try {
                room.localParticipant.publishData(
                    bytes,
                    if (reliable) DataPublishReliability.RELIABLE else DataPublishReliability.LOSSY,
                    ANNOTATE_TOPIC,
                )
            } catch (e: Exception) {
                // best-effort; a dropped annotation packet is not worth surfacing
            }
        }
    }

    fun applyEvent(event: JSONObject) {
        val nowMs = System.currentTimeMillis()
        when (event.optString("type")) {
            "stroke_start" -> {
                strokes = strokes + PenStroke(
                    id = event.optString("id"),
                    color = event.optString("color", COLORS[0]),
                    points = listOf(
                        Pt(
                            event.optDouble("x").toFloat(),
                            event.optDouble("y").toFloat(),
                            nowMs,
                        )
                    ),
                )
            }

            "stroke_point" -> {
                val id = event.optString("id")
                val p = Pt(event.optDouble("x").toFloat(), event.optDouble("y").toFloat(), nowMs)
                strokes = strokes.map { s ->
                    if (s.id == id) s.copy(points = s.points + p) else s
                }
            }

            "laser" -> {
                val pid = event.optString("participantId")
                val existing = lasers[pid]?.points ?: emptyList()
                val p = Pt(event.optDouble("x").toFloat(), event.optDouble("y").toFloat(), nowMs)
                lasers = lasers + (pid to Laser(event.optString("color", COLORS[0]), existing + p))
            }

            "clear" -> strokes = emptyList()
        }
    }

    fun strokeStart(id: String, c: String, x: Float, y: Float) = JSONObject()
        .put("type", "stroke_start").put("id", id).put("color", c)
        .put("x", x.toDouble()).put("y", y.toDouble())

    fun strokePoint(id: String, x: Float, y: Float) = JSONObject()
        .put("type", "stroke_point").put("id", id)
        .put("x", x.toDouble()).put("y", y.toDouble())

    fun strokeEnd(id: String) = JSONObject().put("type", "stroke_end").put("id", id)

    fun laserEvent(x: Float, y: Float) = JSONObject()
        .put("type", "laser").put("participantId", myId).put("color", colorHex)
        .put("x", x.toDouble()).put("y", y.toDouble())

    // Receive annotations from everyone else in the call.
    LaunchedEffect(room) {
        room.events.events.collect { event ->
            if (event is RoomEvent.DataReceived && event.topic == ANNOTATE_TOPIC) {
                try {
                    applyEvent(JSONObject(String(event.data, Charsets.UTF_8)))
                } catch (e: Exception) {
                    // ignore malformed packets
                }
            }
        }
    }

    // Fade + prune laser trails while any are active.
    LaunchedEffect(lasers.isEmpty()) {
        while (lasers.isNotEmpty()) {
            withFrameMillis { }
            val n = System.currentTimeMillis()
            now = n
            val pruned = lasers
                .mapValues { (_, l) -> Laser(l.color, l.points.filter { n - it.t < LASER_FADE_MS }) }
                .filterValues { it.points.isNotEmpty() }
            if (pruned.size != lasers.size ||
                pruned.any { (k, v) -> v.points.size != (lasers[k]?.points?.size ?: -1) }
            ) {
                lasers = pruned
            }
        }
    }

    Box(modifier = modifier) {
        // Drawing surface. Only intercepts touches while a tool is selected,
        // so with no tool active the tile scrolls/behaves normally.
        val drawModifier = if (tool != Tool.None) {
            Modifier.pointerInput(tool, colorHex) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    if (w <= 0f || h <= 0f) return@awaitEachGesture
                    val vRect = calculateVideoContentRect(w, h, videoWidth, videoHeight)
                    fun nx(o: Offset) = if (vRect.width > 0f) ((o.x - vRect.left) / vRect.width).coerceIn(0f, 1f) else 0f
                    fun ny(o: Offset) = if (vRect.height > 0f) ((o.y - vRect.top) / vRect.height).coerceIn(0f, 1f) else 0f
                    down.consume()

                    if (tool == Tool.Pen) {
                        val id = "$myId-${System.currentTimeMillis()}-${(0..9999).random()}"
                        var lx = nx(down.position)
                        var ly = ny(down.position)
                        val start = strokeStart(id, colorHex, lx, ly)
                        applyEvent(start); send(start, true)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) {
                                ch.consume(); break
                            }
                            val px = nx(ch.position)
                            val py = ny(ch.position)
                            if (abs(px - lx) + abs(py - ly) > 0.004f) {
                                val pe = strokePoint(id, px, py)
                                applyEvent(pe); send(pe, true)
                                lx = px; ly = py
                            }
                            ch.consume()
                        }
                        send(strokeEnd(id), true)
                    } else {
                        val e0 = laserEvent(nx(down.position), ny(down.position))
                        applyEvent(e0); send(e0, false)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) {
                                ch.consume(); break
                            }
                            val e1 = laserEvent(nx(ch.position), ny(ch.position))
                            applyEvent(e1); send(e1, false)
                            ch.consume()
                        }
                    }
                }
            }
        } else {
            Modifier
        }

        Canvas(modifier = Modifier.fillMaxSize().then(drawModifier)) {
            val w = size.width
            val h = size.height
            val vRect = calculateVideoContentRect(w, h, videoWidth, videoHeight)
            val strokePx = 3.dp.toPx()

            for (s in strokes) {
                if (s.points.size < 2) continue
                val path = Path()
                s.points.forEachIndexed { i, p ->
                    val x = vRect.left + p.x * vRect.width
                    val y = vRect.top + p.y * vRect.height
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = parseColor(s.color),
                    style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }

            val t = now // read to animate fade
            for ((_, l) in lasers) {
                for (p in l.points) {
                    val age = t - p.t
                    if (age >= LASER_FADE_MS) continue
                    val alpha = (1f - age.toFloat() / LASER_FADE_MS).coerceIn(0f, 1f)
                    drawCircle(
                        color = parseColor(l.color).copy(alpha = alpha),
                        radius = (5f * alpha + 3f).dp.toPx(),
                        center = Offset(vRect.left + p.x * vRect.width, vRect.top + p.y * vRect.height),
                    )
                }
            }
        }

        // Toolbar (top-center): pen · laser · colors · clear
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
                    RoundedCornerShape(percent = 50),
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ToolButton(
                iconRes = R.drawable.ic_edit_24dp,
                desc = stringResource(R.string.annotate_pen),
                selected = tool == Tool.Pen,
            ) { tool = if (tool == Tool.Pen) Tool.None else Tool.Pen }

            ToolButton(
                iconRes = R.drawable.ic_laser_pointer_24dp,
                desc = stringResource(R.string.annotate_laser),
                selected = tool == Tool.Laser,
            ) { tool = if (tool == Tool.Laser) Tool.None else Tool.Laser }

            for (c in COLORS) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(parseColor(c))
                        .border(
                            width = if (colorHex == c) 2.dp else 0.dp,
                            color = if (colorHex == c) Color.White else Color.Transparent,
                            shape = CircleShape,
                        )
                        .pointerInput(c) {
                            awaitEachGesture {
                                awaitFirstDown().consume()
                                colorHex = c
                                if (tool == Tool.None) tool = Tool.Pen
                            }
                        },
                )
            }

            ToolButton(
                iconRes = R.drawable.ic_delete_24dp,
                desc = stringResource(R.string.annotate_clear),
                selected = false,
            ) {
                strokes = emptyList()
                send(JSONObject().put("type", "clear"), true)
            }
        }
    }
}

@Composable
private fun ToolButton(
    iconRes: Int,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
            ),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = desc,
                tint = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
