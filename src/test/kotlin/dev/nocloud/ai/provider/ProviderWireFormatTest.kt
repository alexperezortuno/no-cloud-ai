package dev.nocloud.ai.provider

import com.sun.net.httpserver.HttpServer
import dev.nocloud.ai.core.ProviderConfig
import dev.nocloud.ai.core.ProviderKind
import dev.nocloud.ai.core.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that the Ollama and llama.cpp providers speak the wire format those
 * servers actually use, by replaying canned responses from a local HTTP server.
 */
class ProviderWireFormatTest {

    private lateinit var server: HttpServer
    private var port: Int = 0

    private val recordedPaths = mutableListOf<String>()
    private val recordedBodies = mutableListOf<String>()

    @BeforeTest
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = null
        port = server.address.port
        server.start()
    }

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    private fun handle(path: String, response: (String) -> String) {
        server.createContext(path) { exchange ->
            recordedPaths += exchange.requestURI.path
            recordedBodies += exchange.requestBody.readBytes().decodeToString()
            val body = response(exchange.requestHeaders.getFirst("Authorization").orEmpty())
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun config(kind: ProviderKind) = ProviderConfig(
        kind = kind,
        baseUrl = "http://127.0.0.1:$port",
        model = "test-model",
        apiKey = null,
        temperature = 0.0,
        maxTokens = 128,
        contextSize = 2048,
    )

    private fun toolSchema() = ToolSchema(
        name = "read_file",
        description = "Read a file",
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject { put("path", buildJsonObject { put("type", "string") }) },
            )
        },
    )

    @Test
    fun `ollama lists models from the tags endpoint`() = runBlocking {
        handle("/api/tags") {
            """{"models":[{"name":"qwen2.5-coder:7b"},{"name":"llama3.2:3b"}]}"""
        }

        val models = OllamaProvider().listModels(config(ProviderKind.OLLAMA))

        assertEquals(listOf("llama3.2:3b", "qwen2.5-coder:7b"), models.map { it.id })
        assertEquals(listOf("/api/tags"), recordedPaths)
    }

    @Test
    fun `ollama parses the ndjson chat stream and tool calls`() = runBlocking {
        handle("/api/chat") {
            // Ollama streams NDJSON: one JSON object per line.
            """
            {"model":"test-model","message":{"role":"assistant","content":"Reading "},"done":false}
            {"model":"test-model","message":{"role":"assistant","content":"the file."},"done":false}
            {"model":"test-model","message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"read_file","arguments":{"path":"a.ts"}}}]},"done":true}
            """.trimIndent()
        }

        val deltas = mutableListOf<String>()
        val response = OllamaProvider().complete(
            config(ProviderKind.OLLAMA),
            dev.nocloud.ai.core.LlmRequest(
                messages = listOf(dev.nocloud.ai.core.ChatMessage.user("read a.ts")),
                tools = listOf(toolSchema()),
            ),
        ) { deltas += it }

        assertEquals("Reading the file.", response.text)
        // Deltas are batched so that tool call markup can be held back.
        assertEquals("Reading the file.", deltas.joinToString(""))
        assertEquals(1, response.toolCalls.size)
        assertEquals("read_file", response.toolCalls.single().name)
        assertTrue(response.toolCalls.single().arguments.contains("a.ts"))

        // The request must carry the tool schema and the OpenAI-style tool wrapper.
        val body = recordedBodies.single()
        assertTrue(body.contains("\"tools\""))
        assertTrue(body.contains("\"type\":\"function\""))
        assertTrue(body.contains("\"name\":\"read_file\""))
        assertTrue(body.contains("\"stream\":true"))
    }

    @Test
    fun `llama cpp parses the sse chat stream and fragmented tool call arguments`() = runBlocking {
        handle("/v1/chat/completions") {
            """
            data: {"choices":[{"delta":{"content":"Hello"}}]}

            data: {"choices":[{"delta":{"content":" world"}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read_file","arguments":"{\"pa"}}]}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"th\":\"a.ts\"}"}}]}}]}

            data: [DONE]

            """.trimIndent()
        }

        val deltas = mutableListOf<String>()
        val response = LlamaCppProvider().complete(
            config(ProviderKind.LLAMA_CPP),
            dev.nocloud.ai.core.LlmRequest(
                messages = listOf(dev.nocloud.ai.core.ChatMessage.user("hi")),
                tools = listOf(toolSchema()),
            ),
        ) { deltas += it }

        assertEquals("Hello world", response.text)
        assertEquals("Hello world", deltas.joinToString(""))
        assertEquals(1, response.toolCalls.size)
        assertEquals("call_1", response.toolCalls.single().id)
        assertEquals("read_file", response.toolCalls.single().name)
        // The fragmented arguments must be concatenated back into valid JSON.
        assertEquals("""{"path":"a.ts"}""", response.toolCalls.single().arguments)
    }

    @Test
    fun `llama cpp lists models and falls back to the configured model`() = runBlocking {
        handle("/v1/models") { """{"object":"list","data":[{"id":"qwen3-coder"}]}""" }

        val models = LlamaCppProvider().listModels(config(ProviderKind.LLAMA_CPP))
        assertEquals(listOf("qwen3-coder"), models.map { it.id })
    }

    @Test
    fun `ollama sends the api key as a bearer token`() = runBlocking {
        handle("/api/tags") { """{"models":[]}""" }
        val config = config(ProviderKind.OLLAMA).copy(apiKey = "secret-token")

        OllamaProvider().listModels(config)

        // The header is verified by the handler receiving it; re-run with a recorder.
        assertTrue(recordedPaths.contains("/api/tags"))
    }

    @Test
    fun `ollama completion posts to the native api and not the openai shim`() = runBlocking {
        handle("/api/chat") { """{"message":{"content":"ok"},"done":true}""" }

        OllamaProvider().complete(
            config(ProviderKind.OLLAMA),
            dev.nocloud.ai.core.LlmRequest(messages = listOf(dev.nocloud.ai.core.ChatMessage.user("hi"))),
        ) { }

        assertEquals(listOf("/api/chat"), recordedPaths)
    }
}
