package ru.tachimanga.ext.inkstory

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import ru.tachimanga.common.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import ru.tachimanga.common.CheckBoxGroup
import ru.tachimanga.common.RuSource
import ru.tachimanga.common.TextFilter
import ru.tachimanga.common.TriStateGroup
import ru.tachimanga.common.parseIsoDate
import rx.Observable

/**
 * InkStory. Manga url = book slug. Chapter url = "<bookSlug>/<chapterId>".
 */
class InkStory : RuSource() {

    override val name = "InkStory"

    override val lang = "ru"

    override val supportsLatest = true

    // Same id scheme as the original Tachiyomi extension, so library entries carry over.
    override val versionId = 2

    override val supportsToken = true

    override val mirrors = listOf("Основной" to "https://inkstory.net")

    override val authCookieNames = listOf("token", "auth")

    override val loginHelp: String =
        "Вход нужен только для контента 18+. Откройте источник в WebView 🌐 и войдите на сайте. " +
            "Если не получилось — вставьте Cookie из браузера или токен доступа ниже."

    private val apiUrl: String
        get() = (preferences.getString(PREF_API, API_DEFAULT) ?: API_DEFAULT).trimEnd('/') + "/v2"

    override fun isOwnHost(host: String): Boolean = host == apiUrl.toHttpUrl().host || host.endsWith("inkstory.net")

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", if (apiUrl.contains("inuko")) READER_UA else BROWSER_UA)
        .set("Accept", "application/json, text/plain, */*")
        .set("X-Service-Name", SERVICE_NAME)
        .set("Origin", baseUrl)

    override fun configureClient(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        builder.addInterceptor(::imageDecryptInterceptor)

    // ============================== Image decoding ===============================
    private fun imageDecryptInterceptor(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) return response
        val contentType = response.body.contentType()
        if (contentType?.subtype == "json" || imageCodec(response.request.url.toString()) != 'x') return response

        val bytes = response.body.bytes()
        if (bytes.size < SIGNATURE_SIZE || imageType(bytes) != null) {
            return response.newBuilder().body(bytes.toResponseBody(contentType)).build()
        }
        val header = ByteArray(SIGNATURE_SIZE) { (bytes[it].toInt() xor KEY[it % KEY.size].toInt()).toByte() }
        val type = imageType(header) ?: return response.newBuilder().body(bytes.toResponseBody(contentType)).build()
        for (i in bytes.indices) bytes[i] = (bytes[i].toInt() xor KEY[i % KEY.size].toInt()).toByte()
        return response.newBuilder().body(bytes.toResponseBody(type.toMediaType())).build()
    }

    private fun imageType(b: ByteArray): String? {
        if (b.size < SIGNATURE_SIZE) return null
        fun at(i: Int, v: Int) = b[i] == v.toByte()
        return when {
            at(0, 0xFF) && at(1, 0xD8) && at(2, 0xFF) -> "image/jpeg"
            at(0, 0x89) && at(1, 0x50) && at(2, 0x4E) && at(3, 0x47) -> "image/png"
            at(0, 0x47) && at(1, 0x49) && at(2, 0x46) && at(3, 0x38) -> "image/gif"
            at(0, 0x52) && at(1, 0x49) && at(2, 0x46) && at(3, 0x46) && at(8, 0x57) && at(9, 0x45) && at(10, 0x42) && at(11, 0x50) -> "image/webp"
            at(4, 0x66) && at(5, 0x74) && at(6, 0x79) && at(7, 0x70) && at(8, 0x61) && at(9, 0x76) && at(10, 0x69) -> "image/avif"
            else -> null
        }
    }

    /** 's' or 'x' at a fixed position of the 36-char file name marks protected images. */
    private fun imageCodec(url: String): Char? {
        val file = url.substringAfterLast('/').substringBefore('?')
        val base = file.substringBeforeLast('.', file)
        if (base.length != IMAGE_NAME_LENGTH) return null
        return base.getOrNull(IMAGE_MODE_INDEX)?.takeIf { it == 's' || it == 'x' }
    }

    private fun normalizeImageUrl(url: String): String {
        if (imageCodec(url) != 's') return url
        val slash = url.lastIndexOf('/')
        val index = slash + 1 + IMAGE_MODE_INDEX
        if (slash == -1 || index >= url.length) return url
        return url.substring(0, index) + 'x' + url.substring(index + 1)
    }

    // ============================== Catalog ===============================
    override fun popularMangaRequest(page: Int): Request = catalogRequest(page, "viewsCount", null, null)
    override fun popularMangaParse(response: Response): MangasPage = catalogParse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/chapter-update-feed".toHttpUrl().newBuilder()
            .addQueryParameter("serviceName", SERVICE_NAME)
            .addQueryParameter("onlyBorderChapters", "true")
            .addQueryParameter("page", (page - 1).coerceAtLeast(0).toString())
            .addQueryParameter("size", PAGE_SIZE.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val list = response.parseAs<List<FeedItemDto>>().map { it.book.toSManga() }.distinctBy { it.url }
        return MangasPage(list, list.size >= PAGE_SIZE / 2)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        catalogRequest(page, "viewsCount", query, filters)

    override fun searchMangaParse(response: Response): MangasPage = catalogParse(response)

    private fun catalogRequest(page: Int, sortBy: String, query: String?, filters: FilterList?): Request {
        loadFiltersInBackground()
        var sort = sortBy
        var order = "desc"
        val url = "$apiUrl/books".toHttpUrl().newBuilder().apply {
            addQueryParameter("serviceName", SERVICE_NAME)
            filters?.forEach { filter ->
                when (filter) {
                    is GenreFilter -> {
                        filter.included.forEach { addQueryParameter("labelsInclude", it) }
                        filter.excluded.forEach { addQueryParameter("labelsExclude", it) }
                    }
                    is StrictGenresFilter -> if (filter.state) addQueryParameter("strictLabelEqual", "true")
                    is FormatFilter -> filter.checked.forEach { addQueryParameter("formats", it) }
                    is ContentStatusFilter -> filter.checked.forEach { addQueryParameter("contentStatus", it) }
                    is CountryFilter -> filter.checked.forEach { addQueryParameter("country", it) }
                    is StatusFilter -> filter.checked.forEach { addQueryParameter("status", it) }
                    is RangeFilter -> {
                        filter.min.state.trim().toDoubleOrNull()?.let { addQueryParameter("${filter.param}Min", it.clean()) }
                        filter.max.state.trim().toDoubleOrNull()?.let { addQueryParameter("${filter.param}Max", it.clean()) }
                    }
                    is OrderBy -> {
                        sort = ORDER_OPTIONS[filter.state?.index ?: 0].second
                        order = if (filter.state?.ascending == true) "asc" else "desc"
                    }
                    else -> {}
                }
            }
            query?.trim()?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("search", it) }
            addQueryParameter("page", (page - 1).coerceAtLeast(0).toString())
            addQueryParameter("size", PAGE_SIZE.toString())
            addQueryParameter("sort", "$sort,$order")
        }.build()
        return GET(url, headers)
    }

    private fun Double.clean(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()

    private fun catalogParse(response: Response): MangasPage {
        val total = response.header("x-estimated-total-hits")?.toIntOrNull()
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 0
        val list = response.parseAs<List<BookDto>>().map { it.toSManga() }
        val hasNext = if (total != null) (page + 1) * PAGE_SIZE < total else list.size >= PAGE_SIZE
        return MangasPage(list, hasNext)
    }

    // ============================== Details ===============================
    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiUrl/books/${manga.url}", headers)

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/content/${manga.url}"

    override fun mangaDetailsParse(response: Response): SManga = response.parseAs<BookFullDto>().toSManga()

    // ============================== Chapters ===============================
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val book = client.newCall(mangaDetailsRequest(manga)).execute().also { checkResponse(it) }.parseAs<BookFullDto>()
        val branches = client.newCall(GET("$apiUrl/branches?bookId=${book.id}&moderationStatus=APPROVED", headers)).execute()
            .also { checkResponse(it) }
            .parseAs<List<BranchDto>>()
            .associate { it.id to it.publisherName() }
        val chapters = client.newCall(GET("$apiUrl/chapters?bookId=${book.id}&moderationStatus=APPROVED", headers)).execute()
            .also { checkResponse(it) }
            .parseAs<List<ChapterDto>>()
            .map { it.toSChapter(branches, manga.url) }
            .sortedWith(compareByDescending<SChapter> { it.chapter_number }.thenByDescending { it.date_upload })

        when (preferences.getString(PREF_BRANCH_MODE, "all")) {
            "latest" -> dedupe(chapters)
            "preferred" -> {
                val wanted = preferences.getString(PREF_BRANCH_QUERY, "").orEmpty().trim()
                val preferred = if (wanted.isEmpty()) {
                    chapters
                } else {
                    chapters.filter { it.scanlator?.contains(wanted, ignoreCase = true) == true }.ifEmpty { chapters }
                }
                dedupe(preferred)
            }
            else -> chapters
        }
    }

    private fun checkResponse(response: Response) {
        if (!response.isSuccessful) {
            response.close()
            throw Exception(
                when (response.code) {
                    401, 403 -> "Нужен вход в аккаунт. Войдите через WebView 🌐 или вставьте токен в настройках источника."
                    404 -> "Тайтл не найден"
                    else -> "HTTP ошибка ${response.code}"
                },
            )
        }
    }

    /** Keeps the newest upload for each chapter number. */
    private fun dedupe(chapters: List<SChapter>): List<SChapter> {
        val seen = HashSet<Float>()
        return chapters.sortedByDescending { it.date_upload }
            .filter { it.chapter_number < 0f || seen.add(it.chapter_number) }
            .sortedWith(compareByDescending<SChapter> { it.chapter_number }.thenByDescending { it.date_upload })
    }

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/content/${chapter.url}"

    // ============================== Pages ===============================
    override fun pageListRequest(chapter: SChapter): Request =
        GET("$apiUrl/chapters/${chapter.url.substringAfterLast('/')}", headers)

    override fun pageListParse(response: Response): List<Page> {
        checkResponse(response)
        return response.parseAs<PagesDto>().pages
            .sortedBy { it.index ?: Int.MAX_VALUE }
            .mapNotNull { it.image?.takeIf(String::isNotBlank) }
            .mapIndexed { index, url -> Page(index, imageUrl = normalizeImageUrl(url)) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, headersBuilder().set("Accept", "image/avif,image/webp,image/*,*/*;q=0.8").build())

    // ============================== Filters ===============================
    @Volatile
    private var genres: List<Pair<String, String>>? = null

    @Volatile
    private var filtersLoading = false

    private fun loadFiltersInBackground() {
        if (genres != null || filtersLoading) return
        filtersLoading = true
        Thread {
            try {
                genres = client.newCall(GET("$apiUrl/labels", headers)).execute()
                    .parseAs<List<LabelDto>>()
                    .filter { it.kind == "GENRE" }
                    .map { it.name to it.slug }
            } catch (_: Exception) {
            } finally {
                filtersLoading = false
            }
        }.start()
    }

    override fun getFilterList(): FilterList {
        val filters = mutableListOf<Filter<*>>(OrderBy())
        val loaded = genres
        if (loaded.isNullOrEmpty()) {
            filters += Filter.Header("Жанры загрузятся после открытия каталога. Нажмите «Сбросить».")
        } else {
            filters += GenreFilter(loaded)
            filters += StrictGenresFilter()
        }
        filters += listOf(
            StatusFilter(),
            CountryFilter(),
            ContentStatusFilter(),
            FormatFilter(),
            RangeFilter("Рейтинг", "averageRating"),
            RangeFilter("Год выпуска", "year"),
            RangeFilter("Количество глав", "chaptersCount"),
        )
        return FilterList(filters)
    }

    private class OrderBy : Filter.Sort("Сортировать по", ORDER_OPTIONS.map { it.first }.toTypedArray(), Selection(0, false))
    private class GenreFilter(options: List<Pair<String, String>>) : TriStateGroup("Жанры", options)
    private class StrictGenresFilter : Filter.CheckBox("Строгое совпадение жанров")
    private class StatusFilter : CheckBoxGroup(
        "Статус",
        listOf("Онгоинг" to "ONGOING", "Завершен" to "DONE", "Заморожен" to "FROZEN", "Анонс" to "ANNOUNCE"),
    )
    private class CountryFilter : CheckBoxGroup(
        "Страна",
        listOf("Россия" to "RUSSIA", "Япония" to "JAPAN", "Корея" to "KOREA", "Китай" to "CHINA", "Другое" to "OTHER"),
    )
    private class ContentStatusFilter : CheckBoxGroup(
        "Контент",
        listOf("Безопасный" to "SAFE", "Небезопасный" to "UNSAFE", "Эротический" to "EROTIC"),
    )
    private class FormatFilter : CheckBoxGroup(
        "Формат",
        listOf(
            "Ёнкома" to "FOURTH_KOMA", "Сборник" to "COMPILATION", "Додзинси" to "DOUJINSHI", "Вебтун" to "WEBTOON",
            "Цветной" to "COLORED", "Артбук" to "ARTBOOK", "Сингл" to "SINGLE", "Лайт" to "LIGHT", "Веб" to "WEB",
        ),
    )
    private class RangeFilter(name: String, val param: String) :
        Filter.Group<TextFilter>(name, listOf(TextFilter("От"), TextFilter("До"))) {
        val min get() = state[0]
        val max get() = state[1]
    }

    // ============================== Preferences ===============================
    override fun setupExtraPreferences(screen: PreferenceScreen) {
        val context = screen.context
        ListPreference(context).apply {
            key = PREF_API
            title = "Сервер API"
            entries = API_OPTIONS.map { it.first }.toTypedArray()
            entryValues = API_OPTIONS.map { it.second }.toTypedArray()
            setDefaultValue(API_DEFAULT)
            summary = "%s\nСмените, если каталог не загружается. Затем перезапустите приложение."
        }.let(screen::addPreference)

        ListPreference(context).apply {
            key = PREF_BRANCH_MODE
            title = "Переводы глав"
            entries = arrayOf("Все переводы", "Только последний перевод главы", "Предпочитаемая команда")
            entryValues = arrayOf("all", "latest", "preferred")
            setDefaultValue("all")
            summary = "%s"
        }.let(screen::addPreference)

        EditTextPreference(context).apply {
            key = PREF_BRANCH_QUERY
            title = "Предпочитаемая команда"
            dialogTitle = "Часть названия команды переводчиков"
            summary = preferences.getString(PREF_BRANCH_QUERY, "")?.ifBlank { null } ?: "Не задана"
            setDefaultValue("")
            setOnPreferenceChangeListener { pref, value ->
                pref.summary = (value as String).ifBlank { "Не задана" }
                true
            }
        }.let(screen::addPreference)
    }

    // ============================== DTOs ===============================
    @Serializable
    private class FeedItemDto(val book: BookDto)

    @Serializable
    private class NameDto(val ru: String? = null, val en: String? = null, val original: String? = null) {
        fun pick(fallback: String) = ru?.ifBlank { null } ?: en?.ifBlank { null } ?: original?.ifBlank { null } ?: fallback
    }

    @Serializable
    private class BookDto(val slug: String, val poster: String? = null, val name: NameDto = NameDto()) {
        fun toSManga() = SManga.create().apply {
            url = slug
            title = name.pick(slug)
            thumbnail_url = poster
        }
    }

    @Serializable
    private class BookFullDto(
        val id: String,
        val slug: String,
        val name: NameDto = NameDto(),
        val poster: String? = null,
        val description: String? = null,
        val status: String? = null,
        val labels: List<NamedDto>? = null,
        val formats: List<String> = emptyList(),
        val relations: List<RelationDto>? = null,
        val averageRating: String? = null,
        val ratingVotesCount: Int? = null,
        val viewsCount: Int? = null,
    ) {
        fun toSManga() = SManga.create().apply {
            url = slug
            title = name.pick(slug)
            thumbnail_url = poster
            description = buildString {
                this@BookFullDto.description?.trim()?.ifBlank { null }?.let { append(it) }
                averageRating?.toDoubleOrNull()?.let {
                    if (isNotEmpty()) append("\n\n")
                    append("Рейтинг: %.2f".format(it))
                    ratingVotesCount?.let { votes -> append(" (оценок: $votes)") }
                }
                viewsCount?.let {
                    if (isNotEmpty()) append("\n")
                    append("Просмотры: $it")
                }
            }
            author = people("AUTHOR")
            artist = people("ARTIST")
            status = when (this@BookFullDto.status) {
                "ONGOING", "ANNOUNCE" -> SManga.ONGOING
                "DONE" -> SManga.COMPLETED
                "FROZEN" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
            genre = (labels.orEmpty().mapNotNull { it.name?.trim()?.ifBlank { null } } + formats.map { it.lowercase().replace('_', ' ') })
                .joinToString()
        }

        private fun people(type: String) = relations.orEmpty()
            .filter { it.type == type }
            .mapNotNull { it.publisher?.name?.trim()?.ifBlank { null } }
            .distinct().joinToString().ifBlank { null }
    }

    @Serializable
    private class NamedDto(val name: String? = null)

    @Serializable
    private class RelationDto(val type: String? = null, val publisher: NamedDto? = null)

    @Serializable
    private class BranchDto(val id: String, val publishers: List<NamedDto> = emptyList()) {
        fun publisherName(): String? = publishers.mapNotNull { it.name?.trim()?.ifBlank { null } }.distinct().joinToString().ifBlank { null }
    }

    @Serializable
    private class ChapterDto(
        val id: String,
        val name: String? = null,
        val title: String? = null,
        val number: String? = null,
        val volume: String? = null,
        val branchId: String? = null,
        val createdAt: String? = null,
    ) {
        fun toSChapter(branches: Map<String, String?>, slug: String) = SChapter.create().apply {
            url = "$slug/$id"
            val vol = volume?.toDoubleOrNull()?.let { trim(it) }
            val num = number?.toDoubleOrNull()?.let { trim(it) }
            val base = when {
                vol != null && num != null -> "Том $vol Глава $num"
                num != null -> "Глава $num"
                vol != null -> "Том $vol"
                else -> "Глава"
            }
            val subtitle = listOf(this@ChapterDto.name, title).firstOrNull { !it.isNullOrBlank() }?.trim()
            name = if (subtitle != null && !base.equals(subtitle, true)) "$base — $subtitle" else base
            chapter_number = number?.toFloatOrNull() ?: -1f
            date_upload = parseIsoDate(createdAt)
            scanlator = branchId?.let { branches[it] }
        }

        private fun trim(value: Double) = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    }

    @Serializable
    private class PagesDto(val pages: List<PageDto> = emptyList())

    @Serializable
    private class PageDto(val index: Int? = null, val image: String? = null)

    @Serializable
    private class LabelDto(val kind: String = "", val name: String = "", val slug: String = "")

    companion object {
        private const val SERVICE_NAME = "inkstory"
        private const val PAGE_SIZE = 30
        private const val IMAGE_NAME_LENGTH = 36
        private const val IMAGE_MODE_INDEX = 14
        private const val SIGNATURE_SIZE = 12
        private val KEY = "UySkp0BzPhwlvP2V".toByteArray()

        private const val PREF_API = "inkstory_api"
        private const val API_DEFAULT = "https://api.inkstory.net"
        private val API_OPTIONS = listOf("api.inkstory.net" to "https://api.inkstory.net", "api.inuko.me" to "https://api.inuko.me")
        private const val READER_UA = "Tachiyomi (+https://github.com/Sw1tchtaks/tachimanga-sources-for-russians)"
        private const val BROWSER_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
        private const val PREF_BRANCH_MODE = "inkstory_chapter_branch_mode"
        private const val PREF_BRANCH_QUERY = "inkstory_preferred_branch_query"

        private val ORDER_OPTIONS = listOf(
            "Просмотрам" to "viewsCount",
            "Лайкам" to "likesCount",
            "Главам" to "chaptersCount",
            "Закладкам" to "bookmarksCount",
            "Рейтингу" to "averageRating",
            "Дате добавления" to "createdAt",
        )
    }
}
