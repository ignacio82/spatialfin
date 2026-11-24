package dev.jdtech.jellyfin.setup.presentation.addresses

import java.util.UUID

sealed interface ServerAddressesAction {
    data class OnServerClick(val addressId: UUID) : ServerAddressesAction

    data object OpenAddAddressDialog : ServerAddressesAction

    data object DismissAddAddressDialog : ServerAddressesAction

    data class AddAddress(val address: String) : ServerAddressesAction

    data class DeleteAddress(val addressId: UUID) : ServerAddressesAction

    data object OnBackClick : ServerAddressesAction
}
