package org.dad.foundation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorReporterTest {
    @Test fun kindMapsAccountFailures() {
        assertEquals(ClientError.Kind.SIGNED_OUT, ClientError.Kind.of(AccountFailure.SignedOut))
        assertEquals(ClientError.Kind.UNAVAILABLE, ClientError.Kind.of(AccountFailure.Unavailable))
        assertEquals(ClientError.Kind.INVALID_RESPONSE, ClientError.Kind.of(AccountFailure.InvalidResponse))
        assertEquals(ClientError.Kind.UNKNOWN, ClientError.Kind.of(RuntimeException("boom")))
    }

    @Test fun dedupeKeyIsStablePerScreenOperationKind() {
        val a = ClientError("settings", "load", ClientError.Kind.SIGNED_OUT, "x")
        val b = ClientError("settings", "load", ClientError.Kind.SIGNED_OUT, "different detail")
        val c = ClientError("settings", "save", ClientError.Kind.SIGNED_OUT, "x")
        assertEquals(a.dedupeKey, b.dedupeKey) // detail does not affect the key
        assertTrue(a.dedupeKey != c.dedupeKey) // operation does
    }

    private class RecordingReporter : ErrorReporter {
        val received = mutableListOf<ClientError>()
        override fun report(error: ClientError) { received.add(error) }
    }

    @Test fun dedupingReporterForwardsEachDistinctErrorOnce() {
        val sink = RecordingReporter()
        val reporter = DedupingErrorReporter(sink)
        val err = ClientError("events", "reserve", ClientError.Kind.UNAVAILABLE, "409")
        reporter.report(err)
        reporter.report(err) // same key — dropped
        reporter.report(ClientError("events", "reserve", ClientError.Kind.UNAVAILABLE, "other detail")) // same key — dropped
        reporter.report(ClientError("events", "cancel", ClientError.Kind.UNAVAILABLE, "409")) // different op — forwarded
        assertEquals(2, sink.received.size)
        assertEquals(2, reporter.reportedCount)
    }

    @Test fun noopReporterDoesNothing() {
        // Just exercises the shipped default — no throw, no state.
        NoopErrorReporter.report(ClientError("x", "y", ClientError.Kind.UNKNOWN, "z"))
    }
}
