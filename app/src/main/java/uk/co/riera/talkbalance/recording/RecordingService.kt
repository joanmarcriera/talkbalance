package uk.co.riera.talkbalance.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.util.Timer
import kotlin.concurrent.fixedRateTimer

class RecordingService : Service() {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt = 0L
    private var pausedAt = 0L
    private var pausedDuration = 0L
    private var timer: Timer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            RecordingContract.ACTION_START -> startRecording()
            RecordingContract.ACTION_PAUSE -> pauseRecording()
            RecordingContract.ACTION_RESUME -> resumeRecording()
            RecordingContract.ACTION_FINISH -> finishRecording()
            RecordingContract.ACTION_CANCEL -> cancelRecording()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        timer?.cancel()
        recorder?.runCatching { stop() }
        recorder?.release()
        recorder = null
        super.onDestroy()
    }

    private fun startRecording() {
        if (recorder != null) return
        if (StatFs(filesDir.absolutePath).availableBytes < MIN_FREE_BYTES) {
            fail("LOW_STORAGE", "At least 250 MB of free storage is required.")
            return
        }
        val directory = File(filesDir, "recordings").apply { mkdirs() }
        val file = File(directory, "conversation-${System.currentTimeMillis()}.m4a")
        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        runCatching {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioChannels(1)
            mediaRecorder.setAudioSamplingRate(44_100)
            mediaRecorder.setAudioEncodingBitRate(64_000)
            mediaRecorder.setOutputFile(file.absolutePath)
            mediaRecorder.prepare()

            startedAt = System.currentTimeMillis()
            outputFile = file
            recorder = mediaRecorder
            RecordingController.rememberActive(this, file, startedAt)
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification("Recording conversation", "00:00"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else 0,
            )
            mediaRecorder.start()
            RecordingController.update(RecordingStatus.Recording(startedAt))
            startTimer()
        }.onFailure {
            mediaRecorder.release()
            file.delete()
            recorder = null
            outputFile = null
            fail("START_FAILED", it.message ?: "The microphone could not start.")
        }
    }

    private fun pauseRecording() {
        val active = recorder ?: return
        runCatching {
            active.pause()
            pausedAt = System.currentTimeMillis()
            val elapsed = elapsedMs()
            RecordingController.update(RecordingStatus.Paused(startedAt, elapsed))
            updateNotification("Recording paused", formatElapsed(elapsed))
        }.onFailure { fail("PAUSE_FAILED", it.message ?: "Recording could not pause.") }
    }

    private fun resumeRecording() {
        val active = recorder ?: return
        if (pausedAt == 0L) return
        runCatching {
            active.resume()
            pausedDuration += System.currentTimeMillis() - pausedAt
            pausedAt = 0
            RecordingController.update(RecordingStatus.Recording(startedAt, elapsedMs()))
        }.onFailure { fail("RESUME_FAILED", it.message ?: "Recording could not resume.") }
    }

    private fun finishRecording() {
        val active = recorder ?: return
        val file = outputFile ?: return
        val duration = elapsedMs()
        timer?.cancel()
        runCatching { active.stop() }
            .onSuccess {
                active.release()
                recorder = null
                outputFile = null
                RecordingController.clearActive(this)
                if (file.length() < 1_024) {
                    file.delete()
                    fail("EMPTY_RECORDING", "The recording did not contain usable audio.")
                } else {
                    RecordingController.update(RecordingStatus.Finished(RecordingResult(file, duration, file.sha256())))
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            .onFailure { fail("FINISH_FAILED", it.message ?: "The recording could not be finalised.") }
    }

    private fun cancelRecording() {
        timer?.cancel()
        recorder?.runCatching { stop() }
        recorder?.release()
        recorder = null
        outputFile?.delete()
        outputFile = null
        RecordingController.clearActive(this)
        RecordingController.update(RecordingStatus.Idle)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(code: String, message: String) {
        RecordingController.update(RecordingStatus.Failed(code, message))
        if (recorder == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startTimer() {
        timer?.cancel()
        timer = fixedRateTimer("recording-timer", daemon = true, initialDelay = 1_000, period = 1_000) {
            val elapsed = elapsedMs()
            if (pausedAt == 0L) {
                RecordingController.update(RecordingStatus.Recording(startedAt, elapsed))
                updateNotification("Recording conversation", formatElapsed(elapsed))
            }
        }
    }

    private fun elapsedMs(): Long {
        val now = if (pausedAt == 0L) System.currentTimeMillis() else pausedAt
        return (now - startedAt - pausedDuration).coerceAtLeast(0)
    }

    private fun notification(title: String, text: String): Notification {
        val finishIntent = PendingIntent.getService(
            this, 1, RecordingContract.intent(this, RecordingContract.ACTION_FINISH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(uk.co.riera.talkbalance.R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Finish", finishIntent)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(title, text))
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Active recordings", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows when Talk Balance is recording a conversation"
            },
        )
    }

    private fun formatElapsed(value: Long): String {
        val seconds = value / 1_000
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    companion object {
        private const val CHANNEL_ID = "active_recording"
        private const val NOTIFICATION_ID = 1001
        private const val MIN_FREE_BYTES = 250L * 1024 * 1024
    }
}
