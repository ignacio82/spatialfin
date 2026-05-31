package dev.jdtech.jellyfin.plugins.repository

import dev.jdtech.jellyfin.plugins.engine.PluginClient
import dev.jdtech.jellyfin.plugins.engine.PluginRuntime
import dev.jdtech.jellyfin.plugins.model.PluginConfig
import dev.jdtech.jellyfin.plugins.model.ResolvedVideoUrl
import dev.jdtech.jellyfin.plugins.model.UniversalMediaItem
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class PluginContent(
    val plugin: PluginConfig,
    val items: List<UniversalMediaItem>
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

    suspend fun getHome(): List<UniversalMediaItem> {
        return getHomeByPlugin().flatMap { it.items }
    }

    suspend fun getHomeByPlugin(): List<PluginContent> {
        val plugins = pluginRepository.getInstalledPlugins()
        return plugins.mapNotNull { plugin ->
            val pluginId = plugin.id ?: return@mapNotNull null
            PluginContent(plugin, getPluginHome(pluginId))
        }
    }

    suspend fun getPluginHome(pluginId: String): List<UniversalMediaItem> {
        return runPluginListCall(
            pluginId = pluginId,
            call = "await (typeof source.getHome === 'function' ? source.getHome() : (typeof getHome === 'function' ? getHome() : []))",
            logContext = "home"
        )
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

    suspend fun getVideoUrl(pluginId: String, videoUrl: String): ResolvedVideoUrl? {
        return try {
            val runtime = pluginClient.runPlugin(pluginId).getOrNull() ?: return null
            runtime.evaluate("globalThis.finalResult = null;")
            runtime.evaluate(buildResolveScript(json.encodeToString(videoUrl)))
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

    private fun buildResolveScript(videoUrlJson: String): String {
        return """
            (async function() {
                try {
                    const videoUrlStr = $videoUrlJson;
                    console.log("JS_DEBUG: Resolving plugin media URL for: " + videoUrlStr);
                    if (typeof source === "undefined" || source === null) {
                        globalThis.finalResult = "ERROR: source is undefined or null";
                        return;
                    }

                    const fn = source.resolveVideoUrl ||
                        source.getVideoUrl ||
                        source.getContentDetails ||
                        source.getVideo ||
                        source.getVideoDetails;
                    if (typeof fn !== "function") {
                        globalThis.finalResult = "ERROR: No resolver function found";
                        return;
                    }

                    const promise = fn.call(source, videoUrlStr);
                    const resolved = promise && typeof promise.then === "function" ? await promise : promise;
                    const normalized = await normalizeResolvedVideo(resolved);
                    if (normalized) {
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
