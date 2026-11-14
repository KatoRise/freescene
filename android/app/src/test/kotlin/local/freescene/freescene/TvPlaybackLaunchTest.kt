package local.freescene.freescene

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TvPlaybackLaunchTest {
    @get:Rule val folder = TemporaryFolder()
    private val id = "a".repeat(40)
    private val magnet = "magnet:?xt=urn:btih:$id"
    private val origin = JSONObject().put("provider", "zona")
    private fun launch() = TvPlaybackLaunch(File(folder.root, "launch"))

    @Test fun restoresSelectedTorrentFileAfterProcessDeathBeforePreparing() {
        val bytes = "d4:infodee".toByteArray()
        val token = launch().remember(id, bytes, magnet, origin)
        // A new owner with an empty engine models Android recreating PlayerActivity.
        val restarted = launch()
        var taskExists = false
        var adds = 0
        val add: (JSONObject) -> String = { args ->
            assertArrayEquals(bytes, File(args.getString("source")).readBytes())
            assertEquals(magnet, args.getString("expectedMagnet"))
            assertEquals("zona", args.getJSONObject("origin").getString("provider"))
            adds++; taskExists = true; id
        }
        assertTrue(restarted.prepare(id, token, { taskExists }, add))
        assertFalse(restarted.prepare(id, token, { taskExists }, add))
        assertEquals(1, adds)
    }

    @Test fun restoresMagnetWithoutRequiringTorrentBytes() {
        val token = launch().remember(id, null, magnet, origin)
        assertTrue(launch().prepare(id, token, { false }) {
            assertEquals(magnet, it.getString("source"))
            assertFalse(it.has("expectedMagnet")); id
        })
    }

    @Test fun oldPlayerCannotClosePauseOrRestoreNewSessionWithSameHash() {
        val session = launch()
        val old = session.remember(id, null, magnet, origin)
        val current = session.remember(id, null, magnet, origin)
        assertNotEquals(old, current)
        assertFalse(session.owns(id, old))
        assertTrue(session.owns(id, current))
        assertThrows(IllegalStateException::class.java) {
            session.prepare(id, old, { fail("Stale player reached engine"); false }) { fail("Stale player restored"); id }
        }
        assertTrue(session.owns(id, current))
    }

    @Test fun finishingPlaybackDeletesPayloadAndPreventsResurrection() {
        val token = launch().remember(id, "torrent bytes".toByteArray(), magnet, origin)
        launch().clear()
        assertFalse(File(folder.root, "launch").exists())
        assertFalse(launch().owns(id, token))
        assertThrows(IllegalStateException::class.java) {
            launch().prepare(id, token, { false }) { fail("Finished source restored"); id }
        }
    }

    @Test fun wrongHashOrMissingTokenCannotUseActiveLaunch() {
        val token = launch().remember(id, null, magnet, origin)
        assertFalse(launch().owns("b".repeat(40), token))
        assertFalse(launch().owns(id, ""))
        assertThrows(IllegalStateException::class.java) {
            launch().prepare(id, token, { false }) { "b".repeat(40) }
        }
    }
}
