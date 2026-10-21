package com.ratv.app.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Cleans links and log-in details typed with a TV remote or pasted from a message:
 * digits and punctuation from an Arabic keyboard, invisible direction marks, stray spaces,
 * and a missing or mistyped "http://".
 */
object Links {

    /** "http//", "http:/", "htp://", "HTTPS://"… at the start of a link. */
    private val SCHEME_TYPO = Regex("""^h+t+p+(s?)(?::+[/\\]*|[/\\]+)""", RegexOption.IGNORE_CASE)
    private val OTHER_SCHEME = Regex("""^[a-z][a-z0-9+.\-]*://""", RegexOption.IGNORE_CASE)
    private const val WRAPPERS = "\"'<>\u00AB\u00BB\u201C\u201D\u2018\u2019`"

    /** Latin digits and punctuation in place of what an Arabic keyboard types; invisible marks removed. */
    fun latinize(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) {
            when (c) {
                in '\u0660'..'\u0669' -> sb.append('0' + (c - '\u0660'))
                in '\u06F0'..'\u06F9' -> sb.append('0' + (c - '\u06F0'))
                '\u061F' -> sb.append('?')
                '\u060C' -> sb.append(',')
                '\u061B' -> sb.append(';')
                '\u066B', '\u06D4' -> sb.append('.')
                '\u066A' -> sb.append('%')
                '\u200B', '\u200C', '\u200D', '\u200E', '\u200F', '\u2060', '\uFEFF' -> Unit
                in '\u202A'..'\u202E', in '\u2066'..'\u2069' -> Unit
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** A user name or password: Latin digits, no invisible marks, no spaces around it. */
    fun credential(input: String): String = latinize(input).trim()

    /**
     * A web link as the user meant it: "server.com:٨٠٨٠/get.php؟username=١٢٣" becomes
     * "http://server.com:8080/get.php?username=123". An empty field stays empty, and a file
     * picked on the device (content://) is left as it is.
     */
    fun clean(input: String): String {
        val s = latinize(input).filterNot { it.isWhitespace() }.trim { it in WRAPPERS }
        if (s.isEmpty()) return s
        if (s.startsWith("content://", ignoreCase = true) || s.startsWith("file://", ignoreCase = true)) return s
        val typo = SCHEME_TYPO.find(s)
        return when {
            typo != null -> "http" + typo.groupValues[1].lowercase() + "://" + s.substring(typo.range.last + 1)
            OTHER_SCHEME.containsMatchIn(s) -> s
            else -> "http://" + s.trimStart('/', '\\')
        }
    }

    /** True for a usable http:// or https:// address. */
    fun isWebLink(link: String): Boolean = link.toHttpUrlOrNull()?.host?.isNotEmpty() == true
}
