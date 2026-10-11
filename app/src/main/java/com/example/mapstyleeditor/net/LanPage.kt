package com.example.mapstyleeditor.net

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * A one-page web server on the local network, for a phone to talk to this device (a car head unit,
 * where typing is miserable): the sign-in screen's token page, and "say where to" on the phone.
 *
 * The address carries a random [secret], so only someone who can see the QR code on the screen can
 * reach the page. Every request goes to [handle] with the method and the posted form; it returns
 * the page's body, which is wrapped in MinMap's black page style.
 */
class LanPage(
    private val preferredPort: Int,
    val secret: String = randomSecret(),
    private val handle: (method: String, form: Map<String, String>) -> String,
) {
    private var server: ServerSocket? = null

    val port: Int get() = server?.localPort ?: 0

    /** The address to put in a QR code, or null if this device isn't on a network a phone can reach. */
    fun url(): String? {
        val ip = localAddress() ?: return null
        if (port == 0) return null
        return "http://$ip:$port/$secret"
    }

    fun start() {
        if (server != null) return
        val socket = runCatching { ServerSocket(preferredPort) }.getOrElse { ServerSocket(0) }
        server = socket
        thread(name = "minmap-lan-page", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: SocketException) {
                    break // Closed by stop().
                }
                thread(isDaemon = true) { client.use { runCatching { serve(it) }.onFailure { e -> Log.w(TAG, "Request failed", e) } } }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun serve(client: Socket) {
        client.soTimeout = 15_000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = generateSequence { readLine(input)?.takeIf { it.isNotEmpty() } }
            .mapNotNull { line -> line.split(":", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim().lowercase() to it[1].trim() } }
            .toMap()
        val (method, path) = requestLine.split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val out = client.getOutputStream()
        fun respond(status: String, title: String, body: String) {
            val bytes = page(title, body).toByteArray(Charsets.UTF_8)
            out.write(
                ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                    "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
            )
            out.write(bytes)
            out.flush()
        }

        // Wrong address, or someone guessing at it: nothing here.
        if (path.substringBefore('?') != "/$secret") {
            respond("404 Not Found", "Not found", "<p>This link has expired. Scan the code on the screen again.</p>")
            return
        }
        when (method) {
            "GET" -> respond("200 OK", "MinMap", handle("GET", emptyMap()))
            "POST" -> {
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                if (length !in 1..MAX_BODY) {
                    respond("400 Bad Request", "MinMap", handle("GET", emptyMap()))
                    return
                }
                val form = readBody(input, length).split('&').mapNotNull {
                    it.split('=', limit = 2).takeIf { kv -> kv.size == 2 }
                        ?.let { kv -> URLDecoder.decode(kv[0], "UTF-8") to URLDecoder.decode(kv[1], "UTF-8") }
                }.toMap()
                respond("200 OK", "MinMap", handle("POST", form))
            }
            else -> respond("405 Method Not Allowed", "Not allowed", "")
        }
    }

    private fun page(title: String, body: String) = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>$title</title>
        <style>
          body{margin:0;background:#000;color:#fff;font:16px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
          main{max-width:440px;margin:0 auto;padding:32px 20px}
          h1{font-size:26px;font-weight:600;margin:0 0 6px}
          a{color:#fff}
          ol{padding-left:20px;margin:24px 0}
          li{margin-bottom:12px}
          input{box-sizing:border-box;width:100%;padding:14px;border-radius:14px;border:1px solid #3a3a40;background:#111;color:#fff;font-size:17px}
          input.mono{font:14px ui-monospace,monospace}
          button{width:100%;margin-top:16px;padding:14px;border:0;border-radius:26px;background:#fff;color:#000;font-size:16px;font-weight:600}
          button.quiet{background:none;color:#ff6b6b;border:1px solid #3a3a40}
          .muted{color:#9a9aa2}.small{font-size:12px;margin-top:20px}.error{color:#ff6b6b;font-size:14px}.ok{color:#7ee2a8;font-size:15px}
        </style></head><body><main>$body</main></body></html>
    """.trimIndent()

    companion object {
        private const val TAG = "MinMapLanPage"
        private const val MAX_BODY = 4096
        private val CELLULAR = listOf("rmnet", "ccmni", "pdp", "clat", "v4-", "tun", "ppp", "dummy")

        fun randomSecret(): String {
            val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
            val random = SecureRandom()
            return (1..12).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        }

        fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        /** One header line (ASCII, ending in CRLF or LF), or null at the end of the stream. */
        private fun readLine(input: InputStream): String? {
            val line = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (line.size() == 0) null else line.toString("US-ASCII")
                if (b == '\n'.code) return line.toString("US-ASCII").trimEnd('\r')
                if (line.size() > 8192) return null
                line.write(b)
            }
        }

        private fun readBody(input: InputStream, length: Int): String {
            val bytes = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(bytes, read, length - read)
                if (n < 0) break
                read += n
            }
            return String(bytes, 0, read, Charsets.UTF_8)
        }

        /**
         * This device's address on the local network: Wi-Fi first (including a phone's hotspot),
         * then Ethernet, then anything else private. Null when there's no such network.
         */
        fun localAddress(): String? {
            val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                // Mobile data and VPNs: a carrier's private address isn't reachable from a phone.
                .filterNot { nic -> CELLULAR.any { nic.name.startsWith(it) } }
            val candidates = interfaces.flatMap { nic ->
                nic.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    .filter { it.isSiteLocalAddress }
                    .map { nic.name to it.hostAddress }
            }
            fun rank(name: String) = when {
                name.startsWith("wlan") -> 0
                name.startsWith("ap") || name.startsWith("swlan") -> 1
                name.startsWith("eth") -> 2
                else -> 3
            }
            return candidates.minByOrNull { rank(it.first) }?.second
        }
    }
}
