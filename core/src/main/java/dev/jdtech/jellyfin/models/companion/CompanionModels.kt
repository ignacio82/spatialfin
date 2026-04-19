package dev.jdtech.jellyfin.models.companion

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CompanionConfig(
    val version: Int,
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
    val setup_token: String
)

@Serializable
data class CompanionNetworkShare(
    val id: String,
    val protocol: String,
    val host: String,
    val shareName: String,
    val path: String? = null,
    val displayName: String? = null,
    val username: String? = null,
    val password: String? = null,
    val domain: String? = null,
    val addedAtEpochMs: Long? = null
)

@Serializable
data class CompanionServer(
    val id: String,
    val name: String,
    val addresses: List<String>,
    val users: List<CompanionUser> = emptyList()
)

@Serializable
data class CompanionUser(
    val id: String? = null,
    val name: String? = null,
    val username: String,
    val password: String? = null,
    @SerialName("access_token")
    val accessToken: String? = null,
    val preferences: Map<String, String?> = emptyMap()
)

@Serializable
data class CompanionDiscoveryPayload(
    val version: Int,
    val companion_url: String,
    val setup_token: String
)

@Serializable
data class CompanionTvPairingPayload(
    val version: Int,
    val receiver_url: String,
    val pairing_token: String,
    val manual_code: String,
    val device_name: String,
    val expires_at_epoch_ms: Long,
)

@Serializable
data class CompanionTvPairingEnvelope(
    val version: Int,
    val companion_url: String,
    val setup_token: String,
    val config: CompanionConfig,
)

@Serializable
data class CompanionTvPairingInfo(
    val version: Int,
    val manual_code: String,
    val device_name: String,
    val expires_at_epoch_ms: Long,
)
