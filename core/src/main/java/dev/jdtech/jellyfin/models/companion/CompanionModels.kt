package dev.jdtech.jellyfin.models.companion

import kotlinx.serialization.Serializable

@Serializable
data class CompanionConfig(
    val version: Int,
    val servers: List<CompanionServer> = emptyList(),
    val networkShares: List<CompanionNetworkShare> = emptyList(),
    val preferences: Map<String, String> = emptyMap(),
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
    val username: String,
    val password: String? = null,
    val preferences: Map<String, String> = emptyMap()
)

@Serializable
data class CompanionDiscoveryPayload(
    val version: Int,
    val companion_url: String,
    val setup_token: String
)
