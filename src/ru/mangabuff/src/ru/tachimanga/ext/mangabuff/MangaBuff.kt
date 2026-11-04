package ru.tachimanga.ext.mangabuff

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import ru.tachimanga.common.CheckBoxGroup
import ru.tachimanga.common.RuSource
import ru.tachimanga.common.SelectFilter
import ru.tachimanga.common.TriStateGroup
import ru.tachimanga.common.parseDate
import rx.Observable
import java.io.IOException
import java.util.Calendar
import java.util.Locale

class MangaBuff : RuSource() {

    override val name = "MangaBuff"

    override val lang = "ru"

    override val supportsLatest = true

    override val mirrors = listOf(
        "Основной" to "https://mangabuff.ru",
        "Зеркало wss" to "https://wss.mangabuff.ru",
        "Зеркало wss2" to "https://wss2.mangabuff.ru",
    )

    override val loginHelp: String =
        "Вход нужен только для контента 18+ и закладок. Откройте источник в WebView 🌐 и войдите на сайте. " +
            "Если не получилось — вставьте сюда Cookie из браузера."

    override fun isOwnHost(host: String): Boolean = host.endsWith("mangabuff.ru")

    override val authCookieNames = listOf("remember_web_")

    override fun configureClient(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        builder.addInterceptor(::csrfInterceptor)

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", USER_AGENT)

    private val highQualityCovers: Boolean get() = preferences.getBoolean(PREF_HQ_COVERS, true)

    // ============================== CSRF ===============================
    @Volatile
    private var csrfToken: String? = null

    private fun csrfInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "POST" || request.header("X-CSRF-TOKEN") != null) return chain.proceed(request)

        fun withToken(token: String) = request.newBuilder()
            .header("X-Requested-With", "XMLHttpRequest")
            .header("X-CSRF-TOKEN", token)
            .build()

        val response = chain.proceed(withToken(token(chain)))
        if (response.code != 419) return response
        response.close()
        csrfToken = null
        return chain.proceed(withToken(token(chain)))
    }

    private fun token(chain: Interceptor.Chain): String {
        csrfToken?.let { return it }
        val response = chain.proceed(GET(baseUrl, headers))
        val token = response.asJsoup().select("head meta[name*=csrf-token]").attr("content")
        if (token.isEmpty()) throw IOException("MangaBuff: не удалось получить CSRF-токен")
        csrfToken = token
        return token
    }

    // ============================== Catalog ===============================
    override fun popularMangaRequest(page: Int): Request = catalogRequest(page, "real_views", null)
    override fun popularMangaParse(response: Response): MangasPage = parseCatalog(response.asJsoup())

    override fun latestUpdatesRequest(page: Int): Request = catalogRequest(page, "updated_at", null)
    override fun latestUpdatesParse(response: Response): MangasPage = parseCatalog(response.asJsoup())

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val q = query.trim()
        if (q.isNotEmpty()) {
            if (q.length < 3) throw Exception("Запрос должен содержать не менее 3 символов")
            val url = "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("type", "manga")
                .addQueryParameter("q", q)
                .addQueryParameter("query", q)
                .apply { if (page > 1) addQueryParameter("page", page.toString()) }
                .build()
            return GET(url, headers)
        }
        return catalogRequest(page, "real_views", filters)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseCatalog(response.asJsoup())

    private fun catalogRequest(page: Int, sortBy: String, filters: FilterList?): Request {
        loadFiltersInBackground()
        val url = "$baseUrl/manga".toHttpUrl().newBuilder().apply {
            var sortSet = false
            filters?.forEach { filter ->
                when (filter) {
                    is GenreFilter -> {
                        filter.included.forEach { addQueryParameter("genres[]", it) }
                        filter.excluded.forEach { addQueryParameter("without_genres[]", it) }
                    }
                    is TypeFilter -> {
                        filter.included.forEach { addQueryParameter("type_id[]", it) }
                        filter.excluded.forEach { addQueryParameter("without_type_id[]", it) }
                    }
                    is TagFilter -> {
                        filter.included.forEach { addQueryParameter("tags[]", it) }
                        filter.excluded.forEach { addQueryParameter("without_tags[]", it) }
                    }
                    is StatusFilter -> filter.checked.forEach { addQueryParameter("status_id[]", it) }
                    is AgeFilter -> filter.checked.forEach { addQueryParameter("age_rating[]", it) }
                    is RatingFilter -> filter.checked.forEach { addQueryParameter("rating[]", it) }
                    is YearFilter -> filter.checked.forEach { addQueryParameter("year[]", it) }
                    is ChapterCountFilter -> filter.checked.forEach { addQueryParameter("chapters[]", it) }
                    is SortFilter -> {
                        addQueryParameter("sort", filter.selected)
                        sortSet = true
                    }
                    else -> {}
                }
            }
            if (!sortSet) addQueryParameter("sort", sortBy)
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()
        return GET(url, headers)
    }

    private fun parseCatalog(document: Document): MangasPage {
        val mangas = document.select(".cards .cards__item, a.cards__item").distinctBy { it.absUrl("href") }.mapNotNull { element ->
            val link = if (element.tagName() == "a") element else element.selectFirst("a") ?: return@mapNotNull null
            val href = link.absUrl("href").ifEmpty { return@mapNotNull null }
            SManga.create().apply {
                setUrlWithoutDomain(href)
                title = element.selectFirst(".cards__name")?.text() ?: return@mapNotNull null
                val style = element.selectFirst(".cards__img")?.attr("style").orEmpty()
                val fromStyle = BG_IMAGE_REGEX.find(style)?.groupValues?.get(1)
                val slug = href.toHttpUrl().pathSegments.lastOrNull { it.isNotBlank() }.orEmpty()
                thumbnail_url = posterUrl(fromStyle ?: "/img/manga/posters/$slug.jpg")
            }
        }
        val hasNext = document.selectFirst(".pagination .pagination__button a:contains(Вперёд), .pagination a[rel=next]") != null
        return MangasPage(mangas, hasNext)
    }

    /** Small covers live under /x180/, full size covers without it. */
    private fun posterUrl(url: String): String {
        val path = when {
            url.contains("/x180/img/manga/posters/") -> url.substring(url.indexOf("/x180/") + "/x180".length)
            url.contains("/img/manga/posters/") -> url.substring(url.indexOf("/img/manga/posters/"))
            url.startsWith("http") -> return url
            else -> return baseUrl + (if (url.startsWith("/")) url else "/$url")
        }
        return if (highQualityCovers) "$baseUrl$path" else "$baseUrl/x180$path"
    }

    // ============================== Details ===============================
    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> =
        client.newCall(mangaDetailsRequest(manga)).asObservable404().map { mangaDetailsParse(it) }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1, .manga__name, .manga-mobile__name")?.text().orEmpty()
            description = buildString {
                (document.selectFirst(".manga__description")?.text()
                    ?: document.selectFirst("meta[property=og:description]")?.attr("content"))
                    ?.let { append(it) }
                document.selectFirst(".manga__rating")?.text()?.toDoubleOrNull()?.let {
                    if (isNotEmpty()) append("\n\n")
                    append(String.format(Locale("ru"), "Рейтинг: %.0f%%", it * 10))
                }
                document.selectFirst(".manga__views")?.text()?.replace(" ", "")?.toIntOrNull()?.let {
                    if (isNotEmpty()) append("\n")
                    append(String.format(Locale("ru"), "Просмотров: %,d", it))
                }
                document.select(".manga__name-alt > span, .manga-mobile__name-alt > span").eachText().distinct()
                    .takeIf { it.isNotEmpty() }?.let {
                        if (isNotEmpty()) append("\n\n")
                        append("Альтернативные названия:\n")
                        append(it.joinToString("\n") { name -> "• $name" })
                    }
            }
            genre = (
                document.select(".manga__middle-links > a:not(:last-child)").eachText() +
                    document.select(".manga-mobile__info > a:not(:last-child)").eachText() +
                    document.select(".tags > .tags__item, .manga__middle .tags .tags__item").eachText()
                ).map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString().ifBlank { null }
            status = when (
                document.select(".manga__middle-links > a:last-child, .manga-mobile__info > a:last-child")
                    .firstOrNull()?.text()?.lowercase()
            ) {
                "завершен" -> SManga.COMPLETED
                "продолжается" -> SManga.ONGOING
                "заморожен" -> SManga.ON_HIATUS
                "заброшен" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
            val cover = document.selectFirst(".manga__img img, img.manga-mobile__image")?.absUrl("src")
                ?.ifEmpty { null } ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            thumbnail_url = cover?.let { posterUrl(it) }
        }
    }

    // ============================== Chapters ===============================
    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> =
        client.newCall(chapterListRequest(manga)).asObservable404().map { chapterListParse(it) }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = document.select("a.chapters__item").map(::chapterFromElement).toMutableList()

        // The page shows only 100 chapters, the rest come from the API.
        if (document.selectFirst(".load-chapters-trigger") != null) {
            val mangaId = document.selectFirst(".manga")?.attr("data-id")
            if (!mangaId.isNullOrEmpty()) {
                val body = FormBody.Builder().add("manga_id", mangaId).build()
                val more = client.newCall(POST("$baseUrl/chapters/load", headers, body)).execute()
                    .parseAs<LoadDto>().content
                    .let { Jsoup.parseBodyFragment(it, baseUrl) }
                    .select("a.chapters__item")
                    .map(::chapterFromElement)
                chapters += more
            }
        }
        return chapters.distinctBy { it.url }
    }

    private fun chapterFromElement(element: Element) = SChapter.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        name = element.select(".chapters__volume, .chapters__value, .chapters__name").text().ifBlank { "Глава" }
        date_upload = parseDate(
            element.attr("data-chapter-date").ifBlank { element.selectFirst(".chapters__add-date")?.text() },
            "dd.MM.yyyy",
        )
        chapter_number = element.attr("data-chapter").toFloatOrNull()
            ?: element.select(".chapters__value").text().substringAfter(" ").toFloatOrNull()
            ?: -1f
    }

    // ============================== Pages ===============================
    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val images = document.select(".reader__pages img, .reader__item img")
            .map { it.absUrl("data-src").ifEmpty { it.absUrl("src") } }
            .filter { it.isNotEmpty() }
            .distinct()
        if (images.isEmpty()) throw Exception("Страницы не найдены. Возможно, глава доступна только после входа через WebView 🌐")
        return images.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, headersBuilder().set("Accept", "image/avif,image/webp,image/*,*/*;q=0.8").build())

    // ============================== Filters ===============================
    @Volatile
    private var genres: List<Pair<String, String>>? = null

    @Volatile
    private var tags: List<Pair<String, String>>? = null

    @Volatile
    private var filtersLoading = false

    private fun loadFiltersInBackground() {
        if (genres != null || filtersLoading) return
        filtersLoading = true
        Thread {
            try {
                val doc = client.newCall(GET("$baseUrl/manga", headers)).execute().asJsoup()
                tags = doc.select(".sl-select[name=\"tags[]\"] option").map { it.text() to it.attr("value") }
                genres = doc.select(".sl-select[name=\"genres[]\"] option").map { it.text() to it.attr("value") }
            } catch (_: Exception) {
            } finally {
                filtersLoading = false
            }
        }.start()
    }

    override fun getFilterList(): FilterList {
        val filters = mutableListOf<Filter<*>>(
            Filter.Header("Фильтры не работают вместе с поиском по названию"),
            Filter.Separator(),
            SortFilter(),
        )
        genres?.takeIf { it.isNotEmpty() }?.let { filters += GenreFilter(it) }
        tags?.takeIf { it.isNotEmpty() }?.let { filters += TagFilter(it) }
        if (genres == null) filters += Filter.Header("Жанры и теги загрузятся после открытия каталога. Нажмите «Сбросить».")
        filters += listOf(TypeFilter(), StatusFilter(), AgeFilter(), RatingFilter(), YearFilter(), ChapterCountFilter())
        return FilterList(filters)
    }

    private class SortFilter : SelectFilter(
        "Сортировать по",
        listOf("Популярные" to "real_views", "Обновленные" to "updated_at", "По рейтингу" to "rating", "По новинкам" to "created_at"),
    )

    private class GenreFilter(options: List<Pair<String, String>>) : TriStateGroup("Жанр", options)
    private class TagFilter(options: List<Pair<String, String>>) : TriStateGroup("Теги", options)

    private class TypeFilter : TriStateGroup(
        "Тип",
        listOf(
            "Манга" to "1", "OEL-манга" to "2", "Манхва" to "3", "Маньхуа" to "4",
            "Сингл" to "5", "Руманга" to "6", "Комикс западный" to "7",
        ),
    )

    private class StatusFilter : CheckBoxGroup(
        "Статус",
        listOf("Завершен" to "1", "Продолжается" to "2", "Заморожен" to "3", "Заброшен" to "4"),
    )

    private class AgeFilter : CheckBoxGroup("Возрастной рейтинг", listOf("18+" to "18+", "16+" to "16+"))

    private class RatingFilter : CheckBoxGroup(
        "Рейтинг",
        (5..9).map { "Рейтинг ${it}0%+" to it.toString() },
    )

    private class YearFilter : CheckBoxGroup(
        "Год выпуска",
        (Calendar.getInstance().get(Calendar.YEAR) downTo 1986).map { it.toString() to it.toString() },
    )

    private class ChapterCountFilter : CheckBoxGroup(
        "Количество глав",
        listOf("<50" to "0", "50-100" to "50", "100-200" to "100", ">200" to "200"),
    )

    // ============================== Preferences ===============================
    override fun setupExtraPreferences(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_HQ_COVERS
            title = "Обложки высокого качества"
            summary = "Загружать полноразмерные обложки вместо уменьшенных"
            setDefaultValue(true)
        }.let(screen::addPreference)
    }

    // ============================== Utils ===============================

    private fun okhttp3.Call.asObservable404(): Observable<Response> = asObservableSuccessOr404()

    private fun okhttp3.Call.asObservableSuccessOr404(): Observable<Response> =
        Observable.fromCallable {
            val response = execute()
            if (response.code == 404) {
                response.close()
                throw Exception("Тайтл недоступен. Возможно, нужен вход через WebView 🌐")
            }
            if (!response.isSuccessful) {
                response.close()
                throw Exception("HTTP ошибка ${response.code}")
            }
            response
        }

    private fun Response.asJsoup(): Document = use { Jsoup.parse(it.body.string(), it.request.url.toString()) }

    @Serializable
    private class LoadDto(val content: String = "")

    companion object {
        private const val PREF_HQ_COVERS = "mangabuff_hq_covers"
        private const val USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
        private val BG_IMAGE_REGEX = """url\(['"]?([^'")]+)['"]?\)""".toRegex()
    }
}
