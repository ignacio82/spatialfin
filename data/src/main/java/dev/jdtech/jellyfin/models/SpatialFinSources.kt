package dev.jdtech.jellyfin.models

interface SpatialFinSources {
    val sources: List<SpatialFinSource>
    val runtimeTicks: Long
    val trickplayInfo: Map<String, SpatialFinTrickplayInfo>?
}
