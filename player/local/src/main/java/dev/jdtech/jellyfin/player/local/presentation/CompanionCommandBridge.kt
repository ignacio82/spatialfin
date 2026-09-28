package dev.jdtech.jellyfin.player.local.presentation

/**
 * The Jellyfin socket and player controller live in the same player process, including :xrplayer.
 * This seam avoids a dependency from :player:local back to :player:session.
 */
object CompanionCommandBridge {
    private var owner: String? = null
    private var handler: (suspend (String?, String?) -> String)? = null

    fun bind(id: String, dispatch: suspend (String?, String?) -> String) {
        owner = id
        handler = dispatch
    }

    fun unbind(id: String) {
        if (owner == id) { owner = null; handler = null }
    }

    suspend fun dispatch(action: String?, transcript: String?): String =
        handler?.invoke(action, transcript) ?: "No active player session"
}
