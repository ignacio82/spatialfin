package dev.jdtech.jellyfin.player.core.external

import android.net.Uri
import android.util.Base64
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamRequest
import dev.jdtech.jellyfin.fcast.receiver.ExternalStreamSource
import dev.jdtech.jellyfin.models.DownloadTaskDto
import dev.jdtech.jellyfin.models.NetworkShareDto
import dev.jdtech.jellyfin.network.NetworkCredentials
import dev.jdtech.jellyfin.network.NetworkFileClient
import dev.jdtech.jellyfin.network.NetworkFileClientFactory
import dev.jdtech.jellyfin.network.NetworkFileEntry
import dev.jdtech.jellyfin.network.NetworkStreamProxy
import dev.jdtech.jellyfin.network.SafeUrlBlockedException
import dev.jdtech.jellyfin.player.core.extractor.mkv.SideloadedSubtitleMediaSourceFactory
import dev.jdtech.jellyfin.player.core.security.EncryptedLocalDataSourceFactory
import dev.jdtech.jellyfin.security.ContentKeyManager
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.Socket
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PluginHttpDataSourceFactoryTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        PluginHttpDataSourceFactory.resetForTests()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        PluginHttpDataSourceFactory.resetForTests()
    }

    @Test
    fun pluginHttpDataSourceFactoryBlocksLoopbackByDefaultAndAllowlistsActiveNetworkStreamProxy() {
        // Default allowNetworkProxyLoopback = false blocks localhost / MockWebServer
        val defaultFactory = PluginHttpDataSourceFactory.create(allowNetworkProxyLoopback = false)
        val ds = defaultFactory.createDataSource()
        try {
            ds.open(DataSpec(Uri.parse(server.url("/video.mp4").toString())))
            fail("Expected loopback blocked when allowNetworkProxyLoopback = false")
        } catch (e: Exception) {
            var cur: Throwable? = e
            var foundSsrf = false
            while (cur != null) {
                if (cur is SafeUrlBlockedException) {
                    foundSsrf = true
                    break
                }
                cur = cur.cause
            }
            assertTrue("Expected SafeUrlBlockedException in cause chain, got $e", foundSsrf)
        } finally {
            runCatching { ds.close() }
        }

        // Start NetworkStreamProxy and verify allowNetworkProxyLoopback = true can read its stream
        val shareId = "share-1"
        val payload = "0123456789abcdef".toByteArray()
        val fakeClient = object : NetworkFileClient {
            override suspend fun listFiles(host: String, shareName: String, path: String, credentials: NetworkCredentials) = emptyList<NetworkFileEntry>()
            override suspend fun openFile(host: String, shareName: String, filePath: String, credentials: NetworkCredentials, offset: Long): InputStream =
                ByteArrayInputStream(payload, offset.toInt(), payload.size - offset.toInt())
            override suspend fun getFileSize(host: String, shareName: String, filePath: String, credentials: NetworkCredentials): Long = payload.size.toLong()
            override suspend fun testConnection(host: String, shareName: String, credentials: NetworkCredentials): Boolean = true
        }
        val factory = mockk<NetworkFileClientFactory> {
            every { clientFor(any()) } returns fakeClient
        }
        val db = mockk<ServerDatabaseDao> {
            every { getNetworkShare(shareId) } returns NetworkShareDto(
                id = shareId,
                protocol = "smb",
                host = "192.168.1.50",
                shareName = "media",
                path = "",
                displayName = "NAS",
                username = null,
                password = null,
                domain = null,
                addedAtEpochMs = 1L,
                lastScannedAtEpochMs = null,
            )
        }
        val proxy = NetworkStreamProxy(factory, db)
        proxy.start()
        try {
            val streamUrl = proxy.getStreamUrl(shareId, "movie.mkv")
            assertTrue(streamUrl.contains("&token="))
            val port = Uri.parse(streamUrl).port
            assertTrue(("127.0.0.1" to port) in NetworkStreamProxy.activeLoopbackTargets())

            // Valid token succeeds via allowNetworkProxyLoopback = true
            val proxyDs = PluginHttpDataSourceFactory.create(allowNetworkProxyLoopback = true).createDataSource()
            try {
                val len = proxyDs.open(DataSpec(Uri.parse(streamUrl)))
                assertEquals(payload.size.toLong(), len)
            } finally {
                proxyDs.close()
            }

            // Missing/wrong token returns 403
            val rawClient = OkHttpClient()
            val badTokenUrl = streamUrl.substringBefore("&token=") + "&token=wrong"
            rawClient.newCall(Request.Builder().url(badTokenUrl).build()).execute().use { resp ->
                assertEquals(403, resp.code)
            }

            // Traversal segment ".." returns 400
            val traversalUrl = proxy.getStreamUrl(shareId, "../etc/passwd")
            rawClient.newCall(Request.Builder().url(traversalUrl).build()).execute().use { resp ->
                assertEquals(400, resp.code)
            }

            // Invalid Range returns 416
            rawClient.newCall(
                Request.Builder().url(streamUrl).header("Range", "bytes=999-1000").build(),
            ).execute().use { resp ->
                assertEquals(416, resp.code)
            }

            // Oversized headers (> 8 KiB) rejected with 431
            Socket("127.0.0.1", port).use { sock ->
                sock.soTimeout = 3000
                val hugeHeader = "GET /stream?shareId=$shareId&path=a HTTP/1.1\r\n" +
                    "X-Junk: " + "A".repeat(9000) + "\r\n\r\n"
                sock.getOutputStream().write(hugeHeader.toByteArray())
                sock.getOutputStream().flush()
                val statusLine = sock.getInputStream().bufferedReader().readLine()
                assertNotNull(statusLine)
                assertTrue("Expected 431, got $statusLine", statusLine!!.contains("431"))
            }
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun externalStreamMediaPreparerRejectsLocalSchemesAndUnsafeThumbnails() {
        val context = RuntimeEnvironment.getApplication()
        val preparer = ExternalStreamMediaPreparer(context)

        // file:// and content:// rejected
        for (badUrl in listOf("file:///etc/passwd", "content://media/external/video/1", "asset:///video.mp4", "http://127.0.0.1:8096/v.mp4")) {
            try {
                preparer.prepare(
                    ExternalStreamRequest(
                        source = ExternalStreamSource.Url(badUrl),
                        container = "video/mp4",
                    ),
                )
                fail("Expected $badUrl rejected by ExternalStreamMediaPreparer")
            } catch (_: SafeUrlBlockedException) {
            }
        }

        // Non-http/https or IMDS thumbnailUrl is stripped (not passed to MediaMetadata.artworkUri)
        val prepared = preparer.prepare(
            ExternalStreamRequest(
                source = ExternalStreamSource.Url("https://192.168.1.10:8096/video.mp4"),
                container = "video/mp4",
                thumbnailUrl = "file:///data/data/dev.spatialfin/secret.png",
            ),
        )
        assertNull(prepared.mediaSource.mediaItem.mediaMetadata.artworkUri)
    }

    @Test
    fun sideloadedSubtitleMediaSourceFactoryIsolatesHttpMediaItemsFromLocalDelegate() {
        var localDelegateUsed = false
        val trackingLocalFactory = DataSource.Factory {
            localDelegateUsed = true
            FileDataSource()
        }
        val factory = SideloadedSubtitleMediaSourceFactory(
            dataSourceFactory = trackingLocalFactory,
            parseSubtitlesDuringExtraction = true,
        )

        // Creating an HTTP MediaSource routes through HTTP-only factory (never localDelegate)
        val httpSource = factory.createMediaSource(
            MediaItem.Builder().setUri("https://192.168.1.10:8096/stream.mp4").build(),
        )
        assertNotNull(httpSource)
        assertTrue(!localDelegateUsed)
    }

    @Test
    fun encryptedLocalDataSourceFactoryBlocksPrivateAppDirsExceptTranscodeCacheAndZeroesDekOnOpenFailure() {
        val context = RuntimeEnvironment.getApplication()
        val db = mockk<ServerDatabaseDao>()
        every { db.getDownloadTaskByFinalPath(any()) } returns null

        val dek = ByteArray(32) { 0x42 }
        val keyManager = mockk<ContentKeyManager> {
            every { getDekOrNull() } returns dek
        }

        val factory = EncryptedLocalDataSourceFactory(
            context = context,
            delegate = FileDataSource.Factory(),
            contentKeyManager = keyManager,
            database = db,
        )

        // Private filesDir file blocked (with and without file:// scheme)
        val secretFile = File(context.filesDir, "secret.db").apply { writeText("secret") }
        try {
            val ds1 = factory.createDataSource()
            try {
                ds1.open(DataSpec(Uri.fromFile(secretFile)))
                fail("Expected private filesDir blocked")
            } catch (_: SecurityException) {
            }
            val ds2 = factory.createDataSource()
            try {
                ds2.open(DataSpec(Uri.parse(secretFile.absolutePath)))
                fail("Expected scheme-less private filesDir blocked")
            } catch (_: SecurityException) {
            }
        } finally {
            secretFile.delete()
        }

        // cacheDir/transcode_offline_12345.mp4 IS permitted
        val transcodeFile = File(context.cacheDir, "transcode_offline_12345.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        try {
            val ds = factory.createDataSource()
            try {
                assertEquals(4L, ds.open(DataSpec(Uri.fromFile(transcodeFile))))
            } finally {
                ds.close()
            }
        } finally {
            transcodeFile.delete()
        }

        // Encrypted open failure (missing IV) closes source and zeroes DEK
        val extDir = context.getExternalFilesDir(null) ?: context.cacheDir
        val encFile = File(extDir, "movie.enc").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val badTask = mockk<DownloadTaskDto> {
            every { isEncrypted } returns true
            every { encryptionIv } returns null
        }
        every { db.getDownloadTaskByFinalPath(encFile.canonicalPath) } returns badTask
        try {
            val ds = factory.createDataSource()
            try {
                ds.open(DataSpec(Uri.fromFile(encFile)))
                fail("Expected missing IV failure")
            } catch (_: IllegalStateException) {
            }
            assertTrue("Expected DEK to be zeroed on open() failure", dek.all { it == 0.toByte() })
        } finally {
            encFile.delete()
        }
    }
}
