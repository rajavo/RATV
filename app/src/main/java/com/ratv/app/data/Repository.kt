package com.ratv.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Single source of truth for the playlist, its content, the EPG and user data. */
class Repository(private val context: Context, val prefs: Prefs) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheDir = File(context.filesDir, "ratv-cache").apply { mkdirs() }

    private val _playlist = MutableStateFlow(prefs.loadPlaylist())
    val playlist: StateFlow<Playlist?> = _playlist.asStateFlow()

    private val _state = MutableStateFlow<LoadState>(LoadState.Idle)
    val state: StateFlow<LoadState> = _state.asStateFlow()

    private val _account = MutableStateFlow<AccountInfo?>(null)
    val account: StateFlow<AccountInfo?> = _account.asStateFlow()

    private val _liveCats = MutableStateFlow<List<Category>>(emptyList())
    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    private val _vodCats = MutableStateFlow<List<Category>>(emptyList())
    private val _movies = MutableStateFlow<List<Movie>>(emptyList())
    private val _seriesCats = MutableStateFlow<List<Category>>(emptyList())
    private val _series = MutableStateFlow<List<SeriesItem>>(emptyList())

    private val _libraryLoading = MutableStateFlow(false)
    val libraryLoading: StateFlow<Boolean> = _libraryLoading.asStateFlow()

    private val _epg = MutableStateFlow<Map<String, List<Programme>>>(emptyMap())
    val epg: StateFlow<Map<String, List<Programme>>> = _epg.asStateFlow()

    private val _epgLoading = MutableStateFlow(false)
    val epgLoading: StateFlow<Boolean> = _epgLoading.asStateFlow()

    private val _favorites = MutableStateFlow(prefs.favorites)
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _watched = MutableStateFlow(prefs.watched)
    val watched: StateFlow<Set<String>> = _watched.asStateFlow()

    private val _history = MutableStateFlow(prefs.loadHistory())

    /** Every saved subscription ("users"); [playlist] is the one in use. */
    private val _playlists = MutableStateFlow(prefs.playlists)
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _hideAdult = MutableStateFlow(prefs.hideAdult)
    val hideAdult: StateFlow<Boolean> = _hideAdult.asStateFlow()

    private val _lastUpdated = MutableStateFlow(0L)
    val lastUpdated: StateFlow<Long> = _lastUpdated.asStateFlow()

    private val _recentChannels = MutableStateFlow(prefs.recentChannels)
    val recentChannels: StateFlow<List<String>> = _recentChannels.asStateFlow()

    @Volatile private var channelIndex: Map<String, Channel> = emptyMap()
    @Volatile private var siblingIndex: Map<String, List<Channel>> = emptyMap()
    @Volatile private var movieIndex: Map<String, Movie> = emptyMap()
    @Volatile private var seriesIndex: Map<String, SeriesItem> = emptyMap()
    @Volatile private var movieKeyIndex: Map<String, List<Movie>> = emptyMap()
    @Volatile private var seriesKeyIndex: Map<String, List<SeriesItem>> = emptyMap()
    @Volatile private var m3uEpisodes: Map<String, Map<Int, List<Episode>>> = emptyMap()
    @Volatile private var m3uEpgUrl: String? = null
    private val movieInfoCache = ConcurrentHashMap<String, MovieInfo>()
    private val seriesInfoCache = ConcurrentHashMap<String, SeriesInfo>()

    private var loadJob: Job? = null

    // ------------------------------------------------ filtered (parental) views

    val liveCategories: StateFlow<List<Category>> = combine(_liveCats, _hideAdult) { cats, hide ->
        if (hide) cats.filterNot { isAdultName(it.name) } else cats
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val channels: StateFlow<List<Channel>> = combine(_channels, _liveCats, _hideAdult) { list, cats, hide ->
        filterAdult(list, cats, hide, { it.categoryId }, { it.name }, { it.adult })
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val vodCategories: StateFlow<List<Category>> = combine(_vodCats, _hideAdult) { cats, hide ->
        if (hide) cats.filterNot { isAdultName(it.name) } else cats
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val movies: StateFlow<List<Movie>> = combine(_movies, _vodCats, _hideAdult) { list, cats, hide ->
        filterAdult(list, cats, hide, { it.categoryId }, { it.name }, { it.adult })
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val seriesCategories: StateFlow<List<Category>> = combine(_seriesCats, _hideAdult) { cats, hide ->
        if (hide) cats.filterNot { isAdultName(it.name) } else cats
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val series: StateFlow<List<SeriesItem>> = combine(_series, _seriesCats, _hideAdult) { list, cats, hide ->
        filterAdult(list, cats, hide, { it.categoryId }, { it.name }, { it.adult })
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** "Continue watching"; with the parental filter on, adult films and episodes leave it too. */
    val history: StateFlow<List<WatchEntry>> = combine(_history, _hideAdult, movies, series) { list, hide, m, s ->
        if (!hide) return@combine list
        val films = m.mapTo(HashSet()) { it.id }
        val shows = s.mapTo(HashSet()) { it.id }
        list.filter { e ->
            !isAdultName(e.title) && when (e.kind) {
                "movie" -> films.isEmpty() || e.itemId in films
                "episode" -> shows.isEmpty() || e.seriesId == null || e.seriesId in shows
                else -> true
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, _history.value)

    private fun <T> filterAdult(
        list: List<T>,
        cats: List<Category>,
        hide: Boolean,
        categoryOf: (T) -> String,
        nameOf: (T) -> String,
        flagOf: (T) -> Boolean
    ): List<T> {
        if (!hide) return list
        val hidden = cats.filter { isAdultName(it.name) }.map { it.id }.toHashSet()
        return list.filter { !flagOf(it) && categoryOf(it) !in hidden && !isAdultName(nameOf(it)) }
    }

    // ------------------------------------------------------------- loading

    /** Loads the saved playlist unless it is already loaded or loading. */
    fun start() {
        val p = _playlist.value ?: return
        val s = _state.value
        if (s is LoadState.Ready || s is LoadState.Loading) return
        loadJob?.cancel()
        _state.value = LoadState.Loading("جارٍ تحميل القنوات…")
        loadJob = scope.launch { runLoad(p, force = false) }
    }

    /** Re-downloads everything from the provider. */
    fun refresh() {
        val p = _playlist.value ?: return
        loadJob?.cancel()
        _state.value = LoadState.Loading("جارٍ تحديث المحتوى…")
        loadJob = scope.launch { runLoad(p, force = true) }
    }

    fun refreshEpg() {
        val p = _playlist.value ?: return
        scope.launch(Playback.gentle) { loadEpg(p, force = true) }
    }

    /** Validates, saves and loads a new playlist. Returns an error message or null on success. */
    suspend fun addPlaylist(p: Playlist): String? = withContext(Dispatchers.IO) {
        loadJob?.cancel()
        val previousId = _playlist.value?.id
        try {
            validate(p)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext friendlyError(e)
        }
        if (previousId != p.id) clearData()
        // Added next to the other users, never in place of them; its own favourites and history come back.
        prefs.savePlaylist(p)
        reloadUserData()
        _playlist.value = p
        _state.value = LoadState.Loading("جارٍ تحميل القنوات…")
        loadJob = scope.launch { runLoad(p, force = false) }
        val result = state.first { it is LoadState.Ready || it is LoadState.Error }
        if (result is LoadState.Error) result.message else null
    }

    /** Leaves the current user without deleting any saved user. */
    fun logout() {
        loadJob?.cancel()
        clearData()
        prefs.savePlaylist(null)
        reloadUserData()
        _playlist.value = null
        _state.value = LoadState.Idle
    }

    /** Opens another saved user without signing in again; its favourites and history come back. */
    fun switchTo(p: Playlist) {
        if (_playlist.value?.id == p.id && _state.value !is LoadState.Error) return
        loadJob?.cancel()
        clearData()
        prefs.savePlaylist(p)
        reloadUserData()
        _playlist.value = p
        _state.value = LoadState.Loading("جارٍ تحميل القنوات…")
        loadJob = scope.launch { runLoad(p, force = false) }
    }

    /** Deletes one saved user with its favourites, history and saved lists. Returns the user now in use, if any. */
    fun removePlaylist(id: String): Playlist? {
        val wasCurrent = _playlist.value?.id == id
        prefs.removePlaylist(id)
        cacheDir.listFiles()?.filter { it.name.startsWith(id + "_") }?.forEach { it.delete() }
        _playlists.value = prefs.playlists
        if (!wasCurrent) return _playlist.value
        val next = prefs.playlists.firstOrNull()
        if (next == null) logout() else switchTo(next)
        return next
    }

    /** Clears "continue watching", watched marks and recently watched channels of the current user. */
    fun clearWatchHistory() {
        prefs.clearWatchHistory()
        _history.value = emptyList()
        _watched.value = emptySet()
        _recentChannels.value = emptyList()
    }

    private fun reloadUserData() {
        _favorites.value = prefs.favorites
        _watched.value = prefs.watched
        _history.value = prefs.loadHistory()
        _recentChannels.value = prefs.recentChannels
        _playlists.value = prefs.playlists
    }

    private fun validate(p: Playlist) {
        when (p.type) {
            PlaylistType.XTREAM -> {
                if (p.server.isBlank() || p.username.isBlank() || p.password.isBlank()) {
                    throw IOException("أكمل رابط الخادم واسم المستخدم وكلمة المرور.")
                }
                _account.value = XtreamClient(p).authenticate()
            }
            PlaylistType.M3U -> {
                if (p.m3uUrl.isBlank()) throw IOException("أدخل رابط قائمة M3U أو اختر ملفاً.")
                val file = if (p.m3uUrl.startsWith("content://")) copyContent(p, force = true)
                else fetch(p, "playlist.m3u", p.m3uUrl, force = true)
                val head = Http.openMaybeGzip(file).bufferedReader(Charsets.UTF_8).use { r ->
                    val buf = CharArray(8192)
                    val n = r.read(buf)
                    if (n > 0) String(buf, 0, n) else ""
                }
                if (!head.contains("#EXTINF") && !head.contains("#EXTM3U")) {
                    throw IOException(
                        "هذا الرابط لا يحتوي على قائمة قنوات M3U. إذا كان عندك اسم مستخدم وكلمة مرور فادخل من تبويب Xtream Codes."
                    )
                }
            }
        }
    }

    private suspend fun runLoad(p: Playlist, force: Boolean) {
        try {
            when (p.type) {
                PlaylistType.XTREAM -> loadXtreamCore(p, force)
                PlaylistType.M3U -> loadM3u(p, force)
            }
            _state.value = LoadState.Ready
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = LoadState.Error(friendlyError(e))
            return
        }
        // Films, series and the guide load on low-priority threads, so a channel already playing comes first.
        withContext(Playback.gentle) {
            if (p.type == PlaylistType.XTREAM) loadXtreamLibrary(p, force)
            loadEpg(p, force)
        }
    }

    private fun loadXtreamCore(p: Playlist, force: Boolean) {
        val x = XtreamClient(p)
        try {
            _account.value = x.authenticate()
        } catch (e: AuthException) {
            throw e
        } catch (e: Exception) {
            if (!cacheFile(p, "live.json").exists()) throw e
        }
        val cats = x.parseCategories(fetch(p, "live_cats.json", x.apiUrl("get_live_categories"), force))
        val live = x.parseLive(fetch(p, "live.json", x.apiUrl("get_live_streams"), force))
        _liveCats.value = cats.distinctBy { it.id }
        setChannels(live)
        _lastUpdated.value = cacheFile(p, "live.json").lastModified()
    }

    private suspend fun loadXtreamLibrary(p: Playlist, force: Boolean) {
        _libraryLoading.value = true
        try {
            coroutineScope {
                val x = XtreamClient(p)
                val vod = async {
                    try {
                        val cats = x.parseCategories(fetch(p, "vod_cats.json", x.apiUrl("get_vod_categories"), force))
                        val list = x.parseMovies(fetch(p, "vod.json", x.apiUrl("get_vod_streams"), force))
                        cats to list
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
                val ser = async {
                    try {
                        val cats = x.parseCategories(fetch(p, "series_cats.json", x.apiUrl("get_series_categories"), force))
                        val list = x.parseSeries(fetch(p, "series.json", x.apiUrl("get_series"), force))
                        cats to list
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
                val v = vod.await()
                if (v != null) {
                    _vodCats.value = v.first.distinctBy { it.id }
                    setMovies(v.second)
                }
                val s = ser.await()
                if (s != null) {
                    _seriesCats.value = s.first.distinctBy { it.id }
                    setSeries(s.second)
                }
            }
        } finally {
            _libraryLoading.value = false
        }
    }

    private fun loadM3u(p: Playlist, force: Boolean) {
        val file = if (p.m3uUrl.startsWith("content://")) copyContent(p, force)
        else fetch(p, "playlist.m3u", p.m3uUrl, force)
        val result = Http.openMaybeGzip(file).bufferedReader(Charsets.UTF_8).use { M3uParser.parse(it) }
        if (result.channels.isEmpty() && result.movies.isEmpty() && result.series.isEmpty()) {
            throw IOException("القائمة فارغة أو بصيغة غير مدعومة.")
        }
        m3uEpgUrl = result.epgUrl
        m3uEpisodes = result.episodes
        _account.value = null
        _liveCats.value = result.liveCategories
        setChannels(result.channels)
        _vodCats.value = result.vodCategories
        setMovies(result.movies)
        _seriesCats.value = result.seriesCategories
        setSeries(result.series)
        _lastUpdated.value = file.lastModified()
    }

    private fun loadEpg(p: Playlist, force: Boolean) {
        val url = when (p.type) {
            PlaylistType.XTREAM -> XtreamClient(p).xmltvUrl()
            PlaylistType.M3U -> p.epgUrl.ifBlank { m3uEpgUrl.orEmpty() }
        }
        if (url.isBlank()) return
        val wanted = HashSet<String>()
        channelIndex.values.forEach { ch ->
            ch.epgId?.lowercase()?.trim()?.takeIf { it.isNotEmpty() }?.let { wanted.add(it) }
        }
        if (wanted.isEmpty()) return
        _epgLoading.value = true
        try {
            val ttl = if (prefs.epgAuto) EPG_TTL else Long.MAX_VALUE / 2
            val file = fetch(p, "epg.xml", url, force, ttl)
            val now = System.currentTimeMillis()
            val map = Http.openMaybeGzip(file).use { EpgParser.parse(it, wanted, now - 3 * HOUR, now + 2 * DAY) }
            if (_playlist.value?.id == p.id) _epg.value = map
        } catch (e: Exception) {
            // The guide is optional; keep whatever we had.
        } finally {
            _epgLoading.value = false
        }
    }

    private fun fetch(p: Playlist, name: String, url: String, force: Boolean, ttl: Long = LIST_TTL): File {
        val file = cacheFile(p, name)
        val fresh = file.exists() && file.length() > 0 && System.currentTimeMillis() - file.lastModified() < ttl
        if (!force && fresh) return file
        try {
            Http.download(url, file)
        } catch (e: Exception) {
            if (file.exists() && file.length() > 0) return file
            throw e
        }
        return file
    }

    private fun copyContent(p: Playlist, force: Boolean): File {
        val file = cacheFile(p, "playlist.m3u")
        if (!force && file.exists() && file.length() > 0) return file
        val input = context.contentResolver.openInputStream(Uri.parse(p.m3uUrl))
            ?: throw IOException("تعذّر فتح الملف المختار.")
        input.use { inp -> file.outputStream().use { out -> inp.copyTo(out) } }
        return file
    }

    private fun cacheFile(p: Playlist, name: String) = File(cacheDir, "${p.id}_$name")

    private fun clearCaches() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private fun clearData() {
        _account.value = null
        _liveCats.value = emptyList()
        setChannels(emptyList())
        _vodCats.value = emptyList()
        setMovies(emptyList())
        _seriesCats.value = emptyList()
        setSeries(emptyList())
        m3uEpisodes = emptyMap()
        m3uEpgUrl = null
        _epg.value = emptyMap()
        movieInfoCache.clear()
        seriesInfoCache.clear()
    }

    private fun setChannels(list: List<Channel>) {
        val unique = list.distinctBy { it.id }
        channelIndex = unique.associateBy { it.id }
        siblingIndex = unique.groupBy { channelKey(it.name) }.filterValues { it.size > 1 }
        _channels.value = unique
    }

    private fun setMovies(list: List<Movie>) {
        val unique = list.distinctBy { it.id }
        movieIndex = unique.associateBy { it.id }
        movieKeyIndex = unique.groupBy { vodKey(it.name) }.filterValues { it.size > 1 }
        _movies.value = unique
    }

    private fun setSeries(list: List<SeriesItem>) {
        val unique = list.distinctBy { it.id }
        seriesIndex = unique.associateBy { it.id }
        seriesKeyIndex = unique.groupBy { vodKey(it.name) }.filterValues { it.size > 1 }
        _series.value = unique
    }

    // ------------------------------------------------------------- lookups

    fun channel(id: String): Channel? = channelIndex[id]

    /** Other versions of the same channel (another quality, language or commentator), best quality first. */
    fun siblings(ch: Channel): List<Channel> {
        val group = siblingIndex[channelKey(ch.name)] ?: return emptyList()
        val hidden = _hideAdult.value
        return group
            .filter { it.id != ch.id && (!hidden || !isAdultName(it.name)) }
            .sortedWith(compareByDescending<Channel> { qualityRank(it.quality) }.thenBy { it.num })
    }
    fun movie(id: String): Movie? = movieIndex[id]

    fun movieQuality(m: Movie): String? = vodQuality(m.name, vodCategoryName(m.categoryId))

    fun seriesQuality(s: SeriesItem): String? = vodQuality(s.name, seriesCategoryName(s.categoryId))

    /** The same film elsewhere in the subscription (another quality or section), best quality first. */
    fun movieVersions(m: Movie): List<Movie> {
        val group = movieKeyIndex[vodKey(m.name)] ?: return emptyList()
        val hide = _hideAdult.value
        return group
            .filter { it.id != m.id && (m.year == null || it.year == null || it.year == m.year) && (!hide || !isAdultName(it.name)) }
            .sortedByDescending { qualityRank(movieQuality(it)) }
    }

    /** The same series elsewhere in the subscription, best quality first. */
    fun seriesVersions(s: SeriesItem): List<SeriesItem> {
        val group = seriesKeyIndex[vodKey(s.name)] ?: return emptyList()
        val hide = _hideAdult.value
        return group
            .filter { it.id != s.id && (s.year == null || it.year == null || it.year == s.year) && (!hide || !isAdultName(it.name)) }
            .sortedByDescending { qualityRank(seriesQuality(it)) }
    }

    /** Channels whose current programme is the same game as the one on [ch]. */
    fun sameEventChannels(ch: Channel, now: Long): List<Channel> {
        val epg = _epg.value
        val title = epg.forChannel(ch).current(now)?.title ?: return emptyList()
        val target = eventTokens(title)
        if (target.size < 2) return emptyList()
        val cache = HashMap<String, Boolean>()
        return channels.value.filter { other ->
            if (other.id == ch.id) return@filter false
            val p = epg.forChannel(other).current(now) ?: return@filter false
            cache.getOrPut(p.title) { sameEvent(target, eventTokens(p.title)) }
        }
    }

    /** Sports channels (by name or section), for picking another commentator. */
    fun sportsChannels(exceptId: String?): List<Channel> {
        val sportCats = _liveCats.value.filter { looksSports(it.name) }.mapTo(HashSet()) { it.id }
        return channels.value.filter { it.id != exceptId && (it.categoryId in sportCats || looksSports(it.name)) }
    }

    /** Games in the programme guide between [from] and [to], with every channel showing them. */
    suspend fun matchSchedule(from: Long, to: Long): List<MatchEvent> = withContext(Dispatchers.Default) {
        buildMatches(sportsChannels(null), _epg.value, from, to)
    }

    fun isSportsChannel(ch: Channel): Boolean =
        looksSports(ch.name) || looksSports(liveCategoryName(ch.categoryId).orEmpty())

    /** Remembers a channel for "watched recently" and for starting where the viewer left off. */
    fun noteChannelPlayed(id: String) {
        prefs.lastChannelId = id
        val list = (listOf(id) + _recentChannels.value.filter { it != id }).take(40)
        _recentChannels.value = list
        prefs.recentChannels = list
    }
    fun seriesItem(id: String): SeriesItem? = seriesIndex[id]
    fun cachedSeriesInfo(id: String): SeriesInfo? = seriesInfoCache[id]

    fun liveCategoryName(id: String): String? = _liveCats.value.firstOrNull { it.id == id }?.name
    fun vodCategoryName(id: String): String? = _vodCats.value.firstOrNull { it.id == id }?.name
    fun seriesCategoryName(id: String): String? = _seriesCats.value.firstOrNull { it.id == id }?.name

    suspend fun movieInfo(id: String): MovieInfo? = withContext(Dispatchers.IO) {
        movieInfoCache[id]?.let { return@withContext it }
        val p = _playlist.value ?: return@withContext null
        if (p.type != PlaylistType.XTREAM) return@withContext null
        try {
            XtreamClient(p).vodInfo(id).also { movieInfoCache[id] = it }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun seriesInfo(id: String): SeriesInfo? = withContext(Dispatchers.IO) {
        seriesInfoCache[id]?.let { return@withContext it }
        val p = _playlist.value ?: return@withContext null
        val info: SeriesInfo? = when (p.type) {
            PlaylistType.XTREAM -> try {
                XtreamClient(p).seriesInfo(id)
            } catch (e: Exception) {
                null
            }
            PlaylistType.M3U -> m3uEpisodes[id]?.let { eps ->
                val item = seriesIndex[id]
                SeriesInfo(
                    plot = null,
                    cast = emptyList(),
                    genre = null,
                    year = item?.year,
                    rating = null,
                    backdrop = null,
                    cover = item?.cover,
                    episodes = eps
                )
            }
        }
        if (info != null) seriesInfoCache[id] = info
        info
    }

    // ------------------------------------------------------------ user data

    fun toggleFavorite(key: String) {
        val set = _favorites.value.toMutableSet()
        if (!set.remove(key)) set.add(key)
        _favorites.value = set
        prefs.favorites = set
    }

    fun saveProgress(entry: WatchEntry) {
        val finished = entry.duration > 0 && entry.position >= entry.duration * 0.95
        val list = _history.value.filterNot { it.key == entry.key }.toMutableList()
        if (!finished && entry.position >= 15_000L) list.add(0, entry)
        val trimmed = list.take(60)
        _history.value = trimmed
        prefs.saveHistory(trimmed)
        if (finished) markWatched(entry.key)
    }

    fun markWatched(key: String) {
        if (key in _watched.value) return
        val set = _watched.value + key
        _watched.value = set
        prefs.watched = set
    }

    fun removeFromHistory(key: String) {
        val list = _history.value.filterNot { it.key == key }
        _history.value = list
        prefs.saveHistory(list)
    }

    fun historyEntry(key: String): WatchEntry? = _history.value.firstOrNull { it.key == key }

    fun setHideAdult(value: Boolean) {
        prefs.hideAdult = value
        _hideAdult.value = value
    }

    fun friendlyError(e: Throwable): String = when (e) {
        is AuthException -> e.message ?: "بيانات الدخول غير صحيحة."
        is HttpException -> when (e.code) {
            401, 403 -> "رفض الخادم الدخول. تحقّق من البيانات أو من صلاحية الاشتراك."
            404 -> "الرابط غير موجود. تأكّد من رابط الخادم."
            in 500..599 -> "الخادم لا يستجيب حالياً. حاول لاحقاً."
            else -> "خطأ من الخادم (${e.code})."
        }
        is java.net.UnknownHostException -> "تعذّر الوصول إلى الخادم. تحقّق من الرابط والاتصال بالإنترنت."
        is java.net.SocketTimeoutException -> "انتهت مهلة الاتصال بالخادم."
        is java.net.ConnectException -> "تعذّر الاتصال بالخادم."
        is IllegalArgumentException -> "الرابط غير صحيح. اكتبه كاملاً بالإنجليزية ويبدأ بـ http://\u200E"
        is IOException -> e.message?.takeIf { msg -> msg.any { it in '؀'..'ۿ' } }
            ?: "تعذّر تحميل البيانات. تحقّق من الاتصال."
        else -> "حدث خطأ غير متوقع."
    }

    companion object {
        private const val HOUR = 60L * 60L * 1000L
        private const val DAY = 24L * HOUR
        private const val LIST_TTL = 12L * HOUR
        private const val EPG_TTL = 12L * HOUR
    }
}
