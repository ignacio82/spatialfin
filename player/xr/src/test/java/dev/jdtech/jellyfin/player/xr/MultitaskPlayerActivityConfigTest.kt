package dev.jdtech.jellyfin.player.xr

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.w3c.dom.Element

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MultitaskPlayerActivityConfigTest {

    private fun resolveXrModuleFile(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("player/xr", relativePath),
            File("../player/xr", relativePath),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not locate $relativePath from ${File(".").absolutePath}")
    }

    private fun parseMultitaskActivityElement(): Element {
        val manifestFile = resolveXrModuleFile("src/main/AndroidManifest.xml")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifestFile)
        val activities = doc.getElementsByTagName("activity")
        val fqcn = MultitaskPlayerActivity::class.java.name
        for (i in 0 until activities.length) {
            val el = activities.item(i) as Element
            if (el.getAttributeNS("http://schemas.android.com/apk/res/android", "name") == fqcn) {
                return el
            }
        }
        error("Could not find $fqcn in ${manifestFile.absolutePath}")
    }

    @Test
    fun `MultitaskPlayerActivity extends AppCompatActivity`() {
        assertTrue(AppCompatActivity::class.java.isAssignableFrom(MultitaskPlayerActivity::class.java))
    }

    @Test
    fun `manifest declares Home Space start mode, black theme, density configChanges, and no PiP`() {
        val androidNs = "http://schemas.android.com/apk/res/android"
        val activityEl = parseMultitaskActivityElement()

        assertEquals(
            "@style/Theme.SpatialFin.MultitaskPlayer",
            activityEl.getAttributeNS(androidNs, "theme"),
        )
        assertEquals(
            "",
            activityEl.getAttributeNS(androidNs, "supportsPictureInPicture"),
        )

        val configTokens = activityEl.getAttributeNS(androidNs, "configChanges")
            .split("|")
            .filter { it.isNotBlank() }
            .toSet()
        assertTrue("Expected density in configChanges: $configTokens", "density" in configTokens)
        assertTrue("screenSize" in configTokens)
        assertTrue("smallestScreenSize" in configTokens)
        assertTrue("screenLayout" in configTokens)
        assertTrue("orientation" in configTokens)
        assertTrue("uiMode" in configTokens)

        val properties = activityEl.getElementsByTagName("property")
        var startModeValue: String? = null
        for (i in 0 until properties.length) {
            val prop = properties.item(i) as Element
            if (prop.getAttributeNS(androidNs, "name") == "android.window.PROPERTY_XR_ACTIVITY_START_MODE") {
                startModeValue = prop.getAttributeNS(androidNs, "value")
            }
        }
        assertEquals("XR_ACTIVITY_START_MODE_HOME_SPACE", startModeValue)

        // Also confirm the merged Robolectric ActivityInfo carries the expected flags/theme.
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getActivityInfo(
            ComponentName(app, MultitaskPlayerActivity::class.java),
            PackageManager.GET_META_DATA,
        )
        val requiredMask =
            ActivityInfo.CONFIG_DENSITY or
                ActivityInfo.CONFIG_SCREEN_SIZE or
                ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or
                ActivityInfo.CONFIG_SCREEN_LAYOUT or
                ActivityInfo.CONFIG_ORIENTATION or
                ActivityInfo.CONFIG_UI_MODE
        assertEquals(requiredMask, info.configChanges and requiredMask)
        assertEquals(R.style.Theme_SpatialFin_MultitaskPlayer, info.theme)
    }

    @Test
    fun `MultitaskPlayer theme specifies AppCompat parent and black windowBackground`() {
        val themesFile = resolveXrModuleFile("src/main/res/values/themes.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(themesFile)
        val styles = doc.getElementsByTagName("style")
        var multitaskStyle: Element? = null
        for (i in 0 until styles.length) {
            val el = styles.item(i) as Element
            if (el.getAttribute("name") == "Theme.SpatialFin.MultitaskPlayer") {
                multitaskStyle = el
            }
        }
        val style = requireNotNull(multitaskStyle) { "Missing Theme.SpatialFin.MultitaskPlayer" }
        assertEquals("Theme.AppCompat.DayNight.NoActionBar", style.getAttribute("parent"))
        val items = style.getElementsByTagName("item")
        var windowBackground: String? = null
        for (i in 0 until items.length) {
            val item = items.item(i) as Element
            if (item.getAttribute("name") == "android:windowBackground") {
                windowBackground = item.textContent.trim()
            }
        }
        assertEquals("@android:color/black", windowBackground)
    }

    @Test
    fun `intent factories populate expected extras and URI-only intents work safely`() {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://media/external/video/media/99")
        val uriIntent = MultitaskPlayerActivity.createIntentForUri(context, uri, title = "Clip")
        assertEquals(uri, uriIntent.data)
        assertEquals("Clip", uriIntent.getStringExtra("mediaTitle"))
        assertFalse(uriIntent.getBooleanExtra("startFromBeginning", true))
        assertTrue((uriIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)

        val bareUriIntent = Intent(context, MultitaskPlayerActivity::class.java).apply {
            data = uri
        }
        assertNull(bareUriIntent.extras)
        assertFalse(bareUriIntent.getBooleanExtra("startFromBeginning", false))

        val id = UUID.randomUUID()
        val itemIntent = MultitaskPlayerActivity.createIntent(
            context = context,
            itemId = id,
            itemKind = "Movie",
            startFromBeginning = true,
            audioStreamIndex = 1,
            subtitleStreamIndex = 2,
            subtitlesDisabled = true,
        )
        assertEquals(id.toString(), itemIntent.getStringExtra("itemId"))
        assertEquals("Movie", itemIntent.getStringExtra("itemKind"))
        assertTrue(itemIntent.getBooleanExtra("startFromBeginning", false))
        assertEquals(1, itemIntent.getIntExtra("audioStreamIndex", -1))
        assertEquals(2, itemIntent.getIntExtra("subtitleStreamIndex", -1))
        assertTrue(itemIntent.getBooleanExtra("subtitlesDisabled", false))
    }
}
