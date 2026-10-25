package dev.spatialfin.unified

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.spatialfin.test.SpatialFinTestApplication

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SpatialFinTestApplication::class)
class ViewVideoPolicyTest {

    @Test
    fun testShouldHandleIntent_notActionView() {
        val context = mockk<Context>()
        val intent = Intent(Intent.ACTION_MAIN)
        assertFalse(ViewVideoPolicy.shouldHandleIntent(context, intent))
    }

    @Test
    fun testShouldHandleIntent_internalDeepLink() {
        val context = mockk<Context>()
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("spatialfin://ma/play")
        }
        assertFalse(ViewVideoPolicy.shouldHandleIntent(context, intent))
    }

    @Test
    fun testShouldHandleIntent_supportedVideo() {
        val context = mockk<Context>()
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("file:///storage/emulated/0/Movies/sample.mp4"), "video/mp4")
        }
        assertTrue(ViewVideoPolicy.shouldHandleIntent(context, intent))
    }

    @Test
    fun testResolveStereoAndProjection() {
        val sbsName = "Movie HSBS"
        assertEquals("sbs", ViewVideoPolicy.resolveStereoMode(sbsName))

        val tbName = "Avatar 3D OU"
        assertEquals("top_bottom", ViewVideoPolicy.resolveStereoMode(tbName))

        val vr360 = "Coral Reef VR360"
        assertEquals("360", ViewVideoPolicy.resolveProjection(vr360))

        val flat = "standard_movie.mp4"
        assertEquals("mono", ViewVideoPolicy.resolveStereoMode(flat))
        assertEquals("flat", ViewVideoPolicy.resolveProjection(flat))
    }
}
