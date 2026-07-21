package uk.co.riera.talkbalance.data

/** Per-store outcome of a "delete all data" wipe. */
sealed interface StoreDeletion {
    data object Success : StoreDeletion
    data class Failed(val reason: String) : StoreDeletion

    val ok: Boolean get() = this is Success
}

/**
 * Structured outcome of [uk.co.riera.talkbalance.TalkBalanceApplication.deleteAllData]. Every
 * independent store is reported separately so the Settings UI can tell the user the truth
 * ("all deleted" vs "settings couldn't be cleared") instead of silently claiming success.
 */
data class DeleteAllResult(
    val room: StoreDeletion,
    val settings: StoreDeletion,
    val files: StoreDeletion,
) {
    /** True only when every store was cleared. */
    val allSucceeded: Boolean get() = room.ok && settings.ok && files.ok

    /** A user-facing summary line naming any store that failed. */
    val summary: String
        get() = if (allSucceeded) {
            "All data deleted."
        } else {
            val failed = buildList {
                if (!room.ok) add("people & sessions")
                if (!settings.ok) add("settings")
                if (!files.ok) add("files")
            }
            "Couldn't delete: ${failed.joinToString(", ")}. Try again."
        }
}
