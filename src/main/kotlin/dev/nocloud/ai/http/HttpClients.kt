package dev.nocloud.ai.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.apache5.Apache5
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readUTF8Line
import java.util.concurrent.TimeUnit

/**
 * Creates the Ktor client used for every local request.
 *
 * A fresh client is created per request batch so that a closed connection can never
 * stall a later request; the previous client is closed by the caller.
 */
object HttpClients {

    fun create(debug: Boolean = false): HttpClient = HttpClient(Apache5) {
        expectSuccess = false

        install(HttpTimeout) {
            connectTimeoutMillis = TimeUnit.SECONDS.toMillis(10)
            requestTimeoutMillis = TimeUnit.MINUTES.toMillis(10)
            socketTimeoutMillis = TimeUnit.MINUTES.toMillis(10)
        }

        if (debug) {
            install(Logging) {
                level = LogLevel.INFO
                logger = object : Logger {
                    override fun log(message: String) {
                        println("[no-cloud-ai] $message")
                    }
                }
                sanitizeHeader { header -> header.equals("Authorization", ignoreCase = true) }
            }
        }
    }
}

/**
 * Reads the response body line by line.
 *
 * Both backends stream: llama.cpp sends SSE (`data: {...}` lines), Ollama sends NDJSON
 * (one JSON object per line). Splitting on lines works for both, so the caller decides
 * how to interpret each line.
 */
suspend fun HttpResponse.forEachLine(action: suspend (String) -> Unit) {
    val channel = bodyAsChannel()
    try {
        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break
            if (line.isNotBlank()) {
                action(line)
            }
        }
    } finally {
        channel.cancel(null)
    }
}

/** Reads the whole body as text, for non-streaming endpoints. */
suspend fun HttpResponse.readText(): String = bodyAsText()

/** Human readable error text for a non-2xx response. */
suspend fun HttpResponse.errorText(): String {
    val body = runCatching { bodyAsText() }.getOrDefault("")
    return "HTTP ${status.value} ${status.description}" + if (body.isBlank()) "" else ": $body"
}
