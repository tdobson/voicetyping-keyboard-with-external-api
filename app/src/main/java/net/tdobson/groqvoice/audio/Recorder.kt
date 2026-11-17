package net.tdobson.groqvoice.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import net.tdobson.groqvoice.core.controller.AudioRecorder
import net.tdobson.groqvoice.util.Logx
import java.io.File

/**
 * MediaRecorder writing 16 kHz mono Opus in Ogg (the desktop's ffmpeg settings), at 24 kbps so a 60-minute
 * recording (~10.8 MB) stays under Groq's 25 MB upload limit. One recording at a time. Never throws.
 */
class Recorder(
    private val context: Context,
    private val maxDurationMs: Int = 60 * 60 * 1000,
) : AudioRecorder {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    @Volatile private var maxReached = false

    override fun start(file: File, onMaxDuration: () -> Unit): Boolean {
        if (recorder != null) return false
        file.parentFile?.mkdirs()
        maxReached = false
        val r = newMediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            r.setAudioSamplingRate(SAMPLE_RATE)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(BIT_RATE)
            r.setMaxDuration(maxDurationMs)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    maxReached = true
                    onMaxDuration()
                }
            }
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            this.file = file
            true
        } catch (e: Exception) {
            Logx.w(e) { "recorder failed to start" }
            r.release()
            file.delete()
            false
        }
    }

    override fun stop(): Boolean {
        val r = recorder ?: return false
        val f = file
        recorder = null
        file = null
        val ok = try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            // stop() throws when no audio was captured yet. After the max-duration stop the file is already final.
            Logx.w(e) { "recorder stop failed (maxReached=$maxReached)" }
            maxReached && f != null && f.length() > 0
        } finally {
            r.release()
        }
        if (!ok) f?.delete()
        return ok
    }

    override fun cancel() {
        val r = recorder ?: return
        val f = file
        recorder = null
        file = null
        try {
            r.stop()
        } catch (_: RuntimeException) {
        } finally {
            r.release()
        }
        f?.delete()
    }

    @Suppress("DEPRECATION")
    private fun newMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

    companion object {
        const val SAMPLE_RATE = 16_000
        const val BIT_RATE = 24_000
    }
}
