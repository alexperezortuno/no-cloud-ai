package dev.nocloud.ai.provider

import dev.nocloud.ai.core.ChatMessage
import dev.nocloud.ai.core.LlmRequest
import dev.nocloud.ai.core.ProviderConfig
import dev.nocloud.ai.core.ProviderKind
import dev.nocloud.ai.core.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs against a real local server.
 *
 * Enable it by pointing the environment variable at a server:
 *
 * ```
 * NO_CLOUD_AI_LIVE_URL=http://127.0.0.1:8069 ./gradlew test --tests '*LiveServerTest'
 * ```
 *
 * The model name is taken from `NO_CLOUD_AI_LIVE_MODEL`, or from the first model the
 * server reports.
 */
@EnabledIfEnvironmentVariable(named = "NO_CLOUD_AI_LIVE_URL", matches = ".+")
class LiveServerTest {

    private val baseUrl: String? = System.getenv("NO_CLOUD_AI_LIVE_URL")

    private fun config(model: String) = ProviderConfig(
        kind = ProviderKind.LLAMA_CPP,
        baseUrl = baseUrl ?: "http://127.0.0.1:1",
        model = model,
        apiKey = null,
        temperature = 0.2,
        maxTokens = 512,
        contextSize = 8192,
    )

    private fun toolSchema(name: String, description: String) = ToolSchema(
        name = name,
        description = description,
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("path", buildJsonObject { put("type", "string"); put("description", "File path") })
                },
            )
            put("required", kotlinx.serialization.json.JsonArray(emptyList()))
        },
    )

    private fun firstModel(): String =
        System.getenv("NO_CLOUD_AI_LIVE_MODEL")?.takeIf { it.isNotBlank() }
            ?: runBlocking { LlamaCppProvider().listModels(config("")).firstOrNull()?.id }
            ?: error("The server reported no models")

    @Test
    fun `lists the models of the live server`() = runBlocking {
        val models = LlamaCppProvider().listModels(config(""))
        assertTrue(models.isNotEmpty(), "the server reported no models")
        println("live models: ${models.map { it.id }}")
    }

    @Test
    fun `streams a chat answer from the live server`() = runBlocking {
        val provider = LlamaCppProvider()
        val deltas = mutableListOf<String>()

        val response = provider.complete(
            config(firstModel()),
            LlmRequest(
                messages = listOf(ChatMessage.user("Reply with exactly the word PONG and nothing else.")),
                systemPrompt = "You are a helpful assistant.",
            ),
        ) { deltas += it }

        println("live chat answer: ${response.text}")
        assertTrue(response.text.isNotBlank(), "the server returned an empty answer")
        assertTrue(response.text.uppercase().contains("PONG"), "unexpected answer: ${response.text}")
        assertTrue(deltas.isNotEmpty())
    }

    @Test
    fun `recovers a tool call that the live server returned as text`() = runBlocking {
        val provider = LlamaCppProvider()
        val streamed = StringBuilder()

        val response = provider.complete(
            config(firstModel()),
            LlmRequest(
                messages = listOf(
                    ChatMessage.user("Read the file src/app.ts for me using the tool."),
                ),
                systemPrompt = "You are a coding agent. Use the provided tools.",
                tools = listOf(toolSchema("read_file", "Read a text file from disk.")),
            ),
        ) { streamed.append(it) }

        println("live tool call answer: ${response.text}")
        println("live tool calls: ${response.toolCalls}")

        assertEquals(1, response.toolCalls.size, "expected exactly one tool call")
        assertEquals("read_file", response.toolCalls.single().name)
        assertTrue(response.toolCalls.single().arguments.contains("path"))

        // The raw markup must never reach the chat UI.
        assertTrue(!streamed.contains("read_file"), "the tool call markup leaked into the stream")
        assertTrue(!streamed.contains("```json"), "the code fence leaked into the stream")
    }

    @Test
    fun `runs a full agent turn against the live server`() = runBlocking {
        val provider = LlamaCppProvider()
        val messages = mutableListOf(
            ChatMessage.user("Read the file src/app.ts for me using the tool."),
        )
        val schema = toolSchema("read_file", "Read a text file from disk.")

        val first = provider.complete(
            config(firstModel()),
            LlmRequest(
                messages = messages,
                systemPrompt = "You are a coding agent. Use the provided tools.",
                tools = listOf(schema),
            ),
        ) { }

        assertEquals(1, first.toolCalls.size, "the model did not call the tool")
        messages += ChatMessage.assistant(first.text, first.toolCalls)

        val toolResult = "export const answer = 42;"
        messages += ChatMessage.toolResult(first.toolCalls.single(), toolResult)

        val second = provider.complete(
            config(firstModel()),
            LlmRequest(
                messages = messages,
                systemPrompt = "You are a coding agent. Use the provided tools.",
                tools = listOf(schema),
            ),
        ) { }

        println("live agent answer: ${second.text}")
        assertTrue(second.text.isNotBlank(), "the model produced no answer after the tool call")
        assertTrue(second.text.contains("42"), "the model did not use the tool result: ${second.text}")
    }

    @Test
    fun `produces a fill in the middle completion from the live server`() = runBlocking {
        val provider = LlamaCppProvider()
        val prompt = dev.nocloud.ai.completion.FimTemplate.QWEN.buildPrompt(
            "function add(a, b) {\n    ",
            "\n}\n",
        )

        val completion = provider.completeFim(config(firstModel()), prompt, listOf("<|fim_middle|>"))

        println("live fim completion: '$completion'")
        assertTrue(completion.contains("return"), "unexpected completion: '$completion'")
    }
}
