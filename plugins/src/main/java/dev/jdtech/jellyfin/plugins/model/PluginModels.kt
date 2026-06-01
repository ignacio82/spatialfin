package dev.jdtech.jellyfin.plugins.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class PluginConfig(
    val id: String? = null,
    val name: String? = null,
    val author: String? = null,
    val description: String? = null,
    val version: Int = 0,
    val iconUrl: String? = null,
    val repositoryUrl: String? = null,
    val websiteUrl: String? = null,
    val scriptUrl: String? = null,
    val scriptSignature: String? = null,
    val scriptPublicKey: String? = null,
    val minEngineVersion: Int = 0,
    val capabilities: List<String> = emptyList(),
    val homeRows: List<PluginHomeRow> = emptyList(),
    val settings: List<PluginSetting> = emptyList()
)

@Serializable
data class PluginHomeRow(
    val id: String,
    val name: String,
    val description: String? = null,
    val type: String? = null,
    val defaultEnabled: Boolean = true,
    val options: JsonElement? = null
)

@Serializable
data class PluginSetting(
    val variable: String? = null,
    val name: String? = null,
    val type: String? = null,
    val default: String? = null,
    val description: String? = null,
    val options: JsonElement? = null
)
