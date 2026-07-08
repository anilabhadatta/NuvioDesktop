package com.nuvio.app.features.player.desktop

import com.nuvio.app.core.network.DesktopIPv4FirstDns
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class DesktopPlaybackProxy private constructor(
    val localUrl: String,
    private val server: HttpServer,
    private val executor: java.util.concurrent.ExecutorService,
) : AutoCloseable {
    override fun close() {
        runCatching { server.stop(0) }
        executor.shutdownNow()
    }

    companion object {
        private const val defaultUserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private val client = OkHttpClient.Builder()
            .dns(DesktopIPv4FirstDns())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()

        private val random = SecureRandom()
        private val hopByHopHeaders = setOf(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "trailers",
            "transfer-encoding",
            "upgrade",
        )

        fun startOrNull(
            sourceUrl: String,
            sourceHeaders: Map<String, String>,
        ): DesktopPlaybackProxy? {
            val sourceUri = runCatching { URI(sourceUrl) }.getOrNull() ?: return null
            val scheme = sourceUri.scheme?.lowercase(Locale.US)
            if (scheme != "http" && scheme != "https") return null
            if (sourceUri.rawAuthority.isNullOrBlank()) return null

            val token = randomToken()
            val server = HttpServer.create(
                InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
                0,
            )
            val executor = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "nuvio-desktop-playback-proxy").apply { isDaemon = true }
            }
            server.executor = executor

            val sanitizedHeaders = sourceHeaders.sanitizeForwardHeaders()
            server.createContext("/") { exchange ->
                handleRequest(
                    exchange = exchange,
                    token = token,
                    sourceUri = sourceUri,
                    sourceHeaders = sanitizedHeaders,
                )
            }
            server.start()

            val port = server.address.port
            val localUrl = localUrlFor(port, token, sourceUri)
            return DesktopPlaybackProxy(localUrl, server, executor)
        }

        private fun handleRequest(
            exchange: HttpExchange,
            token: String,
            sourceUri: URI,
            sourceHeaders: Map<String, String>,
        ) {
            try {
                val method = exchange.requestMethod.uppercase(Locale.US)
                if (method != "GET" && method != "HEAD") {
                    exchange.sendText(405, "Method not allowed")
                    return
                }

                val targetUrl = targetUrlFor(exchange.requestURI, token, sourceUri)
                if (targetUrl == null) {
                    exchange.sendText(404, "Not found")
                    return
                }

                val request = buildRequest(method, targetUrl, sourceHeaders, exchange)
                client.newCall(request).execute().use { response ->
                    response.headers.forEach { (name, value) ->
                        if (!name.isHopByHopHeader() && !name.equals("Content-Length", ignoreCase = true)) {
                            exchange.responseHeaders.add(name, value)
                        }
                    }

                    if (method == "HEAD") {
                        exchange.sendResponseHeaders(response.code, -1)
                        return
                    }

                    val responseBody = response.body
                    val responseLength = responseBody?.contentLength()?.takeIf { it >= 0 } ?: 0L
                    exchange.sendResponseHeaders(response.code, responseLength)
                    responseBody?.byteStream()?.use { input ->
                        exchange.responseBody.use { output ->
                            input.copyTo(output)
                        }
                    } ?: exchange.responseBody.close()
                }
            } catch (error: IOException) {
                if (!exchange.responseHeaders.containsKey("Content-Type")) {
                    exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
                }
                runCatching { exchange.sendText(502, error.message ?: "Playback proxy failed") }
            } catch (error: Throwable) {
                runCatching { exchange.sendText(500, error.message ?: "Playback proxy failed") }
            } finally {
                exchange.close()
            }
        }

        private fun buildRequest(
            method: String,
            targetUrl: String,
            sourceHeaders: Map<String, String>,
            exchange: HttpExchange,
        ): Request {
            val builder = Request.Builder().url(targetUrl)
            sourceHeaders.forEach { (name, value) -> builder.header(name, value) }

            exchange.requestHeaders.forEach { (name, values) ->
                val value = values.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@forEach
                if (!name.isForwardablePlayerHeader()) return@forEach
                if (name.equals("Range", ignoreCase = true)) {
                    builder.header(name, value)
                } else if (!sourceHeaders.containsHeader(name)) {
                    builder.header(name, value)
                }
            }

            if (!sourceHeaders.containsHeader("User-Agent")) {
                builder.header("User-Agent", defaultUserAgent)
            }
            return builder.method(method, null).build()
        }

        private fun targetUrlFor(requestUri: URI, token: String, sourceUri: URI): String? {
            val tokenPrefix = "/$token"
            val requestPath = requestUri.rawPath ?: return null
            if (requestPath != tokenPrefix && !requestPath.startsWith("$tokenPrefix/")) return null

            val targetPath = requestPath.removePrefix(tokenPrefix).ifBlank { "/" }
            val query = requestUri.rawQuery?.let { "?$it" }.orEmpty()
            return "${sourceUri.scheme}://${sourceUri.rawAuthority}$targetPath$query"
        }

        private fun localUrlFor(port: Int, token: String, sourceUri: URI): String {
            val rawPath = sourceUri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
            val query = sourceUri.rawQuery?.let { "?$it" }.orEmpty()
            return "http://127.0.0.1:$port/$token$rawPath$query"
        }

        private fun Map<String, String>.sanitizeForwardHeaders(): Map<String, String> =
            mapNotNull { (rawName, rawValue) ->
                val name = rawName.trim()
                val value = rawValue.trim()
                when {
                    name.isBlank() || value.isBlank() -> null
                    name.equals("Host", ignoreCase = true) -> null
                    name.equals("Range", ignoreCase = true) -> null
                    name.equals("Accept-Encoding", ignoreCase = true) -> null
                    name.isHopByHopHeader() -> null
                    else -> name to value
                }
            }.toMap()

        private fun Map<String, String>.containsHeader(name: String): Boolean =
            keys.any { it.equals(name, ignoreCase = true) }

        private fun String.isForwardablePlayerHeader(): Boolean =
            equals("Range", ignoreCase = true) ||
                equals("Accept", ignoreCase = true) ||
                equals("Accept-Language", ignoreCase = true) ||
                equals("Icy-MetaData", ignoreCase = true) ||
                equals("User-Agent", ignoreCase = true)

        private fun String.isHopByHopHeader(): Boolean =
            lowercase(Locale.US) in hopByHopHeaders

        private fun HttpExchange.sendText(status: Int, message: String) {
            val bytes = message.toByteArray(Charsets.UTF_8)
            responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }

        private fun randomToken(): String {
            val bytes = ByteArray(12)
            random.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
