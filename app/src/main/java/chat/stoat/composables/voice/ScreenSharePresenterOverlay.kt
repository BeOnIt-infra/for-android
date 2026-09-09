package chat.stoat.composables.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Displays remote annotations to the Android screen-share presenter, drawn
 * straight onto their real screen so they show up correctly positioned in
 * the capture itself — no coordinate math to keep in sync with a video
 * element on the viewing end. While this is active we publish
 * [ANNOTATIONS_BAKED_IN_ATTR] on the local participant so viewers know the
 * strokes are already in the video and skip drawing their own copy on top.
 */
object ScreenSharePresenterOverlay {
    private const val TOPIC = "annotate"
    private const val LASER_FADE_MS = 800L
    const val ANNOTATIONS_BAKED_IN_ATTR = "io.beonit.annotationsBakedIn"

    private data class Point(val x: Float, val y: Float, val time: Long)
    private data class Stroke(val id: String, val color: Int, val points: MutableList<Point>)
    private data class Laser(val color: Int, val points: MutableList<Point>)

    private var windowManager: WindowManager? = null
    private var overlayView: AnnotationView? = null
    private var scope: CoroutineScope? = null

    fun start(context: Context, room: Room) {
        if (!Settings.canDrawOverlays(context) || overlayView != null) return

        val appContext = context.applicationContext
        val manager = appContext.getSystemService(WindowManager::class.java)
        val view = AnnotationView(appContext)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            // Deliberately no FLAG_SECURE: this overlay only draws annotation
            // strokes, nothing sensitive, and screen-sharing is itself a form
            // of screen capture — FLAG_SECURE blacks the shared frame out
            // wherever this full-screen overlay is mounted, taking the
            // presenter's whole share down with it.
            PixelFormat.TRANSLUCENT,
        )

        manager.addView(view, params)
        windowManager = manager
        overlayView = view

        val overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = overlayScope
        overlayScope.launch {
            runCatching {
                room.localParticipant.updateAttributes(mapOf(ANNOTATIONS_BAKED_IN_ATTR to "true"))
            }
        }
        overlayScope.launch {
            room.events.events.collect { event ->
                if (event is RoomEvent.DataReceived && event.topic == TOPIC) {
                    runCatching {
                        view.applyEvent(JSONObject(String(event.data, Charsets.UTF_8)))
                    }
                }
            }
        }
        overlayScope.launch {
            while (true) {
                delay(16)
                view.pruneAndRedraw()
            }
        }
    }

    fun stop(room: Room? = null) {
        scope?.cancel()
        scope = null
        overlayView?.let { view -> runCatching { windowManager?.removeView(view) } }
        overlayView = null
        windowManager = null
        // Fire-and-forget on a scope of its own: the overlay's own scope was
        // just cancelled above, and this needs to reach the server even
        // after the view is gone.
        if (room != null) {
            CoroutineScope(Dispatchers.Main.immediate).launch {
                runCatching {
                    room.localParticipant.updateAttributes(mapOf(ANNOTATIONS_BAKED_IN_ATTR to "false"))
                }
            }
        }
    }

    private class AnnotationView(context: Context) : View(context) {
        private val strokes = mutableListOf<Stroke>()
        private val lasers = mutableMapOf<String, Laser>()
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val laserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        fun applyEvent(event: JSONObject) {
            val now = System.currentTimeMillis()
            when (event.optString("type")) {
                "stroke_start" -> strokes += Stroke(
                    event.optString("id"),
                    parseColor(event.optString("color", "#ef4444")),
                    mutableListOf(Point(event.optDouble("x").toFloat(), event.optDouble("y").toFloat(), now)),
                )
                "stroke_point" -> strokes.lastOrNull { it.id == event.optString("id") }
                    ?.points?.add(Point(event.optDouble("x").toFloat(), event.optDouble("y").toFloat(), now))
                "laser" -> {
                    val id = event.optString("participantId")
                    val laser = lasers.getOrPut(id) {
                        Laser(parseColor(event.optString("color", "#ef4444")), mutableListOf())
                    }
                    laser.points += Point(event.optDouble("x").toFloat(), event.optDouble("y").toFloat(), now)
                }
                "clear" -> strokes.clear()
            }
            invalidate()
        }

        fun pruneAndRedraw() {
            val now = System.currentTimeMillis()
            lasers.values.forEach { laser -> laser.points.removeAll { now - it.time >= LASER_FADE_MS } }
            lasers.entries.removeAll { it.value.points.isEmpty() }
            if (lasers.isNotEmpty()) invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            strokes.forEach { stroke ->
                if (stroke.points.size < 2) return@forEach
                strokePaint.color = stroke.color
                val path = Path()
                stroke.points.forEachIndexed { index, point ->
                    val x = point.x * width
                    val y = point.y * height
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                canvas.drawPath(path, strokePaint)
            }

            val now = System.currentTimeMillis()
            lasers.values.forEach { laser ->
                laser.points.forEach { point ->
                    val alpha = (1f - (now - point.time).toFloat() / LASER_FADE_MS).coerceIn(0f, 1f)
                    laserPaint.color = laser.color
                    laserPaint.alpha = (alpha * 255).toInt()
                    canvas.drawCircle(point.x * width, point.y * height, 8f + 10f * alpha, laserPaint)
                }
            }
        }

        private fun parseColor(value: String): Int = runCatching { Color.parseColor(value) }.getOrDefault(Color.RED)
    }
}
