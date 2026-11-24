package dev.jdtech.jellyfin.network

import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.models.normalizeJellyfinId
import dev.jdtech.jellyfin.models.parseJellyfinUuid
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SafeUrlPolicyTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun validateInitialUrl_rejectsNonCanonicalIpv4AndSyntacticLoopback() {
        val blockedUrls = listOf(
            "http://127.1:8096/",
            "http://0177.0.0.1:8096/",
            "http://2130706433:8096/",
            "http://0xa9fea9fe/",
            "http://localhost:8096/",
            "http://sub.localhost:8096/",
            "http://ip6-localhost:8096/",
            "http://ip6-loopback:8096/",
            "http://169.254.169.254/latest/meta-data/",
            "http://[fd00:ec2::254]/latest/meta-data/",
            "http://100.100.100.200/latest/meta-data/",
            "http://[fe80::1]:8096/",
            "http://[fe80::1%25wlan0]:8096/",
            "http://[::ffff:127.0.0.1]:8096/",
            "http://[64:ff9b::169.254.169.254]/",
            "http://[2002:7f00:0001::]/",
            "http://[2001:0000:4136:e378:8000:63bf:3fff:fdd2]/",
            "http://[fe80::200:5efe:192.168.1.1]/",
            "http://[fd00::5efe:192.168.1.1]/",
            "http://user:pass@192.168.1.10:8096/",
            "file:///etc/passwd",
        )
        for (url in blockedUrls) {
            try {
                SafeUrlPolicy.validateInitialUrl(url, UrlTargetPolicy.PUBLIC_OR_LAN)
                fail("Expected SafeUrlBlockedException for $url")
            } catch (_: SafeUrlBlockedException) {
                // expected
            }
        }
    }

    @Test
    fun policiesEnforceAllowedTargetsAndProxyIsolation() {
        // PRIVATE_LAN_ONLY allows RFC1918, CGNAT, ULA and blocks public IPs
        SafeUrlPolicy.validateInitialUrl("http://192.168.1.10:1982/", UrlTargetPolicy.PRIVATE_LAN_ONLY)
        SafeUrlPolicy.validateInitialUrl("http://100.64.0.5:1982/", UrlTargetPolicy.PRIVATE_LAN_ONLY)
        SafeUrlPolicy.validateInitialUrl("http://[fd00::1]:1982/", UrlTargetPolicy.PRIVATE_LAN_ONLY)
        try {
            SafeUrlPolicy.validateInitialUrl("https://8.8.8.8/", UrlTargetPolicy.PRIVATE_LAN_ONLY)
            fail("Expected public IP blocked under PRIVATE_LAN_ONLY")
        } catch (_: SafeUrlBlockedException) {
        }

        // LAN_ONLY allows HTTP only to LAN, HTTPS to public or LAN
        SafeUrlPolicy.validateInitialUrl("http://10.0.0.5:1982/", UrlTargetPolicy.LAN_ONLY)
        SafeUrlPolicy.validateInitialUrl("https://8.8.8.8/", UrlTargetPolicy.LAN_ONLY)
        try {
            SafeUrlPolicy.validateInitialUrl("http://8.8.8.8/", UrlTargetPolicy.LAN_ONLY)
            fail("Expected HTTP public IP blocked under LAN_ONLY")
        } catch (_: SafeUrlBlockedException) {
        }

        // PUBLIC_INTERNET_ONLY blocks RFC1918, CGNAT, ULA, and loopback (even with allowLoopbackForTests=true)
        SafeUrlPolicy.validateInitialUrl("https://8.8.8.8/", UrlTargetPolicy.PUBLIC_INTERNET_ONLY)
        for (privateUrl in listOf(
            "http://192.168.1.10/",
            "http://100.64.0.1/",
            "http://[fd00::1]/",
            "http://127.0.0.1:8080/",
        )) {
            try {
                SafeUrlPolicy.validateInitialUrl(
                    privateUrl,
                    UrlTargetPolicy.PUBLIC_INTERNET_ONLY,
                    allowLoopbackForTests = true,
                )
                fail("Expected $privateUrl blocked under PUBLIC_INTERNET_ONLY")
            } catch (_: SafeUrlBlockedException) {
            }
        }

        // Proxy isolation & fastFallback(false)
        val publicInternetClient = OkHttpClient.Builder()
            .withSsrfGuard(UrlTargetPolicy.PUBLIC_INTERNET_ONLY)
            .build()
        assertEquals(Proxy.NO_PROXY, publicInternetClient.proxy)
        assertFalse(publicInternetClient.fastFallback)
        assertFalse(publicInternetClient.followRedirects)
        assertFalse(publicInternetClient.followSslRedirects)

        // newBuilderWithSsrfGuard unwraps and strips prior wrappers without stacking
        val base = OkHttpClient.Builder()
            .withSsrfGuard(UrlTargetPolicy.PUBLIC_OR_LAN)
            .build()
        val derived = base.newBuilderWithSsrfGuard(UrlTargetPolicy.LAN_ONLY).build()
        assertEquals(Proxy.NO_PROXY, derived.proxy)
        assertEquals(1, derived.interceptors.count { it is SafeRedirectInterceptor })
        assertEquals(1, derived.networkInterceptors.count { it is GuardedNetworkInterceptor })
    }

    @Test
    fun isatapRawByteAndTunnelPrefixesAreBlocked() {
        // ISATAP with u=0,g=0 (00:00:5e:fe) and u=1,g=0 (02:00:5e:fe)
        val isatap0 = InetAddress.getByAddress(
            byteArrayOf(
                0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0,
                0x00, 0x00, 0x5e, 0xfe.toByte(), 192.toByte(), 168.toByte(), 1, 1,
            ),
        )
        val isatap2 = InetAddress.getByAddress(
            byteArrayOf(
                0xfd.toByte(), 0x00, 0, 0, 0, 0, 0, 0,
                0x02, 0x00, 0x5e, 0xfe.toByte(), 10, 0, 0, 1,
            ),
        )
        assertTrue(SafeUrlPolicy.isDeprecatedOrTunnelV6(isatap0))
        assertTrue(SafeUrlPolicy.isDeprecatedOrTunnelV6(isatap2))
        assertTrue(SafeUrlPolicy.isUniversallyProhibitedNonLoopback(isatap0))
        assertTrue(SafeUrlPolicy.isUniversallyProhibitedNonLoopback(isatap2))
    }

    @Test
    fun dnsFilteringStripsFe80AndAppliesMdnsLocalFilter() {
        val fe80 = InetAddress.getByAddress(
            byteArrayOf(0xfe.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
        )
        val lan = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 20))
        val pub = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
        val imds = InetAddress.getByAddress(byteArrayOf(169.toByte(), 254.toByte(), 169.toByte(), 254.toByte()))

        // .local under PRIVATE_LAN_ONLY filters fe80 + public and keeps LAN
        val filteredLocal = SafeUrlPolicy.filterResolvedAddresses(
            hostname = "nas.local",
            addresses = listOf(fe80, pub, lan),
            policy = UrlTargetPolicy.PRIVATE_LAN_ONLY,
            allowLoopbackForTests = false,
            loopbackAllowlist = emptySet(),
        )
        assertEquals(listOf(lan), filteredLocal)

        // Any universally prohibited record fails closed even on .local
        try {
            SafeUrlPolicy.filterResolvedAddresses(
                hostname = "nas.local",
                addresses = listOf(lan, imds),
                policy = UrlTargetPolicy.PRIVATE_LAN_ONLY,
                allowLoopbackForTests = false,
                loopbackAllowlist = emptySet(),
            )
            fail("Expected IMDS record to fail closed")
        } catch (_: SafeUrlBlockedException) {
        }

        // Non-.local fails closed if any record is public under PRIVATE_LAN_ONLY
        try {
            SafeUrlPolicy.filterResolvedAddresses(
                hostname = "nas.example.com",
                addresses = listOf(lan, pub),
                policy = UrlTargetPolicy.PRIVATE_LAN_ONLY,
                allowLoopbackForTests = false,
                loopbackAllowlist = emptySet(),
            )
            fail("Expected mixed public+LAN on non-.local to fail closed")
        } catch (_: SafeUrlBlockedException) {
        }
    }

    @Test
    fun resolveLanHostAndNfsLookupChunksBlockUnsafeTargetsAndTraversal() = runBlocking {
        // resolveLanHost accepts LAN literal and blocks public/loopback
        val lanAddr = SafeUrlPolicy.resolveLanHost("192.168.1.50")
        assertEquals("192.168.1.50", lanAddr.hostAddress)

        try {
            SafeUrlPolicy.resolveLanHost("8.8.8.8")
            fail("Expected public IP blocked by resolveLanHost")
        } catch (_: SafeUrlBlockedException) {
        }

        try {
            SafeUrlPolicy.resolveLanHost("127.0.0.1")
            fail("Expected loopback blocked by resolveLanHost")
        } catch (_: SafeUrlBlockedException) {
        }

        // AndroidNfsClient.lookupChunks rejects "." and ".." segments
        assertEquals(listOf(listOf("movies", "clip.mkv")), AndroidNfsClient.lookupChunks("/movies/clip.mkv", 16))
        try {
            AndroidNfsClient.lookupChunks("/movies/../etc/passwd", 16)
            fail("Expected '..' rejected in lookupChunks")
        } catch (_: IllegalArgumentException) {
        }
        try {
            AndroidNfsClient.lookupChunks("/movies/./clip.mkv", 16)
            fail("Expected '.' rejected in lookupChunks")
        } catch (_: IllegalArgumentException) {
        }

        // SmbFileClient and NfsFileClient enforce resolveLanHost before connecting
        val smb = SmbFileClient()
        try {
            smb.listFiles("8.8.8.8", "media", "", NetworkCredentials(null, null))
            fail("Expected SMB public host blocked")
        } catch (_: SafeUrlBlockedException) {
        }

        val nfs = NfsFileClient()
        try {
            nfs.listFiles("8.8.8.8", "/export", "", NetworkCredentials(null, null))
            fail("Expected NFS public host blocked")
        } catch (_: SafeUrlBlockedException) {
        }
    }

    @Test
    fun guardedProxySelectorHandlesAllowlistedLoopbackSocksAndResolvedProxies() {
        val httpProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.corp.local", 3128))
        val delegate = object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> = listOf(httpProxy, Proxy.NO_PROXY)
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
        }
        val selector = GuardedProxySelector(
            delegate = delegate,
            loopbackAllowlistProvider = { setOf("127.0.0.1" to 49152) },
            allowLoopbackForTests = false,
        )

        // Allowlisted literal loopback bypasses system HTTP proxy directly
        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("http://127.0.0.1:49152/stream")))

        // Non-loopback strips Proxy.NO_PROXY when valid HTTP proxy is present
        assertEquals(listOf(httpProxy), selector.select(URI("https://jellyfin.example.com/")))

        // SOCKS proxy throws SafeUrlBlockedException
        val socksSelector = GuardedProxySelector(
            delegate = object : ProxySelector() {
                override fun select(uri: URI?): List<Proxy> =
                    listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("192.168.1.2", 1080)))
                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
            },
            loopbackAllowlistProvider = null,
            allowLoopbackForTests = false,
        )
        try {
            socksSelector.select(URI("https://jellyfin.example.com/"))
            fail("Expected SOCKS proxy to throw")
        } catch (_: SafeUrlBlockedException) {
        }

        // Already-resolved loopback proxy rejected unless hostString is literally 127.0.0.1 / ::1 / localhost
        val spoofedLoopbackProxy = Proxy(
            Proxy.Type.HTTP,
            InetSocketAddress(InetAddress.getByAddress("evil.lan", byteArrayOf(127, 0, 0, 1)), 3128),
        )
        val spoofSelector = GuardedProxySelector(
            delegate = object : ProxySelector() {
                override fun select(uri: URI?): List<Proxy> = listOf(spoofedLoopbackProxy)
                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
            },
            loopbackAllowlistProvider = null,
            allowLoopbackForTests = false,
        )
        try {
            spoofSelector.select(URI("https://jellyfin.example.com/"))
            fail("Expected spoofed resolved loopback proxy to be blocked")
        } catch (_: SafeUrlBlockedException) {
        }

        // Literal 127.0.0.1 HTTP proxy IS allowed
        val literalLoopbackProxy = Proxy(
            Proxy.Type.HTTP,
            InetSocketAddress(InetAddress.getByAddress("127.0.0.1", byteArrayOf(127, 0, 0, 1)), 8888),
        )
        val literalSelector = GuardedProxySelector(
            delegate = object : ProxySelector() {
                override fun select(uri: URI?): List<Proxy> = listOf(literalLoopbackProxy)
                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
            },
            loopbackAllowlistProvider = null,
            allowLoopbackForTests = false,
        )
        assertEquals(listOf(literalLoopbackProxy), literalSelector.select(URI("https://jellyfin.example.com/")))
    }

    @Test
    fun redirectsEnforceMaxRedirectsRewritingHeaderStrippingAndOriginBoundaries() {
        // 1. maxRedirects = 0 returns raw 302
        server.enqueue(MockResponse(code = 302, headers = okhttp3.Headers.headersOf("Location", "/final")))
        val noRedirectClient = OkHttpClient.Builder()
            .withSsrfGuard(UrlTargetPolicy.PUBLIC_OR_LAN, allowLoopbackForTests = true, maxRedirects = 0)
            .build()
        noRedirectClient.newCall(Request.Builder().url(server.url("/start")).build()).execute().use { resp ->
            assertEquals(302, resp.code)
        }
        server.takeRequest()

        // 2. 303 rewrites PUT -> GET and drops body
        server.enqueue(MockResponse(code = 303, headers = okhttp3.Headers.headersOf("Location", "/after303")))
        server.enqueue(MockResponse(code = 200, body = "ok"))
        val redirectClient = OkHttpClient.Builder()
            .withSsrfGuard(UrlTargetPolicy.PUBLIC_OR_LAN, allowLoopbackForTests = true, maxRedirects = 5)
            .build()
        redirectClient.newCall(
            Request.Builder()
                .url(server.url("/put-start"))
                .put("payload".toRequestBody("text/plain".toMediaType()))
                .build(),
        ).execute().use { resp ->
            assertEquals(200, resp.code)
        }
        val firstReq = server.takeRequest()
        val secondReq = server.takeRequest()
        assertEquals("PUT", firstReq.method)
        assertEquals("GET", secondReq.method)
        assertEquals(0L, secondReq.bodySize)

        // 3. Cross-origin redirect strips X-Pairing-Token & Authorization under PUBLIC_OR_LAN
        val server2 = MockWebServer()
        server2.start()
        try {
            server.enqueue(
                MockResponse(
                    code = 302,
                    headers = okhttp3.Headers.headersOf("Location", server2.url("/target").toString()),
                ),
            )
            server2.enqueue(MockResponse(code = 200, body = "redirected"))
            redirectClient.newCall(
                Request.Builder()
                    .url(server.url("/cross"))
                    .header("X-Pairing-Token", "secret-pair")
                    .header("Authorization", "Bearer secret-auth")
                    .build(),
            ).execute().use { resp ->
                assertEquals(200, resp.code)
            }
            server.takeRequest()
            val req2 = server2.takeRequest()
            assertNull(req2.headers["X-Pairing-Token"])
            assertNull(req2.headers["Authorization"])

            // 4. Cross-origin redirect retaining body on 307 is rejected
            server.enqueue(
                MockResponse(
                    code = 307,
                    headers = okhttp3.Headers.headersOf("Location", server2.url("/post-target").toString()),
                ),
            )
            try {
                redirectClient.newCall(
                    Request.Builder()
                        .url(server.url("/post-cross"))
                        .post("body".toRequestBody("text/plain".toMediaType()))
                        .build(),
                ).execute()
                fail("Expected cross-origin 307 with body to be blocked")
            } catch (_: SafeUrlBlockedException) {
            }
            server.takeRequest()

            // 5. One-shot body on 307 same-origin is rejected
            server.enqueue(MockResponse(code = 307, headers = okhttp3.Headers.headersOf("Location", "/same")))
            val oneShotBody = object : RequestBody() {
                override fun contentType() = "text/plain".toMediaType()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) {
                    sink.writeUtf8("once")
                }
            }
            try {
                redirectClient.newCall(
                    Request.Builder().url(server.url("/oneshot")).post(oneShotBody).build(),
                ).execute()
                fail("Expected one-shot body 307 redirect to fail")
            } catch (_: SafeUrlBlockedException) {
            }
            server.takeRequest()
        } finally {
            server2.close()
        }

        // 6. Upgrade: websocket bypasses 3xx following
        server.enqueue(MockResponse(code = 302, headers = okhttp3.Headers.headersOf("Location", "/other")))
        redirectClient.newCall(
            Request.Builder()
                .url(server.url("/ws"))
                .header("Upgrade", "websocket")
                .build(),
        ).execute().use { resp ->
            assertEquals(302, resp.code)
        }
        server.takeRequest()
    }

    @Test
    fun guardedDnsAndSocketFactoryIsolateConnectionPoolEquality() {
        val providerA: () -> Set<Pair<String, Int>> = { setOf("127.0.0.1" to 10001) }
        val providerB: () -> Set<Pair<String, Int>> = { setOf("127.0.0.1" to 10002) }
        val dnsA = GuardedDns(UrlTargetPolicy.PUBLIC_OR_LAN, false, providerA)
        val dnsB = GuardedDns(UrlTargetPolicy.PUBLIC_OR_LAN, false, providerB)
        assertNotEquals(dnsA, dnsB)
        assertNotEquals(
            GuardedSocketFactory(UrlTargetPolicy.PUBLIC_OR_LAN, false, providerA, dnsA),
            GuardedSocketFactory(UrlTargetPolicy.PUBLIC_OR_LAN, false, providerB, dnsB),
        )
    }

    @Test
    fun guardedSocketFactoryBlocksUnresolvedAndProhibitedOverloads() {
        val dns = GuardedDns(UrlTargetPolicy.PUBLIC_INTERNET_ONLY, false, null, Dns { listOf(InetAddress.getByName("127.0.0.1")) })
        val sf = GuardedSocketFactory(UrlTargetPolicy.PUBLIC_INTERNET_ONLY, false, null, dns)

        // Unresolved InetSocketAddress blocked
        try {
            sf.createSocket().connect(InetSocketAddress.createUnresolved("example.com", 80))
            fail("Expected unresolved socket connect blocked")
        } catch (_: SafeUrlBlockedException) {
        }

        // String-host overloads blocked when DNS returns loopback
        try {
            sf.createSocket("evil.example", 80)
            fail("Expected createSocket(String, Int) blocked")
        } catch (_: SafeUrlBlockedException) {
        }
        try {
            sf.createSocket("evil.example", 80, null, 0)
            fail("Expected createSocket(String, Int, InetAddress, Int) blocked")
        } catch (_: SafeUrlBlockedException) {
        }

        // InetAddress overloads blocked for loopback / private IP
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        try {
            sf.createSocket(loopback, 80)
            fail("Expected createSocket(InetAddress, Int) blocked")
        } catch (_: SafeUrlBlockedException) {
        }
        try {
            sf.createSocket(loopback, 80, null, 0)
            fail("Expected createSocket(InetAddress, Int, InetAddress, Int) blocked")
        } catch (_: SafeUrlBlockedException) {
        }
    }

    @Test
    fun jellyfinApiCreateApiFollowRedirectsFalseDoesNotFollowRedirects() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        android.provider.Settings.Secure.putString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID,
            "test-android-id",
        )
        val jellyfinApi = JellyfinApi(androidContext = context, allowLoopbackForTests = true)
        assertFalse(jellyfinApi.httpClientOptions.followRedirects)

        server.enqueue(MockResponse(code = 302, headers = okhttp3.Headers.headersOf("Location", "/System/Info/Public")))
        val apiClient = jellyfinApi.createApi(
            baseUrl = server.url("/").toString().trimEnd('/'),
            followRedirects = false,
        )
        try {
            apiClient.systemApi.getPublicSystemInfo()
            fail("Expected 302 not to be followed when followRedirects = false")
        } catch (_: Exception) {
            // Jellyfin SDK throws InvalidStatusException on raw 302
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun jellyfinIdHelpersParseDashlessAndHyphenatedUuids() {
        val dashless = "fbae46090958aa929b8afa8ab0b68901"
        val hyphenated = "fbae4609-0958-aa92-9b8a-fa8ab0b68901"
        val u1 = parseJellyfinUuid(dashless)
        val u2 = parseJellyfinUuid(hyphenated)
        assertNotNull(u1)
        assertEquals(u1, u2)
        assertNull(parseJellyfinUuid("not-a-uuid"))
        assertEquals(dashless, normalizeJellyfinId("  $hyphenated  ".uppercase()))
    }
}
