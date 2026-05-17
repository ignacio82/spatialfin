package dev.jdtech.jellyfin.offline

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.cancellation.CancellationException
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the contract of [isApparentConnectionFailure]: the SmartJellyfinRepository
 * fallback path and HomeViewModel's offline-fallback catch both ride on this
 * predicate, so a regression that narrows it (e.g. a refactor that drops the
 * cause-chain walk) silently strands the UI on an empty home screen.
 */
class ServerConnectionFailureTest {
    @Test fun `direct IOException is a connection failure`() {
        assertTrue(isApparentConnectionFailure(IOException("boom")))
    }

    @Test fun `direct SocketTimeoutException is a connection failure`() {
        assertTrue(isApparentConnectionFailure(SocketTimeoutException("timeout")))
    }

    @Test fun `direct UnknownHostException is a connection failure`() {
        assertTrue(isApparentConnectionFailure(UnknownHostException("no dns")))
    }

    @Test fun `direct SSLHandshakeException is a connection failure`() {
        // SSLHandshakeException extends IOException; common in captive-portal scenarios.
        assertTrue(isApparentConnectionFailure(SSLHandshakeException("captive portal")))
    }

    @Test fun `RuntimeException wrapping IOException is a connection failure`() {
        // OkHttp interceptors occasionally surface an IOException wrapped in a
        // RuntimeException. Pre-fix this would have leaked through the predicate
        // and stranded the home screen on an empty render.
        val wrapped = RuntimeException("interceptor failure", SocketTimeoutException("timeout"))
        assertTrue(isApparentConnectionFailure(wrapped))
    }

    @Test fun `deeply nested cause chain still resolves to true`() {
        val deep =
            IllegalStateException(
                "outer",
                RuntimeException(
                    "middle",
                    Exception("inner", IOException("buried")),
                ),
            )
        assertTrue(isApparentConnectionFailure(deep))
    }

    @Test fun `non-network exceptions are not connection failures`() {
        assertFalse(isApparentConnectionFailure(IllegalArgumentException("bad arg")))
        assertFalse(isApparentConnectionFailure(NullPointerException("oops")))
        assertFalse(isApparentConnectionFailure(IllegalStateException("state")))
    }

    @Test fun `HTTP 401 is not a connection failure`() {
        // The server answered with 401 — it is reachable; our access token is
        // missing/expired. Treating this as offline live-locked the home
        // screen against the unauthenticated reachability probe.
        assertFalse(isApparentConnectionFailure(InvalidStatusException(401)))
    }

    @Test fun `HTTP 403 is not a connection failure`() {
        assertFalse(isApparentConnectionFailure(InvalidStatusException(403)))
    }

    @Test fun `HTTP 401 wrapped in a cause chain is still not a connection failure`() {
        val wrapped = RuntimeException("call failed", InvalidStatusException(401))
        assertFalse(isApparentConnectionFailure(wrapped))
    }

    @Test fun `HTTP 500 is still a connection failure`() {
        // Server reachable but erroring — offline fallback is still the
        // desired UX here, so this must stay classified as a failure.
        assertTrue(isApparentConnectionFailure(InvalidStatusException(500)))
    }

    @Test fun `HTTP 404 is still a connection failure`() {
        assertTrue(isApparentConnectionFailure(InvalidStatusException(404)))
    }

    @Test fun `bare ApiClientException is still a connection failure`() {
        assertTrue(isApparentConnectionFailure(ApiClientException()))
    }

    @Test fun `CancellationException is never a connection failure`() {
        // Coroutine cancellation must propagate up — treating it as a connection
        // failure would falsely mark the server inaccessible whenever the user
        // navigates away mid-load.
        assertFalse(isApparentConnectionFailure(CancellationException("cancelled")))
    }

}
