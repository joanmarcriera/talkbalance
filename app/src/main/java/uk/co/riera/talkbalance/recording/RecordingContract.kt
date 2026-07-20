package uk.co.riera.talkbalance.recording

import android.content.Context
import android.content.Intent
import java.io.File

object RecordingContract {
    const val ACTION_START = "uk.co.riera.talkbalance.recording.START"
    const val ACTION_PAUSE = "uk.co.riera.talkbalance.recording.PAUSE"
    const val ACTION_RESUME = "uk.co.riera.talkbalance.recording.RESUME"
    const val ACTION_FINISH = "uk.co.riera.talkbalance.recording.FINISH"
    const val ACTION_CANCEL = "uk.co.riera.talkbalance.recording.CANCEL"

    fun intent(context: Context, action: String) = Intent(context, RecordingService::class.java).setAction(action)
}

sealed interface RecordingStatus {
    data object Idle : RecordingStatus
    data class Recording(val startedAtEpochMs: Long, val elapsedMs: Long = 0) : RecordingStatus
    data class Paused(val startedAtEpochMs: Long, val elapsedMs: Long) : RecordingStatus
    data class Finished(val result: RecordingResult) : RecordingStatus
    data class Failed(val code: String, val message: String) : RecordingStatus
}

data class RecordingResult(
    val file: File,
    val durationMs: Long,
    val sha256: String,
    val recovered: Boolean = false,
)
