package dev.jdtech.jellyfin.models.companion

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CompanionConfig(
    val version: Int = 1,
    val servers: List<CompanionServer> = emptyList(),
    val networkShares: List<CompanionNetworkShare> = emptyList(),
    @SerialName("globalPreferences")
    val preferences: Map<String, String?> = emptyMap(),
    /**
     * Per-device preference overrides keyed by `deviceId` (the same ID the
     * device sends in X-Companion-Device header / log uploads). When applying
     * config on device D, overrides under `devicePreferences[D]` win over
     * entries in the global `preferences` map.
     */
    val devicePreferences: Map<String, Map<String, String?>> = emptyMap(),
    val setup_token: String = "",
)

@Serializable
data class CompanionNetworkShare(
    val id: String,
    val protocol: String,
    val host: String,
    val shareName: String = "",
    @SerialName("name")
    val name: String? = null,
    val path: String? = null,
    val displayName: String? = null,
    val username: String? = null,
    val password: String? = null,
    val domain: String? = null,
    val addedAtEpochMs: Long? = null,
)

@Serializable
data class CompanionServer(
    val id: String,
    val name: String,
    val addresses: List<String>,
    val users: List<CompanionUser> = emptyList(),
    @SerialName("legacy_ids")
    val legacyIds: List<String> = emptyList(),
)

@Serializable
data class CompanionUser(
    val id: String? = null,
    val name: String? = null,
    val username: String = "",
    val password: String? = null,
    @SerialName("access_token")
    val accessToken: String? = null,
    @SerialName("has_verified_token")
    val hasVerifiedToken: Boolean? = null,
    val preferences: Map<String, String?> = emptyMap(),
    /** Per-user Music Assistant connection, applied to this user's MA store. */
    val musicAssistant: CompanionMusicAssistant? = null,
    /** Per-user universal-plugin manifest URLs the device installs on sync. */
    val plugins: List<String> = emptyList(),
)

@Serializable
data class CompanionMusicAssistant(
    val url: String,
    val token: String? = null,
    val username: String? = null,
    val password: String? = null,
)

@Serializable
data class CompanionDiscoveryPayload(
    val version: Int,
    val companion_url: String,
    val setup_token: String,
)

@Serializable
data class CompanionTvPairingPayload(
    val version: Int,
    val receiver_url: String,
    val pairing_token: String,
    val manual_code: String? = null,
    val device_name: String,
    val expires_at_epoch_ms: Long,
)

@Serializable
data class CompanionTvPairingEnvelope(
    val version: Int,
    val companion_url: String,
    val setup_token: String,
    val config: CompanionConfig,
    val server_id: String? = null,
    val user_id: String? = null,
)

@Serializable
data class CompanionTvPairingInfo(
    val version: Int,
    val manual_code: String? = null,
    val device_name: String,
    val expires_at_epoch_ms: Long,
    val port: Int? = null,
    val device_class: String? = null,
    val protocol_v: Int? = null,
    val capabilities: List<String> = emptyList(),
)
