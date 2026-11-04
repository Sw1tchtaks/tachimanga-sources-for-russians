package ru.tachimanga.ext.mangalib

import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import ru.tachimanga.common.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.JsonElement
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import ru.tachimanga.common.CheckBoxGroup
import ru.tachimanga.common.POST
import ru.tachimanga.common.RuSource
import ru.tachimanga.common.SelectFilter
import ru.tachimanga.common.TriStateGroup
import ru.tachimanga.common.bool
import ru.tachimanga.common.get
import ru.tachimanga.common.int
import ru.tachimanga.common.list
import ru.tachimanga.common.long
import ru.tachimanga.common.str
import ru.tachimanga.common.parseIsoDate
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * MangaLib (LibSocial API). Manga url = "/<slug_url>". Chapter url = "/<slug_url>/chapter?...".
 */
class MangaLib : RuSource() {

    override val name = "MangaLib"

    override val lang = "ru"

    override val supportsLatest = true

    override val supportsToken = true

    override val mirrors = listOf("Основной" to "https://mangalib.me")

    override val loginHelp: String =
        "1) Включите «Режим входа» ниже. 2) Откройте MangaLib, нажмите 🌐 и войдите в аккаунт. 3) Закройте WebView и выключите «Режим входа». " +
            "Расширение получит вход само. Другой способ — логин и пароль ниже. Без входа доступно почти всё, кроме 18+."

    private val siteId = 1

    @Suppress("PrivatePropertyName")
    private val AUTH_URL: String get() = preferences.getString(PREF_AUTH_SERVER, AUTH_DEFAULT) ?: AUTH_DEFAULT

    private val loginMode: Boolean get() = preferences.getBoolean(PREF_LOGIN_MODE, false)

    /** The real site address, even while the login mode is on. */
    private val siteUrl: String get() = super.baseUrl

    // While the login mode is on, the WebView button 🌐 opens the login server itself,
    // so the app shares its cookies with the extension.
    override val baseUrl: String get() = if (loginMode) AUTH_URL else super.baseUrl

    private val apiUrl: String get() = preferences.getString(PREF_API, API_DEFAULT) ?: API_DEFAULT

    override fun isOwnHost(host: String): Boolean = host == apiUrl.toHttpUrl().host

    override fun configureClient(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        builder.addInterceptor(::oauthInterceptor)

    override fun loginStatus(): Boolean =
        authToken.isNotEmpty() || !preferences.getString(PREF_OAUTH_ACCESS, null).isNullOrEmpty()

    // ============================== Login ===============================
    // MangaLib keeps the login in a token, not in cookies. After the user logs in through
    // WebView, the auth server (auth.hentaicdn.org / auth.lib.social) remembers them by cookie, so we can run
    // the same OAuth flow as the website and get our own token.

    @Volatile
    private var lastLoginAttempt = 0L

    private fun oauthInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != apiUrl.toHttpUrl().host || request.header("Authorization") != null ||
            request.url.encodedPath.contains("/auth/oauth/")
        ) {
            return chain.proceed(request)
        }
        val token = validAccessToken()
        val response = chain.proceed(if (token != null) request.withToken(token) else request)
        if (response.code == 401 && token != null) {
            response.close()
            preferences.edit().remove(PREF_OAUTH_ACCESS).apply()
            val fresh = validAccessToken()
            return chain.proceed(if (fresh != null) request.withToken(fresh) else request)
        }
        return response
    }

    private fun Request.withToken(token: String) = newBuilder().header("Authorization", "Bearer $token").build()

    override fun loginDetails(): String {
        if (authToken.isNotEmpty()) return "используется токен из настроек"
        // Opening the settings also tries to log in, so the next visit shows the result.
        if (preferences.getString(PREF_OAUTH_ACCESS, null).isNullOrEmpty()) {
            runCatching { Thread { runCatching { validAccessToken(force = true) } }.start() }
        }
        val auth = cookieNames(AUTH_URL)
        val site = cookieNames(siteUrl)
        val last = preferences.getString(PREF_LOGIN_DEBUG, null) ?: "попыток входа ещё не было"
        return "версия $VERSION_LABEL; cookie ${AUTH_URL.removePrefix("https://")}: ${auth.size}" +
            (if (auth.isNotEmpty()) " (${auth.take(6).joinToString()})" else "") +
            "; cookie mangalib.me: ${site.size}" +
            (if (site.isNotEmpty()) " (${site.take(6).joinToString()})" else "") +
            "; последняя попытка: $last"
    }

    private fun resetLogin() {
        lastLoginAttempt = 0L
        preferences.edit().remove(PREF_OAUTH_ACCESS).remove(PREF_OAUTH_REFRESH).remove(PREF_LOGIN_DEBUG).apply()
    }

    private fun debug(message: String) {
        preferences.edit().putString(PREF_LOGIN_DEBUG, message).apply()
    }

    private fun cookieNames(url: String): List<String> = runCatching {
        client.cookieJar.loadForRequest(url.toHttpUrl()).map { it.name }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun validAccessToken(force: Boolean = false): String? {
        val access = preferences.getString(PREF_OAUTH_ACCESS, null)
        val expires = preferences.getLong(PREF_OAUTH_EXPIRES, 0L)
        if (!access.isNullOrEmpty() && System.currentTimeMillis() < expires - 60_000) return access

        preferences.getString(PREF_OAUTH_REFRESH, null)?.takeIf { it.isNotEmpty() }?.let { refresh ->
            requestToken(
                """{"grant_type":"refresh_token","client_id":$siteId,"refresh_token":"$refresh","scope":""}""",
            )?.let { return it }
        }

        // Try a fresh login at most every 20 seconds.
        if (!force && System.currentTimeMillis() - lastLoginAttempt < 20_000) return null
        lastLoginAttempt = System.currentTimeMillis()
        val login = preferences.getString(PREF_LOGIN, "").orEmpty().trim()
        val password = preferences.getString(PREF_PASSWORD, "").orEmpty()
        val authCookies = cookieNames(AUTH_URL)
        if (authCookies.isEmpty() && (login.isEmpty() || password.isEmpty())) {
            debug("введите логин и пароль в настройках (вход через WebView 🌐 до расширения не доходит)")
            return null
        }
        // Tachimanga shares only the site's own cookies with the extension, so the login runs
        // in a separate cookie store that keeps the auth server session.
        val loginClient = if (authCookies.isNotEmpty()) client else client.newBuilder().cookieJar(MemoryCookieJar()).build()
        return try {
            oauthLogin(loginClient, login, password)
        } catch (e: Exception) {
            debug("ошибка: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    private fun oauthLogin(httpClient: OkHttpClient, login: String, password: String): String? {
        val random = SecureRandom()
        val verifier = ByteArray(48).also(random::nextBytes).base64Url()
        val challenge = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()).base64Url()
        val state = ByteArray(16).also(random::nextBytes).base64Url()
        val redirectUri = "$siteUrl/ru/front/auth/oauth/callback"

        var request: Request = GET(
            "$AUTH_URL/auth/oauth/authorize".toHttpUrl().newBuilder()
                .addQueryParameter("scope", "")
                .addQueryParameter("client_id", siteId.toString())
                .addQueryParameter("response_type", "code")
                .addQueryParameter("redirect_uri", redirectUri)
                .addQueryParameter("state", state)
                .addQueryParameter("code_challenge", challenge)
                .addQueryParameter("code_challenge_method", "S256")
                .build(),
            pageHeaders(),
        )

        val noRedirect = httpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
        var code: String? = null
        var consentSubmitted = false
        var loginSubmitted = false
        for (step in 0 until 8) {
            val response = noRedirect.newCall(request).execute()
            val location = response.header("Location")
            if (location.isNullOrEmpty()) {
                val html = response.body.string()
                val pageUrl = response.request.url.toString()
                response.close()
                val hasPasswordField = Jsoup.parse(html).selectFirst("input[type=password]") != null
                if (hasPasswordField) {
                    // The login page: fill in the login and password, like typing them on the site.
                    if (loginSubmitted || login.isEmpty() || password.isEmpty()) {
                        debug(
                            when {
                                !loginSubmitted -> "сервер просит войти: введите логин и пароль в настройках"
                                html.contains("captcha", true) -> "сайт требует капчу, автоматический вход невозможен — вставьте токен"
                                else -> "неверный логин или пароль"
                            },
                        )
                        return null
                    }
                    request = loginForm(html, pageUrl, login, password) ?: run {
                        debug("не найдена форма входа на ${response.request.url.host}")
                        return null
                    }
                    loginSubmitted = true
                    continue
                }
                // A consent page ("Разрешить доступ?"): submit its approve form, like pressing the button.
                val form = if (consentSubmitted) null else approveForm(html, pageUrl)
                if (form == null) {
                    debug("сервер вернул страницу без перенаправления (HTTP ${response.code}, ${response.request.url.encodedPath})")
                    return null
                }
                consentSubmitted = true
                request = form
                continue
            }
            response.close()
            val next = response.request.url.resolve(location) ?: break
            if (next.toString().startsWith(redirectUri)) {
                if (next.queryParameter("state") != state) {
                    debug("сервер вернул чужой ответ (state)")
                    return null
                }
                code = next.queryParameter("code")
                if (code == null) debug("вход отклонён: ${next.queryParameter("error") ?: "без кода"}")
                break
            }
            request = GET(next, pageHeaders())
        }
        if (code.isNullOrEmpty()) {
            if (preferences.getString(PREF_LOGIN_DEBUG, null) == null) debug("не удалось получить код входа")
            return null
        }

        return requestToken(
            """{"grant_type":"authorization_code","client_id":$siteId,"redirect_uri":"$redirectUri",""" +
                """"code_verifier":"$verifier","code":"$code"}""",
        )
    }

    private fun pageHeaders(): Headers = headersBuilder()
        .set("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
        .removeAll("Site-Id")
        .build()

    /** Fills in the login form. */
    private fun loginForm(html: String, pageUrl: String, login: String, password: String): Request? {
        val document = Jsoup.parse(html, pageUrl)
        val form = document.select("form").firstOrNull { it.selectFirst("input[type=password]") != null } ?: return null
        val body = FormBody.Builder().apply {
            form.select("input[name]").forEach { input ->
                val name = input.attr("name")
                when {
                    input.attr("type").equals("password", true) -> add(name, password)
                    input.attr("type").equals("email", true) ||
                        name.lowercase() in setOf("email", "login", "username", "user") -> add(name, login)
                    input.attr("type").equals("checkbox", true) ->
                        if (name.contains("remember", true)) add(name, input.attr("value").ifEmpty { "on" })
                    input.attr("type").equals("submit", true) -> {}
                    else -> add(name, input.attr("value"))
                }
            }
        }.build()
        val action = form.absUrl("action").ifEmpty { pageUrl }
        return POST(action, pageHeaders().newBuilder().set("Origin", AUTH_URL).set("Referer", pageUrl).build(), body)
    }

    /** Finds the "allow" form on a consent page and builds the request that submits it. */
    private fun approveForm(html: String, pageUrl: String): Request? {
        val document = Jsoup.parse(html, pageUrl)
        val form = document.select("form").firstOrNull { form ->
            val method = form.selectFirst("input[name=_method]")?.attr("value").orEmpty()
            form.attr("method").equals("post", true) && !method.equals("delete", true) &&
                form.attr("action").contains("authorize")
        } ?: return null
        val body = FormBody.Builder().apply {
            form.select("input[name]").forEach { add(it.attr("name"), it.attr("value")) }
        }.build()
        val action = form.absUrl("action").ifEmpty { pageUrl }
        return POST(action, pageHeaders().newBuilder().set("Origin", AUTH_URL).set("Referer", pageUrl).build(), body)
    }

    private fun requestToken(body: String): String? {
        var lastError = ""
        for (endpoint in listOf("$apiUrl/api/auth/oauth/token", "$AUTH_URL/auth/oauth/token")) {
            val result = runCatching {
                val request = POST(
                    endpoint,
                    headersBuilder().set("Content-Type", "application/json").build(),
                    body.toRequestBody(JSON_MEDIA),
                )
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    lastError = "токен: HTTP ${response.code} (${endpoint.toHttpUrl().host})"
                    response.close()
                    return@runCatching null
                }
                val token = response.tree()
                val access = token["access_token"].str ?: return@runCatching null
                preferences.edit()
                    .putString(PREF_OAUTH_ACCESS, access)
                    .putString(PREF_OAUTH_REFRESH, token["refresh_token"].str ?: preferences.getString(PREF_OAUTH_REFRESH, ""))
                    .putLong(PREF_OAUTH_EXPIRES, System.currentTimeMillis() + (token["expires_in"].long ?: 3600L) * 1000)
                    .putString(PREF_LOGIN_DEBUG, "вход выполнен автоматически")
                    .putBoolean(PREF_LOGIN_MODE, false)
                    .apply()
                access
            }.getOrElse {
                lastError = "токен: ${it.message}"
                null
            }
            if (result != null) return result
        }
        if (body.contains("refresh_token")) preferences.edit().remove(PREF_OAUTH_REFRESH).apply()
        debug(lastError)
        return null
    }

    private fun ByteArray.base64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Accept", "application/json, text/plain, */*")
        .set("Site-Id", siteId.toString())
        .set("Origin", siteUrl)

    private val preferEnglish: Boolean get() = preferences.getString(PREF_TITLE_LANG, "rus") == "eng"

    // ============================== Catalog ===============================
    override fun popularMangaRequest(page: Int): Request {
        loadConstantsInBackground()
        val url = "$apiUrl/api/manga".toHttpUrl().newBuilder()
            .addQueryParameter("site_id[]", siteId.toString())
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = parsePage(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$apiUrl/api/latest-updates?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parsePage(response)

    private fun parsePage(response: Response): MangasPage {
        checkResponse(response)
        val root = response.tree()
        val list = root["data"].list.mapNotNull { MangaShortDto.from(it)?.toSManga(preferEnglish) }
        return MangasPage(list, root["meta"]["has_next_page"].bool ?: false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        loadConstantsInBackground()
        val url = "$apiUrl/api/manga".toHttpUrl().newBuilder()
        url.addQueryParameter("page", page.toString())
        url.addQueryParameter("site_id[]", siteId.toString())
        query.trim().takeIf { it.isNotEmpty() }?.let { url.addQueryParameter("q", it) }
        filters.forEach { filter ->
            when (filter) {
                is OrderBy -> {
                    val index = filter.state?.index ?: 0
                    url.addQueryParameter("sort_type", if (filter.state?.ascending == true) "asc" else "desc")
                    if (index > 0) url.addQueryParameter("sort_by", ORDER_FIELDS[index])
                }
                is TypeFilter -> filter.checked.forEach { url.addQueryParameter("types[]", it) }
                is FormatFilter -> {
                    filter.included.forEach { url.addQueryParameter("format[]", it) }
                    filter.excluded.forEach { url.addQueryParameter("format_exclude[]", it) }
                }
                is GenreFilter -> {
                    filter.included.forEach { url.addQueryParameter("genres[]", it) }
                    filter.excluded.forEach { url.addQueryParameter("genres_exclude[]", it) }
                }
                is TagFilter -> {
                    filter.included.forEach { url.addQueryParameter("tags[]", it) }
                    filter.excluded.forEach { url.addQueryParameter("tags_exclude[]", it) }
                }
                is ScanlateFilter -> filter.checked.forEach { url.addQueryParameter("scanlate_status[]", it) }
                is TitleStatusFilter -> filter.checked.forEach { url.addQueryParameter("status[]", it) }
                is AgeFilter -> filter.checked.forEach { url.addQueryParameter("caution[]", it) }
                is MyListFilter -> {
                    filter.included.forEach { url.addQueryParameter("bookmarks[]", it) }
                    filter.excluded.forEach { url.addQueryParameter("bookmarks_exclude[]", it) }
                }
                is RequireChapters -> if (filter.state == 0) url.setQueryParameter("chap_count_min", "1")
                else -> {}
            }
        }
        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parsePage(response)

    // ============================== Details ===============================
    override fun mangaDetailsRequest(manga: SManga): Request {
        if (!manga.url.contains("--")) throw Exception("Адрес тайтла изменился. Найдите его заново через поиск (миграция).")
        val url = "$apiUrl/api/manga${manga.url}".toHttpUrl().newBuilder().apply {
            listOf(
                "eng_name", "otherNames", "summary", "rate", "genres", "tags", "teams", "authors",
                "publisher", "userRating", "manga_status_id", "status_id", "artists",
            ).forEach { addQueryParameter("fields[]", it) }
        }.build()
        return GET(url, headers)
    }

    override fun getMangaUrl(manga: SManga): String = "$siteUrl/ru/manga${manga.url}"

    override fun mangaDetailsParse(response: Response): SManga {
        checkResponse(response)
        return MangaDto.from(response.tree()["data"]).toSManga(preferEnglish)
    }

    // ============================== Chapters ===============================
    override fun chapterListRequest(manga: SManga): Request = GET("$apiUrl/api/manga${manga.url}/chapters", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        checkResponse(response)
        val slug = response.request.url.pathSegments.let { it[it.size - 2] }
        val chapters = response.tree()["data"].list.map(ChapterDto::from)
        if (chapters.isEmpty()) return emptyList()
        val mode = preferences.getString(PREF_SORTING, "ms_mixing")
        val showPaid = preferences.getBoolean(PREF_PAID, false)
        val defaultBranch = if (mode == "ms_mixing" && chapters.maxOf { it.branches.size } > 1) {
            runCatching {
                client.newCall(GET("$apiUrl/api/branches/${slug.substringBefore("-")}", headers)).execute()
                    .tree()["data"].list.first()["id"].int
            }.getOrNull()
        } else {
            null
        }
        return chapters.flatMap { chapter ->
            when {
                chapter.branchesCount > 1 && mode == "ms_mixing" -> {
                    val branch = chapter.branches.firstOrNull { it.branchId == defaultBranch }?.branchId
                        ?: chapter.branches.firstOrNull()?.branchId
                    listOf(chapter.toSChapter(slug, branch, showPaid))
                }
                chapter.branchesCount > 1 && mode == "ms_combining" ->
                    chapter.branches.map { chapter.toSChapter(slug, it.branchId, showPaid) }
                else -> listOf(chapter.toSChapter(slug, null, showPaid))
            }
        }.filterNotNull().reversed()
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val slug = chapter.url.substringAfter("/").substringBefore("/")
        val volume = chapter.url.substringAfter("volume=").substringBefore("&")
        val number = chapter.url.substringAfter("number=").substringBefore("&")
        val branch = chapter.url.substringAfter("branch_id=", "").substringBefore("&")
        return "$siteUrl/ru/$slug/read/v$volume/c$number" + if (branch.isNotBlank()) "?bid=$branch" else ""
    }

    // ============================== Pages ===============================
    override fun pageListRequest(chapter: SChapter): Request {
        if (chapter.name.startsWith("$$")) throw Exception("Глава платная и не куплена")
        return GET("$apiUrl/api/manga${chapter.url}", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        checkResponse(response)
        val server = imageServer()
        return response.tree()["data"]["pages"].list
            .mapNotNull { page -> page["url"].str?.let { (page["slug"].int ?: 0) to it } }
            .sortedBy { it.first }
            .mapIndexed { index, page -> Page(index, imageUrl = server + page.second) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, headersBuilder().set("Accept", "image/avif,image/webp,image/*,*/*;q=0.8").build())

    private fun imageServer(): String {
        val constants = constants ?: fetchConstants()
        val wanted = preferences.getString(PREF_SERVER, "compress")
        val servers = constants.imageServers.filter { siteId in it.siteIds }
        return (servers.firstOrNull { it.id == wanted } ?: servers.firstOrNull())?.url
            ?: throw Exception("Не удалось получить сервер изображений")
    }

    private fun checkResponse(response: Response) {
        if (response.isSuccessful) return
        response.close()
        throw Exception(
            when (response.code) {
                401 -> "Токен устарел или неверный. Обновите его в настройках источника."
                404 -> "Не найдено. Возможно, тайтл доступен только после входа (18+)."
                419 -> "Сайт требует повторный вход. Обновите токен в настройках."
                429 -> "Слишком много запросов. Подождите немного."
                else -> "HTTP ошибка ${response.code}"
            },
        )
    }

    // ============================== Filters ===============================
    @Volatile
    private var constants: ConstantsDto? = null

    @Volatile
    private var constantsLoading = false

    private fun fetchConstants(): ConstantsDto {
        val url = "$apiUrl/api/constants?" + listOf("genres", "tags", "types", "scanlateStatus", "status", "format", "ageRestriction", "imageServers")
            .joinToString("&") { "fields[]=$it" }
        return ConstantsDto.from(client.newCall(GET(url, headers)).execute().tree()["data"]).also { constants = it }
    }

    private fun loadConstantsInBackground() {
        if (constants != null || constantsLoading) return
        constantsLoading = true
        Thread {
            try {
                fetchConstants()
            } catch (_: Exception) {
            } finally {
                constantsLoading = false
            }
        }.start()
    }

    override fun getFilterList(): FilterList {
        val filters = mutableListOf<Filter<*>>(OrderBy())
        val c = constants
        if (c == null) {
            filters += Filter.Header("Жанры и теги загрузятся после открытия каталога. Нажмите «Сбросить».")
        } else {
            fun List<IdLabel>.forSite() = filter { siteId in it.siteIds }.map { it.title to it.id }
            filters += TypeFilter(c.types.forSite())
            filters += FormatFilter(c.format.forSite())
            filters += GenreFilter(c.genres.forSite())
            filters += TagFilter(c.tags.forSite())
            filters += ScanlateFilter(c.scanlateStatus.forSite())
            filters += TitleStatusFilter(c.status.forSite())
            filters += AgeFilter(c.ageRestriction.forSite())
        }
        filters += MyListFilter()
        filters += RequireChapters()
        return FilterList(filters)
    }

    private class OrderBy : Filter.Sort(
        "Сортировка",
        arrayOf("Популярность", "Рейтинг", "Имя (A-Z)", "Имя (А-Я)", "Просмотры", "Дата релиза", "Дата добавления", "Дата обновления", "Кол-во глав"),
        Selection(0, false),
    )

    private class TypeFilter(o: List<Pair<String, String>>) : CheckBoxGroup("Тип", o)
    private class FormatFilter(o: List<Pair<String, String>>) : TriStateGroup("Формат выпуска", o)
    private class GenreFilter(o: List<Pair<String, String>>) : TriStateGroup("Жанры", o)
    private class TagFilter(o: List<Pair<String, String>>) : TriStateGroup("Теги", o)
    private class ScanlateFilter(o: List<Pair<String, String>>) : CheckBoxGroup("Статус перевода", o)
    private class TitleStatusFilter(o: List<Pair<String, String>>) : CheckBoxGroup("Статус тайтла", o)
    private class AgeFilter(o: List<Pair<String, String>>) : CheckBoxGroup("Возрастное ограничение", o)
    private class MyListFilter : TriStateGroup(
        "Мои списки (нужен вход)",
        listOf("Читаю" to "1", "В планах" to "2", "Брошено" to "3", "Прочитано" to "4", "Любимые" to "5"),
    )
    private class RequireChapters : SelectFilter("Только тайтлы с главами", listOf("Да" to "yes", "Все" to "all"))

    // ============================== Preferences ===============================
    override fun setupExtraPreferences(screen: PreferenceScreen) {
        val context = screen.context
        ListPreference(context).apply {
            key = PREF_AUTH_SERVER
            title = "Сервер входа"
            entries = AUTH_OPTIONS.map { it.first }.toTypedArray()
            entryValues = AUTH_OPTIONS.map { it.second }.toTypedArray()
            setDefaultValue(AUTH_DEFAULT)
            summary = "%s\nСмените, если страница входа не открывается."
            setOnPreferenceChangeListener { _, _ ->
                resetLogin()
                true
            }
        }.let(screen::addPreference)

        SwitchPreferenceCompat(context).apply {
            key = PREF_LOGIN_MODE
            title = "Режим входа"
            summary = "Пока включён, кнопка 🌐 открывает страницу входа MangaLib (сервер входа ниже). После входа выключите."
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ ->
                lastLoginAttempt = 0L
                true
            }
        }.let(screen::addPreference)

        EditTextPreference(context).apply {
            key = PREF_LOGIN
            title = "Логин (e-mail)"
            dialogTitle = "E-mail или логин от MangaLib"
            summary = preferences.getString(PREF_LOGIN, "")?.ifBlank { null } ?: "Не задан"
            setDefaultValue("")
            setOnPreferenceChangeListener { pref, value ->
                pref.summary = (value as String).ifBlank { "Не задан" }
                resetLogin()
                true
            }
        }.let(screen::addPreference)

        EditTextPreference(context).apply {
            key = PREF_PASSWORD
            title = "Пароль"
            dialogTitle = "Пароль от MangaLib"
            summary = if (preferences.getString(PREF_PASSWORD, "").isNullOrEmpty()) "Не задан" else "••••••"
            setDefaultValue("")
            setOnPreferenceChangeListener { pref, value ->
                pref.summary = if ((value as String).isEmpty()) "Не задан" else "••••••"
                resetLogin()
                true
            }
        }.let(screen::addPreference)

        ListPreference(context).apply {
            key = PREF_API
            title = "Сервер API"
            entries = API_OPTIONS.map { it.first }.toTypedArray()
            entryValues = API_OPTIONS.map { it.second }.toTypedArray()
            setDefaultValue(API_DEFAULT)
            summary = "%s\nСмените, если каталог не загружается. Затем перезапустите приложение."
        }.let(screen::addPreference)

        ListPreference(context).apply {
            key = PREF_SERVER
            title = "Сервер изображений"
            entries = arrayOf("Основной", "Второй", "Сжатия (быстрее)")
            entryValues = arrayOf("main", "secondary", "compress")
            setDefaultValue("compress")
            summary = "%s\nСмените, если картинки не грузятся."
        }.let(screen::addPreference)

        ListPreference(context).apply {
            key = PREF_TITLE_LANG
            title = "Язык названий"
            entries = arrayOf("Русский", "Английский")
            entryValues = arrayOf("rus", "eng")
            setDefaultValue("rus")
            summary = "%s"
        }.let(screen::addPreference)

        ListPreference(context).apply {
            key = PREF_SORTING
            title = "Переводы глав"
            entries = arrayOf("Один перевод на главу", "Все переводы подряд")
            entryValues = arrayOf("ms_mixing", "ms_combining")
            setDefaultValue("ms_mixing")
            summary = "%s"
        }.let(screen::addPreference)

        CheckBoxPreference(context).apply {
            key = PREF_PAID
            title = "Показывать платные главы"
            summary = "Некупленные главы будут отмечены $$"
            setDefaultValue(false)
        }.let(screen::addPreference)
    }

    /** Cookie store kept in memory for the login run. */
    private class MemoryCookieJar : CookieJar {
        private val cookies = mutableListOf<Cookie>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { new -> this.cookies.removeAll { it.name == new.name && it.domain == new.domain && it.path == new.path } }
            this.cookies += cookies
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            this.cookies.removeAll { it.expiresAt < now }
            return this.cookies.filter { it.matches(url) }
        }
    }

    // ============================== JSON ===============================
    // Read by hand: see ru.tachimanga.common.JsonTree.
    private fun Response.tree(): JsonElement = use { json.parseToJsonElement(it.body.string()) }

    private class MangaShortDto(val name: String, val rusName: String?, val engName: String?, val slugUrl: String, val cover: String?) {
        fun toSManga(english: Boolean) = SManga.create().apply {
            title = pickTitle(english, rusName, engName, name)
            thumbnail_url = cover
            url = "/$slugUrl"
        }

        companion object {
            fun from(e: JsonElement): MangaShortDto? {
                val slug = e["slug_url"].str ?: return null
                return MangaShortDto(e["name"].str.orEmpty(), e["rus_name"].str, e["eng_name"].str, slug, e["cover"]["default"].str)
            }
        }
    }

    private class MangaDto(val e: JsonElement?) {
        fun toSManga(english: Boolean) = SManga.create().apply {
            val rusName = e["rus_name"].str
            val engName = e["eng_name"].str
            title = pickTitle(english, rusName, engName, e["name"].str.orEmpty())
            thumbnail_url = e["cover"]["default"].str
            author = e["authors"].list.mapNotNull { it["name"].str }.joinToString().ifBlank { null }
            artist = e["artists"].list.mapNotNull { it["name"].str }.joinToString().ifBlank { null }
            status = parseStatus(e["is_licensed"].bool ?: false, e["scanlateStatus"]["label"].str.orEmpty(), e["status"]["label"].str.orEmpty())
            genre = (
                listOfNotNull(e["type"]["label"].str?.ifBlank { "Манга" }, e["ageRestriction"]["label"].str) +
                    e["genres"].list.mapNotNull { it["name"].str?.trim() } +
                    e["tags"].list.mapNotNull { it["name"].str?.trim() }
                ).filter { it.isNotBlank() }.joinToString()
            description = buildString {
                val other = if (english) rusName else engName
                if (!other.isNullOrBlank()) append(other, "\n")
                val average = e["rating"]["average"].str
                if (average != null) append("Рейтинг: $average (голосов: ${e["rating"]["votes"].str ?: "0"})\n")
                val otherNames = e["otherNames"].list.mapNotNull { it.str }
                if (otherNames.isNotEmpty()) append("Альтернативные названия:\n", otherNames.joinToString(" / "), "\n\n")
                append(summaryText(e["summary"]))
            }.trim()
        }

        private fun parseStatus(licensed: Boolean, t: String, s: String): Int = when {
            licensed -> SManga.LICENSED
            (t == "Завершён" && s == "Приостановлен") || t == "Заморожен" || t == "Заброшен" -> SManga.ON_HIATUS
            t == "Завершён" && s == "Выпуск прекращён" -> SManga.CANCELLED
            t == "Продолжается" || t == "Выходит" -> SManga.ONGOING
            t == "Завершён" -> SManga.COMPLETED
            t == "Вышло" -> SManga.PUBLISHING_FINISHED
            s == "Онгоинг" || s == "Анонс" -> SManga.ONGOING
            s == "Завершён" -> SManga.COMPLETED
            s == "Приостановлен" -> SManga.ON_HIATUS
            s == "Выпуск прекращён" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        companion object {
            fun from(e: JsonElement?) = MangaDto(e ?: throw Exception("Тайтл не найден"))
        }
    }

    private class Branch(val branchId: Int?, val createdAt: String?, val team: String?, val user: String?, val isOpen: Boolean)

    private class ChapterDto(val branchesCount: Int, val branches: List<Branch>, val name: String?, val number: String, val volume: String) {
        fun toSChapter(slug: String, branchId: Int?, showPaid: Boolean): SChapter? {
            val branch = branches.firstOrNull { branchId == null || it.branchId == branchId } ?: branches.firstOrNull()
            val open = branch?.isOpen ?: true
            if (!open && !showPaid) return null
            return SChapter.create().apply {
                val base = (if (open) "" else "$$ ") + "Том $volume. Глава $number"
                this.name = if (this@ChapterDto.name.isNullOrBlank()) base else "$base — ${this@ChapterDto.name}"
                val branchStr = if (branchId != null) "&branch_id=$branchId" else ""
                url = "/$slug/chapter?$branchStr&volume=$volume&number=$number"
                scanlator = branch?.team ?: branch?.user
                date_upload = parseIsoDate(branch?.createdAt)
                chapter_number = number.toFloatOrNull() ?: -1f
            }
        }

        companion object {
            fun from(e: JsonElement) = ChapterDto(
                branchesCount = e["branches_count"].int ?: 0,
                branches = e["branches"].list.map { b ->
                    Branch(
                        branchId = b["branch_id"].int,
                        createdAt = b["created_at"].str,
                        team = b["teams"].list.firstOrNull()["name"].str,
                        user = b["user"]["username"].str,
                        isOpen = b["restricted_view"]["is_open"].bool ?: true,
                    )
                },
                name = e["name"].str,
                number = e["number"].str ?: "0",
                volume = e["volume"].str ?: "0",
            )
        }
    }

    private class IdLabel(val id: String, val title: String, val siteIds: List<Int>)

    private class ImageServer(val id: String, val url: String, val siteIds: List<Int>)

    private class ConstantsDto(
        val ageRestriction: List<IdLabel>,
        val format: List<IdLabel>,
        val genres: List<IdLabel>,
        val imageServers: List<ImageServer>,
        val scanlateStatus: List<IdLabel>,
        val status: List<IdLabel>,
        val tags: List<IdLabel>,
        val types: List<IdLabel>,
    ) {
        companion object {
            private fun sites(e: JsonElement?) = e["site_ids"].list.mapNotNull { it.int }

            private fun labels(e: JsonElement?) = e.list.mapNotNull { item ->
                val id = item["id"].str ?: return@mapNotNull null
                IdLabel(id, item["label"].str ?: item["name"].str ?: id, sites(item))
            }

            fun from(e: JsonElement?) = ConstantsDto(
                ageRestriction = labels(e["ageRestriction"]),
                format = labels(e["format"]),
                genres = labels(e["genres"]),
                imageServers = e["imageServers"].list.mapNotNull { item ->
                    val id = item["id"].str
                    val url = item["url"].str
                    if (id == null || url == null) null else ImageServer(id, url, sites(item))
                },
                scanlateStatus = labels(e["scanlateStatus"]),
                status = labels(e["status"]),
                tags = labels(e["tags"]),
                types = labels(e["types"]),
            )
        }
    }

    companion object {
        private const val PREF_API = "mangalib_api"
        private const val API_DEFAULT = "https://api.cdnlibs.org"
        private val API_OPTIONS = listOf(
            "Основной (api.cdnlibs.org)" to API_DEFAULT,
            "Резервный (api2.mangalib.me)" to "https://api2.mangalib.me",
            "Резервный (hapi.hentaicdn.org)" to "https://hapi.hentaicdn.org",
            "Резервный (api.imglib.info)" to "https://api.imglib.info",
        )
        private const val PREF_SERVER = "mangalib_image_server"
        private const val PREF_TITLE_LANG = "mangalib_title_lang"
        private const val PREF_SORTING = "mangalib_sorting"
        private const val PREF_PAID = "mangalib_paid_chapters"
        private const val PREF_OAUTH_ACCESS = "mangalib_oauth_access"
        private const val PREF_OAUTH_REFRESH = "mangalib_oauth_refresh"
        private const val PREF_OAUTH_EXPIRES = "mangalib_oauth_expires"
        private const val PREF_LOGIN_DEBUG = "mangalib_login_debug"
        private const val VERSION_LABEL = "1.4.9"
        private const val PREF_LOGIN_MODE = "mangalib_login_mode"
        private const val PREF_LOGIN = "mangalib_login"
        private const val PREF_PASSWORD = "mangalib_password"
        private const val PREF_AUTH_SERVER = "mangalib_auth_server"
        private const val AUTH_DEFAULT = "https://auth.hentaicdn.org"
        private val AUTH_OPTIONS = listOf(
            "auth.hentaicdn.org" to AUTH_DEFAULT,
            "auth.lib.social" to "https://auth.lib.social",
        )
        private val JSON_MEDIA = "application/json".toMediaType()
        private val ORDER_FIELDS = arrayOf("", "rate_avg", "name", "rus_name", "views", "releaseDate", "created_at", "last_chapter_at", "chap_count")

        private fun pickTitle(english: Boolean, rus: String?, eng: String?, name: String): String = when {
            english && !eng.isNullOrBlank() -> eng
            !english && !rus.isNullOrBlank() -> rus
            else -> name
        }

        private fun summaryText(node: JsonElement?): String = buildString { collectText(node, this) }

        private fun collectText(node: JsonElement?, out: StringBuilder) {
            if (node == null) return
            node.str?.let {
                out.append(it)
                return
            }
            when (node["type"].str) {
                "text" -> node["text"].str?.let { out.append(it) }
                "hardBreak" -> out.append("\n")
                "paragraph" -> {
                    node["content"].list.forEach { collectText(it, out) }
                    out.append("\n")
                }
                null -> node.list.forEach { collectText(it, out) }
                else -> node["content"].list.forEach { collectText(it, out) }
            }
        }
    }
}
