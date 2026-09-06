package uk.co.riera.talkbalance.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uk.co.riera.talkbalance.data.HistorySpeakerRow
import uk.co.riera.talkbalance.data.HistorySummary
import uk.co.riera.talkbalance.data.PersonDao
import uk.co.riera.talkbalance.data.SessionDao
import uk.co.riera.talkbalance.data.SessionHistory
import uk.co.riera.talkbalance.session.Mode
import uk.co.riera.talkbalance.session.SessionPersistence

/**
 * Read side of session history: exposes every saved session, newest first, already resolved to
 * display rows. Purely a reader plus a delete — there is no live audio pipeline here, so unlike
 * [SessionViewModel] it owns no engines, no lease and no microphone.
 *
 * Sessions and people are combined into one flow so that renaming or deleting a person updates
 * every past report immediately, rather than showing stale names until the screen is reopened.
 */
class HistoryViewModel(
    private val sessionDao: SessionDao,
    personDao: PersonDao,
) : ViewModel() {

    /** One saved session, fully resolved for display. */
    data class Entry(
        val sessionId: Long,
        val startedAtEpochMs: Long,
        val durationMs: Long,
        val modeName: String,
        val rows: List<HistorySpeakerRow>,
        val summary: HistorySummary?,
    )

    val sessions: StateFlow<List<Entry>> =
        combine(
            sessionDao.observeSessionsWithStatsNewestFirst(),
            personDao.observeAll(),
        ) { saved, people ->
            val personsById = people.associateBy { it.id }
            saved.map { withStats ->
                val mode = SessionPersistence.modeFromTag(withStats.session.mode)
                val rows = SessionHistory.rowsFor(
                    stats = withStats.stats,
                    personsById = personsById,
                    // An unrecognised mode tag still needs a bucket label; TABLE's is the neutral one.
                    everyoneElseLabel = (mode ?: Mode.TABLE).everyoneElseLabel,
                )
                Entry(
                    sessionId = withStats.session.id,
                    startedAtEpochMs = withStats.session.startedAtEpochMs,
                    durationMs = withStats.session.durationMs ?: 0L,
                    modeName = SessionPersistence.modeDisplayName(withStats.session.mode),
                    rows = rows,
                    summary = SessionHistory.summaryFor(rows),
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Delete one saved session; its speaker rows cascade. Irreversible, so confirm before calling. */
    fun delete(sessionId: Long) {
        viewModelScope.launch { sessionDao.deleteSession(sessionId) }
    }

    companion object {
        fun factory(sessionDao: SessionDao, personDao: PersonDao): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HistoryViewModel(sessionDao, personDao) as T
            }
    }
}
