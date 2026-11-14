package local.freescene.freescene

/** Accessed only on the torrent executor. A lease belongs to one player, not an infohash. */
internal class TvTorrentSession(
    private val dispose: () -> Unit,
    private val scheduleExpiry: (() -> Unit) -> (() -> Unit),
) {
    private var source: String? = null
    private var task: String? = null
    private var generation = 0L
    private var cancelExpiry: (() -> Unit)? = null

    fun prepare(source: String) {
        cancelExpiry?.invoke(); cancelExpiry = null
        if (this.source != null && this.source != source) discard()
        this.source = source
        generation++
    }

    fun opened(id: String): Long { task = id; return generation }

    fun owns(id: String, lease: Long): Boolean = task == id && generation == lease

    fun release(id: String, lease: Long) {
        if (!owns(id, lease)) return
        cancelExpiry?.invoke()
        cancelExpiry = scheduleExpiry {
            if (owns(id, lease)) { cancelExpiry = null; discard() }
        }
    }

    fun abort() {
        cancelExpiry?.invoke(); cancelExpiry = null
        discard()
    }

    private fun discard() {
        dispose()
        source = null; task = null
    }
}
