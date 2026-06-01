package dev.jdtech.jellyfin.plugins.engine

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import dev.jdtech.jellyfin.plugins.bridge.DOMParserBridge
import dev.jdtech.jellyfin.plugins.bridge.HttpBridge
import dev.jdtech.jellyfin.plugins.bridge.UtilitiesBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PluginEngine @Inject constructor(
    private val httpBridge: HttpBridge,
    private val domParserBridge: DOMParserBridge,
    private val utilitiesBridge: UtilitiesBridge
) {
    private val jsDispatcher = Executors.newFixedThreadPool(8).asCoroutineDispatcher()

    suspend fun createRuntime(): PluginRuntime {
        val quickJs = QuickJs.create(jsDispatcher)
        
        // Bind bridges with explicit thread switching for safety
        quickJs.define("httpBridge") {
            function("request") { args ->
                runBlocking(Dispatchers.IO) {
                    httpBridge.request(
                        args[0].toString(),
                        args[1].toString(),
                        args[2].toString(),
                        args[3]?.toString(),
                        args[4] as Boolean
                    )
                }
            }
        }
        
        quickJs.define("domParserBridge") {
            function("parse") { args -> domParserBridge.parse(args[0].toString()) }
            function("querySelector") { args -> domParserBridge.querySelector(args[0].toString(), args[1].toString()) }
            function("querySelectorAll") { args -> domParserBridge.querySelectorAll(args[0].toString(), args[1].toString()) }
            function("getAttribute") { args -> domParserBridge.getAttribute(args[0].toString(), args[1].toString()) }
            function("getTextContent") { args -> domParserBridge.getTextContent(args[0].toString()) }
            function("getInnerHtml") { args -> domParserBridge.getInnerHtml(args[0].toString()) }
            function("getOuterHtml") { args -> domParserBridge.getOuterHtml(args[0].toString()) }
            function("closeHandle") { args -> domParserBridge.closeHandle(args[0].toString()) }
        }
        
        quickJs.define("utilitiesBridge") {
            function("uuid") { _ -> utilitiesBridge.uuid() }
            function("base64Encode") { args -> utilitiesBridge.base64Encode(args[0].toString()) }
            function("base64EncodeBytes") { args ->
                val arg = args[0]
                val intArray = when (arg) {
                    is List<*> -> IntArray(arg.size) { (arg[it] as? Number)?.toInt() ?: 0 }
                    is Array<*> -> IntArray(arg.size) { (arg[it] as? Number)?.toInt() ?: 0 }
                    is DoubleArray -> IntArray(arg.size) { arg[it].toInt() }
                    is IntArray -> arg
                    is ByteArray -> IntArray(arg.size) { arg[it].toInt() }
                    else -> IntArray(0)
                }
                utilitiesBridge.base64EncodeBytes(intArray)
            }
            function("base64Decode") { args -> utilitiesBridge.base64Decode(args[0].toString()) }
            function("md5String") { args -> utilitiesBridge.md5String(args[0].toString()) }
            function("log") { args -> utilitiesBridge.log(args[0].toString()) }
        }

        val runtime = PluginRuntime(quickJs, jsDispatcher)
        
        // Inject JS shims
        try {
            runtime.evaluate(JS_SHIMS)
            android.util.Log.e("CRITICAL_JS", "JS shims initialized successfully")
        } catch (e: Exception) {
            android.util.Log.e("CRITICAL_JS", "JS shims initialization FAILED", e)
        }

        return runtime
    }

    companion object {
        private val JS_SHIMS = """
            (function() {
                const defineGlobal = (name, value) => {
                    if (typeof globalThis[name] === 'undefined') {
                        Object.defineProperty(globalThis, name, {
                            value: value,
                            writable: false,
                            configurable: false,
                            enumerable: true
                        });
                    }
                };

                defineGlobal('source', {});
                defineGlobal('global', globalThis);
                defineGlobal('window', globalThis);
                defineGlobal('__spatialfinDefaultUserAgent', 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36');
                defineGlobal('navigator', {
                    userAgent: 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
                    languages: ['en-US', 'en'],
                    language: 'en-US',
                    platform: 'Linux x86_64',
                    vendor: 'Google Inc.',
                    userAgentData: {
                        brands: [
                            { brand: 'Chromium', version: '124' },
                            { brand: 'Google Chrome', version: '124' }
                        ],
                        mobile: false,
                        platform: 'Linux'
                    }
                });
                defineGlobal('IS_TESTING', false);
                
                const bridge = {
                    supportedFeatures: ["Http", "Utilities", "DOMParser"],
                    isLoggedIn: () => false,
                    hasPackage: (name) => ["Http", "Utilities", "DOMParser"].indexOf(name) >= 0,
                    getSettings: () => ({}),
                    setSettings: (s) => {},
                    devSubmit: function(msg, data) { 
                        console.log("DEV_SUBMIT: " + msg);
                    },
                    toast: (msg) => { console.log("TOAST: " + msg); },
                    base64Encode: (data) => utilitiesBridge.base64Encode(data),
                    base64Decode: (data) => utilitiesBridge.base64Decode(data),
                    buildSpecVersion: 2,
                    buildPlatform: 'android',
                    buildVersion: 999
                };
                defineGlobal('bridge', bridge);

                const http = {
                    processResponse: function(respJson) {
                        let resp = JSON.parse(respJson);
                        if (resp.base64 && resp.body) {
                            try {
                                let raw = atob(resp.body);
                                let uint8Array = new Uint8Array(raw.length);
                                for (let i = 0; i < raw.length; i++) {
                                    uint8Array[i] = raw.charCodeAt(i);
                                }
                                resp.body = uint8Array.buffer;
                            } catch (e) {
                                console.log("JS_DEBUG: Failed to decode base64 response: " + e);
                            }
                        }
                        return resp;
                    },
                    requestWithBody: true,
                    GET: (url, headers, usePlatformAuth) => {
                        console.log("JS_HTTP: GET " + url);
                        const respJson = httpBridge.request("GET", url, JSON.stringify(headers || {}), null, usePlatformAuth || false);
                        return http.processResponse(respJson);
                    },
                    POST: (url, body, headers, usePlatformAuth) => {
                        let bodyStr = "";
                        console.log("JS_HTTP: POST " + url + " Type: " + typeof body + " IsArray: " + Array.isArray(body) + " Cons: " + (body && body.constructor ? body.constructor.name : 'Unknown'));
                        if (typeof body === 'string') {
                            bodyStr = body;
                        } else if (body instanceof Uint8Array || body instanceof ArrayBuffer || (body && body.buffer instanceof ArrayBuffer) || Array.isArray(body)) {
                            let arr = Array.from(body instanceof ArrayBuffer ? new Uint8Array(body) : body);
                            bodyStr = "BASE64:" + utility.toBase64(arr);
                        } else {
                            bodyStr = JSON.stringify(body || {});
                        }
                        const respJson = httpBridge.request("POST", url, JSON.stringify(headers || {}), bodyStr, usePlatformAuth || false);
                        return http.processResponse(respJson);
                    },
                    batch: function() {
                        const reqs = [];
                        const batchObj = {
                            GET: function(url, headers, useAuth) {
                                reqs.push({ method: "GET", url: url, headers: headers, useAuth: useAuth });
                                return batchObj;
                            },
                            POST: function(url, body, headers, useAuth) {
                                let bodyStr = "";
                                console.log("JS_HTTP_BATCH: POST " + url + " Type: " + typeof body + " IsArray: " + Array.isArray(body) + " Cons: " + (body && body.constructor ? body.constructor.name : 'Unknown'));
                                if (typeof body === 'string') {
                                    bodyStr = body;
                                } else if (body instanceof Uint8Array || body instanceof ArrayBuffer || (body && body.buffer instanceof ArrayBuffer) || Array.isArray(body)) {
                                    let arr = Array.from(body instanceof ArrayBuffer ? new Uint8Array(body) : body);
                                    bodyStr = "BASE64:" + utility.toBase64(arr);
                                } else {
                                    bodyStr = JSON.stringify(body || {});
                                }
                                reqs.push({ method: "POST", url: url, body: bodyStr, headers: headers, useAuth: useAuth });
                                return batchObj;
                            },
                            DUMMY: function() {
                                reqs.push({ method: "DUMMY" });
                                return batchObj;
                            },
                            execute: function() {
                                return reqs.map(req => {
                                    if (req.method === "GET") return http.GET(req.url, req.headers, req.useAuth);
                                    if (req.method === "POST") return http.POST(req.url, req.body, req.headers, req.useAuth);
                                    return { isOk: true, code: 200, body: "", headers: {}, base64: false };
                                });
                            }
                        };
                        return batchObj;
                    }
                };
                defineGlobal('http', http);

                class Element {
                    constructor(handle) { this.handle = handle; }
                    querySelector(selector) {
                        const elHandle = domParserBridge.querySelector(this.handle, selector);
                        return elHandle ? new Element(elHandle) : null;
                    }
                    querySelectorAll(selector) {
                        const handles = domParserBridge.querySelectorAll(this.handle, selector);
                        const result = [];
                        if (handles) {
                            for(let i=0; i<handles.length; i++) {
                                result.push(new Element(handles[i]));
                            }
                        }
                        return result;
                    }
                    getAttribute(name) { return domParserBridge.getAttribute(this.handle, name); }
                    get textContent() { return domParserBridge.getTextContent(this.handle); }
                    get innerHTML() { return domParserBridge.getInnerHtml(this.handle); }
                    get outerHTML() { return domParserBridge.getOuterHtml(this.handle); }
                    get innerText() { return this.textContent; }
                    get className() { return this.getAttribute("class") || ""; }
                    get id() { return this.getAttribute("id") || ""; }
                    get attributes() { return []; }
                    get children() { return []; }
                }

                class DOMParser {
                    parseFromString(html, type) {
                        const handle = domParserBridge.parse(html);
                        return new Element(handle);
                    }
                }
                defineGlobal('DOMParser', DOMParser);

                const utilities = {
                    uuid: () => utilitiesBridge.uuid(),
                    base64Encode: (data) => utilitiesBridge.base64Encode(data),
                    base64Decode: (data) => utilitiesBridge.base64Decode(data),
                };
                defineGlobal('utilities', utilities);
                
                const utility = {
                    toBase64: function(arr) {
                        let bytes = [];
                        if (arr && arr.length !== undefined) {
                            for (let i = 0; i < arr.length; i++) bytes.push(arr[i]);
                        }
                        return utilitiesBridge.base64EncodeBytes(bytes);
                    },
                    md5String: function(str) {
                        return utilitiesBridge.md5String(str);
                    }
                };
                defineGlobal('utility', utility);
                
                defineGlobal('atob', (data) => utilitiesBridge.base64Decode(data));
                defineGlobal('btoa', (data) => utilitiesBridge.base64Encode(data));
                
                const console = {
                    log: function() { 
                        let msg = "";
                        for(let i=0; i<arguments.length; i++) {
                            let arg = arguments[i];
                            msg += (typeof arg === 'object' ? JSON.stringify(arg) : arg) + (i < arguments.length - 1 ? " " : "");
                        }
                        utilitiesBridge.log(msg); 
                    },
                    error: function() { 
                        let msg = "ERROR: ";
                        for(let i=0; i<arguments.length; i++) {
                            let arg = arguments[i];
                            msg += (typeof arg === 'object' ? JSON.stringify(arg) : arg) + (i < arguments.length - 1 ? " " : "");
                        }
                        utilitiesBridge.log(msg); 
                    },
                    warn: function() { 
                        let msg = "WARN: ";
                        for(let i=0; i<arguments.length; i++) {
                            let arg = arguments[i];
                            msg += (typeof arg === 'object' ? JSON.stringify(arg) : arg) + (i < arguments.length - 1 ? " " : "");
                        }
                        utilitiesBridge.log(msg); 
                    },
                    clear: () => {}
                };
                defineGlobal('console', console);
                defineGlobal('log', console.log);
                
                const setTimeout = function(fn, delay) { if (typeof fn === 'function') fn(); return 1; };
                const clearTimeout = function(id) {};
                const requestAnimationFrame = function(fn) { if (typeof fn === 'function') fn(); return 1; };
                defineGlobal('setTimeout', setTimeout);
                defineGlobal('clearTimeout', clearTimeout);
                defineGlobal('requestAnimationFrame', requestAnimationFrame);

                class Pager {
                    constructor(results, hasMore, context) {
                        this.results = results || [];
                        this.hasMore = hasMore || false;
                        this.context = context || null;
                    }
                }
                defineGlobal('Pager', Pager);
                class VideoPager extends Pager {}
                class ChannelPager extends Pager {}
                class PlaylistPager extends Pager {}
                class LiveEventPager extends Pager {}
                class CommentPager extends Pager {}
                defineGlobal('VideoPager', VideoPager);
                defineGlobal('ChannelPager', ChannelPager);
                defineGlobal('PlaylistPager', PlaylistPager);
                defineGlobal('LiveEventPager', LiveEventPager);
                defineGlobal('CommentPager', CommentPager);

                class Thumbnails { constructor(thumbnails) { this.thumbnails = thumbnails || []; } }
                defineGlobal('Thumbnails', Thumbnails);
                class Thumbnail { constructor(url, width, height) { this.url = url; this.width = width; this.height = height; } }
                defineGlobal('Thumbnail', Thumbnail);
                class ThumbnailSource { constructor(url, width, height) { this.url = url; this.width = width; this.height = height; } }
                defineGlobal('ThumbnailSource', ThumbnailSource);

                class PlatformID {
                    constructor(platform, id, pluginId, claimType) {
                        this.platform = platform;
                        this.id = id;
                        this.pluginId = pluginId;
                        this.claimType = claimType;
                    }
                    get value() { return this.id; }
                }
                defineGlobal('PlatformID', PlatformID);
                class PlatformItem { constructor(obj) { if(obj) Object.assign(this, obj); } }
                class PlatformContent extends PlatformItem {}
                class PlatformVideo extends PlatformContent {}
                class PlatformChannel extends PlatformItem {}
                class PlatformPlaylist extends PlatformContent {}
                class PlatformAuthorLink extends PlatformItem {}
                defineGlobal('PlatformItem', PlatformItem);
                defineGlobal('PlatformContent', PlatformContent);
                defineGlobal('PlatformVideo', PlatformVideo);
                defineGlobal('PlatformChannel', PlatformChannel);
                defineGlobal('PlatformPlaylist', PlatformPlaylist);
                defineGlobal('PlatformAuthorLink', PlatformAuthorLink);
                class PlatformVideoDetails extends PlatformVideo {}
                class PlatformChannelDetails extends PlatformChannel {}
                defineGlobal('PlatformVideoDetails', PlatformVideoDetails);
                defineGlobal('PlatformChannelDetails', PlatformChannelDetails);

                class VideoSourceDescriptor { constructor(videoSources) { this.videoSources = videoSources || []; } }
                defineGlobal('VideoSourceDescriptor', VideoSourceDescriptor);
                class UnMuxVideoSourceDescriptor extends VideoSourceDescriptor {
                    constructor(videoSources, audioSources) { super(videoSources); this.audioSources = audioSources || []; }
                }
                defineGlobal('UnMuxVideoSourceDescriptor', UnMuxVideoSourceDescriptor);

                class Comment { constructor() {} }
                defineGlobal('Comment', Comment);
                class VideoDetails { constructor() {} }
                defineGlobal('VideoDetails', VideoDetails);
                class RatingLikes { constructor(likes) { this.likes = likes; this.type = 1; } }
                defineGlobal('RatingLikes', RatingLikes);
                class RatingLikesDislikes { constructor(likes, dislikes) { this.likes = likes; this.dislikes = dislikes; this.type = 2; } }
                defineGlobal('RatingLikesDislikes', RatingLikesDislikes);
                class ChannelDetails { constructor() {} }
                class PlaylistDetails { constructor() {} }
                defineGlobal('ChannelDetails', ChannelDetails);
                defineGlobal('PlaylistDetails', PlaylistDetails);
                
                class VideoSource { constructor(obj) { if (obj && typeof obj === 'object') Object.assign(this, obj); } }
                class VideoUrlSource extends VideoSource {
                    constructor(obj, width, height, container) {
                        if (typeof obj === 'string') { super({}); this.url = obj; this.width = width; this.height = height; this.container = container; }
                        else { super(obj); }
                    }
                }
                class VideoUrlRangeSource extends VideoSource {
                    constructor(obj) { super(obj); }
                }
                class DashManifestRawSource extends VideoSource {
                    constructor(obj) { super(obj); }
                }
                class DashSource extends VideoSource {
                    constructor(obj) { super(); if (obj) { this.url = obj.url; this.name = obj.name; } }
                }
                defineGlobal('DashSource', DashSource);
                class DashManifestUrlSource extends VideoSource {}
                class HLSManifestRawSource extends VideoSource {}
                class HLSManifestUrlSource extends VideoSource {
                    constructor(name, duration, url) { super(); this.name = name; this.duration = duration; this.url = url; }
                }
                defineGlobal('VideoSource', VideoSource);
                defineGlobal('VideoUrlSource', VideoUrlSource);
                defineGlobal('VideoUrlRangeSource', VideoUrlRangeSource);
                defineGlobal('DashManifestRawSource', DashManifestRawSource);
                defineGlobal('DashManifestUrlSource', DashManifestUrlSource);
                defineGlobal('HLSManifestRawSource', HLSManifestRawSource);
                defineGlobal('HLSManifestUrlSource', HLSManifestUrlSource);

                class HLSSource extends VideoSource {
                    constructor(nameOrObj, duration, url) {
                        super();
                        if (typeof nameOrObj === 'object' && nameOrObj !== null) {
                            this.name = nameOrObj.name; this.duration = nameOrObj.duration; this.url = nameOrObj.url;
                        } else {
                            this.name = nameOrObj; this.duration = duration; this.url = url;
                        }
                    }
                }
                defineGlobal('HLSSource', HLSSource);

                class AudioSource { constructor(obj) { if (obj && typeof obj === 'object') Object.assign(this, obj); } }
                class AudioUrlSource extends AudioSource {
                    constructor(obj) { super(obj); }
                }
                class AudioUrlRangeSource extends AudioSource {
                    constructor(obj) { super(obj); }
                }
                class DashManifestRawAudioSource extends AudioSource {}
                class DashManifestUrlAudioSource extends AudioSource {}
                class HLSManifestRawAudioSource extends AudioSource {}
                class HLSManifestUrlAudioSource extends AudioSource {}
                defineGlobal('AudioSource', AudioSource);
                defineGlobal('AudioUrlSource', AudioUrlSource);
                defineGlobal('AudioUrlRangeSource', AudioUrlRangeSource);
                defineGlobal('DashManifestRawAudioSource', DashManifestRawAudioSource);
                defineGlobal('DashManifestUrlAudioSource', DashManifestUrlAudioSource);
                defineGlobal('HLSManifestRawAudioSource', HLSManifestRawAudioSource);
                defineGlobal('HLSManifestUrlAudioSource', HLSManifestUrlAudioSource);
                
                class PlaybackTracker { constructor() {} }
                class RequestModifier { constructor() {} }
                class FilterCapability { constructor() {} }
                class ResultCapabilities { constructor() {} }
                class SourceException extends Error { constructor(msg) { super(msg); } }
                class ScriptException extends Error { constructor(msg) { super(msg); } }
                class UnavailableException extends Error { constructor(msg) { super(msg); } }
                defineGlobal('PlaybackTracker', PlaybackTracker);
                defineGlobal('RequestModifier', RequestModifier);
                defineGlobal('FilterCapability', FilterCapability);
                defineGlobal('ResultCapabilities', ResultCapabilities);
                defineGlobal('SourceException', SourceException);
                defineGlobal('ScriptException', ScriptException);
                defineGlobal('UnavailableException', UnavailableException);
                
                const Type = {
                    Feed: { Mixed: 0, Videos: 1, Channels: 2, Playlists: 3, Live: 4, Short: 5, Shorts: 5, Streams: 6 },
                    Order: { Chronological: 0, Popular: 1 },
                    Date: { LastHour: 0, Today: 1, Last24Hours: 1, LastWeek: 2, LastMonth: 3, LastYear: 4 },
                    Duration: { Any: 0, Short: 1, Medium: 2, Long: 3 },
                    Capability: { None: 0, Search: 1, Trending: 2, Comments: 4, Subtitles: 8, Rating: 16 }
                };
                defineGlobal('Type', Type);
                const Language = {
                    UNKNOWN: "unknown", ENGLISH: "en", SPANISH: "es", FRENCH: "fr", GERMAN: "de", HINDI: "hi",
                    ARABIC: "ar", RUSSIAN: "ru", PORTUGUESE: "pt", INDONESIAN: "id", TURKISH: "tr",
                    KOREAN: "ko", VIETNAMESE: "vi", THAI: "th", PORTBRAZIL: "pt-BR"
                };
                defineGlobal('Language', Language);
                const Batch = {
                    POST: (url, body, headers, usePlatformAuth) => { return http.POST(url, body, headers, usePlatformAuth); },
                    DUMMY: () => ({ isOk: true, code: 200, body: "", headers: {}, base64: false })
                };
                defineGlobal('Batch', Batch);
            })();
        """.trimIndent()
    }
}

class PluginRuntime(val quickJs: QuickJs, private val dispatcher: kotlinx.coroutines.CoroutineDispatcher) {
    suspend fun evaluate(script: String): Any? {
        return withContext(dispatcher) {
            try {
                quickJs.evaluate<Any?>(script)
            } catch (e: Exception) {
                android.util.Log.e("CRITICAL_JS", "Script evaluation failed", e)
                null
            }
        }
    }

    fun close() {
        quickJs.close()
    }
}
