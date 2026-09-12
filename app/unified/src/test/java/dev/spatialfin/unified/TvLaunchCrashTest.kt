package dev.spatialfin.unified

import android.content.Context
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createComposeRule
import dev.jdtech.jellyfin.core.R as CoreR
import dev.spatialfin.test.SpatialFinTestApplication
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Carousel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.tv.material3.Button
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpatialFinTestApplication::class)
class TvLaunchCrashTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun testIcLauncherForegroundPainter() {
        composeTestRule.setContent {
            Image(
                painter = painterResource(id = CoreR.drawable.ic_launcher_foreground),
                contentDescription = "test",
            )
        }
    }

    @Test
    fun testTvOnboardingHeroInLazyColumn() {
        composeTestRule.setContent {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                contentPadding = PaddingValues(top = 64.dp, bottom = 48.dp),
            ) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().height(420.dp)) {
                        Button(onClick = {}) {
                            Text("Pair companion")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun testSpatialFinSearchProviderSafety() {
        val provider = dev.jdtech.jellyfin.search.SpatialFinSearchProvider()
        // Calling onCreate without attachInfo (blank authority) should not throw
        val created = provider.onCreate()
        org.junit.Assert.assertTrue(created)
        // Querying with uninitialized authority should safely return null rather than throw
        val cursor = provider.query(
            android.net.Uri.parse("content://dummy.search/search_suggest_query/movie"),
            null,
            null,
            null,
            null,
        )
        org.junit.Assert.assertNull(cursor)
    }

    @Test
    fun testTvDeviceClassCapabilities() {
        val tvCapabilities = DeviceClassCapabilities(DeviceClass.TV)
        org.junit.Assert.assertTrue(tvCapabilities.isTv)
        org.junit.Assert.assertFalse(tvCapabilities.hasWearCompanionHost)

        val phoneCapabilities = DeviceClassCapabilities(DeviceClass.PHONE)
        org.junit.Assert.assertFalse(phoneCapabilities.isTv)
        org.junit.Assert.assertTrue(phoneCapabilities.hasWearCompanionHost)

        val xrCapabilities = DeviceClassCapabilities(DeviceClass.XR)
        org.junit.Assert.assertTrue(xrCapabilities.hasWearCompanionHost)
    }
}
