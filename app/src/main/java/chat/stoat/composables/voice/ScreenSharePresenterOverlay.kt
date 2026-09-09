package chat.stoat.composables.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import chat.stoat.R
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.track.DataPublishReliability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs

/**
 * Displays remote annotations to the Android screen-share presenter, drawn
 * straight onto their real screen so they show up correctly positioned in
 * the capture itself — no coordinate math to keep in sync with a video
 * element on the viewing end. While this is active we publish
 * [ANNOTATIONS_BAKED_IN_ATTR] on the local participant so viewers know the
 * strokes are already in the video and skip drawing their own copy on top.
 *
 * A floating toolbar — pen, laser, colors, clear, mirroring the in-call
 * one — sits always-on-top so the presenter can annotate their own share
 * from anywhere, not just from the call screen. The full-screen drawing
 * layer underneath only takes over touches while a tool is selected;
 * otherwise it's click-through so the presenter can keep using whatever
 * they're sharing.
 */
object ScreenSharePresenterOverlay {
    private const val TOPIC = "annotate"
    private const val LASER_FADE_MS = 800L
    /** Matches the web/Android in-call overlays so all three don't flood the
     * data channel at different rates. */
    private const val MIN_POINT_DISTANCE = 0.004f
    private val COLORS = listOf("#ef4444", "#f97316", "#eab308", "#22c55e", "#3b82f6")

    const val ANNOTATIONS_BAKED_IN_ATTR = "io.beonit.annotationsBakedIn"

    private enum class Tool { NONE, PEN, LASER }

    private data class Point(val x: Float, val y: Float, val time: Long)
    private data class Stroke(val id: String, val color: Int, val points: MutableList<Point>)
    private data class Laser(val color: Int, val points: MutableList<Point>)

    private var windowManager: WindowManager? = null
    private var overlayView: AnnotationView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var toolbarView: View? = null
    private var penButton: ImageButton? = null
    private var laserButton: ImageButton? = null
    private var scope: CoroutineScope? = null
    private var room: Room? = null
    private var currentTool = Tool.NONE
    private var currentColorHex = COLORS[0]
    private var wakeLock: PowerManager.WakeLock? = null

    private fun fullScreenFlags(touchable: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            // The view must cover the true captured frame exactly, status bar
            // and cutout included, or every stroke lands a few dp off from
            // where the video actually shows it.
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return flags
    }

    fun start(context: Context, activeRoom: Room) {
        if (!Settings.canDrawOverlays(context) || overlayView != null) return

        val appContext = context.applicationContext
        val manager = appContext.getSystemService(WindowManager::class.java)
        room = activeRoom

        // The captured frame goes black once the real display turns off —
        // MediaProjection keeps "recording" but there's nothing left to
        // capture. Screen shares often go long stretches with nobody
        // touching the phone (that's the point of the floating toolbar), so
        // without this the share goes dark on its own screen timeout.
        runCatching {
            val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK,
                "chat.stoat:screenSharePresenter",
            ).apply { acquire() }
        }
        currentTool = Tool.NONE

        val view = AnnotationView(appContext) { x, y, phase -> onLocalTouch(x, y, phase) }
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
            fullScreenFlags(touchable = false),
            // Deliberately no FLAG_SECURE: this overlay only draws annotation
            // strokes, nothing sensitive, and screen-sharing is itself a form
            // of screen capture — FLAG_SECURE blacks the shared frame out
            // wherever this full-screen overlay is mounted, taking the
            // presenter's whole share down with it.
            PixelFormat.TRANSLUCENT,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        manager.addView(view, params)
        windowManager = manager
        overlayView = view
        overlayParams = params

        addToolbar(appContext, manager, type)

        val overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = overlayScope
        overlayScope.launch {
            runCatching {
                activeRoom.localParticipant.updateAttributes(mapOf(ANNOTATIONS_BAKED_IN_ATTR to "true"))
            }
        }
        overlayScope.launch {
            activeRoom.events.events.collect { event ->
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

    fun stop(closingRoom: Room? = null) {
        scope?.cancel()
        scope = null
        overlayView?.let { view -> runCatching { windowManager?.removeView(view) } }
        toolbarView?.let { bar -> runCatching { windowManager?.removeView(bar) } }
        overlayView = null
        overlayParams = null
        toolbarView = null
        penButton = null
        laserButton = null
        windowManager = null
        room = null
        currentTool = Tool.NONE
        runCatching { wakeLock?.release() }
        wakeLock = null
        // Fire-and-forget on a scope of its own: the overlay's own scope was
        // just cancelled above, and this needs to reach the server even
        // after the view is gone.
        if (closingRoom != null) {
            CoroutineScope(Dispatchers.Main.immediate).launch {
                runCatching {
                    closingRoom.localParticipant.updateAttributes(mapOf(ANNOTATIONS_BAKED_IN_ATTR to "false"))
                }
            }
        }
    }

    private fun addToolbar(context: Context, manager: WindowManager, type: Int) {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#E6202020"))
            }
        }

        val iconSize = dp(40)
        val iconMargin = dp(2)
        fun iconParams() = LinearLayout.LayoutParams(iconSize, iconSize).apply {
            marginStart = iconMargin
            marginEnd = iconMargin
        }

        fun toolButton(iconRes: Int, onTap: () -> Unit): ImageButton =
            ImageButton(context).apply {
                setImageResource(iconRes)
                imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = null
                setOnClickListener { onTap() }
            }

        penButton = toolButton(R.drawable.ic_edit_24dp) {
            setTool(if (currentTool == Tool.PEN) Tool.NONE else Tool.PEN)
        }
        bar.addView(penButton, iconParams())

        laserButton = toolButton(R.drawable.ic_laser_pointer_24dp) {
            setTool(if (currentTool == Tool.LASER) Tool.NONE else Tool.LASER)
        }
        bar.addView(laserButton, iconParams())

        val dotSize = dp(22)
        val dotMargin = dp(4)
        for (hex in COLORS) {
            val dot = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(hex))
                }
                setOnClickListener {
                    currentColorHex = hex
                    if (currentTool == Tool.NONE) setTool(Tool.PEN)
                }
            }
            bar.addView(
                dot,
                LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    marginStart = dotMargin
                    marginEnd = dotMargin
                },
            )
        }

        bar.addView(
            toolButton(R.drawable.ic_delete_24dp) {
                val activeRoom = room ?: return@toolButton
                val event = JSONObject().put("type", "clear")
                overlayView?.applyEvent(event)
                publish(activeRoom, event, reliable = true)
            },
            iconParams(),
        )

        val params = WindowManager.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(48)
        }
        manager.addView(bar, params)
        toolbarView = bar
        updateToolButtonHighlights()
    }

    private fun setTool(tool: Tool) {
        val view = overlayView ?: return
        val params = overlayParams ?: return
        val manager = windowManager ?: return
        currentTool = tool
        params.flags = fullScreenFlags(touchable = tool != Tool.NONE)
        runCatching { manager.updateViewLayout(view, params) }
        view.setInteractive(tool != Tool.NONE)
        updateToolButtonHighlights()
    }

    private fun updateToolButtonHighlights() {
        fun highlight(button: ImageButton?, selected: Boolean) {
            button?.background = if (selected) {
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(currentColorHex))
                }
            } else {
                null
            }
        }
        highlight(penButton, currentTool == Tool.PEN)
        highlight(laserButton, currentTool == Tool.LASER)
    }

    /** phase: 0 = down, 1 = move, 2 = up/cancel */
    private fun onLocalTouch(nx: Float, ny: Float, phase: Int) {
        val activeRoom = room ?: return
        val view = overlayView ?: return
        val myId = activeRoom.localParticipant.identity?.value ?: "me"

        if (currentTool == Tool.LASER) {
            if (phase == 2) return
            val last = view.localLastPoint
            if (phase == 1 && last != null &&
                abs(nx - last.first) + abs(ny - last.second) < MIN_POINT_DISTANCE
            ) {
                return
            }
            view.localLastPoint = nx to ny
            val event = JSONObject()
                .put("type", "laser").put("participantId", myId)
                .put("color", currentColorHex).put("x", nx.toDouble()).put("y", ny.toDouble())
            view.applyEvent(event)
            publish(activeRoom, event, reliable = false)
            return
        }

        when (phase) {
            0 -> {
                val id = "$myId-${System.currentTimeMillis()}-${(0..9999).random()}"
                view.localStrokeId = id
                view.localLastPoint = nx to ny
                val event = JSONObject()
                    .put("type", "stroke_start").put("id", id)
                    .put("color", currentColorHex).put("x", nx.toDouble()).put("y", ny.toDouble())
                view.applyEvent(event)
                publish(activeRoom, event, reliable = true)
            }
            1 -> {
                val id = view.localStrokeId ?: return
                val last = view.localLastPoint
                if (last != null && abs(nx - last.first) + abs(ny - last.second) < MIN_POINT_DISTANCE) {
                    return
                }
                view.localLastPoint = nx to ny
                val event = JSONObject()
                    .put("type", "stroke_point").put("id", id)
                    .put("x", nx.toDouble()).put("y", ny.toDouble())
                view.applyEvent(event)
                publish(activeRoom, event, reliable = true)
            }
            else -> {
                val id = view.localStrokeId ?: return
                view.localStrokeId = null
                view.localLastPoint = null
                val event = JSONObject().put("type", "stroke_end").put("id", id)
                view.applyEvent(event)
                publish(activeRoom, event, reliable = true)
            }
        }
    }

    private fun publish(activeRoom: Room, event: JSONObject, reliable: Boolean) {
        val bytes = event.toString().toByteArray(Charsets.UTF_8)
        scope?.launch {
            runCatching {
                activeRoom.localParticipant.publishData(
                    bytes,
                    if (reliable) DataPublishReliability.RELIABLE else DataPublishReliability.LOSSY,
                    TOPIC,
                )
            }
        }
    }

    private class AnnotationView(
        context: Context,
        private val onTouch: (x: Float, y: Float, phase: Int) -> Unit,
    ) : View(context) {
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

        private var interactive = false
        var localStrokeId: String? = null
        var localLastPoint: Pair<Float, Float>? = null

        fun setInteractive(value: Boolean) {
            interactive = value
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!interactive || width == 0 || height == 0) return false
            val nx = (event.x / width).coerceIn(0f, 1f)
            val ny = (event.y / height).coerceIn(0f, 1f)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> onTouch(nx, ny, 0)
                MotionEvent.ACTION_MOVE -> onTouch(nx, ny, 1)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onTouch(nx, ny, 2)
                else -> return false
            }
            return true
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
