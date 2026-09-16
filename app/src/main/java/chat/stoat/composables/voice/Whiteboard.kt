package chat.stoat.composables.voice

import android.annotation.SuppressLint
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import chat.stoat.api.buildUserAgent
import chat.stoat.core.model.data.STOAT_WEB_APP
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.track.DataPublishReliability
import kotlinx.coroutines.launch

private const val WHITEBOARD_TOPIC = "whiteboard"

/**
 * Shared whiteboard for a voice call, backed by the real tldraw editor.
 *
 * Android has no tldraw SDK of its own, and this device is already an
 * authenticated participant in the call's real LiveKit room -- so rather
 * than have a WebView open a second connection as the same identity (which
 * LiveKit would treat as a duplicate session), this loads a small
 * standalone page (whiteboard-embed.html in the web client) that talks to
 * LiveKit only through this bridge: JS -> Kotlin via
 * `AndroidWhiteboard.postMessage`, Kotlin -> JS by calling the page's
 * `window.__onNativeWhiteboardMessage` directly. See whiteboardSync.ts and
 * whiteboardEmbed.tsx in the web client for the other half of this.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WhiteboardView(room: Room, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }

    // Relay data received from other participants into the page. Base64
    // round-tripped rather than interpolated as a raw JS string literal so
    // quotes/backslashes/unicode in the JSON can never break out of it.
    LaunchedEffect(room) {
        room.events.events.collect { event ->
            if (event is RoomEvent.DataReceived && event.topic == WHITEBOARD_TOPIC) {
                val json = String(event.data, Charsets.UTF_8)
                val encoded = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                webView?.post {
                    webView?.evaluateJavascript(
                        "window.__onNativeWhiteboardMessage && " +
                            "window.__onNativeWhiteboardMessage(decodeURIComponent(escape(window.atob('$encoded'))))",
                        null,
                    )
                }
            }
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = buildUserAgent("WhiteboardView")
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun postMessage(json: String) {
                            scope.launch {
                                try {
                                    room.localParticipant.publishData(
                                        json.toByteArray(Charsets.UTF_8),
                                        DataPublishReliability.RELIABLE,
                                        WHITEBOARD_TOPIC,
                                    )
                                } catch (e: Exception) {
                                    // best-effort; a dropped whiteboard packet is not worth surfacing
                                }
                            }
                        }
                    },
                    "AndroidWhiteboard",
                )
                loadUrl("$STOAT_WEB_APP/whiteboard-embed.html")
                webView = this
            }
        },
        onRelease = { it.destroy() },
    )
}
