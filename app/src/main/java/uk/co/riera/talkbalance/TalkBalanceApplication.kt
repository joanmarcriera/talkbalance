package uk.co.riera.talkbalance

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.riera.talkbalance.data.AppDatabase
import uk.co.riera.talkbalance.data.DeleteAllResult
import uk.co.riera.talkbalance.data.StoreDeletion
import uk.co.riera.talkbalance.ml.SherpaEngines
import uk.co.riera.talkbalance.settings.SettingsRepository

class TalkBalanceApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var sherpaEngines: SherpaEngines
        private set

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        sherpaEngines = SherpaEngines(assets)
        applicationScope.launch {
            sherpaEngines.initialize()
                .onSuccess { snapshot ->
                    Log.i(
                        TAG,
                        "vad ok, extractor dim=${snapshot.embeddingDimension}, " +
                            "model=${snapshot.embeddingModel}, initMs=${snapshot.initializationMs}",
                    )
                }
                .onFailure { error ->
                    Log.e(TAG, "sherpa initialization failed", error)
                }
        }
    }

    /**
     * "Delete all data": clears every persistent store the app owns — Room (people, sessions,
     * per-speaker stats), the DataStore settings, and every file under `filesDir` (the T3 bench CSV
     * and any legacy `recordings/` — no audio is written any more).
     *
     * Each store is deleted independently and its outcome checked, so the caller (Settings UI) can
     * report the truth instead of assuming success:
     * - Room deletes run in **one transaction** so the DB is never left half-cleared, followed by a
     *   `wal_checkpoint(TRUNCATE)` to fold the write-ahead log back into the main DB file. Note this
     *   does NOT scrub already-freed pages from the main database (there is no `VACUUM` or
     *   crypto-erase), so the UI copy is the honest "removes all Talk Balance data from the app",
     *   not a secure/forensic-erasure claim.
     * - Every file must actually be gone; a failed `deleteRecursively()` is reported, not swallowed.
     */
    suspend fun deleteAllData(): DeleteAllResult {
        val room = runCatching {
            database.sessionDao().deleteAllPersonsSessionsStatsTx()
            withContext(Dispatchers.IO) {
                database.openHelper.writableDatabase
                    .query("PRAGMA wal_checkpoint(TRUNCATE)")
                    .use { it.moveToFirst() }
            }
        }.fold({ StoreDeletion.Success }, { StoreDeletion.Failed(it.message ?: "room error") })

        val settings = runCatching { settingsRepository.clearAll() }
            .fold({ StoreDeletion.Success }, { StoreDeletion.Failed(it.message ?: "settings error") })

        val files = runCatching {
            val remaining = filesDir.listFiles() ?: emptyArray()
            val allDeleted = remaining.all { it.deleteRecursively() }
            check(allDeleted) { "one or more files could not be deleted" }
        }.fold({ StoreDeletion.Success }, { StoreDeletion.Failed(it.message ?: "file error") })

        return DeleteAllResult(room = room, settings = settings, files = files)
            .also { if (!it.allSucceeded) Log.w(TAG, "deleteAllData incomplete: ${it.summary}") }
    }

    companion object {
        private const val TAG = "TalkBalanceSherpa"
    }
}
