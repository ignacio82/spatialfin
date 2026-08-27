package dev.jdtech.jellyfin.settings.domain

import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Stable, storage-facing identifiers for every row the home screen can render.
 *
 * These strings are persisted (see [HomeRowPreferences]) so they must never
 * change once shipped. Dynamic rows (per-library "Latest", Music Assistant,
 * plugin rows, network shares) derive their id from the entity that produced
 * them so a row keeps its slot across reloads.
 */
object HomeRowIds {
    const val SUGGESTIONS = "suggestions"
    const val CONTINUE_WATCHING = "continue"
    const val NEXT_UP = "next_up"

    /** The Beam "Your libraries" chip row — not a media row, but reorderable. */
    const val LIBRARIES = "libraries"

    const val LATEST_PREFIX = "latest:"
    const val MUSIC_ASSISTANT_PREFIX = "ma:"
    const val PLUGIN_PREFIX = "plugin:"
    const val NETWORK_SHARE_PREFIX = "share:"
    const val OFFLINE_PREFIX = "offline:"

    /**
     * Sentinel added to [HomeRowLayout.hidden] when the global "Latest media"
     * preference is off, so every `latest:*` row resolves to hidden without
     * enumerating libraries.
     */
    const val ALL_LATEST = "latest:*"

    fun latest(libraryId: Any) = "$LATEST_PREFIX$libraryId"

    fun musicAssistant(key: String) = "$MUSIC_ASSISTANT_PREFIX$key"

    fun plugin(pluginId: String, rowId: String?) = "$PLUGIN_PREFIX$pluginId:${rowId ?: "home"}"

    fun networkShare(shareId: String) = "$NETWORK_SHARE_PREFIX$shareId"

    fun offline(key: String) = "$OFFLINE_PREFIX$key"

    /** Music Assistant row keys, in the order [HomeRowPreferences] mirrors them. */
    val musicAssistantKeys =
        listOf(
            "recently_played",
            "favorites",
            "playlists",
            "recommendations",
            "audiobooks",
            "radio",
            "podcasts",
        )
}

/**
 * A snapshot of the user's home layout: the explicit row order plus the set of
 * rows that must not render.
 *
 * Both fields are *effective* values — [hidden] already folds in the legacy
 * per-row booleans (`home_suggestions`, `home_ma_*`, …), so consumers only ever
 * need this one object to decide what to draw. That is deliberate: visibility
 * used to be resolved at fetch time and baked into the cached home state, which
 * let a disabled row survive in the cache and reappear on the next cache hit.
 */
data class HomeRowLayout(
    val order: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
) {
    fun isVisible(rowId: String): Boolean =
        when {
            rowId in hidden -> false
            rowId.startsWith(HomeRowIds.LATEST_PREFIX) -> HomeRowIds.ALL_LATEST !in hidden
            else -> true
        }

    /** Position of [rowId] in the saved order, or `null` when never moved. */
    fun positionOf(rowId: String): Int? = order.indexOf(rowId).takeIf { it >= 0 }

    /**
     * Filters [rows] down to the visible ones and sorts them into the saved
     * order. Rows the user has never moved keep their natural relative order and
     * sit after the explicitly ordered ones ([sortedBy] is stable).
     */
    fun <T> arrange(rows: List<T>, id: (T) -> String): List<T> =
        rows.filter { isVisible(id(it)) }.sortedBy { positionOf(id(it)) ?: Int.MAX_VALUE }
}

/**
 * Single source of truth for home row order and visibility, shared by the XR,
 * Beam and TV shells and by the Sources settings screen.
 *
 * Order lives in one JSON blob ([AppPreferences.homeRowLayout]); visibility is
 * written back to whichever preference already owned it — the four
 * `home_*` booleans and the seven `home_ma_*` booleans — so the Sources tab, the
 * Settings tab, companion sync and the home screen never disagree. Rows without
 * a legacy preference (plugins, network shares, offline sections, individual
 * "Latest in <library>" rows) use the JSON `hidden` set.
 */
@Singleton
class HomeRowPreferences
@Inject
constructor(private val appPreferences: AppPreferences) {
    private val sharedPreferences: SharedPreferences
        get() = appPreferences.sharedPreferences

    private val _layout = MutableStateFlow(read())

    /** Emits a new snapshot whenever any row's order or visibility changes. */
    val layout: StateFlow<HomeRowLayout> = _layout.asStateFlow()

    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || affectsLayout(key)) {
                _layout.value = read()
            }
        }

    init {
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun isVisible(rowId: String): Boolean = _layout.value.isVisible(rowId)

    /**
     * Shows or hides a row, writing through to the preference that owns it so
     * every surface that renders a toggle for the same row stays in sync.
     */
    fun setVisible(rowId: String, visible: Boolean) {
        legacyPreference(rowId)?.let { preference ->
            appPreferences.setValue(preference, visible)
            // A legacy row can also carry a stale entry in the JSON hidden set
            // (e.g. hidden before it gained a boolean); clear it either way.
            writeHidden(rowId, hidden = false)
            return
        }
        musicAssistantKeyOf(rowId)?.let { maKey ->
            sharedPreferences.edit().putBoolean(maPreferenceKey(maKey), visible).apply()
            writeHidden(rowId, hidden = false)
            return
        }
        writeHidden(rowId, hidden = !visible)
        Timber.d("Home row %s -> %s", rowId, if (visible) "shown" else "hidden")
    }

    /**
     * Moves [rowId] one slot towards the top (or bottom) of the home screen.
     *
     * [currentOrder] is the list of row ids as the caller is rendering them right
     * now; persisting it wholesale is what turns the implicit natural order into
     * an explicit one the first time the user rearranges anything.
     */
    fun move(rowId: String, currentOrder: List<String>, up: Boolean) {
        val reordered = currentOrder.toMutableList()
        val from = reordered.indexOf(rowId)
        if (from < 0) return
        val to = if (up) from - 1 else from + 1
        if (to !in reordered.indices) return
        reordered[from] = reordered[to]
        reordered[to] = rowId
        // Keep ids the caller could not see (hidden rows, rows from another form
        // factor) at the end rather than dropping their saved position.
        val merged = reordered + _layout.value.order.filterNot { it in reordered }
        write(order = merged, hidden = storedHidden())
        Timber.d("Home row %s moved %s -> order %s", rowId, if (up) "up" else "down", merged)
    }

    private fun writeHidden(rowId: String, hidden: Boolean) {
        val stored = storedHidden().toMutableSet()
        val changed = if (hidden) stored.add(rowId) else stored.remove(rowId)
        if (!changed) return
        write(order = _layout.value.order, hidden = stored)
    }

    private fun write(order: List<String>, hidden: Set<String>) {
        val json =
            JSONObject()
                .put(KEY_ORDER, JSONArray(order))
                .put(KEY_HIDDEN, JSONArray(hidden.toList()))
                .toString()
        appPreferences.setValue(appPreferences.homeRowLayout, json)
    }

    private fun storedHidden(): Set<String> = parse(appPreferences.getValue(appPreferences.homeRowLayout)).second

    /**
     * Builds the effective layout: the stored order, plus the stored hidden set
     * widened with every row whose legacy boolean is currently off.
     */
    private fun read(): HomeRowLayout {
        val (order, storedHidden) = parse(appPreferences.getValue(appPreferences.homeRowLayout))
        val hidden = storedHidden.toMutableSet()
        if (!appPreferences.getValue(appPreferences.homeSuggestions)) hidden += HomeRowIds.SUGGESTIONS
        if (!appPreferences.getValue(appPreferences.homeContinueWatching)) {
            hidden += HomeRowIds.CONTINUE_WATCHING
        }
        if (!appPreferences.getValue(appPreferences.homeNextUp)) hidden += HomeRowIds.NEXT_UP
        if (!appPreferences.getValue(appPreferences.homeLatest)) hidden += HomeRowIds.ALL_LATEST
        HomeRowIds.musicAssistantKeys.forEach { key ->
            if (!sharedPreferences.getBoolean(maPreferenceKey(key), true)) {
                hidden += HomeRowIds.musicAssistant(key)
            }
        }
        return HomeRowLayout(order = order, hidden = hidden)
    }

    private fun parse(raw: String?): Pair<List<String>, Set<String>> {
        if (raw.isNullOrBlank()) return emptyList<String>() to emptySet()
        return try {
            val json = JSONObject(raw)
            json.optJSONArray(KEY_ORDER).toStringList() to
                json.optJSONArray(KEY_HIDDEN).toStringList().toSet()
        } catch (e: Exception) {
            Timber.w(e, "Unreadable home row layout; falling back to the default order")
            emptyList<String>() to emptySet()
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index -> optString(index).takeIf { it.isNotBlank() } }
    }

    private fun affectsLayout(key: String): Boolean =
        key == appPreferences.homeRowLayout.backendName ||
            key == appPreferences.homeSuggestions.backendName ||
            key == appPreferences.homeContinueWatching.backendName ||
            key == appPreferences.homeNextUp.backendName ||
            key == appPreferences.homeLatest.backendName ||
            key.startsWith(MA_PREFERENCE_PREFIX)

    private fun legacyPreference(rowId: String) =
        when (rowId) {
            HomeRowIds.SUGGESTIONS -> appPreferences.homeSuggestions
            HomeRowIds.CONTINUE_WATCHING -> appPreferences.homeContinueWatching
            HomeRowIds.NEXT_UP -> appPreferences.homeNextUp
            HomeRowIds.ALL_LATEST -> appPreferences.homeLatest
            else -> null
        }

    private fun musicAssistantKeyOf(rowId: String): String? =
        rowId
            .removePrefix(HomeRowIds.MUSIC_ASSISTANT_PREFIX)
            .takeIf { rowId.startsWith(HomeRowIds.MUSIC_ASSISTANT_PREFIX) && it in HomeRowIds.musicAssistantKeys }

    private fun maPreferenceKey(key: String) = "$MA_PREFERENCE_PREFIX$key"

    private companion object {
        const val KEY_ORDER = "order"
        const val KEY_HIDDEN = "hidden"
        const val MA_PREFERENCE_PREFIX = "home_ma_"
    }
}

/**
 * A human-readable name for a row id, used when a row is hidden and the shell no
 * longer holds the section that would have supplied its title.
 */
fun homeRowFallbackTitle(rowId: String): String =
    when {
        rowId == HomeRowIds.SUGGESTIONS -> "Suggestions"
        rowId == HomeRowIds.CONTINUE_WATCHING -> "Continue Watching"
        rowId == HomeRowIds.NEXT_UP -> "Next Up"
        rowId == HomeRowIds.LIBRARIES -> "Your libraries"
        rowId == HomeRowIds.ALL_LATEST -> "Latest media"
        rowId.startsWith(HomeRowIds.MUSIC_ASSISTANT_PREFIX) ->
            rowId
                .removePrefix(HomeRowIds.MUSIC_ASSISTANT_PREFIX)
                .split("_")
                .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
                .plus(" (Music Assistant)")
        rowId.startsWith(HomeRowIds.LATEST_PREFIX) -> "Latest media"
        rowId.startsWith(HomeRowIds.PLUGIN_PREFIX) ->
            rowId.removePrefix(HomeRowIds.PLUGIN_PREFIX).substringBefore(':')
        rowId.startsWith(HomeRowIds.NETWORK_SHARE_PREFIX) -> "Network share"
        rowId.startsWith(HomeRowIds.OFFLINE_PREFIX) ->
            rowId.removePrefix(HomeRowIds.OFFLINE_PREFIX).replaceFirstChar { it.uppercase() }
        else -> rowId
    }

/**
 * The hidden rows a shell can offer to bring back, as `row id to title`.
 *
 * Rows whose data is fetch-gated (suggestions, continue watching, next up, the
 * global "Latest media" switch, the Music Assistant rows) are always listed —
 * the shell no longer holds their sections, but their names are fixed. Dynamic
 * rows are only listed when the caller can still name them, which is what
 * [libraryTitles] (`latest:<id>` to library name) supplies; plugin, network
 * share and offline rows stay owned by the Sources screen.
 */
fun HomeRowLayout.restorableHiddenRows(
    libraryTitles: Map<String, String> = emptyMap()
): List<Pair<String, String>> {
    val fixed =
        listOf(
            HomeRowIds.SUGGESTIONS,
            HomeRowIds.CONTINUE_WATCHING,
            HomeRowIds.NEXT_UP,
            HomeRowIds.LIBRARIES,
            HomeRowIds.ALL_LATEST,
        )
    return hidden
        .filter { rowId ->
            rowId in fixed ||
                rowId.startsWith(HomeRowIds.MUSIC_ASSISTANT_PREFIX) ||
                rowId in libraryTitles
        }
        .sortedBy { rowId -> fixed.indexOf(rowId).takeIf { it >= 0 } ?: Int.MAX_VALUE }
        .map { rowId -> rowId to (libraryTitles[rowId] ?: homeRowFallbackTitle(rowId)) }
}
