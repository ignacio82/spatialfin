package dev.jdtech.jellyfin.sendspin.receiver.remote

/**
 * Unique identifier for a Music Assistant server instance, used to reach it through
 * the WebRTC signaling server when off the home LAN.
 *
 * The server derives it from its DTLS certificate SHA-256 fingerprint (first 128 bits,
 * Base32-encoded) and shows it in **Music Assistant → Settings → Remote Access**. It is a
 * 26-character uppercase alphanumeric string, sometimes displayed with cosmetic hyphens
 * (e.g. `PGSVX-KGZJC-FA6MO-H4UPB-H5Q9H-Y`).
 *
 * Mirrors the official MA mobile app's `RemoteId` so the same user-pasted value works here.
 */
@JvmInline
value class RemoteId(val rawId: String) {
    init {
        require(rawId.matches(VALID)) {
            "Invalid Remote ID: expected 26 uppercase alphanumeric characters, got '$rawId'"
        }
    }

    override fun toString(): String = rawId

    companion object {
        private val VALID = Regex("[A-Z0-9]{26}")

        /**
         * Parse a Remote ID from raw user input, stripping hyphens/whitespace and
         * upper-casing. Returns null when the cleaned value isn't a valid Remote ID.
         */
        fun parse(input: String?): RemoteId? {
            if (input == null) return null
            val cleaned = input.replace("-", "").replace(" ", "").trim().uppercase()
            return if (cleaned.matches(VALID)) RemoteId(cleaned) else null
        }

        fun isValid(input: String?): Boolean = parse(input) != null
    }
}
