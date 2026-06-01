package dev.jdtech.jellyfin.plugins.repository

import dev.jdtech.jellyfin.plugins.engine.PluginClient
import dev.jdtech.jellyfin.plugins.engine.PluginRuntime
import dev.jdtech.jellyfin.plugins.model.PluginConfig
import dev.jdtech.jellyfin.plugins.model.PluginHomeRow
import dev.jdtech.jellyfin.plugins.model.ResolvedVideoUrl
import dev.jdtech.jellyfin.plugins.model.UniversalMediaItem
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class PluginContent(
    val plugin: PluginConfig,
    val items: List<UniversalMediaItem>,
    val rowId: String? = null,
    val rowName: String? = null
)

@Singleton
class PluginContentRepository @Inject constructor(
    private val pluginClient: PluginClient,
    private val pluginRepository: PluginRepository
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val homeCache = ConcurrentHashMap<String, List<UniversalMediaItem>>()

    suspend fun getHome(): List<UniversalMediaItem> {
        return getHomeByPlugin().flatMap { it.items }
    }

    suspend fun getHomeByPlugin(): List<PluginContent> = coroutineScope {
        val plugins = pluginRepository.getInstalledPlugins()
        plugins.flatMap { plugin ->
            val pluginId = plugin.id ?: return@flatMap emptyList()
            val rows = plugin.homeRows.ifEmpty {
                listOf(PluginHomeRow(id = "home", name = plugin.name ?: "Home", type = "home"))
            }
            rows.map { row ->
                async {
                    if (!pluginRepository.isPluginHomeRowEnabled(pluginId, row.id, row.defaultEnabled)) {
                        null
                    } else {
                        val items = getPluginHome(pluginId, row.id)
                        if (items.isEmpty()) null else PluginContent(plugin, items, row.id, row.name)
                    }
                }
            }
        }.mapNotNull { it.await() }
    }

    suspend fun getPluginHome(pluginId: String, rowId: String? = null): List<UniversalMediaItem> {
        val manifest = pluginRepository.getInstalledPlugins().find { it.id == pluginId }
        val row = rowId?.let { id -> manifest?.homeRows?.find { it.id == id } }
        val rowJson = row?.let { json.encodeToString(it) } ?: "null"
        val cacheKey = "$pluginId:${rowId ?: "home"}"
        val items = runPluginListCall(
            pluginId = pluginId,
            call = "await (typeof source.getHome === 'function' ? source.getHome($rowJson) : (typeof getHome === 'function' ? getHome($rowJson) : []))",
            logContext = "home"
        ).map { item -> if (rowId == null || item.homeRowId == rowId) item else item.copy(homeRowId = rowId) }
        if (items.isNotEmpty()) {
            homeCache[cacheKey] = items
            return items
        }
        return homeCache[cacheKey].orEmpty().also { cached ->
            if (cached.isNotEmpty()) {
                android.util.Log.e("PluginContent", "Using cached home items for $cacheKey after empty plugin response")
            }
        }
    }

    suspend fun getPager(pluginId: String, currentUrl: String): List<UniversalMediaItem> {
        val urlJson = json.encodeToString(currentUrl)
        return runPluginListCall(
            pluginId = pluginId,
            call = "await (typeof source.getPager === 'function' ? source.getPager($urlJson) : [])",
            logContext = "pager"
        )
    }

    suspend fun search(query: String): List<UniversalMediaItem> {
        val plugins = pluginRepository.getInstalledPlugins()
        return plugins.flatMap { plugin ->
            searchPlugin(plugin.id ?: return@flatMap emptyList(), query)
        }
    }

    suspend fun searchPlugin(pluginId: String, query: String): List<UniversalMediaItem> {
        val queryJson = json.encodeToString(query)
        return runPluginListCall(
            pluginId = pluginId,
            call = "await (typeof source.search === 'function' ? source.search($queryJson) : [])",
            logContext = "search"
        )
    }

    suspend fun getVideoUrl(pluginId: String, videoUrl: String, isLive: Boolean = false): ResolvedVideoUrl? {
        return try {
            val runtime = pluginClient.runPlugin(pluginId).getOrNull() ?: return null
            runtime.evaluate("globalThis.finalResult = null;")
            
            val videoUrlJson = json.encodeToString(videoUrl)
            runtime.evaluate(buildResolveScript(videoUrlJson, isLive))
            val result = awaitResult(runtime)
            runtime.close()

            if (result != null && !result.startsWith("ERROR:")) {
                android.util.Log.e("PluginContent", "Resolved plugin media result: $result")
                json.decodeFromString<ResolvedVideoUrl>(result)
            } else {
                android.util.Log.e("PluginContent", "Failed to resolve URL for $videoUrl: $result")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception getting video URL for $videoUrl")
            null
        }
    }

    private suspend fun runPluginListCall(
        pluginId: String,
        call: String,
        logContext: String
    ): List<UniversalMediaItem> {
        return try {
            val runtime = pluginClient.runPlugin(pluginId).getOrNull() ?: return emptyList()
            runtime.evaluate("globalThis.finalResult = null;")
            runtime.evaluate(buildContentScript(call, pluginId))
            val result = awaitResult(runtime)
            runtime.close()

            if (result != null && !result.startsWith("ERROR:")) {
                try {
                    json.decodeFromString<List<UniversalMediaItem>>(result)
                } catch (e: Exception) {
                    android.util.Log.e("PluginContent", "Failed to parse $logContext items from $pluginId: $result", e)
                    emptyList()
                }
            } else {
                android.util.Log.e("PluginContent", "$logContext error from plugin $pluginId: $result")
                emptyList()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to run $logContext for plugin $pluginId")
            emptyList()
        }
    }

    private suspend fun awaitResult(runtime: PluginRuntime): String? {
        repeat(201) {
            val res = runtime.evaluate("globalThis.finalResult") as? String
            if (res != null) return res
            delay(100)
        }
        return "ERROR: Timeout waiting for async result"
    }

    private fun buildResolveScript(videoUrlJson: String, isLive: Boolean = false): String {
        return """
            (async function() {
                try {
                    const videoUrlStr = $videoUrlJson;
                    const isLiveItem = $isLive;
                    console.log("JS_DEBUG: Resolving plugin media URL for: " + videoUrlStr + " (isLive=" + isLiveItem + ")");
                    if (typeof source === "undefined" || source === null) {
                        globalThis.finalResult = "ERROR: source is undefined or null";
                        return;
                    }

                    let fn = null;
                    if (isLiveItem && source.getLive) {
                        fn = source.getLive;
                    } else {
                        fn = source.resolveVideoUrl ||
                             source.getVideoUrl ||
                             source.getContentDetails ||
                             source.getVideo ||
                             source.getVideoDetails;
                    }

                    if (typeof fn !== "function") {
                        globalThis.finalResult = "ERROR: No resolver function found";
                        return;
                    }

                    let promise = fn.call(source, videoUrlStr);
                    let resolved = promise && typeof promise.then === "function" ? await promise : promise;
                    let normalized = await normalizeResolvedVideo(resolved);
                    
                    // Fallback to getLive if the item wasn't marked as live but the video result lacked media streams
                    if ((!normalized || normalized.url === videoUrlStr || normalized.mimeType === null) && !isLiveItem && typeof source.getLive === "function") {
                        console.log("JS_DEBUG: Normal resolver didn't yield streams, falling back to getLive");
                        promise = source.getLive(videoUrlStr);
                        resolved = promise && typeof promise.then === "function" ? await promise : promise;
                        normalized = await normalizeResolvedVideo(resolved);
                    }

                    if (normalized && normalized.url && normalized.url !== videoUrlStr) {
                        globalThis.finalResult = JSON.stringify(normalized);
                    } else {
                        globalThis.finalResult = "ERROR: Resolver result did not include a playable URL";
                    }
                } catch (e) {
                    console.log("JS_DEBUG: Error in plugin resolver: " + (e && e.message ? e.message : e));
                    globalThis.finalResult = "ERROR: " + (e && e.message ? e.message : String(e));
                }

                async function normalizeResolvedVideo(value) {
                    if (!value) return null;

                    const video = value.video || value;
                    let videoSources = video.videoSources;
                    if (!videoSources && typeof video.getVideoSources === "function") {
                        try { videoSources = await video.getVideoSources(); } catch(e) {}
                    } else if (!videoSources && typeof value.getVideoSources === "function") {
                        try { videoSources = await value.getVideoSources(); } catch(e) {}
                    }

                    if (Array.isArray(videoSources)) {
                        for (const candidate of videoSources) {
                            const normalized = normalizeSource(candidate);
                            if (normalized) return normalized;
                        }
                    }

                    if (value.video) {
                        const videoDirect = normalizeSource(value.video);
                        if (videoDirect) return videoDirect;
                    }

                    return normalizeSource(value);
                }

                function normalizeSource(value) {
                    if (!value) return null;
                    if (typeof value === "string") return { url: value };
                    if (typeof value.url !== "string") return null;
                    return {
                        url: value.url,
                        mimeType: value.mimeType || value.container || value.type || null,
                        videoUrl: value.videoUrl || null,
                        audioUrl: value.audioUrl || null,
                        videoMimeType: value.videoMimeType || null,
                        audioMimeType: value.audioMimeType || null
                    };
                }
            })();
        """.trimIndent()
    }

    private fun buildContentScript(call: String, pluginId: String): String {
        return """
            (async function() {
                try {
                    const res = $call;
                    const items = res.results || res || [];
                    if (!Array.isArray(items)) {
                        globalThis.finalResult = "[]";
                        return;
                    }

                    const mapped = items.map(item => {
                        try {
                            let extractedId = "";
                            if (item.id) {
                                if (typeof item.id === "string") extractedId = item.id;
                                else if (typeof item.id === "number") extractedId = String(item.id);
                                else if (item.id.value) extractedId = String(item.id.value);
                                else if (item.id.id) extractedId = String(item.id.id);
                                else extractedId = JSON.stringify(item.id);
                            } else if (item.url) {
                                extractedId = item.url;
                            }

                            let extractedAuthor = "Unknown";
                            if (item.author) {
                                if (typeof item.author === "string") extractedAuthor = item.author;
                                else if (item.author.name) extractedAuthor = item.author.name;
                                else if (item.author.id) extractedAuthor = item.author.id;
                            }

                            let thumb = "";
                            if (item.thumbnails) {
                                const tArray = Array.isArray(item.thumbnails) ? item.thumbnails : (item.thumbnails.thumbnails || item.thumbnails.sources);
                                if (tArray && tArray.length > 0) {
                                    const first = tArray[0];
                                    thumb = first.url || first.src || (typeof first === "string" ? first : "");
                                }
                            }
                            if (!thumb) thumb = item.thumbnail || item.thumbnailUrl || "";

                            return {
                                id: extractedId,
                                pluginId: "$pluginId",
                                title: item.name || item.title || "Unknown",
                                author: extractedAuthor,
                                description: item.description || "",
                                thumbnailUrl: thumb,
                                videoUrl: item.url || item.videoUrl || "",
                                durationMs: (item.durationMs || ((item.duration || 0) * 1000)),
                                viewCount: item.viewCount || 0,
                                isLive: item.isLive || false
                            };
                        } catch(e) {
                            return null;
                        }
                    }).filter(x => x !== null);
                    globalThis.finalResult = JSON.stringify(mapped);
                } catch (e) {
                    globalThis.finalResult = "ERROR: " + (e.message || e.toString());
                }
            })();
        """.trimIndent()
    }
}
