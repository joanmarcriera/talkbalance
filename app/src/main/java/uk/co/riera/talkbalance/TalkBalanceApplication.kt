package uk.co.riera.talkbalance

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uk.co.riera.talkbalance.data.AppDatabase
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
     * and any legacy `recordings/` — no audio is written any more). Idempotent; failures on
     * individual files are swallowed so one locked file never blocks the wipe.
     */
    suspend fun deleteAllData() {
        database.personDao().deleteAll()
        database.sessionDao().deleteAllSessions() // CASCADE removes speaker_stats
        database.sessionDao().deleteAllStats()    // belt-and-braces for any orphan rows
        settingsRepository.clearAll()
        filesDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    companion object {
        private const val TAG = "TalkBalanceSherpa"
    }
}
