package local.freescene.freescene

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WatchHistoryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val metadata get() = JSONObject().put("title", "Series").put("sourceType", "torrent").put("provider", "Zona")
    @Before fun clear() { context.getSharedPreferences("watch-history", Context.MODE_PRIVATE).edit().clear().commit() }
    private fun entries(store: WatchHistory = WatchHistory(context)) = store.snapshot()["entries"] as List<*>
    @Test fun threeSecondsExcludePauseAndCountOnlyOncePerFile() {
        var records = 0
        val clock = WatchLaunchClock { records++; true }
        clock.sample(true, 0); clock.sample(true, 1000); clock.sample(false, 2999)
        assertEquals(0, records)
        clock.sample(false, 100000); clock.sample(true, 101000); clock.sample(true, 101001)
        assertEquals(1, records)
        clock.sample(true, 102000); assertEquals(1, records)
        clock.reset(); clock.sample(true, 103000); clock.sample(true, 105999); assertEquals(1, records)
        clock.sample(true, 106000); assertEquals(2, records)
        clock.reset(); clock.sample(true, 0); clock.sample(true, 1000000); assertEquals(0L, clock.elapsed)
    }
    @Test fun filesAreSeparateAndRepeatLaunchMergesKnownFields() {
        val store = WatchHistory(context)
        assertTrue(store.record("torrent:abc:1", metadata.put("subtitle", "/private/Series.S01E01.mkv").put("url", "https://secret/?token=yes"), 1000))
        store.record("torrent:abc:2", metadata.put("subtitle", "Series.S01E02.mkv"), 2000)
        store.record("torrent:abc:1", JSONObject().put("title", "Series").put("sourceType", "torrent"), 3000)
        val values = entries(); assertEquals(2, values.size)
        val first = values.first() as Map<*, *>
        assertEquals(WatchHistory.hash("torrent:abc:1"), first["id"])
        assertEquals("Zona", first["provider"]); assertEquals("Series.S01E01.mkv", first["subtitle"])
        assertFalse(first.containsKey("url"))
        assertEquals(1, store.remove(listOf(first["id"]))); assertEquals(1, entries().size)
    }
    @Test fun limitAndMalformedDataRecoverWithoutTouchingOtherStores() {
        val store = WatchHistory(context)
        WatchedHistory(context).set("source", "title", true)
        repeat(502) { store.record("file:$it", metadata, 1000L + it) }
        assertEquals(500, entries().size)
        assertEquals(1501L, (entries().first() as Map<*, *>)["watchedAt"])
        context.getSharedPreferences("watch-history", Context.MODE_PRIVATE).edit().putString("entries", "broken").commit()
        assertTrue(entries().isEmpty()); assertTrue(store.record("fresh", metadata))
        assertEquals("title", WatchedHistory(context).snapshot()["source"])
    }
    @Test fun validationRejectsSecretsAndInvalidNumbers() {
        val value = WatchHistory.clean(metadata.put("quality", "https://host/?token=yes").put("season", -1).put("episode", 10000).put("sizeBytes", true))
        assertFalse(value.has("quality")); assertFalse(value.has("season")); assertFalse(value.has("episode")); assertFalse(value.has("sizeBytes"))
        assertFalse(WatchHistory(context).record("bad", metadata.put("title", "magnet:?xt=secret")))
        assertThrows(IllegalArgumentException::class.java) { WatchHistory(context).remove(listOf("not-an-id")) }
    }
    @Test fun webProgressCannotEarnTimeBySeeking() {
        var records = 0
        val clock = WatchLaunchClock { records++; true }
        val bridge = WebPlaybackProgress(received = { _, _ -> }, playing = { clock.sample(it) })
        val looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        fun report(position: Long, playing: Boolean) { bridge.postMessage("""{"position":$position,"duration":7200000,"playing":$playing}"""); looper.idle() }
        report(0, true); looper.idleFor(java.time.Duration.ofSeconds(1)); report(3600000, false)
        looper.idleFor(java.time.Duration.ofSeconds(90)); report(3600000, false); assertEquals(0, records)
        report(3600000, true)
        repeat(2) { looper.idleFor(java.time.Duration.ofSeconds(1)); report(3601000L + it * 1000, true) }
        assertEquals(1, records)
    }
}
