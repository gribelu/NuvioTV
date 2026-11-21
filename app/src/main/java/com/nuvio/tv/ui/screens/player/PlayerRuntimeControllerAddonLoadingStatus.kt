package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

// An add-on stream can name a status URL in `behaviorHints.loadingStatusUrl`. While the internal
// player loads that stream, Nuvio polls it and shows its `message` and `progress` in the loading
// overlay, the same way its own torrent engine reports peers and speed.

internal const val ADDON_LOADING_STATUS_POLL_INTERVAL_MS = 1_000L
internal const val ADDON_LOADING_STATUS_TIMEOUT_MS = 3_000L
internal const val ADDON_LOADING_STATUS_MAX_FAILURES = 5
internal const val ADDON_LOADING_STATUS_MAX_DURATION_MS = 180_000L

/** One answer from the status URL. A null field leaves that part of the overlay as it is. */
internal data class AddonLoadingStatus(
    val message: String?,
    val progress: Float?
)

/**
 * Reads `{"message": "...", "progress": 0.37}`. Both fields are optional, unknown fields are
 * ignored, a field of the wrong type is dropped and progress is clamped to 0..1.
 * Returns null when the body is not a JSON object.
 */
internal fun parseAddonLoadingStatus(body: String): AddonLoadingStatus? {
    val json = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
    val message = (json["message"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val progress = (json["progress"] as? JsonPrimitive)
        ?.takeIf { !it.isString }
        ?.doubleOrNull
        ?.takeIf { it.isFinite() }
        ?.coerceIn(0.0, 1.0)
        ?.toFloat()
    return AddonLoadingStatus(message = message, progress = progress)
}

/**
 * Calls [fetch] about once per [intervalMs], one request at a time, while [shouldPoll] holds.
 * [fetch] returns null for a failed poll; [maxFailures] failures in a row end the polling, as
 * does [maxDurationMs] in total.
 */
internal suspend fun pollAddonLoadingStatus(
    fetch: suspend () -> AddonLoadingStatus?,
    shouldPoll: () -> Boolean,
    onStatus: (AddonLoadingStatus) -> Unit,
    intervalMs: Long = ADDON_LOADING_STATUS_POLL_INTERVAL_MS,
    maxFailures: Int = ADDON_LOADING_STATUS_MAX_FAILURES,
    maxDurationMs: Long = ADDON_LOADING_STATUS_MAX_DURATION_MS
) {
    withTimeoutOrNull(maxDurationMs) {
        var failures = 0
        while (shouldPoll()) {
            val status = fetch()
            // The stream may have started, changed or failed while the request was out.
            if (!shouldPoll()) break
            if (status == null) {
                failures++
                if (failures >= maxFailures) break
            } else {
                failures = 0
                onStatus(status)
            }
            delay(intervalMs)
        }
    }
}

// Status polls are not stream traffic: short timeouts, no trust-all retry, and kept out of the
// playback connection events that go into issue reports.
private val addonLoadingStatusHttpClient: OkHttpClient by lazy {
    PlayerPlaybackNetworking.playbackHttpClient.newBuilder()
        .apply { interceptors().clear() }
        .eventListener(EventListener.NONE)
        .connectTimeout(ADDON_LOADING_STATUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(ADDON_LOADING_STATUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
}

private suspend fun fetchAddonLoadingStatus(statusUrl: String): AddonLoadingStatus? =
    withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(statusUrl).get().build()
            addonLoadingStatusHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                parseAddonLoadingStatus(response.body.string())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

/**
 * Starts polling the current stream's status URL for the start-up that begins now, until its
 * first frame, a stream change, an error, the app leaving the foreground or the player release.
 * Streams from Nuvio's own torrent engine report through [observeTorrentState] instead.
 */
internal fun PlayerRuntimeController.startAddonLoadingStatusPolling() {
    stopAddonLoadingStatusPolling()
    val statusUrl = currentLoadingStatusUrl ?: return
    if (isTorrentStream) return
    val streamUrl = currentStreamUrl
    addonLoadingStatusJob = scope.launch {
        pollAddonLoadingStatus(
            fetch = { fetchAddonLoadingStatus(statusUrl) },
            shouldPoll = {
                !hasRenderedFirstFrame && currentStreamUrl == streamUrl &&
                    _uiState.value.error == null && !isInBackground
            },
            onStatus = { status ->
                val state = _uiState.value
                setLoadingStatus(
                    phase = "addon_loading_status",
                    message = status.message ?: state.loadingMessage,
                    progress = status.progress ?: state.loadingProgress
                )
            }
        )
    }
}

internal fun PlayerRuntimeController.stopAddonLoadingStatusPolling() {
    addonLoadingStatusJob?.cancel()
    addonLoadingStatusJob = null
}
