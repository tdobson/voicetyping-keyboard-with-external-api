package net.tdobson.groqvoice.audio

/** Whether [Recorder.stop] keeps the file. Pure, so it is a JVM test. */
internal object StoppedRecording {
    /** Same floor as PendingStore.isValid and recoverInterrupted: anything smaller is headers, not speech. */
    const val MIN_KEEP_BYTES = 1_024L

    /**
     * Under [Recorder.MIN_RECORDING_MS] is consistently "nothing recorded" (MediaRecorder may or may not manage to
     * finalise a few milliseconds). If MediaRecorder.stop() threw (encoder error, mic taken away) after real audio,
     * the file is unfinalised but its Ogg pages are still sendable, like a file recovered after a crash: keep it
     * when it is over [MIN_KEEP_BYTES] rather than lose minutes of speech.
     */
    fun keep(stopThrew: Boolean, maxReached: Boolean, durationMs: Long, fileBytes: Long): Boolean {
        val longEnough = durationMs >= Recorder.MIN_RECORDING_MS
        return if (!stopThrew) longEnough else (longEnough || maxReached) && fileBytes > MIN_KEEP_BYTES
    }
}
