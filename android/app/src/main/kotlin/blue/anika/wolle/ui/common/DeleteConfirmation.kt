package blue.anika.wolle.ui.common

/**
 * Guards for deleting a record. A delete is only sent for the exact record the user was asked
 * about, and only for a tap that comes a moment after the confirmation appeared, so a double tap or
 * a touch that was still on its way from the menu cannot confirm it by accident.
 */
object DeleteConfirmation {
    /** How long a confirmation ignores taps after it appeared. */
    const val ARM_DELAY_MILLIS = 700L

    /**
     * The id to delete, or null when the confirmation no longer applies: nothing was asked
     * ([requestedId] null), the screen shows another record than the one that was asked about, or
     * the record is not loaded.
     */
    fun targetToDelete(requestedId: Int?, currentId: Int?, loaded: Boolean): Int? =
        requestedId?.takeIf {
            loaded && currentId != null && it == currentId
        }

    /**
     * Proof that the user confirmed deleting [id]. Only [confirm] makes one (and [forTesting] for
     * tests), so no automatic code path - an import draft, a sync, a back handler - can call
     * `ProjectRepository.deleteProject`: it does not compile without a confirmation.
     */
    class Confirmed private constructor(val id: Int) {
        companion object {
            internal fun create(id: Int) = Confirmed(id)

            /** For tests that need a delete without a screen. */
            fun forTesting(id: Int) = Confirmed(id)
        }
    }

    /** Like [targetToDelete], as a [Confirmed] that the repository accepts. */
    fun confirm(requestedId: Int?, currentId: Int?, loaded: Boolean): Confirmed? =
        targetToDelete(requestedId, currentId, loaded)?.let(Confirmed::create)

    /** True once a confirmation shown at [shownAtMillis] may be confirmed at [nowMillis]. */
    fun isArmed(shownAtMillis: Long, nowMillis: Long): Boolean =
        nowMillis - shownAtMillis >= ARM_DELAY_MILLIS
}
