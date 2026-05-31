package dev.jdtech.jellyfin.plugins.engine

import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import dev.jdtech.jellyfin.plugins.bridge.RealDOMParserBridge
import dev.jdtech.jellyfin.plugins.bridge.RealHttpBridge
import dev.jdtech.jellyfin.plugins.bridge.RealUtilitiesBridge
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

interface ResultBridge {
    fun setResult(value: String)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PluginEngineTest {

    private lateinit var engine: PluginEngine
    private val okHttpClient = mockk<OkHttpClient>()
    private var testResult: String? = null

    private val resultBridge = object : ResultBridge {
        override fun setResult(value: String) {
            testResult = value
        }
    }

    @Before
    fun setup() {
        val httpBridge = RealHttpBridge(okHttpClient)
        val domParserBridge = RealDOMParserBridge()
        val utilitiesBridge = RealUtilitiesBridge()
        engine = PluginEngine(httpBridge, domParserBridge, utilitiesBridge)
    }

    @Test
    fun `test http GET from JS`() = runBlocking {
        val mockCall = mockk<Call>()
        val mockResponse = mockk<Response>()
        
        every { okHttpClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } returns mockResponse
        every { mockResponse.isSuccessful } returns true
        every { mockResponse.code } returns 200
        every { mockResponse.body } returns "{\"status\":\"ok\"}".toResponseBody()
        every { mockResponse.headers } returns okhttp3.Headers.Builder().build()
        every { mockResponse.close() } returns Unit

        val runtime = engine.createRuntime()
        runtime.quickJs.define("resultBridge") {
            function("setResult") { args -> resultBridge.setResult(args[0].toString()) }
        }
        runtime.evaluate("""
            const response = http.GET("https://api.test.com", {}, false);
            resultBridge.setResult(response.body);
        """)
        
        assertEquals("{\"status\":\"ok\"}", testResult)
        runtime.close()
    }

    @Test
    fun `test DOMParser from JS`() = runBlocking {
        val runtime = engine.createRuntime()
        runtime.quickJs.define("resultBridge") {
            function("setResult") { args -> resultBridge.setResult(args[0].toString()) }
        }
        runtime.evaluate("""
            const html = "<html><body><div id='test'>Hello World</div></body></html>";
            const parser = new DOMParser();
            const doc = parser.parseFromString(html, "text/html");
            const div = doc.querySelector("#test");
            resultBridge.setResult(div.textContent);
        """)
        
        assertEquals("Hello World", testResult)
        runtime.close()
    }

    @Test
    fun `test querySelectorAll from JS`() = runBlocking {
        val runtime = engine.createRuntime()
        runtime.quickJs.define("resultBridge") {
            function("setResult") { args -> resultBridge.setResult(args[0].toString()) }
        }
        runtime.evaluate("""
            const html = "<ul><li>Item 1</li><li>Item 2</li></ul>";
            const parser = new DOMParser();
            const doc = parser.parseFromString(html, "text/html");
            const items = doc.querySelectorAll("li");
            resultBridge.setResult(items.length.toString() + ": " + items[0].textContent);
        """)
        
        assertEquals("2: Item 1", testResult)
        runtime.close()
    }
}
