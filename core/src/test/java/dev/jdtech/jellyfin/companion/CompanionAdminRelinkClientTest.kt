package dev.jdtech.jellyfin.companion

import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.network.newBuilderWithSsrfGuard
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.quickconnect.ApproveResult
import dev.jdtech.jellyfin.quickconnect.QuickConnectClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CompanionAdminRelinkClientTest {

    private fun loopbackClient(): OkHttpClient =
        OkHttpClient()
            .newBuilderWithSsrfGuard(
                policy = UrlTargetPolicy.PUBLIC_OR_LAN,
                allowLoopbackForTests = true,
            )
            .build()

    @Test
    fun `relinkUser authenticates ephemerally, approves QC locally without leaking token, and logs out`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                // 1. POST /api/admin/login
                server.enqueue(
                    MockResponse(
                        code = 200,
                        body = """{"authenticated":true}""",
                        headers = okhttp3.Headers.headersOf("Set-Cookie", "session=admin-sess-1; HttpOnly; Path=/; SameSite=Strict"),
                    ),
                )
                // 2. POST /api/admin/quick-connect/initiate
                server.enqueue(
                    MockResponse(
                        code = 200,
                        body = """{"pendingId":"pend-1","code":"654321","expiresAt":9999999999}""",
                    ),
                )
                // 3. POST /api/admin/quick-connect/poll (pending -> authenticated)
                server.enqueue(MockResponse(code = 200, body = """{"status":"pending"}"""))
                server.enqueue(
                    MockResponse(
                        code = 200,
                        body = """{"status":"authenticated","serverId":"srv-1","userId":"u-1","username":"ignacio"}""",
                    ),
                )
                // 4. POST /api/admin/logout
                server.enqueue(MockResponse(code = 200, body = """{"ok":true}"""))

                val qcClient = mockk<QuickConnectClient>()
                coEvery {
                    qcClient.approve("http://192.168.1.90:8096", "device-secret-jf-token", "654321")
                } returns ApproveResult.Approved

                val client =
                    CompanionAdminRelinkClient(loopbackClient(), qcClient).apply {
                        allowLoopbackForTests = true
                    }

                val res =
                    client.relinkUser(
                        companionBaseUrl = server.url("/").toString(),
                        adminPassword = "companion-admin-pass",
                        serverId = "srv-1",
                        jellyfinBaseUrl = "http://192.168.1.90:8096",
                        jellyfinAccessToken = "device-secret-jf-token",
                        jellyfinUsername = "ignacio",
                    )

                assertEquals(CompanionRelinkResult.Success("ignacio"), res)
                coVerify(exactly = 1) {
                    qcClient.approve("http://192.168.1.90:8096", "device-secret-jf-token", "654321")
                }

                val requests = mutableListOf<RecordedRequest>()
                repeat(5) { requests.add(server.takeRequest()) }
                assertEquals(
                    listOf(
                        "/api/admin/login",
                        "/api/admin/quick-connect/initiate",
                        "/api/admin/quick-connect/poll",
                        "/api/admin/quick-connect/poll",
                        "/api/admin/logout",
                    ),
                    requests.map { it.target },
                )
                for (req in requests) {
                    assertEquals("1", req.headers["X-Companion-Admin"])
                    val bodyText = req.body?.utf8().orEmpty()
                    assertFalse("Jellyfin token must never be sent to Companion", bodyText.contains("device-secret-jf-token"))
                }
                assertEquals("session=admin-sess-1", requests[1].headers["Cookie"])
                assertEquals("session=admin-sess-1", requests[4].headers["Cookie"])
            } finally {
                server.close()
            }
        }

    @Test
    fun `relinkUser returns InvalidAdminPassword on 401`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(
                    MockResponse(code = 401, body = """{"error":"Invalid password","error_code":"invalid_credentials"}"""),
                )
                val qcClient = mockk<QuickConnectClient>()
                val client =
                    CompanionAdminRelinkClient(loopbackClient(), qcClient).apply {
                        allowLoopbackForTests = true
                    }

                val res =
                    client.relinkUser(
                        companionBaseUrl = server.url("/").toString(),
                        adminPassword = "wrong-password",
                        serverId = "srv-1",
                        jellyfinBaseUrl = "http://192.168.1.90:8096",
                        jellyfinAccessToken = "jf-tok",
                    )

                assertTrue("Expected InvalidAdminPassword, got $res", res is CompanionRelinkResult.InvalidAdminPassword)
                assertEquals(1, server.requestCount)
            } finally {
                server.close()
            }
        }

    @Test
    fun `relinkUser falls back to verify-jellyfin-user when Quick Connect is disabled and password was entered`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(
                    MockResponse(
                        code = 200,
                        body = """{"authenticated":true}""",
                        headers = okhttp3.Headers.headersOf("Set-Cookie", "session=admin-sess-2; HttpOnly"),
                    ),
                )
                server.enqueue(
                    MockResponse(
                        code = 409,
                        body = """{"error":"Quick Connect disabled","error_code":"qc_disabled"}""",
                    ),
                )
                server.enqueue(
                    MockResponse(
                        code = 200,
                        body = """{"success":true,"serverId":"srv-1","user":{"id":"u-1","username":"ignacio"}}""",
                    ),
                )
                server.enqueue(MockResponse(code = 200, body = """{"ok":true}"""))

                val qcClient = mockk<QuickConnectClient>()
                val client =
                    CompanionAdminRelinkClient(loopbackClient(), qcClient).apply {
                        allowLoopbackForTests = true
                    }

                val res =
                    client.relinkUser(
                        companionBaseUrl = server.url("/").toString(),
                        adminPassword = "admin",
                        serverId = "srv-1",
                        jellyfinBaseUrl = "http://192.168.1.90:8096",
                        jellyfinAccessToken = "jf-tok",
                        jellyfinUsername = "ignacio",
                        jellyfinPassword = "jf-password",
                    )

                assertEquals(CompanionRelinkResult.Success("ignacio"), res)
                val paths = (0 until 4).map { server.takeRequest().target }
                assertEquals(
                    listOf(
                        "/api/admin/login",
                        "/api/admin/quick-connect/initiate",
                        "/api/admin/verify-jellyfin-user",
                        "/api/admin/logout",
                    ),
                    paths,
                )
            } finally {
                server.close()
            }
        }

    @Test
    fun `relinkUser re-enables quick_connect_broker_enabled when recovering from qc_broker_rejected`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(
                    MockResponse(
                        code = 200,
                        body = """{"authenticated":true}""",
                        headers = okhttp3.Headers.headersOf("Set-Cookie", "session=admin-sess-3; HttpOnly"),
                    ),
                )
                server.enqueue(
                    MockResponse(code = 200, body = """{"pendingId":"pend-2","code":"123456","expiresAt":9999999999}"""),
                )
                val pollBody =
                    """
                    {
                      "status": "authenticated",
                      "serverId": "srv-1",
                      "userId": "aaaa-1111",
                      "username": "ignacio",
                      "configVersion": 5,
                      "config": {
                        "servers": [
                          {
                            "id": "srv-1",
                            "addresses": ["http://192.168.1.90:8096"],
                            "users": [
                              {
                                "id": "aaaa1111",
                                "username": "ignacio",
                                "access_token": "***REDACTED***",
                                "needs_relink": false,
                                "quick_connect_broker_enabled": false
                              }
                            ]
                          }
                        ]
                      }
                    }
                    """.trimIndent()
                server.enqueue(MockResponse(code = 200, body = pollBody))
                server.enqueue(MockResponse(code = 200, body = """{"status":"ok","configVersion":6}"""))
                server.enqueue(MockResponse(code = 200, body = """{"ok":true}"""))

                val qcClient = mockk<QuickConnectClient>()
                coEvery {
                    qcClient.approve("http://192.168.1.90:8096", "jf-tok", "123456")
                } returns ApproveResult.Approved

                val client =
                    CompanionAdminRelinkClient(loopbackClient(), qcClient).apply {
                        allowLoopbackForTests = true
                    }

                val res =
                    client.relinkUser(
                        companionBaseUrl = server.url("/").toString(),
                        adminPassword = "admin",
                        serverId = "srv-1",
                        jellyfinBaseUrl = "http://192.168.1.90:8096",
                        jellyfinAccessToken = "jf-tok",
                        jellyfinUsername = "ignacio",
                        enableQuickConnectBroker = true,
                    )

                assertEquals(CompanionRelinkResult.Success("ignacio"), res)
                val reqLogin = server.takeRequest()
                val reqInit = server.takeRequest()
                val reqPoll = server.takeRequest()
                val reqConfig = server.takeRequest()
                val reqLogout = server.takeRequest()

                assertEquals("/api/admin/config", reqConfig.target)
                assertEquals("\"5\"", reqConfig.headers["If-Match"])
                val sentPatch = JSONObject(reqConfig.body!!.utf8())
                val updatedUser = sentPatch.getJSONArray("servers").getJSONObject(0).getJSONArray("users").getJSONObject(0)
                assertTrue(updatedUser.getBoolean("quick_connect_broker_enabled"))
                assertEquals("***REDACTED***", updatedUser.getString("access_token"))
                assertEquals("/api/admin/logout", reqLogout.target)
            } finally {
                server.close()
            }
        }
}
