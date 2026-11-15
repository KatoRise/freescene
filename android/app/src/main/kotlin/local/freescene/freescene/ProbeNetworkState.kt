package local.freescene.freescene

import java.io.File

/** Copy routing contacts, never share the writable playback state with a probe. */
internal object ProbeNetworkState {
    fun snapshot(files: File, directory: File): File {
        val target = File(directory, "dht.state")
        val candidates = listOf(File(files, "torrent-network/dht.state"), File(files, "source-probe-network/dht.state"))
        for (source in candidates.sortedByDescending { it.lastModified() }) {
            try {
                if (!source.isFile || source.length() !in 1..1024 * 1024) continue
                source.copyTo(target, overwrite = true)
                break
            } catch (_: Exception) { /* A routing cache is optional. */ }
        }
        return target
    }

    fun save(files: File, snapshot: File) {
        try {
            if (!snapshot.isFile || snapshot.length() !in 1..1024 * 1024) return
            val target = File(files, "source-probe-network/dht.state")
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, "dht.state.tmp")
            snapshot.copyTo(temporary, overwrite = true)
            if (!temporary.renameTo(target)) temporary.delete()
        } catch (_: Exception) { /* Never fail a source check for a cache write. */ }
    }
}
