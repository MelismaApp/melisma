package com.melisma.app.lyrics

import android.content.Context
import android.util.Log
import android.util.Xml
import com.melisma.app.lyrics.provider.Http
import com.melisma.app.lyrics.romanize.HokkienWords
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * The Ministry of Education's 臺灣台語常用詞辭典, downloaded when asked for.
 *
 * The reference dictionary for Taiwanese, and not bundled: it is CC BY-ND, which allows copies only
 * as they are, and the app's tables are a merge. So the phone fetches it from the Ministry itself,
 * reads the words and readings out of it into its own storage, and deletes the download.
 * [HokkienWords] then reads the words it lists before the bundled ones.
 */
class HokkienDictionary(context: Context, private val scope: CoroutineScope) {

    sealed interface Status {
        data object Absent : Status
        data object Loading : Status
        /** [fraction] is null while the size is unknown. */
        data class Downloading(val fraction: Float?) : Status
        data class Reading(val fraction: Float) : Status
        /** [words] it lists; [changed] of them read differently from the bundled tables, or are new. */
        data class Ready(val words: Int, val changed: Int) : Status
        data class Failed(val reason: String) : Status
    }

    private val directory = File(context.filesDir, DIRECTORY)
    private val index = File(directory, INDEX)

    private val _status = MutableStateFlow<Status>(if (index.isFile) Status.Loading else Status.Absent)
    val status: StateFlow<Status> = _status

    private var job: Job? = null

    init {
        if (index.isFile) job = scope.launch(Dispatchers.IO) { load() }
    }

    fun download() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val spreadsheet = File(directory, SPREADSHEET)
            try {
                directory.mkdirs()
                _status.value = Status.Downloading(null)
                fetch(spreadsheet)
                _status.value = Status.Reading(0f)
                val entries = MoeSpreadsheet.read(spreadsheet) { _status.value = Status.Reading(it) }
                if (entries.isEmpty()) error("it held no words")
                val part = File(directory, "$INDEX.part")
                part.bufferedWriter().use { out ->
                    for ((word, readings) in entries) {
                        out.append(word).append('\t').append(readings.joinToString("/")).append('\n')
                    }
                }
                if (!part.renameTo(index)) error("could not save it")
                use(entries)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "dictionary download failed", e)
                _status.value = Status.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                spreadsheet.delete()
                File(directory, "$SPREADSHEET.part").delete()
            }
        }
    }

    fun remove() {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            HokkienWords.useDictionary(null)
            directory.listFiles()?.forEach { it.delete() }
            _status.value = Status.Absent
        }
    }

    private fun load() {
        val entries = LinkedHashMap<String, List<String>>()
        runCatching {
            index.forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) entries[line.substring(0, tab)] = line.substring(tab + 1).split('/')
            }
        }
        if (entries.isEmpty()) {
            index.delete()
            _status.value = Status.Absent
        } else {
            use(entries)
        }
    }

    private fun use(entries: Map<String, List<String>>) {
        val changed = HokkienWords.useDictionary(entries)
        _status.value = Status.Ready(entries.size, changed)
    }

    private suspend fun fetch(target: File) {
        val part = File(target.parentFile, target.name + ".part")
        Http.execute(http.newCall(Http.request(URL))) { response ->
            if (!response.isSuccessful) error("the Ministry's server answered ${response.code}")
            val body = response.body ?: error("the Ministry's server sent nothing")
            val total = body.contentLength()
            if (total > MAX_BYTES) error("it was ${total / 1_000_000} MB, not the 4.5 MB expected")
            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    var published = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > MAX_BYTES) error("it was larger than expected")
                        output.write(buffer, 0, read)
                        if (total > 0 && written - published > total / 100) {
                            published = written
                            _status.value = Status.Downloading(written.toFloat() / total)
                        }
                    }
                }
            }
        }
        if (!part.renameTo(target)) error("could not save it")
    }

    /** The shared client gives a whole call twenty seconds, which a slow connection cannot meet. */
    private val http: OkHttpClient by lazy {
        Http.client.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        const val URL = "https://sutian.moe.edu.tw/media/senn/ods/kautian.ods"
        private const val TAG = "HokkienDictionary"
        private const val DIRECTORY = "hokkien-moe"
        private const val SPREADSHEET = "kautian.ods"
        private const val INDEX = "words.txt"
        private const val MAX_BYTES = 40_000_000L
    }
}

/**
 * The words of the Ministry's dictionary spreadsheet, each with its readings in order.
 *
 * An OpenDocument spreadsheet is a zip holding one `content.xml`, here about 70 MB, so it is read
 * as a stream and never held whole. Three of its sheets matter: 詞目, the entries, with each one's
 * characters and Tâi-lô; 又唸作, further readings of an entry; and 異用字, other ways of writing
 * one, which is how lyrics often do.
 */
internal object MoeSpreadsheet {

    private const val TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
    private const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    private const val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"

    private const val ENTRIES = "詞目"
    private const val ALSO_READ = "又唸作"
    private const val VARIANTS = "異用字"

    /** "一【替】": the bracket says how the entry is used, and is not part of the word. */
    private val annotation = Regex("【[^】]*】")

    fun read(file: File, onProgress: (Float) -> Unit = {}): Map<String, List<String>> {
        val size = file.length().coerceAtLeast(1)
        return file.inputStream().use { raw ->
            var consumed = 0L
            var published = 0L
            val counting = object : FilterInputStream(raw) {
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { n ->
                        if (n > 0) consumed += n
                        if (consumed - published > size / 100) {
                            published = consumed
                            onProgress(consumed.toFloat() / size)
                        }
                    }
            }
            read(counting)
        }
    }

    fun read(input: InputStream): Map<String, List<String>> {
        val zip = ZipInputStream(input.buffered())
        while (true) {
            val entry = zip.nextEntry ?: error("it was not a dictionary spreadsheet")
            if (entry.name == "content.xml") return words(zip)
        }
    }

    private fun words(xml: InputStream): Map<String, List<String>> {
        val headwords = LinkedHashMap<String, String>()
        val readings = HashMap<String, MutableList<String>>()
        val variants = ArrayList<Pair<String, String>>()

        rows(xml) { sheet, cells ->
            when (sheet) {
                ENTRIES -> {
                    val id = cells.getOrNull(0) ?: return@rows
                    val word = word(cells.getOrNull(2)) ?: return@rows
                    val found = readingsOf(cells.getOrNull(3))
                    if (found.isEmpty()) return@rows
                    headwords[id] = word
                    readings.getOrPut(id) { ArrayList() } += found
                }
                ALSO_READ -> {
                    val id = cells.getOrNull(0) ?: return@rows
                    readings[id]?.addAll(readingsOf(cells.getOrNull(2)))
                }
                VARIANTS -> {
                    val id = cells.getOrNull(0) ?: return@rows
                    val variant = word(cells.getOrNull(2)) ?: return@rows
                    variants += id to variant
                }
            }
        }

        val out = LinkedHashMap<String, MutableList<String>>()
        for ((id, word) in headwords) {
            val list = out.getOrPut(word) { ArrayList() }
            readings[id]?.forEach { if (it !in list) list += it }
        }
        // A spelling the dictionary lists as its own word keeps its own readings.
        for ((id, variant) in variants) {
            if (variant in out) continue
            val word = headwords[id] ?: continue
            if (variant.codePointCount(0, variant.length) != word.codePointCount(0, word.length)) continue
            out[variant] = out[word]?.toMutableList() ?: continue
        }
        return out.filterValues { it.isNotEmpty() }
    }

    /** Every row of the three sheets that matter, as the text of its first four cells. */
    private fun rows(xml: InputStream, each: (sheet: String, cells: List<String>) -> Unit) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(xml, "UTF-8")

        var sheet: String? = null
        val cells = ArrayList<String>(4)
        val cell = StringBuilder()
        var inCell = false
        var span = 1
        // A number is written in an attribute, and again as text only for display.
        var numeric = false

        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> return
                XmlPullParser.START_TAG -> when (parser.namespace) {
                    TABLE -> when (parser.name) {
                        "table" -> sheet = parser.getAttributeValue(TABLE, "name")
                            ?.takeIf { it == ENTRIES || it == ALSO_READ || it == VARIANTS }
                        "table-row" -> cells.clear()
                        "table-cell", "covered-table-cell" -> if (sheet != null) {
                            inCell = true
                            cell.setLength(0)
                            span = parser.getAttributeValue(TABLE, "number-columns-repeated")
                                ?.toIntOrNull()?.coerceIn(1, 4) ?: 1
                            val value = parser.getAttributeValue(OFFICE, "value")
                            numeric = value != null
                            value?.let { cell.append(it) }
                        }
                    }
                    TEXT -> if (inCell && parser.name == "s") {
                        val count = parser.getAttributeValue(TEXT, "c")?.toIntOrNull() ?: 1
                        repeat(count.coerceIn(1, 8)) { cell.append(' ') }
                    }
                }
                XmlPullParser.TEXT -> if (inCell && !numeric) cell.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "table-cell", "covered-table-cell" -> if (inCell) {
                        inCell = false
                        repeat(span) { if (cells.size < 4) cells += cell.toString().trim() }
                    }
                    "table-row" -> if (sheet != null && cells.isNotEmpty()) each(sheet, cells.toList())
                    "table" -> if (parser.namespace == TABLE) sheet = null
                }
            }
        }
    }

    private fun word(cell: String?): String? {
        val text = cell?.replace(annotation, "")?.trim() ?: return null
        if (text.isEmpty()) return null
        var index = 0
        while (index < text.length) {
            val point = text.codePointAt(index)
            if (Character.UnicodeScript.of(point) != Character.UnicodeScript.HAN) return null
            index += Character.charCount(point)
        }
        return text
    }

    /** "tāi-jîn/tāi-lîn" is two readings. Anything that is not letters, marks and joins is dropped. */
    private fun readingsOf(cell: String?): List<String> =
        cell.orEmpty().split('/').mapNotNull { raw ->
            val reading = Normalizer.normalize(raw.trim().lowercase(), Normalizer.Form.NFC)
                .replace(Regex("\\s+"), " ")
            reading.takeIf { r ->
                r.isNotEmpty() && r.all { it.isLetter() || it == '-' || it == ' ' || Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
            }
        }
}
