package com.ratv.app.data

import android.util.Log
import com.ratv.app.i18n.I18n
import com.ratv.app.i18n.tr
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * What the phone page sent: a playlist link (with an optional guide link), or a subscription's server,
 * user name and password, cleaned the way the sign-in screen cleans them. A plain class, so the
 * password never shows up in a printed or logged copy.
 */
class PhoneEntry(
    val xtream: Boolean,
    val link: String = "",
    val epg: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = ""
)

/**
 * A small page on the TV itself, for sending a playlist link, or a subscription's server, user name
 * and password, from a phone instead of typing them with the remote. The sign-in screen shows its
 * address as a QR code; the phone (on the same home network) opens it, the details are typed there
 * and they arrive on the TV.
 *
 * It runs only while the sign-in screen is open, answers only at an address with a random part
 * (another device on the network cannot guess it), and never writes the link or the password to the log.
 */
class PhoneLink(private val onEntry: (PhoneEntry) -> Unit) {
    @Volatile
    private var socket: ServerSocket? = null

    private val token = (1..6).map { TOKEN_CHARS.random() }.joinToString("")

    /** Starts listening; returns the address of the phone page, or null without a home network. */
    fun start(): String? {
        val ip = localAddress() ?: return null
        var server: ServerSocket? = null
        for (port in PORTS) {
            server = runCatching { ServerSocket(port, 8) }.getOrNull()
            if (server != null) break
        }
        val s = server ?: return null
        socket = s
        val url = "http://$ip:${s.localPort}/$token"
        Log.i(TAG, "phone page at $url")
        thread(name = "RATVPhone", isDaemon = true) { serve(s) }
        return url
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun serve(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try {
                s.accept()
            } catch (e: IOException) {
                break
            }
            runCatching { c.use { handle(it) } }
        }
    }

    private fun handle(c: Socket) {
        c.soTimeout = 15_000
        val input = BufferedInputStream(c.getInputStream())
        val request = readLine(input) ?: return
        val parts = request.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?').trim('/')
        var length = 0
        while (true) {
            val h = readLine(input) ?: return
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0 && h.substring(0, i).trim().equals("Content-Length", ignoreCase = true)) {
                length = h.substring(i + 1).trim().toIntOrNull() ?: 0
            }
        }
        val out = c.getOutputStream()
        if (path != token) {
            respond(out, 404, page(note(tr("هذا الرمز قديم. امسح الرمز اللي على شاشة التلفزيون من جديد."), error = true)))
            return
        }
        if (method == "GET" || method == "HEAD") {
            respond(out, 200, form(null, "", ""))
            return
        }
        if (method != "POST" || length < 0 || length > MAX_BODY) {
            respond(out, 400, form(tr("ما وصل شي. جرّب مرة ثانية."), "", ""))
            return
        }
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        val fields = parseForm(String(body, 0, read, Charsets.UTF_8))
        val typed = fields["link"].orEmpty()
        val typedEpg = fields["epg"].orEmpty()
        val typedServer = fields["server"].orEmpty()
        val typedUser = fields["username"].orEmpty()
        val typedPass = fields["password"].orEmpty()
        // Subscription details when any of the three is filled in; otherwise a playlist link.
        val xtream = listOf(typedServer, typedUser, typedPass).any { it.isNotBlank() }
        // Cleaned exactly as the sign-in screen cleans them: a pasted get.php link gives its own user name and password.
        val sub = XtreamClient.fromUserInput("", typedServer, typedUser, typedPass)
        val entry = if (xtream) {
            PhoneEntry(xtream = true, server = sub.server, username = sub.username, password = sub.password)
        } else {
            PhoneEntry(xtream = false, link = Links.clean(typed), epg = Links.clean(typedEpg))
        }
        val error = if (xtream) {
            when {
                entry.server.isEmpty() || entry.username.isEmpty() || entry.password.isEmpty() ->
                    tr("أكمل رابط الخادم واسم المستخدم وكلمة المرور.")
                !Links.isWebLink(entry.server) -> tr("رابط الخادم غير صحيح. اكتبه كاملاً مثل: http://server.com:8080")
                else -> null
            }
        } else {
            when {
                entry.link.isEmpty() -> tr("الصق رابط القائمة، أو اكتب بيانات الاشتراك أولاً.")
                !Links.isWebLink(entry.link) -> tr("رابط القائمة غير صحيح. الصقه كاملاً كما وصلك، ويبدأ بـ http://‎")
                entry.epg.isNotEmpty() && !Links.isWebLink(entry.epg) -> tr("رابط دليل البرامج غير صحيح. صحّحه أو اترك خانته فارغة.")
                else -> null
            }
        }
        if (error != null) {
            Log.i(TAG, "details from the phone were refused: $error")
            respond(out, 200, form(error, typed, typedEpg, typedServer, typedUser, typedPass))
            return
        }
        Log.i(TAG, if (xtream) "subscription details came from the phone" else "a link came from the phone")
        onEntry(entry)
        val done = if (xtream) tr("وصلت بيانات الاشتراك للتلفزيون ✓") else tr("وصل الرابط للتلفزيون ✓")
        respond(
            out, 200,
            page(
                "<p class=\"ok\">" + esc(done) + "</p>" +
                    "<p>" + esc(tr("شوف التلفزيون: يضيف القائمة الحين. إذا ظهر خطأ على الشاشة، صحّح ما فات وأرسله من جديد.")) + "</p>" +
                    "<p><a href=\"/$token\">" + esc(tr("إرسال رابط ثاني")) + "</a></p>"
            )
        )
    }

    private fun form(
        error: String?,
        link: String,
        epg: String,
        server: String = "",
        username: String = "",
        password: String = ""
    ): String = page(
        "<h1>" + esc(tr("أرسل قائمتك للتلفزيون")) + "</h1>" +
            "<p>" + esc(tr("الصق رابط القائمة (M3U أو M3U8) كما وصلك من المزوّد، أو اكتب بيانات الاشتراك: رابط الخادم واسم المستخدم وكلمة المرور، واضغط «أرسل للتلفزيون».")) + "</p>" +
            (if (error != null) note(error, error = true) else "") +
            "<form method=\"post\" action=\"/$token\">" +
            "<label for=\"link\">" + esc(tr("رابط القائمة")) + "</label>" +
            "<textarea id=\"link\" name=\"link\" autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\" " +
            "placeholder=\"http://server.example/playlist.m3u8\">" + esc(link) + "</textarea>" +
            "<label for=\"epg\">" + esc(tr("رابط دليل البرامج EPG (اختياري)")) + "</label>" +
            "<input id=\"epg\" name=\"epg\" type=\"url\" autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\" " +
            "placeholder=\"http://server.example/epg.xml\" value=\"" + esc(epg) + "\">" +
            "<p class=\"or\">" + esc(tr("أو بيانات اشتراكك")) + "</p>" +
            "<label for=\"server\">" + esc(tr("رابط الخادم")) + "</label>" +
            "<input id=\"server\" name=\"server\" inputmode=\"url\" autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\" " +
            "placeholder=\"http://server.example:8080\" value=\"" + esc(server) + "\">" +
            "<label for=\"username\">" + esc(tr("اسم المستخدم")) + "</label>" +
            "<input id=\"username\" name=\"username\" autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\" " +
            "value=\"" + esc(username) + "\">" +
            "<label for=\"password\">" + esc(tr("كلمة المرور")) + "</label>" +
            "<input id=\"password\" name=\"password\" type=\"password\" autocapitalize=\"off\" autocorrect=\"off\" " +
            "spellcheck=\"false\" autocomplete=\"off\" value=\"" + esc(password) + "\">" +
            "<button type=\"submit\">" + esc(tr("أرسل للتلفزيون")) + "</button>" +
            "</form>"
    )

    private fun note(text: String, error: Boolean) = "<p class=\"" + (if (error) "err" else "ok") + "\">" + esc(text) + "</p>"

    private fun page(content: String): String {
        val dir = if (I18n.rtl) "rtl" else "ltr"
        return "<!doctype html><html dir=\"$dir\"><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>RA TV</title><style>" +
            "body{margin:0;background:#141210;color:#f3eee8;font:18px/1.6 system-ui,-apple-system,sans-serif}" +
            "main{max-width:520px;margin:0 auto;padding:28px 16px}" +
            "h1{font-size:24px;margin:0 0 8px}p{color:#c9c0b6;margin:0 0 16px}" +
            "label{display:block;font-weight:700;margin:16px 0 6px}" +
            "textarea,input{width:100%;box-sizing:border-box;background:#211e1b;color:#f3eee8;border:1px solid #4a423a;" +
            "border-radius:12px;padding:14px;font:16px/1.4 ui-monospace,monospace;direction:ltr;text-align:left}" +
            "textarea{min-height:120px;word-break:break-all}" +
            ".or{color:#f3eee8;font-weight:800;margin:26px 0 0;padding-top:18px;border-top:1px solid #4a423a}" +
            "button{width:100%;margin-top:22px;padding:17px;border:0;border-radius:14px;background:#e8730c;color:#1a1000;" +
            "font:700 20px system-ui,-apple-system,sans-serif}" +
            ".err{background:#3d1714;color:#ffb8ad;border-radius:12px;padding:12px 14px}" +
            ".ok{font-size:22px;font-weight:700;color:#86dca0}a{color:#ff9a3d}" +
            "</style></head><body><main><p style=\"color:#e8730c;font-weight:800;margin-bottom:18px\">RA TV</p>" +
            content + "</main></body></html>"
    }

    private fun respond(out: OutputStream, code: Int, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "OK"
            404 -> "Not Found"
            else -> "Bad Request"
        }
        val head = "HTTP/1.1 $code $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    companion object {
        private const val TAG = "RATVPhone"
        private const val MAX_BODY = 16 * 1024
        private const val TOKEN_CHARS = "abcdefghjkmnpqrstuvwxyz23456789"
        private val PORTS = 8686..8696

        /** The TV's address on the home network (Wi-Fi or cable), or null. */
        fun localAddress(): String? {
            val found = runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                    .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                    .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { ni.name to it } }
                    .filter { (_, a) -> !a.isLoopbackAddress && a.isSiteLocalAddress }
            }.getOrNull().orEmpty()
            // Wi-Fi and cable first (a phone can reach those), then anything else on a home range.
            val best = found.firstOrNull { (n, _) -> n.startsWith("wlan") || n.startsWith("eth") } ?: found.firstOrNull()
            return best?.second?.hostAddress
        }

        private fun readLine(input: InputStream): String? {
            val b = ByteArrayOutputStream()
            while (true) {
                val c = input.read()
                if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
                if (c == '\n'.code) break
                if (b.size() > 8192) return null
                b.write(c)
            }
            return b.toString("UTF-8").trimEnd('\r')
        }

        fun parseForm(body: String): Map<String, String> = body.split('&').mapNotNull { pair ->
            if (pair.isEmpty()) return@mapNotNull null
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            runCatching { URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8") }.getOrNull()
        }.toMap()

        fun esc(s: String): String = buildString {
            for (ch in s) when (ch) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }
}
