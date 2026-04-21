package dev.jdtech.jellyfin.core.presentation.components

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Smoke test that also proves the new Robolectric + MockK test infra is wired up in :core.
 * Anything in this module that needs `android.net.Uri.parse` can now be unit-tested on the JVM
 * via Robolectric instead of hitting "Method parse not mocked" under bare JUnit.
 *
 * `@Config(sdk = [35])` pins the SDK level — Robolectric 4.15.x ships resource jars up to API 35;
 * compileSdk = 36 would otherwise trip DefaultSdkPicker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UserImageUriTest {
    private val userId = UUID.fromString("b1a2c3d4-5678-90ab-cdef-1234567890ab")

    @Test fun `builds Primary image URL when server address is present`() {
        val uri = userPrimaryImageUri("https://jellyfin.example.com", userId)
        assertEquals(
            "https://jellyfin.example.com/Users/$userId/Images/Primary",
            uri.toString(),
        )
    }

    @Test fun `strips trailing slashes on the server address`() {
        val uri = userPrimaryImageUri("https://jellyfin.example.com/", userId)
        assertEquals(
            "https://jellyfin.example.com/Users/$userId/Images/Primary",
            uri.toString(),
        )
    }

    @Test fun `returns null when the server address is blank`() {
        assertNull(userPrimaryImageUri("", userId))
        assertNull(userPrimaryImageUri(null, userId))
    }

    @Test fun `returns null when the user id is missing`() {
        assertNull(userPrimaryImageUri("https://jellyfin.example.com", null))
    }
}
