package local.freescene.freescene

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProbeNetworkStateTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun probeCopiesContactsWithoutOverwritingPlaybackState() {
        val files = folder.newFolder("files")
        val playback = File(files, "torrent-network/dht.state").apply { parentFile!!.mkdirs(); writeText("playback") }
        val copy = ProbeNetworkState.snapshot(files, folder.newFolder("probe"))
        assertEquals("playback", copy.readText())
        copy.writeText("probe")
        ProbeNetworkState.save(files, copy)
        assertEquals("playback", playback.readText())
        val cache = File(files, "source-probe-network/dht.state")
        assertEquals("probe", cache.readText())
        cache.setLastModified(playback.lastModified() + 1000)
        assertEquals("probe", ProbeNetworkState.snapshot(files, folder.newFolder("next")).readText())
    }
    @Test fun absentOrOversizedContactsDoNotBlockColdStart() {
        val files = folder.newFolder("files")
        assertFalse(ProbeNetworkState.snapshot(files, folder.newFolder("empty")).exists())
        File(files, "torrent-network/dht.state").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(1024 * 1024 + 1)) }
        assertFalse(ProbeNetworkState.snapshot(files, folder.newFolder("large")).exists())
    }
}
