package com.ratv.app.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import com.ratv.app.data.Http
import com.ratv.app.data.debugProperty
import okhttp3.Request
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * «تخطي المقدمة»: where the opening of an episode (or the recap before it) is, so the player can skip it.
 *
 * Most series have no list of their openings, so RA TV finds them by ear: the sound of the first 12
 * minutes of each episode is printed (IntroPrint.kt) as the player loads it, minutes ahead of the
 * picture (the player's own download, read on its way: no second connection to the server); the
 * stretch of sound an episode shares with another episode of the series is its opening, and from
 * then on it is found again in every episode, before the picture gets there. The series and films
 * TheIntroDB knows (by their TMDB number) have theirs from the first episode.
 */
data class IntroWindow(val startMs: Long, val endMs: Long, val kind: Int, val source: String) {
    companion object {
        const val INTRO = 0
        const val RECAP = 1
    }
}

private const val TAG = "RATVIntro"

/** Only the beginning of an episode is printed: openings come in its first minutes. */
private const val LIMIT_MS = 12 * 60_000L
private const val LIMIT_US = LIMIT_MS * 1000L

/** A shared stretch is an opening from 20 s (shorter ones are recaps' clips or channels' logos) up to 5 minutes. */
private const val LEARN_MIN = 20_000 / IntroPrint.FRAME_MS
private const val MAX_FRAMES = 5 * 60_000 / IntroPrint.FRAME_MS

// ------------------------------------------------------------------------------------- the store

/**
 * What RA TV knows of one series' sound: its opening's prints (once found) and the first minutes of
 * the last few episodes played, kept in the app's files.
 */
class IntroStore(private val file: File) {
    var intro: Prints? = null
        private set
    /** Episodes the opening was found in after it was learnt: an opening never found again may be a mistake. */
    var hits = 0
        private set
    private val episodes = LinkedHashMap<String, Prints>()
    /** Goes up whenever the store changes (an episode playing now looks again at what came in). */
    @Volatile var version = 0
        private set

    @Synchronized fun others(episodeId: String): List<Prints> = episodes.filterKeys { it != episodeId }.values.reversed()

    @Synchronized fun snapshot(): Pair<Prints?, Int> = intro to hits

    @Synchronized fun learn(prints: Prints) {
        intro = prints
        hits = 0
        version++
    }

    @Synchronized fun foundAgain() {
        hits++
    }

    /** Keeps [prints] of [episodeId] (the newest three episodes are kept), and writes the store. */
    @Synchronized fun keep(episodeId: String, prints: Prints) {
        if (prints.heardCount >= 60_000 / IntroPrint.FRAME_MS) {
            episodes.remove(episodeId)
            episodes[episodeId] = prints.slice(0, prints.end)
            while (episodes.size > 3) episodes.remove(episodes.keys.first())
            version++
        }
        save()
    }

    @Synchronized fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                val i = intro
                out.writeInt(i?.end ?: 0)
                if (i != null) {
                    out.writeInt(hits)
                    writePrints(out, i)
                }
                out.writeInt(episodes.size)
                for ((id, p) in episodes) {
                    out.writeUTF(id)
                    out.writeInt(p.end)
                    writePrints(out, p)
                }
            }
            tmp.renameTo(file)
        }.onFailure { Log.w(TAG, "could not keep the series' prints", it) }
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            DataInputStream(file.inputStream().buffered()).use { inp ->
                if (inp.readInt() != MAGIC) return
                val n = inp.readInt()
                if (n > 0) {
                    hits = inp.readInt()
                    intro = readPrints(inp, n)
                }
                repeat(inp.readInt()) {
                    val id = inp.readUTF()
                    episodes[id] = readPrints(inp, inp.readInt())
                }
            }
            file.setLastModified(System.currentTimeMillis())
        }.onFailure {
            Log.w(TAG, "the series' prints could not be read: starting again", it)
            intro = null
            episodes.clear()
        }
    }

    companion object {
        private const val MAGIC = 0x52414931 // "RAI1"
        private val open = HashMap<String, IntroStore>()

        /** The store of one series ([key]: the server and the series). */
        fun of(context: Context, key: String): IntroStore = synchronized(open) {
            open.getOrPut(key) {
                val dir = File(context.filesDir, "intros")
                val name = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
                    .joinToString("") { "%02x".format(it) }.take(24)
                prune(dir)
                IntroStore(File(dir, "$name.bin")).also { it.load() }
            }
        }

        /** The 80 series played last are kept. */
        private fun prune(dir: File) {
            val files = dir.listFiles { f -> f.name.endsWith(".bin") } ?: return
            if (files.size <= 80) return
            files.sortedByDescending { it.lastModified() }.drop(80).forEach { it.delete() }
        }

        private fun writePrints(out: DataOutputStream, p: Prints) {
            for (k in 0 until p.end) out.writeInt(p.bits[k])
            val packed = ByteArray((p.end + 7) / 8)
            for (k in 0 until p.end) if (p.heard[k]) packed[k shr 3] = (packed[k shr 3].toInt() or (1 shl (k and 7))).toByte()
            out.write(packed)
        }

        private fun readPrints(inp: DataInputStream, n: Int): Prints {
            if (n < 0 || n > 20 * 60_000 / IntroPrint.FRAME_MS) throw EOFException("bad length")
            val bits = IntArray(n) { inp.readInt() }
            val packed = ByteArray((n + 7) / 8)
            inp.readFully(packed)
            val heard = BooleanArray(n) { k -> (packed[k shr 3].toInt() shr (k and 7)) and 1 == 1 }
            return Prints.of(bits, heard)
        }
    }
}

// ----------------------------------------------------------------------------------- one episode

/**
 * The episode being played: its sound as the player loads it ([onFormat], [onSample], from the tap
 * below), decoded and printed on a thread of its own, and the opening looked for in it.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class IntroSession(
    private val openStore: () -> IntroStore,
    private val episodeId: String,
    private val label: String,
    private val onWindow: (IntroWindow?) -> Unit
) {
    @Volatile private var closed = false
    @Volatile private var lastTimeUs = 0L
    private val queued = AtomicLong(0L)
    private val queue = LinkedBlockingQueue<Any>()
    private val prints = Prints(IntroPrint.frameAt(LIMIT_MS))
    private val printer = Printer(prints)
    private val main = Handler(Looper.getMainLooper())
    private val thread = Thread({ work() }, "RATVIntro").apply {
        priority = Thread.MIN_PRIORITY
        isDaemon = true
    }

    private class Sample(val timeUs: Long, val data: ByteArray)
    private class Jump(val timeUs: Long)
    private object Close

    /** True while the sound is still worth reading (the session is open, and within the first minutes). */
    val wants: Boolean get() = !closed && lastTimeUs < LIMIT_US

    fun onFormat(format: Format) {
        if (!closed) queue.offer(format)
    }

    fun onSample(timeUs: Long, data: ByteArray, offset: Int, size: Int) {
        lastTimeUs = timeUs
        if (closed || timeUs >= LIMIT_US || timeUs < 0) return
        // A slow TV never holds more than 8 MB of sound waiting.
        if (queued.get() > 8L shl 20) return
        queued.addAndGet(size.toLong())
        queue.offer(Sample(timeUs, data.copyOfRange(offset, offset + size)))
    }

    fun onSeek(timeUs: Long) {
        lastTimeUs = timeUs
        if (!closed) queue.offer(Jump(timeUs))
    }

    /** The episode is left: its prints are kept for the next episodes. */
    fun close() {
        if (closed) return
        closed = true
        queue.offer(Close)
    }

    // ------------------------------------------------------------------ on the session's thread

    private var codec: MediaCodec? = null
    private var format: Format? = null
    private var broken = false
    private var outRate = 0
    private var outChannels = 0
    private var outFloat = false
    private var mono = FloatArray(4096)
    private var newFrames = 0
    private var published: IntroWindow? = null
    private var settled = false
    private var confirmed = false
    private var store: IntroStore? = null
    private var seenVersion = -1

    private fun work() {
        try {
            // The series' store is read here, off the main thread.
            store = openStore()
            while (true) {
                val job = queue.poll() ?: run {
                    // Caught up with the player: look now at what came in (here, or from an episode just left).
                    if (newFrames > 0 || store?.version != seenVersion) look()
                    queue.poll(3, TimeUnit.SECONDS)
                } ?: continue
                when (job) {
                    is Close -> break
                    is Format -> configure(job)
                    is Jump -> jump()
                    is Sample -> {
                        queued.addAndGet(-job.data.size.toLong())
                        decode(job)
                        if (newFrames >= 40) look()
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "$label: stopped", e)
        } finally {
            release()
            store?.let { s ->
                if (newFrames > 0 && !closedBeforeStart()) runCatching { look() }
                s.keep(episodeId, prints)
                Log.i(TAG, "$label: kept ${prints.heardCount * IntroPrint.FRAME_MS / 1000} s of prints")
            }
        }
    }

    private fun closedBeforeStart() = prints.end == 0

    private fun configure(f: Format) {
        val old = format
        format = f
        if (old != null && old.sampleMimeType == f.sampleMimeType && old.sampleRate == f.sampleRate &&
            old.channelCount == f.channelCount && old.initializationDataEquals(f) && codec != null
        ) {
            return
        }
        release()
        broken = false
        val mime = f.sampleMimeType ?: return
        try {
            val name = decoderFor(mime) ?: run {
                Log.i(TAG, "$label: no decoder for $mime: no prints")
                broken = true
                return
            }
            val mf = MediaFormat.createAudioFormat(mime, if (f.sampleRate > 0) f.sampleRate else 48_000, if (f.channelCount > 0) f.channelCount else 2)
            f.initializationData.forEachIndexed { i, csd -> mf.setByteBuffer("csd-$i", ByteBuffer.wrap(csd)) }
            if (f.maxInputSize > 0) mf.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, f.maxInputSize)
            codec = MediaCodec.createByCodecName(name).apply {
                configure(mf, null, null, 0)
                start()
            }
            outRate = if (f.sampleRate > 0) f.sampleRate else 48_000
            outChannels = if (f.channelCount > 0) f.channelCount else 2
            outFloat = false
            Log.i(TAG, "$label: printing its sound ($mime with $name)")
        } catch (e: Exception) {
            Log.w(TAG, "$label: the decoder for $mime did not start", e)
            release()
            broken = true
        }
    }

    private fun jump() {
        runCatching { codec?.flush() }
        printer.reset()
    }

    private val info = MediaCodec.BufferInfo()

    private fun decode(s: Sample) {
        val c = codec ?: return
        if (broken) return
        try {
            var index = c.dequeueInputBuffer(10_000L)
            var tries = 0
            while (index < 0) {
                drain(c)
                if (closed || ++tries > 50) return
                index = c.dequeueInputBuffer(10_000L)
            }
            val buf = c.getInputBuffer(index) ?: return
            buf.clear()
            if (buf.capacity() < s.data.size) {
                c.queueInputBuffer(index, 0, 0, s.timeUs, 0)
                return
            }
            buf.put(s.data)
            c.queueInputBuffer(index, 0, s.data.size, s.timeUs, 0)
            drain(c)
        } catch (e: Exception) {
            // A damaged piece of sound: start the decoder again from the next one.
            Log.w(TAG, "$label: decoding failed", e)
            runCatching { c.flush() }
            printer.reset()
        }
    }

    private fun drain(c: MediaCodec) {
        while (true) {
            val out = c.dequeueOutputBuffer(info, 0L)
            when {
                out >= 0 -> {
                    val b = c.getOutputBuffer(out)
                    if (b != null && info.size > 0) print(b, info.offset, info.size, info.presentationTimeUs)
                    c.releaseOutputBuffer(out, false)
                }
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val of = c.outputFormat
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) outRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) outChannels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    outFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                }
                else -> return
            }
        }
    }

    private fun print(b: ByteBuffer, offset: Int, size: Int, timeUs: Long) {
        val ch = outChannels.coerceAtLeast(1)
        b.limit(offset + size)
        b.position(offset)
        val data = b.slice().order(ByteOrder.nativeOrder())
        val frames = if (outFloat) size / (4 * ch) else size / (2 * ch)
        if (frames <= 0) return
        if (mono.size < frames) mono = FloatArray(frames)
        if (outFloat) {
            val fb = data.asFloatBuffer()
            for (i in 0 until frames) {
                var s = 0f
                for (k in 0 until ch) s += fb.get(i * ch + k)
                mono[i] = s / ch
            }
        } else {
            val sb = data.asShortBuffer()
            for (i in 0 until frames) {
                var s = 0
                for (k in 0 until ch) s += sb.get(i * ch + k)
                mono[i] = s / (32768f * ch)
            }
        }
        newFrames += printer.feed(mono, frames, outRate, timeUs)
    }

    private fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    /** Looks for the opening in what is printed so far, and tells the player when what it knows changed. */
    private fun look() {
        newFrames = 0
        val store = store ?: return
        seenVersion = store.version
        if (settled || prints.end == 0) return
        val (intro, hits) = store.snapshot()
        var window: IntroWindow? = null
        var open = false
        if (intro != null) {
            val s = IntroMatch.shared(intro, prints, minOf(120, intro.end * 6 / 10).coerceAtLeast(40), MAX_FRAMES)
            if (s != null) {
                // Back to the opening's first frame when the match starts a little into it.
                val start = if (s.aFrom <= 60) s.bFrom - s.aFrom else s.bFrom
                open = s.bTo >= prints.end - 3
                val end = if (open) maxOf(s.bTo, s.bFrom - s.aFrom + intro.end) else s.bTo
                val found = IntroWindow(IntroPrint.msOf(start.coerceAtLeast(0)), IntroPrint.msOf(end), IntroWindow.INTRO, "sound")
                window = found
                if (!open && !confirmed) {
                    confirmed = true
                    store.foundAgain()
                    Log.i(TAG, "$label: the opening found again at ${sec(found.startMs)}-${sec(found.endMs)} s")
                }
            }
        }
        // Not known yet (or known but never found again, and not in this episode either): learn it from the others.
        val relearn = intro != null && hits == 0 && window == null && prints.end >= IntroPrint.frameAt(8 * 60_000L)
        if (window == null && (intro == null || relearn)) {
            for (other in store.others(episodeId)) {
                val s = IntroMatch.shared(other, prints, LEARN_MIN, MAX_FRAMES) ?: continue
                // Only a stretch that has ended in both (a longer one may still be coming).
                if (s.bTo >= prints.end - 20 || s.aTo >= other.end - 20) continue
                store.learn(prints.slice(s.bFrom, s.bTo))
                val learnt = IntroWindow(IntroPrint.msOf(s.bFrom), IntroPrint.msOf(s.bTo), IntroWindow.INTRO, "sound")
                window = learnt
                Log.i(TAG, "$label: opening learnt at ${sec(learnt.startMs)}-${sec(learnt.endMs)} s (shared with an episode before)")
                break
            }
        }
        if (window != null && !open) settled = true
        val told = window
        if (told != published) {
            published = told
            main.post { if (!closed) onWindow(told) }
        }
    }

    private fun sec(ms: Long) = String.format(java.util.Locale.US, "%.1f", ms / 1000.0)

    // Last: the thread starts once everything above is set.
    init {
        thread.start()
    }

    companion object {
        /** A decoder for [mime], the device's own software one first (it leaves the hardware to the player). */
        fun decoderFor(mime: String): String? {
            val all = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }.getOrNull() ?: return null
            return all.filter { info -> !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
                .sortedBy { if (it.name.startsWith("c2.android.") || it.name.startsWith("OMX.google.")) 0 else 1 }
                .firstOrNull()?.name
        }
    }
}

/** The sessions of the items playing, by their address (the player's extractors are made for an address). */
@androidx.annotation.OptIn(UnstableApi::class)
object IntroSessions {
    private val byUrl = ConcurrentHashMap<String, IntroSession>()

    fun forUrl(url: String): IntroSession? = byUrl[url]

    fun open(url: String, session: IntroSession) {
        byUrl.put(url, session)?.takeIf { it !== session }?.close()
    }

    fun close(url: String) {
        byUrl.remove(url)?.close()
    }
}

// --------------------------------------------------------------------------------------- the tap

/**
 * The player's extractors, with the first sound track of an item that has a session read on its way
 * to the player (the samples are copied, the player gets them as before).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class IntroTapExtractors(private val delegate: ExtractorsFactory) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = delegate.createExtractors()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> {
        val list = delegate.createExtractors(uri, responseHeaders)
        val session = IntroSessions.forUrl(uri.toString()) ?: return list
        return Array(list.size) { TapExtractor(list[it], session) }
    }

    @Deprecated("Media3's own")
    @Suppress("DEPRECATION")
    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory {
        delegate.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
        return this
    }

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        delegate.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private class TapExtractor(private val delegate: Extractor, private val session: IntroSession) : Extractor {
    private var output: TapOutput? = null

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun getSniffFailureDetails(): List<SniffFailure> = delegate.sniffFailureDetails

    override fun init(output: ExtractorOutput) {
        val tap = TapOutput(output, session)
        this.output = tap
        delegate.init(tap)
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) {
        output?.track?.dropPending()
        session.onSeek(timeUs)
        delegate.seek(position, timeUs)
    }

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
}

@androidx.annotation.OptIn(UnstableApi::class)
private class TapOutput(private val delegate: ExtractorOutput, private val session: IntroSession) : ExtractorOutput {
    var track: TapTrack? = null
    private var trackId = -1

    override fun track(id: Int, type: Int): TrackOutput {
        val out = delegate.track(id, type)
        if (type != C.TRACK_TYPE_AUDIO) return out
        val tapped = track
        if (tapped == null) {
            trackId = id
            return TapTrack(out, session).also { track = it }
        }
        return if (id == trackId) tapped else out
    }

    override fun endTracks() = delegate.endTracks()

    override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
}

/** One sound track: its bytes go to the player's queue as before, and each whole sample to the session. */
@androidx.annotation.OptIn(UnstableApi::class)
private class TapTrack(private val delegate: TrackOutput, private val session: IntroSession) : TrackOutput {
    private var pending = ByteArray(8192)
    private var pendingSize = 0
    private var scratch = ByteArray(8192)
    private val wrapper = ParsableByteArray()

    fun dropPending() {
        pendingSize = 0
    }

    private fun append(data: ByteArray, offset: Int, length: Int) {
        if (pendingSize + length > pending.size) {
            // A sample larger than 1 MB is no sound: stop collecting it.
            if (pendingSize + length > 1 shl 20) {
                pendingSize = 0
                return
            }
            pending = pending.copyOf(maxOf(pending.size * 2, pendingSize + length))
        }
        System.arraycopy(data, offset, pending, pendingSize, length)
        pendingSize += length
    }

    override fun format(format: Format) {
        delegate.format(format)
        session.onFormat(format)
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN || !session.wants || length > 1 shl 20) {
            return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        }
        if (scratch.size < length) scratch = ByteArray(length)
        val n = input.read(scratch, 0, length)
        if (n == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        append(scratch, 0, n)
        wrapper.reset(scratch, n)
        delegate.sampleData(wrapper, n, sampleDataPart)
        return n
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN && session.wants) append(data.data, data.position, length)
        delegate.sampleData(data, length, sampleDataPart)
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        val end = pendingSize - offset
        val start = end - size
        if (start >= 0 && size > 0 && cryptoData == null) session.onSample(timeUs, pending, start, size)
        // The bytes after the sample belong to the next one.
        if (offset in 1..pendingSize && end >= 0) {
            System.arraycopy(pending, end, pending, 0, offset)
            pendingSize = offset
        } else {
            pendingSize = 0
        }
    }
}

// ------------------------------------------------------------------------------- TheIntroDB

/**
 * Openings and recaps people marked on TheIntroDB (theintrodb.org), for series and films with a
 * TMDB number. Asked once per episode or film; what it does not know is not asked again.
 */
object IntroDb {
    private val base: String get() = debugProperty("debug.ratv.introdb") ?: "https://api.theintrodb.org/v4"
    private val known = ConcurrentHashMap<String, List<IntroWindow>>()
    @Volatile private var quietUntil = 0L

    /** The windows of a film ([season] and [episode] null) or an episode, or none. Off the main thread. */
    fun lookup(tmdb: Int, season: Int?, episode: Int?, durationMs: Long): List<IntroWindow> {
        val key = "$tmdb/$season/$episode"
        known[key]?.let { return it }
        if (SystemClock.elapsedRealtime() < quietUntil) return emptyList()
        val query = StringBuilder("tmdb_id=").append(tmdb)
        if (season != null && episode != null) query.append("&season=").append(season).append("&episode=").append(episode)
        if (durationMs > 0) query.append("&duration_ms=").append(durationMs)
        return try {
            val request = Request.Builder().url("$base/media?$query").header("Accept", "application/json").build()
            Http.client.newCall(request).execute().use { r ->
                when {
                    r.code == 404 -> emptyList<IntroWindow>().also { known[key] = it }
                    r.code == 429 -> {
                        // Asked too much from this address today: quiet for a while.
                        quietUntil = SystemClock.elapsedRealtime() + 30 * 60_000L
                        emptyList()
                    }
                    !r.isSuccessful -> emptyList()
                    else -> parse(r.body?.string().orEmpty(), durationMs).also { known[key] = it }
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "TheIntroDB not reached: ${e.message}")
            emptyList()
        }
    }

    internal fun parse(json: String, durationMs: Long): List<IntroWindow> {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val out = ArrayList<IntroWindow>()
        for ((name, kind) in listOf("recap" to IntroWindow.RECAP, "intro" to IntroWindow.INTRO)) {
            val arr = o.optJSONArray(name) ?: continue
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                if (s.isNull("end_ms")) continue
                val start = if (s.isNull("start_ms")) 0L else s.optLong("start_ms", 0L)
                val end = s.optLong("end_ms", 0L)
                // A real stretch: over 3 s, under 10 minutes, inside the item.
                if (end - start < 3_000L || end - start > 10 * 60_000L) continue
                if (durationMs > 0 && end > durationMs) continue
                out.add(IntroWindow(start, end, kind, "TheIntroDB"))
            }
        }
        return out
    }
}
