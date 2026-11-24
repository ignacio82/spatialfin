package dev.jdtech.jellyfin.session

/**
 * Pure shell-agnostic navigation predicates shared by XR (`NavigationRoot`),
 * Phone (`BeamNavigationRoot`), and TV (`TvNavigationRoot`).
 */
object SessionNavigationPolicy {
    fun shouldRedirectToSetup(
        mode: SessionMode,
        role: RouteRole,
    ): Boolean =
        mode == SessionMode.UNCONFIGURED &&
            role != RouteRole.SOURCE_PICKER &&
            role != RouteRole.SETUP_FLOW &&
            role != RouteRole.REAUTH_GATE

    fun shouldResetToSourcePickerOnUnconfiguredTransition(
        previousMode: SessionMode?,
        currentMode: SessionMode,
        role: RouteRole,
    ): Boolean =
        previousMode != null &&
            previousMode != SessionMode.UNCONFIGURED &&
            currentMode == SessionMode.UNCONFIGURED &&
            role != RouteRole.SOURCE_PICKER

    fun shouldRedirectToReauthGate(
        mode: SessionMode,
        role: RouteRole,
        offlineBrowseEntered: Boolean,
    ): Boolean {
        if (mode != SessionMode.SERVER_NEEDS_AUTH) return false
        if (role == RouteRole.SERVER_CONTENT || role == RouteRole.SOURCE_PICKER) return true
        return !offlineBrowseEntered &&
            (role == RouteRole.MA_CONTENT ||
                role == RouteRole.OFFLINE_OR_LOCAL_CONTENT ||
                role == RouteRole.SETTINGS)
    }

    fun shouldRedirectFromStandalone(
        mode: SessionMode,
        role: RouteRole,
    ): Boolean =
        mode == SessionMode.STANDALONE_SOURCES &&
            (role == RouteRole.SERVER_CONTENT || role == RouteRole.SOURCE_PICKER)

    fun shouldExitSourcePicker(
        mode: SessionMode,
        role: RouteRole,
    ): Boolean =
        role == RouteRole.SOURCE_PICKER &&
            (mode == SessionMode.SERVER_AUTHENTICATED || mode == SessionMode.STANDALONE_SOURCES)

    fun shouldNavigateHomeOnActivation(
        mode: SessionMode,
        role: RouteRole,
        activationEpochChanged: Boolean,
        wasAuthenticated: Boolean,
    ): Boolean {
        if (!activationEpochChanged) return false
        if (mode != SessionMode.SERVER_AUTHENTICATED && mode != SessionMode.STANDALONE_SOURCES) {
            return false
        }
        if (role == RouteRole.SOURCE_PICKER ||
            role == RouteRole.SETUP_FLOW ||
            role == RouteRole.REAUTH_GATE
        ) {
            return true
        }
        return !wasAuthenticated &&
            (role == RouteRole.MA_CONTENT ||
                role == RouteRole.OFFLINE_OR_LOCAL_CONTENT ||
                role == RouteRole.SETTINGS)
    }

    fun shouldPopMissingServerRoute(
        role: RouteRole,
        targetServerId: String?,
        isPersistedServer: Boolean,
        savedServerIds: Set<String>,
    ): Boolean =
        isPersistedServer &&
            targetServerId != null &&
            targetServerId !in savedServerIds &&
            (role == RouteRole.REAUTH_GATE || role == RouteRole.SETUP_FLOW)

    fun isSearchAllowed(mode: SessionMode): Boolean =
        mode == SessionMode.SERVER_AUTHENTICATED

    fun isMaOverlayAllowed(mode: SessionMode): Boolean =
        mode == SessionMode.SERVER_AUTHENTICATED || mode == SessionMode.STANDALONE_SOURCES
}
