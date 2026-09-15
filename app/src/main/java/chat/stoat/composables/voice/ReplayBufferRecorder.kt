package chat.stoat.composables.voice

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.livekit.android.room.Room
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink
import livekit.org.webrtc.YuvHelper
import java.io.File
import java.nio.ByteBuffer

/**
 * Be On It: rolling "instant replay" buffer for the local screen share on
 * Android, mirroring desktop/web's "save last 15 seconds" feature.
 *
 * Desktop shells out to a bundled ffmpeg binary; the browser build uses
 * ffmpeg.wasm. Neither is available here -- FFmpegKit (the usual Android
 * ffmpeg wrapper) was retired in April 2025 with no maintained drop-in
 * replacement -- so this encodes and buffers the clip itself with the
 * platform's own [MediaCodec]/[MediaMuxer], no third-party video library
 * involved.
 *
 * Design: rather than desktop's approach (restart a recorder every few
 * seconds, producing independently-complete segments that get concatenated
 * on save), this runs ONE continuous H.264 encoder for the whole time the
 * screen share is active, and keeps a rolling deque of its encoded output
 * (each access unit tagged with its timestamp and keyframe flag). Saving a
 * clip just means: find the newest buffered keyframe that's still at least
 * as old as the requested clip length, and mux everything from there to
 * "now" into an mp4 with MediaMuxer -- no re-encoding, no second pass. The
 * buffer is trimmed the same way (drop everything before the oldest
 * keyframe still worth keeping for MAX_CLIP_SECONDS), so it only ever holds
 * a little more than that much footage.
 *
 * Frames come from a [VideoSink] on the local screen-share [VideoTrack] --
 * the same hook point [ScreenShareAnnotationOverlay] uses for its capture
 * button, including the I420->NV12 conversion (the only YUV->encoder path
 * this codebase already has proven working).
 */
object ReplayBufferRecorder {
    /** Longest clip the UI offers (see the duration menu in VoiceSheet) --
     * bounds how much footage the buffer needs to retain. */
    private const val MAX_CLIP_SECONDS = 120

    /** Buffer a bit more than MAX_CLIP_SECONDS so there's always a keyframe
     * to start a save from at or before "now - requested seconds". */
    private const val RETAIN_SECONDS = MAX_CLIP_SECONDS + 6

    /** Screen-share content rarely needs full frame rate to be useful as a
     * "what just happened" reference, and a lower rate keeps CPU/battery/
     * buffer-memory cost down for something that runs the whole call. */
    private const val TARGET_FPS = 10
    private const val BITRATE = 3_000_000
    private const val KEYFRAME_INTERVAL_SEC = 2

    private class Sample(val data: ByteArray, val presentationTimeUs: Long, val flags: Int)

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var codec: MediaCodec? = null
    private var outputFormat: MediaFormat? = null
    private var encWidth = 0
    private var encHeight = 0
    private var startTimeNs = 0L
    private var lastFeedNs = 0L

    private var track: VideoTrack? = null
    private var sink: VideoSink? = null

    private val bufferLock = Any()
    private val buffer = ArrayDeque<Sample>()

    /** Drives visibility of the "save last 15 seconds" button. */
    var isAvailable: Boolean by mutableStateOf(false)
        private set

    fun start(room: Room) {
        if (handler != null) return
        val videoTrack =
            room.localParticipant.getTrackPublication(Track.Source.SCREEN_SHARE)?.track as? VideoTrack
                ?: return

        val thread = HandlerThread("ReplayBufferEncoder").apply { start() }
        handlerThread = thread
        val h = Handler(thread.looper)
        handler = h
        lastFeedNs = 0L

        val minIntervalNs = 1_000_000_000L / TARGET_FPS
        val videoSink = object : VideoSink {
            override fun onFrame(frame: VideoFrame) {
                val now = System.nanoTime()
                // Cheap check on WebRTC's own frame-delivery thread, before
                // paying for a retain()+thread-hop on a frame we're just
                // going to drop anyway.
                if (now - lastFeedNs < minIntervalNs) return
                lastFeedNs = now
                frame.retain()
                h.post {
                    try {
                        encodeFrame(frame)
                    } catch (e: Exception) {
                        // Best-effort, same as the rest of this feature --
                        // one bad frame shouldn't take the buffer down.
                    } finally {
                        frame.release()
                    }
                }
            }
        }
        videoTrack.addRenderer(videoSink)
        track = videoTrack
        sink = videoSink
        isAvailable = true
    }

    fun stop() {
        val t = track
        val s = sink
        if (t != null && s != null) t.removeRenderer(s)
        track = null
        sink = null
        isAvailable = false

        val h = handler
        handler = null
        h?.post { releaseCodec() }
        h?.looper?.quitSafely()
        handlerThread = null

        synchronized(bufferLock) { buffer.clear() }
        outputFormat = null
        startTimeNs = 0L
        encWidth = 0
        encHeight = 0
    }

    // Everything below runs on the encoder HandlerThread only -- MediaCodec
    // in synchronous buffer-input mode is not meant to be driven from
    // multiple threads.

    private fun encodeFrame(frame: VideoFrame) {
        val i420 = frame.buffer.toI420() ?: return
        try {
            val width = i420.width
            val height = i420.height
            if (width <= 0 || height <= 0) return

            if (codec == null || width != encWidth || height != encHeight) {
                reconfigure(width, height)
            }
            val c = codec ?: return

            val ySize = width * height
            val uvSize = ((width + 1) / 2) * ((height + 1) / 2)
            val nv12 = ByteArray(ySize + uvSize * 2)
            YuvHelper.I420ToNV12(
                i420.dataY, i420.strideY,
                i420.dataU, i420.strideU,
                i420.dataV, i420.strideV,
                ByteBuffer.wrap(nv12), width, height,
            )

            if (startTimeNs == 0L) startTimeNs = frame.timestampNs
            val ptsUs = (frame.timestampNs - startTimeNs) / 1000

            val inIndex = c.dequeueInputBuffer(0)
            if (inIndex >= 0) {
                val inputBuffer = c.getInputBuffer(inIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(nv12)
                    c.queueInputBuffer(inIndex, 0, nv12.size, ptsUs, 0)
                }
            }
            drainEncoder(c)
        } finally {
            i420.release()
        }
    }

    private fun reconfigure(width: Int, height: Int) {
        releaseCodec()
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_INTERVAL_SEC)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c
            encWidth = width
            encHeight = height
        } catch (e: Exception) {
            codec = null
            encWidth = 0
            encHeight = 0
        }
        startTimeNs = 0L
        outputFormat = null
        synchronized(bufferLock) { buffer.clear() }
    }

    private fun releaseCodec() {
        try {
            codec?.stop()
        } catch (e: Exception) {
        }
        try {
            codec?.release()
        } catch (e: Exception) {
        }
        codec = null
    }

    private fun drainEncoder(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = c.dequeueOutputBuffer(info, 0)
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outputFormat = c.outputFormat
                continue
            }
            if (outIndex < 0) return

            val outBuffer = c.getOutputBuffer(outIndex)
            if (outBuffer != null && info.size > 0 &&
                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
            ) {
                val data = ByteArray(info.size)
                outBuffer.position(info.offset)
                outBuffer.limit(info.offset + info.size)
                outBuffer.get(data)
                addSample(Sample(data, info.presentationTimeUs, info.flags))
            }
            c.releaseOutputBuffer(outIndex, false)
        }
    }

    private fun addSample(sample: Sample) {
        synchronized(bufferLock) {
            buffer.addLast(sample)
            val cutoffUs = sample.presentationTimeUs - RETAIN_SECONDS * 1_000_000L
            // Only drop whole GOPs: find the newest keyframe at or before
            // the cutoff and remove everything strictly before it, so the
            // buffer always still starts at a keyframe.
            var keepFromIndex = -1
            for ((idx, s) in buffer.withIndex()) {
                if (s.presentationTimeUs <= cutoffUs && (s.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                    keepFromIndex = idx
                }
            }
            repeat(keepFromIndex) { buffer.removeFirst() }
        }
    }

    /**
     * Muxes the last [seconds] of buffered footage into a temp mp4 file
     * under [Context.getCacheDir], or null if there's nothing to save yet.
     * Caller owns the file and must delete it when done.
     */
    private suspend fun muxToTempFile(context: Context, seconds: Int): File? = withContext(Dispatchers.Default) {
        val clipSeconds = seconds.coerceIn(1, MAX_CLIP_SECONDS)
        val format = outputFormat ?: return@withContext null
        val samples = synchronized(bufferLock) {
            val list = buffer.toList()
            val latestPts = list.lastOrNull()?.presentationTimeUs ?: return@withContext null
            val targetCutoff = latestPts - clipSeconds * 1_000_000L
            var startIdx = list.indexOfFirst { (it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 }
            for ((idx, s) in list.withIndex()) {
                if (s.presentationTimeUs <= targetCutoff && (s.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                    startIdx = idx
                }
            }
            if (startIdx < 0) return@withContext null
            list.subList(startIdx, list.size)
        }
        if (samples.isEmpty()) return@withContext null

        val tempFile = File.createTempFile("replay", ".mp4", context.cacheDir)
        try {
            val muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackIndex = muxer.addTrack(format)
            muxer.start()
            val basePts = samples.first().presentationTimeUs
            val info = MediaCodec.BufferInfo()
            for (s in samples) {
                info.set(0, s.data.size, s.presentationTimeUs - basePts, s.flags)
                muxer.writeSampleData(trackIndex, ByteBuffer.wrap(s.data), info)
            }
            muxer.stop()
            muxer.release()
            tempFile
        } catch (e: Exception) {
            tempFile.delete()
            null
        }
    }

    /**
     * Saves the last [seconds] of buffered footage to [destination] -- a
     * SAF ("Storage Access Framework") URI the user picked themselves via
     * an ACTION_CREATE_DOCUMENT launcher, so the save location is theirs to
     * choose rather than a fixed Movies/BeOnIt folder. See VoiceSheet's
     * replay duration menu for how [destination] gets here.
     */
    suspend fun saveReplay(context: Context, seconds: Int, destination: Uri): Boolean =
        withContext(Dispatchers.Default) {
            val tempFile = muxToTempFile(context, seconds) ?: return@withContext false
            try {
                val wrote = context.contentResolver.openOutputStream(destination)?.use { out ->
                    tempFile.inputStream().use { it.copyTo(out) }
                }
                wrote != null
            } catch (e: Exception) {
                false
            } finally {
                tempFile.delete()
            }
        }
}
