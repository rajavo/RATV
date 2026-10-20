package com.ratv.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class HttpException(val code: Int) : IOException("HTTP $code")

object Http {
    const val USER_AGENT = "RATV/1.0 (Android)"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
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
                body.byteStream().use { input -> input.copyTo(out, 64 * 1024) }
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
