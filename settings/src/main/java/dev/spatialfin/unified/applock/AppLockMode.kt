package dev.spatialfin.unified.applock

enum class AppLockMode(val backendKey: String) {
    Off("off"),
    Biometric("biometric"),
    Pin("pin"),
    ;

    companion object {
        fun fromKey(key: String?): AppLockMode =
            entries.firstOrNull { it.backendKey == key } ?: Off
    }
}
