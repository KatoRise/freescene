package local.freescene.freescene

import org.junit.Assert.*
import org.junit.Test

class TvTorrentSessionTest {
    private class Fixture {
        var disposed = 0
        val expirations = mutableListOf<() -> Unit>()
        val cancelled = mutableListOf<Int>()
        val session = TvTorrentSession(dispose = { disposed++ }, scheduleExpiry = { expiry ->
            val index = expirations.size
            expirations.add(expiry)
            val cancel: () -> Unit = { cancelled.add(index) }
            cancel
        })
        fun open(source: String = "magnet:first", id: String = "a".repeat(40)): Long {
            session.prepare(source)
            return session.opened(id)
        }
    }

    @Test fun quickReturnRetainsPeersAndBufferAndCancelsCleanup() {
        val f = Fixture(); val id = "a".repeat(40)
        val old = f.open()
        f.session.release(id, old)
        assertEquals(0, f.disposed)
        val current = f.open()
        assertEquals(listOf(0), f.cancelled)
        // Even an already queued cleanup for the old player cannot delete the new stream.
        f.expirations.single().invoke()
        assertEquals(0, f.disposed)
        assertTrue(f.session.owns(id, current))
        assertFalse(f.session.owns(id, old))
    }

    @Test fun oldPlayerCannotPauseOrCloseReopenedTorrentWithSameHash() {
        val f = Fixture(); val id = "a".repeat(40)
        val old = f.open(); val current = f.open()
        assertFalse(f.session.owns(id, old))
        f.session.release(id, old)
        assertTrue(f.expirations.isEmpty())
        assertTrue(f.session.owns(id, current))
        assertEquals(0, f.disposed)
    }

    @Test fun abandonedStreamExpiresAndItsLeaseCannotTouchANewSession() {
        val f = Fixture(); val id = "a".repeat(40)
        val old = f.open()
        f.session.release(id, old)
        f.expirations.single().invoke()
        assertEquals(1, f.disposed)
        assertFalse(f.session.owns(id, old))
        val current = f.open()
        f.session.release(id, old)
        assertTrue(f.session.owns(id, current))
        assertEquals(1, f.expirations.size)
    }

    @Test fun anotherSourceDiscardsThePreviousBufferImmediately() {
        val f = Fixture()
        val first = f.open()
        f.session.release("a".repeat(40), first)
        val current = f.open("magnet:second", "b".repeat(40))
        assertEquals(1, f.disposed)
        f.expirations.single().invoke()
        assertEquals(1, f.disposed)
        assertTrue(f.session.owns("b".repeat(40), current))
        assertFalse(f.session.owns("a".repeat(40), first))
    }

    @Test fun failedImportDiscardsAPartiallyPreparedSession() {
        val f = Fixture(); val first = f.open()
        f.session.release("a".repeat(40), first)
        f.session.prepare("magnet:first")
        f.session.abort()
        f.expirations.single().invoke()
        assertEquals(1, f.disposed)
        assertFalse(f.session.owns("a".repeat(40), first))
    }
}
