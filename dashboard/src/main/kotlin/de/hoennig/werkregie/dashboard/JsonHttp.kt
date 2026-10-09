package de.hoennig.werkregie.dashboard

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class HttpStatusException(url: String, status: Int) : RuntimeException("HTTP $status: $url")

/** Read-only JSON GETs; headers such as tokens never appear in an exception message. */
class JsonHttp(private val mapper: ObjectMapper) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun get(url: String, headers: Map<String, String> = emptyMap()): JsonNode {
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw HttpStatusException(url, response.statusCode())
        return mapper.readTree(response.body())
    }
}
