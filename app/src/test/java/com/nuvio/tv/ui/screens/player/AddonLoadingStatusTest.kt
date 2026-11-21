package com.nuvio.tv.ui.screens.player

import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.dto.BehaviorHintsDto
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.toStreamLoadingStatusUrl
import com.nuvio.tv.ui.navigation.Screen
import com.squareup.moshi.Moshi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

@OptIn(ExperimentalCoroutinesApi::class)
class AddonLoadingStatusTest {

    private val streamResponseAdapter = Moshi.Builder().build().adapter(StreamResponseDto::class.java)

    private val statusUrl = "https://addon.example/status/abc?token=a%2Bb&file=1"

    // --- Stream hint ---

    @Test
    fun `addon stream with loadingStatusUrl parses and unknown hints are still ignored`() {
        val json = """
            {"streams":[
              {"url":"http://192.168.1.10:7000/play/abc.mkv","behaviorHints":{
                "bingeGroup":"addon|1080p",
                "loadingStatusUrl":"http://192.168.1.10:7000/status/abc",
                "someFutureHint":{"nested":[1,2,3]},
                "anotherFutureHint":true}},
              {"url":"https://b","behaviorHints":{"notWebReady":true,"futureNumber":12.5}},
              {"url":"https://c","behaviorHints":{"loadingStatusUrl":"/status/relative"}},
              {"url":"https://d"}
            ]}
        """.trimIndent()

        val hints = requireNotNull(streamResponseAdapter.fromJson(json)?.streams)
            .map { it.behaviorHints?.toDomain() }

        assertEquals("http://192.168.1.10:7000/status/abc", hints[0]?.loadingStatusUrl)
        assertEquals("addon|1080p", hints[0]?.bingeGroup)
        assertNull(hints[1]?.loadingStatusUrl)
        assertEquals(true, hints[1]?.notWebReady)
        assertNull(hints[2]?.loadingStatusUrl)
        assertNull(hints[3])
    }

    @Test
    fun `only absolute http and https status urls are kept`() {
        assertEquals(statusUrl, "  $statusUrl ".toStreamLoadingStatusUrl())
        assertEquals("http://192.168.1.10:7000/status", "http://192.168.1.10:7000/status".toStreamLoadingStatusUrl())
        assertNull("/status/abc".toStreamLoadingStatusUrl())
        assertNull("status".toStreamLoadingStatusUrl())
        assertNull("ftp://addon.example/status".toStreamLoadingStatusUrl())
        assertNull("magnet:?xt=urn:btih:abc".toStreamLoadingStatusUrl())
        assertNull("javascript:alert(1)".toStreamLoadingStatusUrl())
        assertNull("   ".toStreamLoadingStatusUrl())
        assertNull((null as String?).toStreamLoadingStatusUrl())
        assertNull(BehaviorHintsDto(loadingStatusUrl = "file:///sdcard/x").toDomain().loadingStatusUrl)
        assertNull(BehaviorHintsDto().toDomain().loadingStatusUrl)
    }

    // --- The hint on its way to the player ---

    @Test
    fun `player route carries the status url and the player reads it back`() {
        val route = Screen.Player.createRoute(
            streamUrl = "https://addon.example/play/abc.mkv",
            title = "Title",
            launchStartedAtMs = 0L,
            loadingStatusUrl = statusUrl
        )
        assertTrue(route.contains("&loadingStatusUrl=${URLEncoder.encode(statusUrl, "UTF-8")}&"))

        // NavController decodes query arguments once; the player must not decode them again.
        val args = PlayerNavigationArgs.from(
            SavedStateHandle(mapOf("streamUrl" to "s", "title" to "T", "loadingStatusUrl" to statusUrl))
        )
        assertEquals(statusUrl, args.loadingStatusUrl)
    }

    @Test
    fun `player route without the hint leaves it unset`() {
        val route = Screen.Player.createRoute(streamUrl = "https://a/b.mkv", title = "Title", launchStartedAtMs = 0L)
        assertTrue(route.contains("&loadingStatusUrl=&"))

        assertNull(PlayerNavigationArgs.from(SavedStateHandle(mapOf("streamUrl" to "s", "title" to "T"))).loadingStatusUrl)
        assertNull(
            PlayerNavigationArgs.from(
                SavedStateHandle(mapOf("streamUrl" to "s", "title" to "T", "loadingStatusUrl" to ""))
            ).loadingStatusUrl
        )
    }

    @Test
    fun `player restored after an in-place switch keeps the new stream's hint`() {
        fun restoredHint(hint: String?): String? {
            val values = playerStreamRouteValues(
                stream = streamWithHint(hint),
                streamUrl = "https://addon.example/play/ep2.mkv",
                headers = emptyMap(),
                videoId = "tt1:1:2",
                season = 1,
                episode = 2,
                episodeTitle = null
            )
            // The first stream had a hint; the route values of the switch overwrite it.
            val handle = SavedStateHandle(mapOf("streamUrl" to "s", "title" to "T", "loadingStatusUrl" to "https://old/status"))
            values.forEach { (key, value) -> handle[key] = value }
            return PlayerNavigationArgs.from(handle).loadingStatusUrl
        }

        assertEquals(statusUrl, restoredHint(statusUrl))
        assertNull(restoredHint(null))
    }

    // --- Status response ---

    @Test
    fun `status with message and progress`() {
        assertEquals(
            AddonLoadingStatus(message = "Downloading 37% · 12 peers", progress = 0.37f),
            parseAddonLoadingStatus("""{"message":"Downloading 37% · 12 peers","progress":0.37}""")
        )
    }

    @Test
    fun `a missing field stays null so the overlay keeps its default for that part`() {
        assertEquals(AddonLoadingStatus(message = null, progress = 0.5f), parseAddonLoadingStatus("""{"progress":0.5}"""))
        assertEquals(AddonLoadingStatus(message = "Queued", progress = null), parseAddonLoadingStatus("""{"message":"Queued"}"""))
        assertEquals(AddonLoadingStatus(message = null, progress = null), parseAddonLoadingStatus("{}"))
    }

    @Test
    fun `progress is clamped to 0 to 1 and non-numbers or non-finite values are dropped`() {
        fun progressOf(value: String): Float? = parseAddonLoadingStatus("""{"message":"m","progress":$value}""")?.progress

        assertEquals(1f, progressOf("1.7"))
        assertEquals(0f, progressOf("-0.2"))
        assertEquals(1f, progressOf("1"))
        assertEquals(0f, progressOf("0"))
        assertNull(progressOf("\"0.5\""))
        assertNull(progressOf("true"))
        assertNull(progressOf("null"))
        assertNull(progressOf("[0.5]"))
        assertNull(progressOf("""{"value":0.5}"""))
        assertNull(progressOf("1e400"))
        assertNull(progressOf("NaN"))
    }

    @Test
    fun `message of the wrong type or blank is dropped and unknown fields are ignored`() {
        assertEquals(AddonLoadingStatus(null, 0.1f), parseAddonLoadingStatus("""{"message":42,"progress":0.1}"""))
        assertEquals(AddonLoadingStatus(null, 0.1f), parseAddonLoadingStatus("""{"message":"   ","progress":0.1}"""))
        assertEquals(AddonLoadingStatus(null, null), parseAddonLoadingStatus("""{"message":null}"""))
        assertEquals(
            AddonLoadingStatus("Seeding", null),
            parseAddonLoadingStatus("""{"message":"  Seeding ","eta":30,"peers":[1,2],"extra":{"a":1}}""")
        )
    }

    @Test
    fun `a body that is not a json object is a failed poll`() {
        listOf("", "not json", "[1,2]", "\"text\"", "0.5", "<html>busy</html>", """{"message": """).forEach { body ->
            assertNull(body, parseAddonLoadingStatus(body))
        }
    }

    // --- Poller ---

    @Test
    fun `polls about once a second, one request at a time, until the first frame`() = runTest {
        var firstFrameRendered = false
        var inFlight = 0
        var maxInFlight = 0
        val requestTimes = mutableListOf<Long>()
        val shown = mutableListOf<AddonLoadingStatus>()

        val poller = launch {
            pollAddonLoadingStatus(
                fetch = {
                    requestTimes += currentTime
                    inFlight++
                    maxInFlight = maxOf(maxInFlight, inFlight)
                    delay(400) // the server takes 400 ms to answer
                    inFlight--
                    AddonLoadingStatus("Downloading", requestTimes.size / 10f)
                },
                shouldPoll = { !firstFrameRendered },
                onStatus = { shown += it }
            )
        }
        advanceTimeBy(5_000)
        firstFrameRendered = true
        advanceUntilIdle()

        assertTrue(poller.isCompleted)
        assertEquals(1, maxInFlight)
        assertEquals(listOf(0L, 1_400L, 2_800L, 4_200L), requestTimes)
        assertEquals(listOf(0.1f, 0.2f, 0.3f, 0.4f), shown.map { it.progress })
    }

    @Test
    fun `nothing is requested when the stream already started`() = runTest {
        var requests = 0
        pollAddonLoadingStatus(fetch = { requests++; null }, shouldPoll = { false }, onStatus = {})
        assertEquals(0, requests)
    }

    @Test
    fun `five failed polls in a row stop polling and a good answer resets the count`() = runTest {
        val ok = AddonLoadingStatus("ok", null)
        val answers = ArrayDeque(listOf(null, null, ok, null, null, null, null, null, AddonLoadingStatus("never", null)))
        var requests = 0
        val shown = mutableListOf<AddonLoadingStatus>()

        pollAddonLoadingStatus(
            fetch = { requests++; answers.removeFirst() },
            shouldPoll = { true },
            onStatus = { shown += it }
        )

        assertEquals(8, requests)
        assertEquals(listOf(ok), shown)
    }

    @Test
    fun `polling stops 180 s after it started`() = runTest {
        var requests = 0
        pollAddonLoadingStatus(
            fetch = { requests++; AddonLoadingStatus("Still downloading", null) },
            shouldPoll = { true },
            onStatus = {}
        )

        assertEquals(ADDON_LOADING_STATUS_MAX_DURATION_MS, currentTime)
        assertEquals(180, requests)
    }

    @Test
    fun `an answer that arrives after the stream changed is dropped and polling stops`() = runTest {
        var currentStreamUrl = "https://addon.example/play/a.mkv"
        var requests = 0
        val shown = mutableListOf<AddonLoadingStatus>()

        pollAddonLoadingStatus(
            fetch = {
                requests++
                delay(500)
                // Source fallback switched to another stream while the second request was out.
                if (requests == 2) currentStreamUrl = "https://other.example/b.mkv"
                AddonLoadingStatus("answer $requests", null)
            },
            shouldPoll = { currentStreamUrl == "https://addon.example/play/a.mkv" },
            onStatus = { shown += it }
        )

        assertEquals(2, requests)
        assertEquals(listOf("answer 1"), shown.map { it.message })
    }

    @Test
    fun `an error shown on screen stops polling`() = runTest {
        var error: String? = null
        var requests = 0

        val poller = launch {
            pollAddonLoadingStatus(
                fetch = { requests++; AddonLoadingStatus("Downloading", null) },
                shouldPoll = { error == null },
                onStatus = {}
            )
        }
        advanceTimeBy(2_500)
        error = "Playback failed"
        advanceUntilIdle()

        assertTrue(poller.isCompleted)
        assertEquals(3, requests)
    }

    @Test
    fun `releasing the player cancels polling`() = runTest {
        var requests = 0
        val poller = launch {
            pollAddonLoadingStatus(
                fetch = { requests++; AddonLoadingStatus("Downloading", null) },
                shouldPoll = { true },
                onStatus = {}
            )
        }
        advanceTimeBy(2_500)
        poller.cancel() // releasePlayer(): closed, backgrounded or handed to an external player
        advanceTimeBy(10_000)

        assertEquals(3, requests)
    }

    private fun streamWithHint(loadingStatusUrl: String?) = Stream(
        name = "1080p",
        title = null,
        description = null,
        url = "https://addon.example/play/ep2.mkv",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = null,
            loadingStatusUrl = loadingStatusUrl
        ),
        addonName = "Addon",
        addonLogo = null
    )
}
