package uk.co.riera.talkbalance.recording

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest

object RecordingController {
    private const val PREFS = "recording_recovery"
    private const val KEY_PATH = "active_path"
    private const val KEY_STARTED = "started_at"

    private val mutableStatus = MutableStateFlow<RecordingStatus>(RecordingStatus.Idle)
    val status = mutableStatus.asStateFlow()

    internal fun update(status: RecordingStatus) { mutableStatus.value = status }

    internal fun rememberActive(context: Context, file: File, startedAt: Long) {
        prefs(context).edit().putString(KEY_PATH, file.absolutePath).putLong(KEY_STARTED, startedAt).apply()
    }

    internal fun clearActive(context: Context) { prefs(context).edit().clear().apply() }

    fun recoverInterrupted(context: Context): RecordingResult? {
        val preferences = prefs(context)
        val path = preferences.getString(KEY_PATH, null) ?: return null
        val startedAt = preferences.getLong(KEY_STARTED, 0)
        val file = File(path)
        preferences.edit().clear().apply()
        if (!file.isFile || file.length() < 1_024) return null
        return RecordingResult(
            file = file,
            durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0),
            sha256 = file.sha256(),
            recovered = true,
        ).also { mutableStatus.value = RecordingStatus.Finished(it) }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

internal fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
