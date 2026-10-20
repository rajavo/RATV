package com.ratv.app.data

import java.io.BufferedReader

/** Streaming parser for M3U / M3U8 (extended) playlists. */
object M3uParser {

    class Result(
        val epgUrl: String?,
        val liveCategories: List<Category>,
        val channels: List<Channel>,
        val vodCategories: List<Category>,
        val movies: List<Movie>,
        val seriesCategories: List<Category>,
        val series: List<SeriesItem>,
        val episodes: Map<String, Map<Int, List<Episode>>>
    )

    private val ATTR = Regex("""([A-Za-z0-9_\-]+)="([^"]*)"""")
    private val SXE = Regex("""^(.*?)[\s._|\-]*[Ss](\d{1,2})[\s._\-]*[Ee](\d{1,3})""")
    private val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "flv")

    private class PendingSeries(val item: SeriesItem, val seasons: HashMap<Int, MutableList<Episode>>)

    fun parse(reader: BufferedReader): Result {
        var epgUrl: String? = null
        var attrs: Map<String, String> = emptyMap()
        var title: String? = null
        var group: String? = null
        var duration = 0

        val liveCats = LinkedHashMap<String, Category>()
        val vodCats = LinkedHashMap<String, Category>()
        val serCats = LinkedHashMap<String, Category>()
        val channels = ArrayList<Channel>()
        val movies = ArrayList<Movie>()
        val series = LinkedHashMap<String, PendingSeries>()
        var index = 0

        while (true) {
            val raw = reader.readLine() ?: break
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U") -> {
                    val a = parseAttrs(line)
                    epgUrl = (a["url-tvg"] ?: a["x-tvg-url"])
                        ?.split(',')
                        ?.map { it.trim() }
                        ?.firstOrNull { it.startsWith("http") }
                }
                line.startsWith("#EXTINF") -> {
                    val (head, name) = splitExtinf(line)
                    attrs = parseAttrs(head)
                    title = name.ifBlank { attrs["tvg-name"] ?: "" }
                    group = attrs["group-title"]
                    duration = head.substringAfter(':').substringBefore(' ').trim().toDoubleOrNull()?.toInt() ?: 0
                }
                line.startsWith("#EXTGRP:") -> group = line.substringAfter(':').trim()
                line.startsWith("#") -> Unit
                else -> {
                    val url = line
                    val name = title?.takeIf { it.isNotBlank() } ?: url.substringAfterLast('/').substringBefore('?')
                    val logo = attrs["tvg-logo"]?.takeIf { it.startsWith("http") }
                    val groupName = group?.takeIf { it.isNotBlank() } ?: "عام"
                    val id = stableId(url)
                    index++
                    when (classify(url)) {
                        Kind.LIVE -> {
                            liveCats.getOrPut(groupName) { Category(groupName, groupName) }
                            val epgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() } ?: attrs["tvg-name"]?.takeIf { it.isNotBlank() }
                            channels.add(
                                Channel(
                                    id = id,
                                    num = attrs["tvg-chno"]?.toIntOrNull() ?: (channels.size + 1),
                                    name = name,
                                    logo = logo,
                                    categoryId = groupName,
                                    epgId = epgId,
                                    url = url
                                )
                            )
                        }
                        Kind.MOVIE -> {
                            vodCats.getOrPut(groupName) { Category(groupName, groupName) }
                            movies.add(
                                Movie(
                                    id = id,
                                    name = name,
                                    poster = logo,
                                    categoryId = groupName,
                                    rating = null,
                                    added = index.toLong(),
                                    url = url
                                )
                            )
                        }
                        Kind.EPISODE -> {
                            serCats.getOrPut(groupName) { Category(groupName, groupName) }
                            val m = SXE.find(name)
                            val seriesName = m?.groupValues?.get(1)?.trim(' ', '-', '|', '.', '_')?.takeIf { it.isNotBlank() } ?: name
                            val season = m?.groupValues?.get(2)?.toIntOrNull() ?: 1
                            val seriesId = stableId(groupName + "|" + seriesName)
                            val entry = series.getOrPut(seriesId) {
                                PendingSeries(
                                    SeriesItem(
                                        id = seriesId,
                                        name = seriesName,
                                        cover = logo,
                                        categoryId = groupName,
                                        rating = null,
                                        plot = null,
                                        year = yearOf(seriesName),
                                        added = index.toLong()
                                    ),
                                    HashMap()
                                )
                            }
                            val list = entry.seasons.getOrPut(season) { ArrayList() }
                            val epNum = m?.groupValues?.get(3)?.toIntOrNull() ?: (list.size + 1)
                            list.add(
                                Episode(
                                    id = id,
                                    season = season,
                                    num = epNum,
                                    title = name,
                                    url = url,
                                    durationSecs = if (duration > 0) duration else 0,
                                    plot = null,
                                    image = logo
                                )
                            )
                        }
                    }
                    attrs = emptyMap()
                    title = null
                    group = null
                    duration = 0
                }
            }
        }

        val seriesItems = series.values.map { it.item }
        val episodes = series.mapValues { (_, s) -> s.seasons.mapValues { (_, l) -> l.sortedBy { it.num } } }
        return Result(
            epgUrl = epgUrl,
            liveCategories = liveCats.values.toList(),
            channels = channels,
            vodCategories = vodCats.values.toList(),
            movies = movies,
            seriesCategories = serCats.values.toList(),
            series = seriesItems,
            episodes = episodes
        )
    }

    private enum class Kind { LIVE, MOVIE, EPISODE }

    private fun classify(url: String): Kind {
        val lower = url.lowercase()
        if (lower.contains("/series/")) return Kind.EPISODE
        if (lower.contains("/movie/")) return Kind.MOVIE
        val ext = lower.substringBefore('?').substringAfterLast('.', "")
        if (ext in VIDEO_EXT && !lower.contains("/live/")) return Kind.MOVIE
        return Kind.LIVE
    }

    private fun parseAttrs(text: String): Map<String, String> {
        val map = HashMap<String, String>()
        ATTR.findAll(text).forEach { m -> map[m.groupValues[1].lowercase()] = m.groupValues[2].trim() }
        return map
    }

    /** Splits `#EXTINF:-1 key="v, w",Name` into the attribute part and the display name. */
    private fun splitExtinf(line: String): Pair<String, String> {
        var inQuotes = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') {
                inQuotes = !inQuotes
            } else if (c == ',' && !inQuotes) {
                return line.substring(0, i) to line.substring(i + 1).trim()
            }
        }
        return line to ""
    }

    fun stableId(s: String): String = s.hashCode().toUInt().toString(16)
}
