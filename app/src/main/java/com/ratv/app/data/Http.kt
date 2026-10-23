package com.ratv.app.data

import android.os.SystemClock
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class HttpException(val code: Int) : IOException("HTTP $code")

/**
 * Name lookups: when one fails (the first thing to fail on a shaky line), the address found last
 * time is used, so a reconnect does not wait for the name server. IPv4 addresses come first: on a
 * home line whose IPv6 is broken, each connection would otherwise wait out its timeout on the IPv6
 * address before trying IPv4 (the picture would stop for those seconds at every reconnect).
 */
object StickyDns : Dns {
    private val known = ConcurrentHashMap<String, List<InetAddress>>()

    override fun lookup(hostname: String): List<InetAddress> = try {
        Dns.SYSTEM.lookup(hostname)
            .sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
            .also { if (it.isNotEmpty()) known[hostname] = it }
    } catch (e: UnknownHostException) {
        known[hostname] ?: throw e
    }
}

object Http {
    const val USER_AGENT = "RATV/1.0 (Android)"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .dns(StickyDns)
            .addInterceptor { chain ->
                val request = chain.request()
                val withAgent = if (request.header("User-Agent") == null) {
                    request.newBuilder().header("User-Agent", USER_AGENT).build()
                } else {
                    request
                }
                chain.proceed(withAgent)
            }
            .build()
    }

    /** Downloads [url] into [target], replacing it only when the download succeeds. */
    fun download(url: String, target: File) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code)
            val body = response.body ?: throw IOException("Empty response")
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".part")
            tmp.outputStream().use { out ->
                body.byteStream().use { input ->
                    // While a channel or film plays, lists and the guide come in slowly, so they
                    // never take the line from the picture.
                    val buf = ByteArray(64 * 1024)
                    var since = SystemClock.elapsedRealtime()
                    var bytes = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (!Playback.active) {
                            since = SystemClock.elapsedRealtime()
                            bytes = 0L
                            continue
                        }
                        bytes += n
                        val ahead = bytes * 1000L / Playback.BACKGROUND_BYTES_PER_SECOND - (SystemClock.elapsedRealtime() - since)
                        if (ahead > 0) Thread.sleep(ahead)
                    }
                }
            }
            if (tmp.length() == 0L) {
                tmp.delete()
                throw IOException("Empty response")
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    fun getString(url: String): String {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code)
            return response.body?.string() ?: ""
        }
    }

    /** Opens a cached file, transparently un-gzipping it when needed. */
    fun openMaybeGzip(file: File): InputStream {
        val input = BufferedInputStream(FileInputStream(file), 64 * 1024)
        input.mark(4)
        val b1 = input.read()
        val b2 = input.read()
        input.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(input, 64 * 1024) else input
    }
}
