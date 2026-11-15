package local.freescene.freescene

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Launch history is independent of resume positions, watched marks and downloads. */
class WatchHistory(context: Context) {
    private val prefs = context.getSharedPreferences("watch-history", Context.MODE_PRIVATE)
    fun ids(): List<String> = synchronized(lock) { read().map { it.getString("id") } }
    fun snapshot(): Map<String, Any> = synchronized(lock) { mapOf("entries" to read().map(TorrentEngine::map)) }
    private fun read(): List<JSONObject> = try {
        val root = JSONObject(prefs.getString("entries", "{}") ?: "{}")
        val entries = if (root.opt("version") == 1) root.optJSONArray("entries") else null
        (0 until (entries?.length() ?: 0).coerceAtMost(5000)).mapNotNull { i ->
            entries!!.optJSONObject(i)?.let { raw ->
                val id = raw.opt("id") as? String
                val time = number(raw.opt("watchedAt"), 1, 8640000000000000)
                val value = clean(raw)
                if (id == null || !idPattern.matches(id) || time == null || !value.has("title") || !value.has("sourceType")) null
                else value.put("id", id).put("watchedAt", time)
            }
        }.sortedWith(compareByDescending<JSONObject> { it.getLong("watchedAt") }.thenBy { it.getString("id") })
            .distinctBy { it.getString("id") }.take(500)
    } catch (_: Exception) { emptyList() }
    private fun write(entries: List<JSONObject>) {
        check(prefs.edit().putString("entries", JSONObject().put("version", 1).put("entries", JSONArray(entries.take(500))).toString()).commit())
    }
    fun record(key: String, metadata: JSONObject, now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        try {
            val value = clean(metadata)
            if (!value.has("title") || !value.has("sourceType") || now <= 0) return@synchronized false
            val id = hash(key.take(16384))
            val old = read()
            val merged = old.find { it.optString("id") == id } ?: JSONObject()
            value.keys().forEach { merged.put(it, value.get(it)) }
            merged.put("id", id).put("watchedAt", now)
            write((old.filter { it.optString("id") != id } + merged).sortedWith(
                compareByDescending<JSONObject> { it.getLong("watchedAt") }.thenBy { it.getString("id") }))
            true
        } catch (_: Exception) { false } // Never interrupt playback for a history write.
    }
    fun remove(ids: List<*>): Int = synchronized(lock) {
        require(ids.size <= 500 && ids.all { it is String && idPattern.matches(it) })
        val old = read(); val kept = old.filter { it.getString("id") !in ids }
        write(kept); old.size - kept.size
    }
    companion object {
        private val lock = Any()
        private val idPattern = Regex("[a-f0-9]{64}")
        fun hash(key: String): String = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun number(value: Any?, min: Long, max: Long): Long? = (value as? Number)?.toDouble()?.let {
            if (it.isFinite() && it >= min && it <= max && it == it.toLong().toDouble()) it.toLong() else null
        }
        fun clean(raw: JSONObject): JSONObject = JSONObject().apply {
            for (field in listOf("title", "subtitle", "provider", "quality")) {
                var value = (raw.opt(field) as? String)?.trim() ?: continue
                if (value.contains(Regex("(?i)://|magnet:|(?:token|cookie|authorization|password)\\s*="))) continue
                if (field == "subtitle") value = value.replace('\\', '/').substringAfterLast('/')
                else if (value.startsWith('/') || value.contains(Regex("^[A-Za-z]:[\\\\/]"))) continue
                value = value.filter { !it.isISOControl() }.take(if (field == "quality") 64 else 1024)
                if (value.isNotBlank() && value != "—") put(field, value)
            }
            for ((field, max) in listOf("season" to 999L, "episode" to 9999L, "sizeBytes" to 9007199254740991L))
                number(raw.opt(field), if (field == "sizeBytes") 1 else 0, max)?.let { put(field, it) }
            (raw.opt("sourceType") as? String)?.takeIf { it in setOf("torrent", "provider", "download") }?.let { put("sourceType", it) }
        }
        fun metadata(value: String?): JSONObject = try { JSONObject(value ?: "{}") } catch (_: Exception) { JSONObject() }
        fun key(metadata: JSONObject, stable: String?): String = stable?.takeIf { it.isNotBlank() } ?: listOf("sourceType", "title", "subtitle", "provider", "quality").joinToString("|") { metadata.optString(it) }
    }
}

/** Three seconds of actual playback; seeking and time spent paused do not count. */
class WatchLaunchClock(private val record: () -> Boolean) {
    var elapsed = 0L
    var recorded = false
    private var last: Long? = null
    private var active = false
    fun reset() { elapsed = 0; recorded = false; last = null; active = false }
    fun sample(playing: Boolean, now: Long = android.os.SystemClock.elapsedRealtime()) {
        val previous = last
        if (!recorded && active && previous != null && now - previous in 0..6000) {
            elapsed += now - previous
            if (elapsed >= 3000) recorded = record()
        }
        active = playing; last = now
    }
}
