package ru.tachimanga.multisrc.senkuro

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import ru.tachimanga.common.RuSource
import ru.tachimanga.common.TriStateGroup
import ru.tachimanga.common.capitalizeRu
import ru.tachimanga.common.parseIsoDate
import java.util.concurrent.TimeUnit

/**
 * Senkuro and Senkognito share one GraphQL API made for Tachiyomi-style readers.
 * Manga url format: "<id>,,<slug>". Chapter url format: "<mangaId>,,<mangaSlug>,,<chapterId>,,<chapterSlug>".
 */
abstract class Senkuro : RuSource() {

    override val lang = "ru"

    override val supportsLatest = false

    protected abstract val appId: String

    override val loginHelp: String =
        "Для чтения вход не нужен. Если на сайте есть контент только для вошедших: откройте источник в WebView 🌐 " +
            "и войдите. Для 18+ контента используйте Senkognito."

    private val apiUrl: String
        get() = baseUrl.replace("https://", "https://api.") + "/graphql"

    override fun isOwnHost(host: String): Boolean = host.startsWith("api.senk")

    override fun configureClient(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", "Tachiyomi (+https://github.com/Sw1tchtaks/tachimanga-sources-for-russians)")
        .set("App-Id", appId)
        .set("App-Version", "060626")

    private val preferEnglish: Boolean get() = preferences.getBoolean(PREF_ENGLISH, false)

    private val jsonMedia = "application/json".toMediaType()

    private inline fun <reified V> graphQL(operation: String, query: String, variables: V): Request {
        val body = buildJsonObject {
            put("operationName", operation)
            put("query", query)
            put("variables", json.encodeToJsonElement(serializer<V>() as KSerializer<V>, variables))
        }
        return POST(apiUrl, headers, json.encodeToString(JsonObject.serializer(), body).toRequestBody(jsonMedia))
    }

    private inline fun <reified T> Response.parseGraphQL(): T {
        val root = use { json.parseToJsonElement(it.body.string()).jsonObject }
        val errors = root["errors"]
        val data = root["data"]
        if ((data == null || data is kotlinx.serialization.json.JsonNull) && errors != null) {
            val message = errors.jsonArray.firstOrNull()?.jsonObject?.get("message")?.jsonPrimitive?.content
            throw Exception("Senkuro: ${message ?: "ошибка API"}")
        }
        return json.decodeFromJsonElement(data!!)
    }

    // ============================== Popular ==============================
    override fun popularMangaRequest(page: Int): Request = searchRequest(page, SearchTachiyomiMangaVariables(
        orderBy = OrderByDto("DESC", "POPULARITY_SCORE"),
        offset = (page - 1) * OFFSET_COUNT,
    ))

    override fun popularMangaParse(response: Response): MangasPage = parseMangasPage(response)

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()

    private fun searchRequest(page: Int, variables: SearchTachiyomiMangaVariables): Request {
        loadFiltersInBackground()
        return graphQL("searchTachiyomiManga", SEARCH_QUERY, variables)
    }

    private fun parseMangasPage(response: Response): MangasPage {
        val data = response.parseGraphQL<TachiyomiSearchResponseDto>()
        val list = data.mangaTachiyomiSearch.mangas.map { it.toSManga(preferEnglish) }
        return MangasPage(list, list.size >= OFFSET_COUNT)
    }

    // ============================== Search ===============================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val labels = Pair(mutableListOf<String>(), mutableListOf<String>())
        fun ExcludeInclude.orNull() = takeIf { it.isNotEmpty() }
        var type: ExcludeInclude? = null
        var format: ExcludeInclude? = null
        var status: ExcludeInclude? = null
        var tStatus: ExcludeInclude? = null
        var rating: ExcludeInclude? = null
        var orderField = "POPULARITY_SCORE"
        var orderDirection = "DESC"

        filters.forEach { filter ->
            when (filter) {
                is OrderBy -> {
                    orderField = ORDER_FIELDS[filter.state?.index ?: 1]
                    orderDirection = if (filter.state?.ascending == true) "ASC" else "DESC"
                }
                is LabelGroup -> {
                    labels.first += filter.included
                    labels.second += filter.excluded
                }
                is TypeList -> type = ExcludeInclude(filter.included, filter.excluded).orNull()
                is FormatList -> format = ExcludeInclude(filter.included, filter.excluded).orNull()
                is StatusList -> status = ExcludeInclude(filter.included, filter.excluded).orNull()
                is TranslationList -> tStatus = ExcludeInclude(filter.included, filter.excluded).orNull()
                is AgeList -> rating = ExcludeInclude(filter.included, filter.excluded).orNull()
                else -> {}
            }
        }

        return searchRequest(page, SearchTachiyomiMangaVariables(
            query = query.trim().takeIf { it.isNotEmpty() },
            type = type,
            status = status,
            rating = rating,
            format = format,
            translationStatus = tStatus,
            label = ExcludeInclude(labels.first, labels.second).orNull(),
            orderBy = OrderByDto(orderDirection, orderField),
            offset = (page - 1) * OFFSET_COUNT,
        ))
    }

    override fun searchMangaParse(response: Response): MangasPage = parseMangasPage(response)

    // ============================== Details ==============================
    private fun mangaId(manga: SManga) = manga.url.split(",,").first()

    override fun mangaDetailsRequest(manga: SManga): Request =
        graphQL("fetchTachiyomiManga", DETAILS_QUERY, FetchTachiyomiMangaVariables(mangaId(manga)))

    override fun mangaDetailsParse(response: Response): SManga =
        response.parseGraphQL<TachiyomiMangaInfoResponseDto>().mangaTachiyomiInfo?.toSManga(preferEnglish)
            ?: throw Exception("Тайтл не найден")

    override fun getMangaUrl(manga: SManga): String {
        val slug = manga.url.split(",,").getOrNull(1) ?: return baseUrl
        return "$baseUrl/manga/$slug"
    }

    // ============================= Chapters ==============================
    override fun chapterListRequest(manga: SManga): Request =
        graphQL("fetchTachiyomiChapters", CHAPTERS_QUERY, FetchTachiyomiChaptersVariables(mangaId(manga)))
            .newBuilder().tag(String::class.java, manga.url).build()

    override fun chapterListParse(response: Response): List<SChapter> {
        val mangaUrl = response.request.tag(String::class.java).orEmpty()
        val data = response.parseGraphQL<TachiyomiChaptersResponseDto>().mangaTachiyomiChapters
        if (data.chapters.isEmpty() && !data.message.isNullOrBlank()) throw Exception(data.message)
        val teams = data.teams.associateBy { it.id }

        return data.chapters.map { chapter ->
            SChapter.create().apply {
                chapter_number = chapter.number.toFloatOrNull() ?: -1f
                name = buildString {
                    append("Том ${chapter.volume}. Глава ${chapter.number}")
                    chapter.name?.takeIf { it.isNotBlank() }?.let { append(" — ", it) }
                }
                url = "$mangaUrl,,${chapter.id},,${chapter.slug}"
                date_upload = parseIsoDate(chapter.updatedAt ?: chapter.createdAt)
                scanlator = chapter.teamIds.mapNotNull { teams[it]?.name }.joinToString().takeIf { it.isNotEmpty() }
            }
        }
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val parts = chapter.url.split(",,")
        val mangaSlug = parts.getOrNull(1) ?: return baseUrl
        val chapterSlug = parts.getOrNull(3) ?: return "$baseUrl/manga/$mangaSlug"
        return "$baseUrl/manga/$mangaSlug/chapters/$chapterSlug"
    }

    // =============================== Pages ===============================
    override fun pageListRequest(chapter: SChapter): Request {
        val parts = chapter.url.split(",,")
        return graphQL(
            "fetchTachiyomiChapterPages",
            PAGES_QUERY,
            FetchTachiyomiChapterPagesVariables(mangaId = parts.getOrNull(0).orEmpty(), chapterId = parts.getOrNull(2).orEmpty()),
        )
    }

    override fun pageListParse(response: Response): List<Page> =
        response.parseGraphQL<TachiyomiChapterPagesResponseDto>().mangaTachiyomiChapterPages.pages
            .mapIndexed { index, page -> Page(index, imageUrl = page.url) }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val imgHeaders = headersBuilder().set("Accept", "image/avif,image/webp,image/*,*/*;q=0.8").build()
        return eu.kanade.tachiyomi.network.GET(page.imageUrl!!, imgHeaders)
    }

    // ============================== Filters ==============================
    @Volatile
    private var labels: List<LabelDto>? = null

    @Volatile
    private var loadingFilters = false

    private fun loadFiltersInBackground() {
        if (labels != null || loadingFilters) return
        loadingFilters = true
        Thread {
            try {
                val response = client.newCall(graphQL("fetchTachiyomiSearchFilters", FILTERS_QUERY, EmptyObject)).execute()
                labels = response.parseGraphQL<TachiyomiSearchFiltersResponseDto>().mangaTachiyomiSearchFilters.labels
            } catch (_: Exception) {
            } finally {
                loadingFilters = false
            }
        }.start()
    }

    override fun getFilterList(): FilterList {
        val filters = mutableListOf<Filter<*>>(
            OrderBy(),
            TypeList(),
            FormatList(),
            StatusList(),
            TranslationList(),
            AgeList(),
        )
        val loaded = labels
        if (loaded.isNullOrEmpty()) {
            filters += Filter.Separator()
            filters += Filter.Header("Жанры и теги загрузятся после открытия каталога. Нажмите «Сбросить», чтобы обновить.")
        } else {
            fun group(name: String, rootId: String) = LabelGroup(
                name,
                loaded.filter { it.rootId == rootId }.map { label ->
                    (label.titles.find { it.lang == "RU" }?.content?.capitalizeRu() ?: label.slug) to label.slug
                }.sortedBy { it.first },
            )
            filters += group("Темы", "TEFCRUw6NQ")
            filters += group("Сеттинг", "TEFCRUw6NA")
            filters += group("Элементы", "TEFCRUw6Ng")
            filters += group("Черты", "TEFCRUw6Mw")
            filters += group("Демография", "TEFCRUw6Nw")
        }
        return FilterList(filters)
    }

    private class LabelGroup(name: String, options: List<Pair<String, String>>) : TriStateGroup(name, options)

    private class OrderBy : Filter.Sort("Сортировка", arrayOf("По рейтингу", "По популярности"), Selection(1, false))

    private class TypeList : TriStateGroup(
        "Тип",
        listOf("Манга" to "MANGA", "Манхва" to "MANHWA", "Маньхуа" to "MANHUA", "Комикс" to "COMICS", "OEL Манга" to "OEL_MANGA", "РуМанга" to "RU_MANGA"),
    )

    private class FormatList : TriStateGroup(
        "Формат",
        listOf(
            "Сборник" to "DIGEST", "Додзинси" to "DOUJINSHI", "В цвете" to "IN_COLOR", "Сингл" to "SINGLE",
            "Веб" to "WEB", "Вебтун" to "WEBTOON", "Ёнкома" to "YONKOMA", "Короткая" to "SHORT",
        ),
    )

    private class StatusList : TriStateGroup(
        "Статус",
        listOf("Анонс" to "ANNOUNCE", "Онгоинг" to "ONGOING", "Выпущено" to "FINISHED", "Приостановлено" to "HIATUS", "Отменено" to "CANCELLED"),
    )

    private class TranslationList : TriStateGroup(
        "Статус перевода",
        listOf("Переводится" to "IN_PROGRESS", "Завершён" to "FINISHED", "Заморожен" to "FROZEN", "Заброшен" to "ABANDONED"),
    )

    private class AgeList : TriStateGroup(
        "Возрастное ограничение",
        listOf("0+" to "GENERAL", "12+" to "SENSITIVE", "16+" to "QUESTIONABLE", "18+" to "EXPLICIT"),
    )

    // ============================ Preferences ============================
    override fun setupExtraPreferences(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ENGLISH
            title = "Английские названия"
            summary = "Показывать английское название вместо русского, если оно есть"
            setDefaultValue(false)
        }.let(screen::addPreference)
    }

    companion object {
        private const val OFFSET_COUNT = 10
        private const val PREF_ENGLISH = "senkuro_english_titles"
        private val ORDER_FIELDS = arrayOf("SCORE", "POPULARITY_SCORE")
    }
}
