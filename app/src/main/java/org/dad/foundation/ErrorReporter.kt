package org.dad.foundation

// Client error tracking (PRD §10 observability, Phase 9). A single seam the screens report
// through, so a failure on any screen can reach a crash/error backend without the UI coupling to
// a specific SDK. The default is a no-op (nothing is sent until a reporter is installed at
// release); the recorder de-dups so one failing load does not spam the backend. Pure + testable.

/** One reported client error. [screen] and [operation] locate it; [kind] is a coarse bucket. */
data class ClientError(
    val screen: String,
    val operation: String,
    val kind: Kind,
    val detail: String,
) {
    enum class Kind { SIGNED_OUT, UNAVAILABLE, INVALID_RESPONSE, UNKNOWN;
        companion object {
            /** Map an AccountFailure (or anything) to a coarse, PII-free bucket. */
            fun of(error: Throwable): Kind = when (error) {
                is AccountFailure.SignedOut -> SIGNED_OUT
                is AccountFailure.Unavailable -> UNAVAILABLE
                is AccountFailure.InvalidResponse -> INVALID_RESPONSE
                else -> UNKNOWN
            }
        }
    }

    /** A stable de-dup key: the same error on the same screen/operation reports once per session. */
    val dedupeKey: String get() = "$screen|$operation|$kind"
}

/** The seam the screens report through. A conformer forwards to a crash/error backend. */
interface ErrorReporter {
    fun report(error: ClientError)
}

/** The shipped default until a backend reporter is installed: reports nothing. Keeps the call
 *  sites in place (screens always report) without sending anything in a build with no backend. */
object NoopErrorReporter : ErrorReporter {
    override fun report(error: ClientError) {}
}

/**
 * Wraps a reporter and drops repeats within a session (one failing load should not spam the
 * backend). Thread-confined to the main thread like the models that use it; not for concurrent use.
 */
class DedupingErrorReporter(private val delegate: ErrorReporter) : ErrorReporter {
    private val seen = mutableSetOf<String>()
    override fun report(error: ClientError) {
        if (seen.add(error.dedupeKey)) delegate.report(error)
    }
    /** Test/inspection aid: how many distinct errors have been forwarded this session. */
    val reportedCount: Int get() = seen.size
}
