package local.freescene.freescene

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** The selected source outlives the disposable native engine, but only until Back. */
internal class TvPlaybackLaunch(private val directory: File) {
    private val manifest get() = AtomicFile(File(directory, "launch.json"))
    private fun read(): JSONObject? = runCatching {
        JSONObject(manifest.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
    }.getOrNull()

    fun remember(id: String, bytes: ByteArray?, magnet: String?, origin: JSONObject): String {
        clear()
        check(directory.mkdirs())
        val token = UUID.randomUUID().toString()
        val source = if (bytes != null) File(directory, "source.torrent").apply { writeBytes(bytes) }.path else magnet!!
        val add = JSONObject().put("method", "add").put("source", source).put("origin", origin)
        if (bytes != null && magnet != null) add.put("expectedMagnet", magnet)
        val record = JSONObject().put("id", id).put("token", token).put("add", add)
        val file = manifest
        val output = file.startWrite()
        try {
            output.write(record.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            clear()
            throw failure
        }
        return token
    }

    fun owns(id: String, token: String): Boolean = read()?.let {
        token.isNotEmpty() && it.optString("token") == token && it.optString("id") == id
    } == true

    /** Never revive an old player over a newer selection, even for the same hash. */
    fun prepare(id: String, token: String, hasTask: () -> Boolean, add: (JSONObject) -> String): Boolean {
        val record = read()
        check(record != null && token.isNotEmpty() && record.optString("id") == id && record.optString("token") == token) {
            "Сеанс просмотра завершён. Вернитесь к источникам и выберите фильм снова."
        }
        if (hasTask()) return false
        check(add(record.getJSONObject("add")) == id) { "Восстановленная раздача не совпадает с выбранной" }
        return true
    }

    fun clear() { directory.deleteRecursively() }
}
