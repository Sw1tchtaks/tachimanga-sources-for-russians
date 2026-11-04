package ru.tachimanga.ext.mangalib

import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import ru.tachimanga.common.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import ru.tachimanga.common.CheckBoxGroup
import ru.tachimanga.common.POST
import ru.tachimanga.common.RuSource
import ru.tachimanga.common.SelectFilter
import ru.tachimanga.common.TriStateGroup
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
        "Откройте источник в WebView 🌐 и войдите на сайте MangaLib. Потом откройте каталог: расширение само " +
            "получит вход. Если не вышло — вставьте токен в поле «Токен доступа» ниже. Без входа доступно почти всё, кроме 18+."

    private val siteId = 1

    private val apiUrl: String get() = preferences.getString(PREF_API, API_DEFAULT) ?: API_DEFAULT

    override fun isOwnHost(host: String): Boolean = host == apiUrl.toHttpUrl().host

    override fun configureClient(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        builder.addInterceptor(::oauthInterceptor)

    override fun loginStatus(): Boolean =
        authToken.isNotEmpty() || !preferences.getString(PREF_OAUTH_ACCESS, null).isNullOrEmpty()

    // ============================== Login ===============================
    // MangaLib keeps the login in a token, not in cookies. After the user logs in through
    // WebView, the auth server (auth.lib.social) remembers them by cookie, so we can run
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

    @Synchronized
    private fun validAccessToken(): String? {
        val access = preferences.getString(PREF_OAUTH_ACCESS, null)
        val expires = preferences.getLong(PREF_OAUTH_EXPIRES, 0L)
        if (!access.isNullOrEmpty() && System.currentTimeMillis() < expires - 60_000) return access

        preferences.getString(PREF_OAUTH_REFRESH, null)?.takeIf { it.isNotEmpty() }?.let { refresh ->
            requestToken(
                """{"grant_type":"refresh_token","client_id":$siteId,"refresh_token":"$refresh","scope":""}""",
            )?.let { return it }
        }

        // Try a fresh login at most once a minute, and only if the user logged in through WebView.
        if (System.currentTimeMillis() - lastLoginAttempt < 60_000) return null
        lastLoginAttempt = System.currentTimeMillis()
        val hasAuthCookies = runCatching {
            client.cookieJar.loadForRequest(AUTH_URL.toHttpUrl()).isNotEmpty()
        }.getOrDefault(false)
        if (!hasAuthCookies) return null
        return runCatching { oauthLogin() }.getOrNull()
    }

    private fun oauthLogin(): String? {
        val random = SecureRandom()
        val verifier = ByteArray(48).also(random::nextBytes).base64Url()
        val challenge = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()).base64Url()
        val state = ByteArray(16).also(random::nextBytes).base64Url()
        val redirectUri = "$baseUrl/ru/front/auth/oauth/callback"

        var url = "$AUTH_URL/auth/oauth/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("scope", "")
            .addQueryParameter("client_id", siteId.toString())
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("state", state)
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()

        val noRedirect = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val pageHeaders = headersBuilder().set("Accept", "text/html,application/xhtml+xml,*/*;q=0.8").build()
        var code: String? = null
        for (step in 0 until 6) {
            val response = noRedirect.newCall(GET(url, pageHeaders)).execute()
            val location = response.header("Location")
            response.close()
            if (location.isNullOrEmpty()) return null
            val next = response.request.url.resolve(location) ?: return null
            if (next.toString().startsWith(redirectUri)) {
                if (next.queryParameter("state") != state) return null
                code = next.queryParameter("code")
                break
            }
            url = next.toString()
        }
        if (code.isNullOrEmpty()) return null

        return requestToken(
            """{"grant_type":"authorization_code","client_id":$siteId,"redirect_uri":"$redirectUri",""" +
                """"code_verifier":"$verifier","code":"$code"}""",
        )
    }

    private fun requestToken(body: String): String? = runCatching {
        val request = POST(
            "$apiUrl/api/auth/oauth/token",
            headersBuilder().set("Content-Type", "application/json").build(),
            body.toRequestBody(JSON_MEDIA),
        )
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            if (response.code in 400..499) preferences.edit().remove(PREF_OAUTH_REFRESH).apply()
            return@runCatching null
        }
        val token = response.parseAs<OAuthTokenDto>()
        preferences.edit()
            .putString(PREF_OAUTH_ACCESS, token.accessToken)
            .putString(PREF_OAUTH_REFRESH, token.refreshToken ?: preferences.getString(PREF_OAUTH_REFRESH, ""))
            .putLong(PREF_OAUTH_EXPIRES, System.currentTimeMillis() + token.expiresIn * 1000)
            .apply()
        token.accessToken
    }.getOrNull()

    private fun ByteArray.base64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Accept", "application/json, text/plain, */*")
        .set("Site-Id", siteId.toString())
        .set("Origin", baseUrl)

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
        val data = response.parseAs<MangasPageDto>()
        return MangasPage(data.data.map { it.toSManga(preferEnglish) }, data.meta?.hasNextPage ?: false)
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

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/ru/manga${manga.url}"

    override fun mangaDetailsParse(response: Response): SManga {
        checkResponse(response)
        return response.parseAs<Data<MangaDto>>().data.toSManga(preferEnglish)
    }

    // ============================== Chapters ===============================
    override fun chapterListRequest(manga: SManga): Request = GET("$apiUrl/api/manga${manga.url}/chapters", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        checkResponse(response)
        val slug = response.request.url.pathSegments.let { it[it.size - 2] }
        val chapters = response.parseAs<Data<List<ChapterDto>>>().data
        if (chapters.isEmpty()) return emptyList()
        val mode = preferences.getString(PREF_SORTING, "ms_mixing")
        val showPaid = preferences.getBoolean(PREF_PAID, false)
        val defaultBranch = if (mode == "ms_mixing" && chapters.maxOf { it.branches.size } > 1) {
            runCatching {
                client.newCall(GET("$apiUrl/api/branches/${slug.substringBefore("-")}", headers)).execute()
                    .parseAs<Data<List<BranchIdDto>>>().data.first().id
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
        return "$baseUrl/ru/$slug/read/v$volume/c$number" + if (branch.isNotBlank()) "?bid=$branch" else ""
    }

    // ============================== Pages ===============================
    override fun pageListRequest(chapter: SChapter): Request {
        if (chapter.name.startsWith("$$")) throw Exception("Глава платная и не куплена")
        return GET("$apiUrl/api/manga${chapter.url}", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        checkResponse(response)
        val server = imageServer()
        return response.parseAs<Data<PagesDto>>().data.pages
            .sortedBy { it.slug }
            .mapIndexed { index, page -> Page(index, imageUrl = server + page.url) }
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
        return client.newCall(GET(url, headers)).execute().parseAs<Data<ConstantsDto>>().data.also { constants = it }
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
            fun List<IdLabel>.forSite() = filter { siteId in it.siteIds }.map { it.title to it.id.toString() }
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

    // ============================== DTOs ===============================
    @Serializable
    private class Data<T>(val data: T)

    @Serializable
    private class MangasPageDto(val data: List<MangaShortDto> = emptyList(), val meta: Meta? = null) {
        @Serializable
        class Meta(@SerialName("has_next_page") val hasNextPage: Boolean = false)
    }

    @Serializable
    private class CoverDto(val default: String? = null)

    @Serializable
    private class MangaShortDto(
        val name: String = "",
        @SerialName("rus_name") val rusName: String? = null,
        @SerialName("eng_name") val engName: String? = null,
        @SerialName("slug_url") val slugUrl: String,
        val cover: CoverDto? = null,
    ) {
        fun toSManga(english: Boolean) = SManga.create().apply {
            title = pickTitle(english, rusName, engName, name)
            thumbnail_url = cover?.default
            url = "/$slugUrl"
        }
    }

    @Serializable
    private class Label(val label: String = "")

    @Serializable
    private class Named(val name: String = "")

    @Serializable
    private class MangaDto(
        val type: Label? = null,
        val ageRestriction: Label? = null,
        val rating: Rating? = null,
        val genres: List<Named> = emptyList(),
        val tags: List<Named> = emptyList(),
        @SerialName("rus_name") val rusName: String? = null,
        @SerialName("eng_name") val engName: String? = null,
        val name: String = "",
        val cover: CoverDto? = null,
        val authors: List<Named> = emptyList(),
        val artists: List<Named> = emptyList(),
        val status: Label? = null,
        val scanlateStatus: Label? = null,
        @SerialName("is_licensed") val isLicensed: Boolean = false,
        val otherNames: List<String> = emptyList(),
        val summary: JsonElement? = null,
    ) {
        @Serializable
        class Rating(val average: Float = 0f, val votes: Int = 0)

        fun toSManga(english: Boolean) = SManga.create().apply {
            title = pickTitle(english, rusName, engName, name)
            thumbnail_url = cover?.default
            author = authors.joinToString { it.name }.ifBlank { null }
            artist = artists.joinToString { it.name }.ifBlank { null }
            status = parseStatus()
            genre = (listOfNotNull(type?.label?.ifBlank { "Манга" }, ageRestriction?.label) + genres.map { it.name.trim() } + tags.map { it.name.trim() })
                .filter { it.isNotBlank() }.joinToString()
            description = buildString {
                val other = if (english) rusName else engName
                if (!other.isNullOrBlank()) append(other, "\n")
                rating?.let { append("Рейтинг: ${it.average} (голосов: ${it.votes})\n") }
                if (otherNames.isNotEmpty()) append("Альтернативные названия:\n", otherNames.joinToString(" / "), "\n\n")
                append(summaryText(summary))
            }.trim()
        }

        private fun parseStatus(): Int {
            val t = scanlateStatus?.label.orEmpty()
            val s = status?.label.orEmpty()
            return when {
                isLicensed -> SManga.LICENSED
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
        }
    }

    @Serializable
    private class ChapterDto(
        val id: Int = 0,
        @SerialName("branches_count") val branchesCount: Int = 0,
        val branches: List<Branch> = emptyList(),
        val name: String? = null,
        val number: String = "0",
        val volume: String = "0",
    ) {
        @Serializable
        class Branch(
            @SerialName("branch_id") val branchId: Int? = null,
            @SerialName("created_at") val createdAt: String? = null,
            val teams: List<Named> = emptyList(),
            @SerialName("restricted_view") val restrictedView: Restricted? = null,
            val user: User? = null,
        )

        @Serializable
        class Restricted(@SerialName("is_open") val isOpen: Boolean = true)

        @Serializable
        class User(val username: String = "")

        fun toSChapter(slug: String, branchId: Int?, showPaid: Boolean): SChapter? {
            val branch = branches.firstOrNull { branchId == null || it.branchId == branchId } ?: branches.firstOrNull()
            val open = branch?.restrictedView?.isOpen ?: true
            if (!open && !showPaid) return null
            return SChapter.create().apply {
                val base = (if (open) "" else "$$ ") + "Том $volume. Глава $number"
                this.name = if (this@ChapterDto.name.isNullOrBlank()) base else "$base — ${this@ChapterDto.name}"
                val branchStr = if (branchId != null) "&branch_id=$branchId" else ""
                url = "/$slug/chapter?$branchStr&volume=$volume&number=$number"
                scanlator = branch?.teams?.firstOrNull()?.name ?: branch?.user?.username
                date_upload = parseIsoDate(branch?.createdAt)
                chapter_number = number.toFloatOrNull() ?: -1f
            }
        }
    }

    @Serializable
    private class BranchIdDto(val id: Int)

    @Serializable
    private class OAuthTokenDto(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long = 3600,
    )

    @Serializable
    private class PagesDto(val pages: List<PageDto> = emptyList())

    @Serializable
    private class PageDto(val slug: Int = 0, val url: String)

    @Serializable
    private class IdLabel(val id: Int, val label: String? = null, val name: String? = null, @SerialName("site_ids") val siteIds: List<Int> = emptyList()) {
        val title get() = label ?: name ?: id.toString()
    }

    @Serializable
    private class ImageServerDto(val id: String, val url: String, @SerialName("site_ids") val siteIds: List<Int> = emptyList())

    @Serializable
    private class ConstantsDto(
        val ageRestriction: List<IdLabel> = emptyList(),
        val format: List<IdLabel> = emptyList(),
        val genres: List<IdLabel> = emptyList(),
        val imageServers: List<ImageServerDto> = emptyList(),
        val scanlateStatus: List<IdLabel> = emptyList(),
        val status: List<IdLabel> = emptyList(),
        val tags: List<IdLabel> = emptyList(),
        val types: List<IdLabel> = emptyList(),
    )

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
        private const val AUTH_URL = "https://auth.lib.social"
        private val JSON_MEDIA = "application/json".toMediaType()
        private val ORDER_FIELDS = arrayOf("", "rate_avg", "name", "rus_name", "views", "releaseDate", "created_at", "last_chapter_at", "chap_count")

        private fun pickTitle(english: Boolean, rus: String?, eng: String?, name: String): String = when {
            english && !eng.isNullOrBlank() -> eng
            !english && !rus.isNullOrBlank() -> rus
            else -> name
        }

        private fun summaryText(node: JsonElement?): String = try {
            buildString { node?.let { collectText(it, this) } }
        } catch (_: Exception) {
            node?.toString().orEmpty()
        }

        private fun collectText(node: JsonElement, out: StringBuilder) {
            when (node) {
                is JsonObject -> when (node["type"]?.jsonPrimitive?.content) {
                    "text" -> node["text"]?.jsonPrimitive?.content?.let { out.append(it) }
                    "hardBreak" -> out.append("\n")
                    "paragraph" -> {
                        node["content"]?.jsonArray?.forEach { collectText(it, out) }
                        out.append("\n")
                    }
                    else -> node["content"]?.jsonArray?.forEach { collectText(it, out) }
                }
                is JsonArray -> node.forEach { collectText(it, out) }
                is JsonPrimitive -> out.append(node.content)
            }
        }
    }
}
