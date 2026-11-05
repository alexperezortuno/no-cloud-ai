package dev.nocloud.ai.agent

import dev.nocloud.ai.core.ChatMessage
import dev.nocloud.ai.core.LlmRequest
import dev.nocloud.ai.core.LlmResponse
import dev.nocloud.ai.core.ModelInfo
import dev.nocloud.ai.core.ProviderConfig
import dev.nocloud.ai.core.ProviderKind
import dev.nocloud.ai.core.ToolCall
import dev.nocloud.ai.core.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentRunnerTest {

    /** Replays a scripted list of responses and records the requests it received. */
    private class ScriptedProvider(private val responses: List<LlmResponse>) : dev.nocloud.ai.provider.LlmProvider {
        override val kind: ProviderKind = ProviderKind.OLLAMA
        val requests = mutableListOf<LlmRequest>()

        override suspend fun listModels(config: ProviderConfig): List<ModelInfo> = emptyList()

        override suspend fun completeFim(
            config: ProviderConfig,
            prompt: String,
            stopTokens: List<String>,
            maxTokens: Int,
        ): String = ""

        override suspend fun complete(
            config: ProviderConfig,
            request: LlmRequest,
            onDelta: suspend (String) -> Unit,
        ): LlmResponse {
            requests += request
            val response = responses[minOf(requests.size - 1, responses.lastIndex)]
            response.text.takeIf { it.isNotEmpty() }?.let { onDelta(it) }
            return response
        }
    }

    private class RecordingTool(
        override val name: String,
        private val result: String,
    ) : AgentTool {
        var callCount = 0
        var lastArguments: String? = null

        override val description: String = "test tool"
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        }

        override suspend fun execute(arguments: String): String {
            callCount++
            lastArguments = arguments
            return result
        }
    }

    private fun config() = ProviderConfig(
        kind = ProviderKind.OLLAMA,
        baseUrl = "http://localhost:11434",
        model = "test-model",
        apiKey = null,
        temperature = 0.0,
        maxTokens = 256,
        contextSize = 2048,
    )

    @Test
    fun `tool call is executed and its result is sent back to the model`() = runBlocking {
        val call = ToolCall(id = "call_1", name = "read_file", arguments = """{"path":"a.ts"}""")
        val provider = ScriptedProvider(
            listOf(
                LlmResponse(text = "", toolCalls = listOf(call)),
                LlmResponse(text = "done"),
            ),
        )
        val tool = RecordingTool("read_file", "file contents")
        val sink = RecordingSink()

        AgentRunner(provider, config(), maxIterations = 5).run(
            userMessage = "read a.ts",
            tools = listOf(tool),
            sink = sink,
        )

        assertEquals(1, tool.callCount)
        assertEquals("""{"path":"a.ts"}""", tool.lastArguments)
        assertEquals(listOf("read_file"), sink.toolNames)
        assertEquals(listOf("file contents"), sink.toolResults)
        assertEquals("done", sink.finishedWith)

        // The second request must carry the tool result back to the model.
        val second = provider.requests[1]
        val toolMessage = second.messages.last()
        assertEquals(ChatMessage.Role.TOOL, toolMessage.role)
        assertEquals("file contents", toolMessage.content)
        assertEquals("call_1", toolMessage.toolCallId)
    }

    @Test
    fun `an unknown tool produces an error result instead of throwing`() = runBlocking {
        val call = ToolCall(id = "call_1", name = "nope", arguments = "{}")
        val provider = ScriptedProvider(
            listOf(
                LlmResponse(text = "", toolCalls = listOf(call)),
                LlmResponse(text = "recovered"),
            ),
        )
        val sink = RecordingSink()

        AgentRunner(provider, config(), maxIterations = 5).run(
            userMessage = "do it",
            tools = listOf(RecordingTool("read_file", "x")),
            sink = sink,
        )

        assertTrue(sink.toolResults.single().startsWith("Error: unknown tool 'nope'"))
        assertEquals("recovered", sink.finishedWith)
    }

    @Test
    fun `the loop stops after the configured number of iterations`() = runBlocking {
        val call = ToolCall(id = "c", name = "read_file", arguments = "{}")
        val provider = ScriptedProvider(listOf(LlmResponse(text = "", toolCalls = listOf(call))))
        val tool = RecordingTool("read_file", "x")
        val sink = RecordingSink()

        AgentRunner(provider, config(), maxIterations = 3).run(
            userMessage = "loop forever",
            tools = listOf(tool),
            sink = sink,
        )

        assertEquals(3, tool.callCount)
        assertTrue(sink.finishedWith!!.contains("3 tool iterations"))
    }

    @Test
    fun `a plain answer finishes the run without calling tools`() = runBlocking {
        val provider = ScriptedProvider(listOf(LlmResponse(text = "hello")))
        val sink = RecordingSink()

        AgentRunner(provider, config(), maxIterations = 5).run(
            userMessage = "hi",
            tools = emptyList(),
            sink = sink,
        )

        assertEquals("hello", sink.finishedWith)
        assertTrue(sink.toolNames.isEmpty())
    }

    @Test
    fun `tool schemas are passed to the provider`() = runBlocking {
        val provider = ScriptedProvider(listOf(LlmResponse(text = "ok")))
        val tool = RecordingTool("read_file", "x")

        AgentRunner(provider, config(), maxIterations = 2).run(
            userMessage = "hi",
            tools = listOf(tool),
            sink = RecordingSink(),
        )

        val schemas: List<ToolSchema> = provider.requests.first().tools
        assertEquals(listOf("read_file"), schemas.map { it.name })
    }

    @Test
    fun `the produced messages keep the tool call and its result`() = runBlocking {
        val call = ToolCall(id = "call_1", name = "read_file", arguments = """{"path":"a.ts"}""")
        val provider = ScriptedProvider(
            listOf(
                LlmResponse(text = "", toolCalls = listOf(call)),
                LlmResponse(text = "done"),
            ),
        )

        val produced = AgentRunner(provider, config(), maxIterations = 5).run(
            userMessage = "read a.ts",
            tools = listOf(RecordingTool("read_file", "file contents")),
            sink = RecordingSink(),
        )

        // user, assistant (with the tool call), tool result, final assistant answer.
        assertEquals(4, produced.size)
        assertEquals(ChatMessage.Role.USER, produced[0].role)
        assertEquals(ChatMessage.Role.ASSISTANT, produced[1].role)
        assertEquals(listOf(call), produced[1].toolCalls)
        assertEquals(ChatMessage.Role.TOOL, produced[2].role)
        assertEquals("file contents", produced[2].content)
        assertEquals("call_1", produced[2].toolCallId)
        assertEquals(ChatMessage.Role.ASSISTANT, produced[3].role)
        assertEquals("done", produced[3].content)
    }

    @Test
    fun `a second turn starts from the messages of the first one`() = runBlocking {
        val call = ToolCall(id = "call_1", name = "read_file", arguments = """{"path":"a.ts"}""")
        val provider = ScriptedProvider(
            listOf(
                LlmResponse(text = "", toolCalls = listOf(call)),
                LlmResponse(text = "the file says hello"),
                LlmResponse(text = "still here"),
            ),
        )
        val tool = RecordingTool("read_file", "file contents")

        val first = AgentRunner(provider, config(), maxIterations = 5).run(
            userMessage = "read a.ts",
            tools = listOf(tool),
            sink = RecordingSink(),
        )

        // The second turn must see everything the first turn produced.
        AgentRunner(provider, config(), maxIterations = 5).run(
            userMessage = "what did it say?",
            history = first,
            tools = listOf(tool),
            sink = RecordingSink(),
        )

        val secondRequest = provider.requests[provider.requests.size - 1]
        val roles = secondRequest.messages.map { it.role }
        assertEquals(
            listOf(
                ChatMessage.Role.USER,
                ChatMessage.Role.ASSISTANT,
                ChatMessage.Role.TOOL,
                ChatMessage.Role.ASSISTANT,
                ChatMessage.Role.USER,
            ),
            roles,
        )
        assertTrue(secondRequest.messages.any { it.role == ChatMessage.Role.TOOL && it.content == "file contents" })
    }

    private class RecordingSink : AgentSink {
        val toolNames = mutableListOf<String>()
        val toolResults = mutableListOf<String>()
        var finishedWith: String? = null

        override fun onText(delta: String) = Unit

        override fun onToolCall(call: ToolCall, result: String) {
            toolNames += call.name
            toolResults += result
        }

        override fun onFinished(finalText: String) {
            finishedWith = finalText
        }
    }
}
