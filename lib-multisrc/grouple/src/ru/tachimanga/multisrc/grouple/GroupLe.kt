package ru.tachimanga.multisrc.grouple

import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservable
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
import ru.tachimanga.common.RuSource
import ru.tachimanga.common.SelectFilter
import ru.tachimanga.common.TextFilter
import ru.tachimanga.common.TriStateGroup
import ru.tachimanga.common.parseDate
import rx.Observable
import java.io.IOException
import java.text.DecimalFormat
import java.util.Calendar
import java.util.Locale

/**
 * Sites on the Grouple engine (ReadManga, AllHentai, ...).
 * Login options, simplest first:
 *  1. login + password in the settings (the extension signs in by itself);
 *  2. WebView login;
 *  3. pasted browser cookies.
 */
abstract class GroupLe : RuSource() {

    override val lang = "ru"

    override val supportsLatest = true

    override val supportsToken = true

    protected open val siteId: Int = 0

    protected open val authApiUrl: String = "https://3.grouple.co"

    override val loginHelp: String =
        "Самый простой способ: включите «Автоматический вход» ниже и введите логин и пароль от сайта. " +
            "Или откройте источник в WebView 🌐 и войдите там. Если не получилось — вставьте сюда Cookie из браузера."

    override fun isOwnHost(host: String): Boolean = host == authApiUrl.toHttpUrl().host

    override fun configureClient(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder
        .addNetworkInterceptor(::redirectInterceptor)
        .addInterceptor(::autoLoginInterceptor)

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", preferences.getString(PREF_UA, UA_DEFAULT)?.ifBlank { UA_DEFAULT } ?: UA_DEFAULT)

    private val apiHeaders: Headers
        get() = headersBuilder()
            .set("Accept", "application/json, text/plain, */*")
            .set("X-Requested-With", "XMLHttpRequest")
            .build()

    // ============================== Interceptors ===============================
    private fun redirectInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val url = request.url.toString()
        if (url.contains(baseUrl) && (url.contains("internal/redirect") || response.code == 301)) {
            response.close()
            if (url.contains("api/catalog")) {
                throw IOException("Сайт переехал. Смените зеркало или домен в настройках источника $name")
            }
            throw IOException("Адрес тайтла изменился. Найдите его заново через поиск (миграция) в $name")
        }
        return response
    }

    private fun autoLoginInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val ext = request.url.pathSegments.lastOrNull()?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (ext in IMAGE_EXTENSIONS || request.header("Accept")?.contains("image") == true) return chain.proceed(request)

        val path = request.url.encodedPath
        if (path.contains("/internal/auth") || path.contains("/login/")) return chain.proceed(request)
        if (request.url.host.contains("usagi.one") || request.url.host.contains("ahen.me")) return chain.proceed(request)
        if (!autoAuth) return chain.proceed(request)

        val jar = client.cookieJar
        val baseHttpUrl = baseUrl.toHttpUrl()
        if (jar.loadForRequest(baseHttpUrl).any { it.name == "remember_me" }) return chain.proceed(request)

        val authHttpUrl = authApiUrl.toHttpUrl()
        val authResponse = chain.proceed(request.newBuilder().url("$baseUrl/internal/auth").get().build())
        val finalUrl = authResponse.request.url
        authResponse.close()

        if (finalUrl.host == authHttpUrl.host && authLogin.isNotBlank() && authPass.isNotBlank()) {
            val body = FormBody.Builder()
                .add("targetUri", "/login/continueSso?siteId=$siteId&targetUri=")
                .add("username", authLogin)
                .add("password", authPass)
                .add("remember_me", "true")
                .add("_remember_me_yes", "")
                .add("remember_me_yes", "on")
                .build()
            val loginRequest = request.newBuilder()
                .url("$authApiUrl/login/authenticate?ttt=${System.currentTimeMillis()}&siteId=$siteId")
                .headers(headers)
                .header("Origin", baseUrl)
                .post(body)
                .build()
            val loginResponse = chain.proceed(loginRequest)
            val loginUrl = loginResponse.request.url
            loginResponse.close()
            if (loginUrl.host == authHttpUrl.host || loginUrl.queryParameter("login_error") == "1") {
                preferences.edit().putBoolean(PREF_AUTO_AUTH, false).apply()
                throw IOException("Не удалось войти: проверьте логин и пароль. Автоматический вход выключен.")
            }
        }
        return chain.proceed(request)
    }

    // ============================== Catalog ===============================
    override fun popularMangaRequest(page: Int): Request = catalogRequest(page, "RATING", null, null)
    override fun popularMangaParse(response: Response): MangasPage = catalogParse(response)

    override fun latestUpdatesRequest(page: Int): Request = catalogRequest(page, "DATE_UPDATE", null, null)
    override fun latestUpdatesParse(response: Response): MangasPage = catalogParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        catalogRequest(page, defaultSortOrder, query, filters)

    override fun searchMangaParse(response: Response): MangasPage = catalogParse(response)

    private fun catalogRequest(page: Int, sortBy: String, query: String?, filters: FilterList?): Request {
        loadFiltersInBackground()
        val url = "$baseUrl/api/catalog/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("offset", (50 * (page - 1)).toString())
            query?.trim()?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("q", it) }
            var sortSet = false
            filters?.forEach { filter ->
                when (filter) {
                    is OrderBy -> {
                        addQueryParameter("sortType", filter.selected)
                        sortSet = true
                    }
                    is StatusFilter -> {
                        filter.included.forEach { addQueryParameter("includeProductionStatuses", it) }
                        filter.excluded.forEach { addQueryParameter("excludeProductionStatuses", it) }
                    }
                    is TranslationStatusFilter -> {
                        filter.included.forEach { addQueryParameter("includeTranslationStatuses", it) }
                        filter.excluded.forEach { addQueryParameter("excludeTranslationStatuses", it) }
                    }
                    is AdditionalFilters -> {
                        filter.included.forEach { addQueryParameter("includeSearchFilter", it) }
                        filter.excluded.forEach { addQueryParameter("excludeSearchFilter", it) }
                    }
                    is ElementGroup -> {
                        filter.included.forEach { addQueryParameter("includeElementIds", it) }
                        filter.excluded.forEach { addQueryParameter("excludeElementIds", it) }
                    }
                    is YearRangeFilter -> {
                        val min = filter.min.state.trim().toIntOrNull()
                        val max = filter.max.state.trim().toIntOrNull()
                        if (min != null || max != null) {
                            addQueryParameter("years", "${min ?: 1950},${max ?: Calendar.getInstance().get(Calendar.YEAR) + 1}")
                        }
                    }
                    else -> {}
                }
            }
            if (!sortSet) addQueryParameter("sortType", sortBy)
        }.build()
        return GET(url, apiHeaders)
    }

    private fun catalogParse(response: Response): MangasPage {
        val result = response.parseAs<SearchResponse>()
        return MangasPage(result.list.map { it.toSManga() }, result.offset + result.limit < result.total)
    }

    // ============================== Details ===============================
    protected open val tagsSelector: String =
        ".creation-element-tags .creation-element-tags__item:not(.creation-element-tags__item--misc) span:not(.text-secondary)"

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return parseMangaDetails(document)
    }

    protected open fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst(".cr-hero-names__main")?.text()
            ?: document.selectFirst("meta[itemprop=name]")?.attr("content")
            ?: document.selectFirst("h1")?.text().orEmpty()

        val details = mutableMapOf<String, String>()
        document.selectFirst(".cr-hero .cr-info-details")?.children()?.forEach { element ->
            val key = element.selectFirst(".cr-info-details-item__title")?.text()?.lowercase(Locale.ROOT).orEmpty()
            val value = element.selectFirst(".cr-info-details-item__status")?.text()?.lowercase(Locale.ROOT).orEmpty()
            if (key.isNotEmpty() && value.isNotEmpty() && key !in details) details[key] = value
        }
        val release = details["выпуск"].orEmpty()
        val translation = details["перевод"].orEmpty()
        status = when {
            release.contains("продолж") || release.contains("начат") -> SManga.ONGOING
            release.contains("заверш") -> if (translation.contains("заверш")) SManga.COMPLETED else SManga.PUBLISHING_FINISHED
            release.contains("приост") || release.contains("заморож") -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }

        val authors = mutableListOf<String>()
        val artists = mutableListOf<String>()
        document.select(".cr-main-person-item").forEach { person ->
            val role = person.selectFirst(".cr-main-person-item__role")?.text()?.lowercase(Locale.ROOT).orEmpty()
            val names = person.select(".cr-main-person-item__name a").map { it.text() }
                .ifEmpty { person.select(".cr-main-person-item__name").map { it.text() } }
            when {
                role.contains("автор") || role.contains("сценар") -> authors += names
                role.contains("худож") || role.contains("иллюст") -> artists += names
            }
        }
        author = authors.distinct().joinToString().ifBlank { null }
        artist = artists.distinct().joinToString().ifBlank { null }

        val category = document.selectFirst(".cr-hero-short-details a[href*=\"/list/category/\"]")?.text().orEmpty()
        val age = normalizeAge(document.selectFirst(".cr-hero-short-details a[href*=\"/list/limitation/\"]")?.text().orEmpty())
        genre = (listOf(category, age) + document.select(tagsSelector).map { it.text() })
            .filter { it.isNotBlank() }.map { it.lowercase(Locale.ROOT) }.distinct().joinToString()

        val altNames = document.select("#alt-names-dialog .modal-body .py-1").mapNotNull { it.text().ifBlank { null } }.distinct()
        val rating = document.selectFirst(".cr-hero-rating .cr-hero-rating__value")?.text()?.toFloatOrNull()
        val votes = document.selectFirst(".cr-hero-rating__text")?.text()?.filter { it.isDigit() }.orEmpty()
        description = buildString {
            rating?.let { append("Рейтинг: $it из 10") ; if (votes.isNotEmpty()) append(" (голосов: $votes)") }
            document.selectFirst(".cr-description__content")?.text()?.let {
                if (isNotEmpty()) append("\n\n")
                append(it)
            }
            if (altNames.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Альтернативные названия:\n")
                append(altNames.joinToString("\n") { "• $it" })
            }
        }

        val thumb = document.selectFirst(".cr-hero-poster__img") ?: document.selectFirst(".cr-hero-overlay__bg")
        thumbnail_url = thumb?.let {
            it.absUrl("src").ifEmpty { it.absUrl("data-src") }.ifEmpty { it.absUrl("data-original") }.ifEmpty { it.absUrl("data-bg") }
        }
    }

    protected fun normalizeAge(raw: String): String = when (raw) {
        "NC-17", "R18+" -> "18+"
        "R", "G", "PG" -> "16+"
        "PG-13" -> "12+"
        else -> raw
    }

    // ============================== Chapters ===============================
    override fun chapterListRequest(manga: SManga): Request =
        GET(baseUrl + manga.url, headers).newBuilder().tag(String::class.java, manga.title).build()

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val mangaTitle = response.request.tag(String::class.java).orEmpty()
        authGuard(document)
        if (document.selectFirst(".alert.alert-warning:contains(Запрещена публикация произведения по копирайту)") != null) {
            throw Exception("Лицензировано — главы удалены по требованию правообладателя.")
        }
        val params = chapterSearchParams(document)

        return document.select("tr.item-row:has(td > a):has(td.date:not(.text-info))").mapNotNull { row ->
            val link = row.selectFirst("a.chapter-link") ?: return@mapNotNull null
            SChapter.create().apply {
                setUrlWithoutDomain(link.absUrl("href") + params)
                scanlator = link.attr("title").takeIf { it.isNotBlank() }
                    ?.replace("(Переводчик),", "&")
                    ?.replace("Переводчик,", "&")
                    ?.removeSuffix(" (Переводчик)")
                    ?.removeSuffix(" Переводчик")
                chapter_number = row.selectFirst("td.item-title")?.attr("data-num")?.toFloatOrNull()?.div(10) ?: -1f

                var chapterName = link.ownText().trim()
                if (mangaTitle.length > 25) {
                    for (word in mangaTitle.split(' ')) chapterName = chapterName.removePrefix(word).trim()
                }
                val dots = chapterName.indexOf("…")
                val firstDigit = chapterName.indexOfFirst { it.isDigit() }.coerceAtLeast(0)
                if (dots in 0 until firstDigit) chapterName = chapterName.substringAfter("…").trim()
                val number = DecimalFormat("#,###.##").format(chapter_number).replace(",", ".")
                when {
                    EXTRA_REGEX.containsMatchIn(chapterName) && chapterName.substringAfter("Экстра").isBlank() ->
                        chapterName = chapterName.replaceFirst(" ", " - $number ")
                    SINGLE_REGEX.containsMatchIn(chapterName) && chapterName.substringAfter("Сингл").isBlank() ->
                        chapterName = "$number $chapterName"
                }
                name = chapterName.ifBlank { "Глава $number" }
                date_upload = parseDate(row.select("td.d-none").last()?.text(), "dd.MM.yy", "d.MM.yy")
            }
        }
    }

    private fun chapterSearchParams(document: Document): String {
        val script = document.selectFirst("script:containsData(user_hash)")?.data()
        val hash = script?.let { USER_HASH_REGEX.find(it)?.groupValues?.get(1) }
        return if (hash != null) {
            if (userHash != hash) preferences.edit().putString(PREF_USER_HASH, hash).apply()
            "?d=$hash&mtr=true"
        } else {
            "?mtr=true"
        }
    }

    protected open fun authGuard(document: Document) {
        val settings = document.selectFirst("script:containsData(viewSettings)")?.data() ?: return
        val blocked = BLOCKED_ANON_REGEX.find(settings)?.value?.contains(ALLOW_ANON_REGEX) ?: false
        if (blocked && document.selectFirst("script:containsData(window.current_user_id)") == null) {
            throw Exception("Этот тайтл доступен только после входа. Войдите через WebView 🌐 или включите автоматический вход в настройках источника.")
        }
    }

    // ============================== Pages ===============================
    override fun pageListRequest(chapter: SChapter): Request {
        var url = (baseUrl + chapter.url).toHttpUrl()
        val hash = userHash
        if (hash != "none" && !url.queryParameterNames.contains("d")) {
            url = url.newBuilder().setQueryParameter("d", hash).build()
        }
        return GET(url, headers)
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> =
        client.newCall(pageListRequest(chapter)).asObservable().map { response ->
            if (!response.isSuccessful) {
                response.close()
                if (response.code == 404) {
                    throw Exception("Для этой главы нужен вход в аккаунт (ошибка 404). Войдите через WebView 🌐 и обновите тайтл.")
                }
                throw Exception("HTTP ошибка ${response.code}")
            }
            if (!response.request.url.toString().contains(baseUrl)) {
                response.close()
                throw Exception("Не удалось загрузить главу: сайт перенаправил на ${response.request.url.host}")
            }
            pageListParse(response)
        }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        if (document.selectFirst("form.purchase-form") != null) {
            throw Exception("Эта глава платная. Купите её на сайте, чтобы прочитать.")
        }
        if (document.selectFirst("h1:contains(требуется премиум)") != null) {
            throw Exception("Для доступа к главе нужна премиум-подписка.")
        }
        val html = document.selectFirst("script:containsData(chapterInfo)")?.data()
            ?: document.selectFirst("script:containsData(rm_h.readerInit)")?.data()
            ?: document.selectFirst("script:containsData(rm_h.readerDoInit)")?.data()
            ?: throw Exception(
                if (document.selectFirst("div.alert") != null) {
                    "Глава недоступна: ${document.selectFirst("div.alert")!!.text()}"
                } else {
                    "Не удалось найти страницы. Возможно, нужен вход или сайт обновил дизайн."
                },
            )
        val mark = when {
            html.contains("rm_h.readerInit(") -> "rm_h.readerInit("
            html.contains("rm_h.readerDoInit(") -> "rm_h.readerDoInit("
            else -> throw Exception("Сайт обновил читалку, нужно обновить расширение.")
        }
        val begin = html.indexOf(mark)
        val end = html.indexOf(");", begin).takeIf { it > begin } ?: html.length
        val trimmed = html.substring(begin, end)

        return PAGES_REGEX.findAll(trimmed).mapIndexed { index, result ->
            val host = result.groupValues[1]
            val middle = result.groupValues[2]
            val tail = result.groupValues[3]
            var imageUrl = when {
                middle.isBlank() && tail.startsWith("/static/") -> baseUrl + tail
                middle.endsWith("/manga/") -> host + tail
                else -> middle + host + tail
            }
            if (!imageUrl.contains("://")) imageUrl = "https:$imageUrl"
            if (imageUrl.contains("one-way.work")) imageUrl = imageUrl.substringBefore("?")
            Page(index, imageUrl = imageUrl.replace("//resh", "//h"))
        }.toList()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, headersBuilder().set("Accept", "image/avif,image/webp,image/*,*/*;q=0.8").build())

    // ============================== Filters ===============================
    protected open val defaultSortOrder = "RATING"

    @Volatile
    protected var filtersData: FiltersData? = null

    @Volatile
    private var filtersLoading = false

    private fun loadFiltersInBackground() {
        if (filtersData != null || filtersLoading) return
        filtersLoading = true
        Thread {
            try {
                filtersData = fetchFilters()
            } catch (_: Exception) {
            } finally {
                filtersLoading = false
            }
        }.start()
    }

    protected open fun fetchFilters(): FiltersData {
        val document = client.newCall(GET("$baseUrl/search/advanced", headers)).execute().asJsoup()
        val tags = runCatching { fetchTags() }.getOrNull()
        val data = document.selectFirst("script:containsData(window.__FILTERS)")?.data()
            ?: throw Exception("Не удалось найти фильтры")
        val raw = FILTERS_REGEX.findAll(data).associate { it.groupValues[1] to it.groupValues[2] }
        fun map(key: String) = raw[key]?.let { runCatching { it.parseAs<Map<String, String>>() }.getOrNull() }
            ?.map { it.value to it.key }.orEmpty()
        val years = raw["years"]?.let { runCatching { it.replace(CHECK_JSON, "\"$1\":").parseAs<YearsData>() }.getOrNull() }
        return FiltersData(
            sortType = map("sortType"),
            productionStatus = map("productionStatus"),
            translationStatus = map("translationStatus"),
            searchFilters = map("searchFilters"),
            genre = map("genre"),
            category = map("category"),
            limitation = map("limitation"),
            another = map("another"),
            tags = tags.orEmpty(),
            years = years,
        )
    }

    protected fun fetchTags(): List<Pair<String, String>> =
        client.newCall(GET("$baseUrl/api/catalog/elementsByType?type=40", apiHeaders)).execute()
            .parseAs<FiltersApiResponse>().results.orEmpty().map { it.text to it.id }

    override fun getFilterList(): FilterList {
        val data = filtersData
        val filters = mutableListOf<Filter<*>>()
        if (data == null) {
            filters += Filter.Header("Фильтры загрузятся после открытия каталога. Нажмите «Сбросить», чтобы обновить.")
            return FilterList(filters)
        }
        if (data.sortType.isNotEmpty()) filters += OrderBy(data.sortType, defaultSortOrder)
        if (data.genre.isNotEmpty()) filters += ElementGroup("Жанры", data.genre)
        if (data.tags.isNotEmpty()) filters += ElementGroup("Теги", data.tags)
        if (data.category.isNotEmpty()) filters += ElementGroup("Категории", data.category)
        if (data.productionStatus.isNotEmpty()) filters += StatusFilter(data.productionStatus)
        if (data.translationStatus.isNotEmpty()) filters += TranslationStatusFilter(data.translationStatus)
        if (data.limitation.isNotEmpty()) filters += ElementGroup("Возрастная рекомендация", data.limitation)
        if (data.another.isNotEmpty()) filters += ElementGroup("Прочее", data.another)
        if (data.searchFilters.isNotEmpty()) filters += AdditionalFilters(data.searchFilters)
        filters += YearRangeFilter()
        return FilterList(filters)
    }

    protected class OrderBy(options: List<Pair<String, String>>, default: String) :
        SelectFilter("Сортировать по", options, options.indexOfFirst { it.second == default }.coerceAtLeast(0))

    protected class ElementGroup(name: String, options: List<Pair<String, String>>) : TriStateGroup(name, options)
    protected class StatusFilter(options: List<Pair<String, String>>) : TriStateGroup("Статус выхода", options)
    protected class TranslationStatusFilter(options: List<Pair<String, String>>) : TriStateGroup("Статус перевода", options)
    protected class AdditionalFilters(options: List<Pair<String, String>>) : TriStateGroup("Фильтры", options)

    protected class YearRangeFilter : Filter.Group<TextFilter>("Год выпуска", listOf(TextFilter("От"), TextFilter("До"))) {
        val min get() = state[0]
        val max get() = state[1]
    }

    // ============================== Preferences ===============================
    private val userHash: String get() = preferences.getString(PREF_USER_HASH, "none") ?: "none"
    private val autoAuth: Boolean get() = preferences.getBoolean(PREF_AUTO_AUTH, false)
    private val authLogin: String get() = preferences.getString(PREF_LOGIN, "").orEmpty()
    private val authPass: String get() = preferences.getString(PREF_PASSWORD, "").orEmpty()

    override fun setupExtraPreferences(screen: PreferenceScreen) {
        val context = screen.context
        SwitchPreferenceCompat(context).apply {
            key = PREF_AUTO_AUTH
            title = "Автоматический вход"
            summary = "Расширение само войдёт в аккаунт с логином и паролем ниже"
            setDefaultValue(false)
        }.let(screen::addPreference)

        EditTextPreference(context).apply {
            key = PREF_LOGIN
            title = "Логин"
            dialogTitle = "Логин (e-mail или имя пользователя)"
            summary = authLogin.ifBlank { "Не задан" }
            setDefaultValue("")
            setOnPreferenceChangeListener { pref, value ->
                pref.summary = (value as String).ifBlank { "Не задан" }
                true
            }
        }.let(screen::addPreference)

        EditTextPreference(context).apply {
            key = PREF_PASSWORD
            title = "Пароль"
            dialogTitle = "Пароль"
            summary = if (authPass.isBlank()) "Не задан" else "••••••"
            setDefaultValue("")
            setOnPreferenceChangeListener { pref, value ->
                pref.summary = if ((value as String).isBlank()) "Не задан" else "••••••"
                true
            }
        }.let(screen::addPreference)

        EditTextPreference(context).apply {
            key = PREF_UA
            title = "User-Agent"
            dialogTitle = "User-Agent (менять только если сайт не открывается)"
            summary = preferences.getString(PREF_UA, UA_DEFAULT)?.ifBlank { UA_DEFAULT } ?: UA_DEFAULT
            setDefaultValue(UA_DEFAULT)
            setOnPreferenceChangeListener { pref, value ->
                pref.summary = (value as String).ifBlank { UA_DEFAULT } + "\nПерезапустите приложение."
                true
            }
        }.let(screen::addPreference)
    }

    protected fun Response.asJsoup(): Document = use { Jsoup.parse(it.body.string(), it.request.url.toString()) }

    // ============================== DTOs ===============================
    @Serializable
    protected class SearchResponse(
        val total: Int = 0,
        val offset: Int = 0,
        val limit: Int = 50,
        val list: List<Item> = emptyList(),
    ) {
        @Serializable
        class Item(val name: String, val picUrl: String? = null, val elementId: ElementId) {
            fun toSManga() = SManga.create().apply {
                title = name
                thumbnail_url = picUrl
                url = "/${elementId.linkName}"
            }
        }

        @Serializable
        class ElementId(val linkName: String)
    }

    protected class FiltersData(
        val sortType: List<Pair<String, String>> = emptyList(),
        val productionStatus: List<Pair<String, String>> = emptyList(),
        val translationStatus: List<Pair<String, String>> = emptyList(),
        val searchFilters: List<Pair<String, String>> = emptyList(),
        val genre: List<Pair<String, String>> = emptyList(),
        val category: List<Pair<String, String>> = emptyList(),
        val limitation: List<Pair<String, String>> = emptyList(),
        val another: List<Pair<String, String>> = emptyList(),
        val tags: List<Pair<String, String>> = emptyList(),
        val years: YearsData? = null,
    )

    @Serializable
    protected class YearsData(val min: Int? = null, val max: Int? = null)

    @Serializable
    protected class FiltersApiResponse(val results: List<Element>? = null) {
        @Serializable
        class Element(val text: String, val id: String)
    }

    companion object {
        private const val UA_DEFAULT = "arora"
        private const val PREF_UA = "grouple_user_agent"
        private const val PREF_USER_HASH = "user_hash"
        private const val PREF_AUTO_AUTH = "auto_authorization"
        private const val PREF_LOGIN = "auto_authorization_login"
        private const val PREF_PASSWORD = "auto_authorization_password"
        private val USER_HASH_REGEX = "user_hash.+'(.+)'".toRegex()
        private val EXTRA_REGEX = Regex("""\s*([0-9]+\sЭкстра)\s*""")
        private val SINGLE_REGEX = Regex("""\s*Сингл\s*""")
        private val FILTERS_REGEX = """window\.__FILTERS\.(\w+)\s*=\s*([{].*?[}]);""".toRegex()
        private val PAGES_REGEX = """\[['"](.*?)['"],['"](.*?)['"],['"](.*?)['"].*?]""".toRegex()
        private val BLOCKED_ANON_REGEX = """\{[^{}]*?['"]blockedForAnonymous['"][^{}]*?\}""".toRegex()
        private val ALLOW_ANON_REGEX = """enabled:\s*true""".toRegex()
        private val CHECK_JSON = """(\w+)\s*:""".toRegex()
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "svg")
    }
}
