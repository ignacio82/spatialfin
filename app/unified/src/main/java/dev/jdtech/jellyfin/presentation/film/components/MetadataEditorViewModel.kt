package dev.jdtech.jellyfin.presentation.film.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.api.OmdbApi
import dev.jdtech.jellyfin.api.OmdbResult
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State for the external-IDs editor dialog. Deliberately flat — the dialog is
 * small enough that a handful of independent flags reads cleaner than a
 * sealed-class sum type, and UI code observing the `saved` flag to auto-close
 * benefits from the direct boolean.
 *
 * @property currentImdbId the IMDb ID the server is currently storing, or null
 *   when the item has none (first-time set).
 * @property editedImdbId the text the user is typing / about to submit. Diverges
 *   from `currentImdbId` when the user has edited or accepted a search result.
 * @property searchResult the single best OMDb match for the last search (the
 *   `?t=...` endpoint returns one canonical result, not a list — the dialog
 *   shows one at a time and the user retypes the query to refine).
 */
data class MetadataEditorState(
    val itemId: UUID? = null,
    val currentImdbId: String? = null,
    val editedImdbId: String = "",
    val searchQuery: String = "",
    val searchResult: OmdbResult? = null,
    val isLoading: Boolean = false,
    val isSearching: Boolean = false,
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
    val omdbConfigured: Boolean = false,
)

/**
 * Drives the "Edit external IDs" dialog. Owns:
 *   - read of the server's current providerIds via [JellyfinRepository]
 *   - title → OMDb lookup via [OmdbApi], returning a single best match
 *   - save round trip (update + metadata refresh) back to Jellyfin
 *
 * The dialog is a one-and-done surface — a user opens it to fix an item,
 * saves, and the parent composable dismisses. No need for StateFlow of
 * anything richer than the dialog-local state; nothing else in the app
 * cares about this state flow.
 */
@HiltViewModel
class MetadataEditorViewModel @Inject constructor(
    private val repository: JellyfinRepository,
    private val omdbApi: OmdbApi,
) : ViewModel() {
    private val _state = MutableStateFlow(MetadataEditorState())
    val state = _state.asStateFlow()

    /**
     * Open the editor on [itemId], prefilling the search box with the item's
     * title (so a user who opened the dialog because metadata is wrong can
     * tap Search immediately and see OMDb's match). Idempotent: repeated
     * calls replace state rather than stacking.
     */
    fun load(itemId: UUID, initialTitle: String, initialYear: Int?) {
        _state.value = MetadataEditorState(
            itemId = itemId,
            isLoading = true,
            searchQuery = buildString {
                append(initialTitle)
                if (initialYear != null && initialYear > 1800) append(" ($initialYear)")
            },
            omdbConfigured = omdbApi.isConfigured(),
        )
        viewModelScope.launch {
            val providerIds = repository.getItemProviderIds(itemId)
            val currentImdb = providerIds[IMDB_KEY]
            _state.value = _state.value.copy(
                currentImdbId = currentImdb,
                editedImdbId = currentImdb.orEmpty(),
                isLoading = false,
            )
        }
    }

    fun updateEditedImdbId(value: String) {
        _state.value = _state.value.copy(editedImdbId = value, error = null)
    }

    fun updateSearchQuery(value: String) {
        _state.value = _state.value.copy(searchQuery = value)
    }

    /**
     * Fire the OMDb lookup. Accepts either a title ("Dune 2021") or an IMDb
     * ID ("tt1160419") in the same text field — the shape of the input picks
     * the endpoint. Title path uses `?t=...` which returns the single
     * highest-confidence match; ID path uses `?i=...` which is an exact
     * lookup. Either returns one result or nothing; the dialog shows one at
     * a time and the user retypes to refine.
     */
    fun searchOmdb() {
        val raw = _state.value.searchQuery.trim()
        if (raw.isEmpty() || _state.value.isSearching) return
        if (!omdbApi.isConfigured()) {
            _state.value = _state.value.copy(
                error = "OMDb API key not configured — you can still paste an IMDb ID manually.",
            )
            return
        }
        _state.value = _state.value.copy(isSearching = true, error = null, searchResult = null)
        viewModelScope.launch {
            val result = if (LOOKS_LIKE_IMDB_ID.matches(raw)) {
                omdbApi.findByImdbId(raw)
            } else {
                val (title, year) = extractYearFromQuery(raw)
                // Try movie first (common case), then series. Either call can
                // return null on miss; fall through rather than surfacing an
                // OMDb-specific error so the user just sees "no match".
                omdbApi.searchMovie(title, year) ?: omdbApi.searchSeries(title, year)
            }
            _state.value = _state.value.copy(
                isSearching = false,
                searchResult = result,
                error = if (result == null) "No OMDb match for \"$raw\"." else null,
            )
        }
    }

    /** Copy the search result's IMDb ID into the editable field. */
    fun acceptSearchResult() {
        val hit = _state.value.searchResult ?: return
        if (hit.imdbId.isBlank()) return
        _state.value = _state.value.copy(editedImdbId = hit.imdbId)
    }

    /**
     * Push the edited IMDb ID back to Jellyfin and trigger a metadata refresh.
     * Sets [MetadataEditorState.saved] = true on success so the dialog can
     * auto-dismiss. On failure, surfaces a user-visible error and leaves the
     * dialog open so the user can retry or cancel.
     */
    fun save() {
        val current = _state.value
        val id = current.itemId ?: return
        if (current.isSaving) return
        val trimmed = current.editedImdbId.trim()
        // Guard against accidental no-op saves: if the value matches what's
        // already on the server, dismiss without a write.
        if (trimmed == current.currentImdbId.orEmpty()) {
            _state.value = current.copy(saved = true)
            return
        }
        if (trimmed.isNotEmpty() && !LOOKS_LIKE_IMDB_ID.matches(trimmed)) {
            _state.value = current.copy(
                error = "IMDb IDs look like \"tt1234567\" — check the value before saving.",
            )
            return
        }
        _state.value = current.copy(isSaving = true, error = null)
        viewModelScope.launch {
            val ok = repository.setItemProviderId(
                itemId = id,
                providerKey = IMDB_KEY,
                value = trimmed.ifBlank { null },
            )
            _state.value = _state.value.copy(
                isSaving = false,
                saved = ok,
                error = if (ok) null else "Jellyfin rejected the update — check your admin permissions.",
            )
        }
    }

    /** Reset the just-dismissed flag so a subsequent [load] re-opens cleanly. */
    fun acknowledgeSaved() {
        _state.value = _state.value.copy(saved = false)
    }

    companion object {
        /** Jellyfin's canonical key in the providerIds map. */
        private const val IMDB_KEY = "Imdb"
        private val LOOKS_LIKE_IMDB_ID = Regex("^tt\\d{5,10}$")
        private val YEAR_SUFFIX = Regex("(.*?)[\\s(]+(\\d{4})\\)?\\s*$")

        /**
         * Accept either "Dune" or "Dune 2021" or "Dune (2021)" as inputs —
         * splitting the year out lets OMDb narrow the lookup, which otherwise
         * returns whichever remake is most popular.
         */
        private fun extractYearFromQuery(raw: String): Pair<String, Int?> {
            val match = YEAR_SUFFIX.matchEntire(raw) ?: return raw to null
            val title = match.groupValues[1].trim().ifBlank { raw }
            val year = match.groupValues[2].toIntOrNull()
            return if (year != null && year in 1870..2200) title to year else raw to null
        }
    }
}
