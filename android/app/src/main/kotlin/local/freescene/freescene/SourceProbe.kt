package local.freescene.freescene

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** One bounded, disposable probe; never uses the playback engine or its cache. */
object SourceProbe {
    private val worker = Executors.newSingleThreadExecutor()
    private val epoch = AtomicInteger()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var connection: HttpURLConnection? = null
    fun cancel() { epoch.incrementAndGet(); connection?.disconnect() }
    fun start(context: Context, call: MethodCall, result: MethodChannel.Result) {
        val generation = epoch.get()
        val bytes = call.argument<ByteArray>("bytes")
        val magnet = call.argument<String>("magnet")
        if ((bytes == null && magnet == null) ||
            (bytes != null && (bytes.size !in 1..16*1024*1024 || bytes[0] != 'd'.code.toByte())) ||
            (magnet != null && !TorrentPolicy.magnet(magnet))) {
            result.success("unavailable"); return
        }
        worker.execute {
            val directory = File(context.cacheDir, "source-probe").apply { deleteRecursively(); mkdirs() }
            var handle = 0L
            var status = "timeout"
            val network = ProbeNetworkState.snapshot(context.filesDir, directory)
            try {
                if (generation != epoch.get()) return@execute main.post { result.success("cancelled") }.let { }
                val ca = File(directory, "ca.pem")
                val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
                ca.writeText(store.aliases().toList().mapNotNull { store.getCertificate(it) }.joinToString("\n") {
                    "-----BEGIN CERTIFICATE-----\n" + android.util.Base64.encodeToString(it.encoded, android.util.Base64.NO_WRAP).chunked(64).joinToString("\n") + "\n-----END CERTIFICATE-----"
                })
                handle = TorrentNative.create(JSONObject().put("profile", File(directory,"profile").path).put("downloads", File(directory,"buffer").path)
                    .put("networkState", network.path)
                    .put("lite",true).put("streamOnly",true).put("caBundle",ca.path).toString())
                check(handle != 0L)
                fun command(request: JSONObject): JSONObject {
                    val reply = JSONObject(TorrentNative.command(handle,request.toString()))
                    check(!reply.has("error"))
                    return reply.getJSONObject("result")
                }
                val source = if(bytes != null) File(directory,"source.torrent").apply { writeBytes(bytes) }.path else magnet!!
                val add = JSONObject().put("method","add").put("source",source)
                if(bytes != null && magnet != null) add.put("expectedMagnet",magnet)
                val id = command(add).getString("id")
                command(JSONObject().put("method","prepareStream").put("id",id))
                val until = System.nanoTime() + 20_000_000_000L
                var file = -1
                while(generation == epoch.get() && System.nanoTime() < until) {
                    val tasks = command(JSONObject().put("method","status")).getJSONArray("tasks")
                    if(tasks.length()>0) {
                        val files = tasks.getJSONObject(0).getJSONArray("files")
                        for(i in 0 until files.length()) if(files.getJSONObject(i).optBoolean("video")) { file=files.getJSONObject(i).getInt("index"); break }
                    }
                    if(file>=0) break
                    Thread.sleep(200)
                }
                if(file>=0 && generation==epoch.get() && System.nanoTime() < until) {
                    val url=command(JSONObject().put("method","stream").put("id",id).put("file",file)).getString("url")
                    val http=URL(url).openConnection() as HttpURLConnection
                    connection=http
                    http.connectTimeout=2000
                    http.readTimeout=((until-System.nanoTime())/1_000_000).coerceIn(1,20000).toInt()
                    http.setRequestProperty("Range","bytes=0-65535")
                    status = if(http.responseCode==206) http.inputStream.use { if(it.read()>=0) "data" else "unavailable" } else "unavailable"
                }
                if(generation != epoch.get()) status="cancelled"
            } catch (_: SocketTimeoutException) { status = if(generation != epoch.get()) "cancelled" else "timeout" }
            catch (_: Exception) { status = if(generation != epoch.get()) "cancelled" else "unavailable" }
            finally {
                connection?.disconnect(); connection=null
                if(handle!=0L) TorrentNative.destroy(handle)
                ProbeNetworkState.save(context.filesDir, network)
                directory.deleteRecursively()
            }
            main.post { result.success(status) }
        }
    }
}
