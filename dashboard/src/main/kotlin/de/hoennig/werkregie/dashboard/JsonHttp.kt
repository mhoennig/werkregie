package de.hoennig.werkregie.dashboard

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class HttpStatusException(url: String, val status: Int) : RuntimeException("HTTP $status: $url")

/** JSON GETs, and POSTs for a login; headers such as tokens never appear in an exception message. */
class JsonHttp(private val mapper: ObjectMapper) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun get(url: String, headers: Map<String, String> = emptyMap(), timeout: Duration = Duration.ofSeconds(10)): JsonNode {
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(timeout)
            .header("Accept", "application/json")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .GET()
            .build()
        return send(url, request)
    }

    /** A JSON POST, e.g. a login; the body may carry a password, so it never appears in an exception message. */
    fun post(url: String, body: Any, headers: Map<String, String> = emptyMap(), timeout: Duration = Duration.ofSeconds(10)): JsonNode {
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(timeout)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build()
        return send(url, request)
    }

    private fun send(url: String, request: HttpRequest): JsonNode {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw HttpStatusException(url, response.statusCode())
        return mapper.readTree(response.body())
    }
}
