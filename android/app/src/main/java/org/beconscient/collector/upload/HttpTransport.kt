package org.beconscient.collector.upload

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class HttpResult(val code: Int, val body: String?)

/** POSTs a JSON body. Throws [IOException] when nothing usable came back. */
fun interface HttpTransport {
    @Throws(IOException::class)
    fun postJson(url: String, body: String): HttpResult
}

/**
 * Plain HttpURLConnection. Redirects are not followed: a captive portal answers
 * with one, and a redirected envelope would only fail later.
 */
class UrlConnectionTransport(
    private val allowCleartext: Boolean,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : HttpTransport {

    override fun postJson(url: String, body: String): HttpResult {
        if (!allowCleartext && !url.startsWith("https://")) throw IOException("refusing a non-https server URL")
        val bytes = body.toByteArray(Charsets.UTF_8)
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            // One request every few minutes: a pooled connection only lingers.
            conn.setRequestProperty("Connection", "close")
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            return HttpResult(code, stream?.use { readBounded(it) })
        } finally {
            conn.disconnect()
        }
    }

    private fun readBounded(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_RESPONSE_BYTES) throw IOException("response too large")
        }
        return out.toString(Charsets.UTF_8.name())
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    }
}
