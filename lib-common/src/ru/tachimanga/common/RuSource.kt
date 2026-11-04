package ru.tachimanga.common

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import ru.tachimanga.common.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import java.io.IOException
import java.util.concurrent.TimeUnit
import uy.kohesive.injekt.api.get

/**
 * Base class for every source in this repo.
 *
 * Gives each source the same settings screen:
 *  - a site mirror picker plus a field for a custom domain;
 *  - a short "how to log in" note (log in through the app's WebView, cookies are kept);
 *  - optional fields for pasting browser cookies or an access token, as a fallback.
 */
abstract class RuSource : HttpSource(), ConfigurableSource {

    /** Mirrors as "label" to "https://domain". The first one is the default. */
    protected abstract val mirrors: List<Pair<String, String>>

    /** Whether the settings show a field for a Bearer token. */
    protected open val supportsToken: Boolean = false

    /** Text shown under "Как войти". */
    protected open val loginHelp: String =
        "Откройте источник в WebView (значок глобуса 🌐 в каталоге источника), войдите в аккаунт на сайте " +
            "и закройте WebView. Приложение запомнит вход. Если не получилось — вставьте Cookie из браузера ниже."

    protected val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    protected val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    override val baseUrl: String
        get() {
            val custom = preferences.getString(PREF_CUSTOM_DOMAIN, "")?.trim().orEmpty()
            if (custom.isNotEmpty()) return normalizeDomain(custom)
            val chosen = preferences.getString(PREF_DOMAIN, null)
            return mirrors.firstOrNull { it.second == chosen }?.second ?: mirrors.first().second
        }

    protected val manualCookies: String
        get() = preferences.getString(PREF_COOKIES, "")?.trim().orEmpty()

    protected val authToken: String
        get() = preferences.getString(PREF_TOKEN, "")?.trim()?.removePrefix("Bearer ")?.trim().orEmpty()

    override val client: OkHttpClient by lazy {
        val builder = network.cloudflareClient.newBuilder()
            // Tachimanga gives a source about 20 seconds to open a title, so fail fast
            // and switch to another mirror instead of waiting.
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(::mirrorFallbackInterceptor)
            .addInterceptor(::authInterceptor)
        configureClient(builder).build()
    }

    /**
     * When the current mirror can't be reached, retries the request on the other mirrors
     * and remembers the one that answered.
     */
    private fun mirrorFallbackInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val custom = preferences.getString(PREF_CUSTOM_DOMAIN, "")?.trim().orEmpty()
        val current = baseUrl.toHttpUrlOrNull()?.host
        if (custom.isNotEmpty() || mirrors.size < 2 || current == null || !request.url.host.endsWith(current)) {
            return chain.proceed(request)
        }
        val firstError = try {
            val response = chain.proceed(request)
            if (response.code !in MIRROR_DOWN_CODES) return response
            response.close()
            IOException("HTTP ${response.code}")
        } catch (e: IOException) {
            e
        }
        for (mirror in mirrors.map { it.second }) {
            val host = mirror.toHttpUrlOrNull()?.host ?: continue
            if (host == current) continue
            val newHost = request.url.host.removeSuffix(current) + host
            val retry = request.newBuilder()
                .url(request.url.newBuilder().host(newHost).build())
                .header("Referer", "$mirror/")
                .build()
            try {
                val response = chain.proceed(retry)
                if (response.code in MIRROR_DOWN_CODES) {
                    response.close()
                    continue
                }
                preferences.edit().putString(PREF_DOMAIN, mirror).apply()
                return response
            } catch (_: IOException) {
            }
        }
        throw IOException("Сайт $name не отвечает (${firstError.message}). Проверьте интернет или выберите другое зеркало в настройках.", firstError)
    }

    /**
     * Login status shown at the top of the settings. Return null when the source has no login.
     * By default it looks for one of [authCookieNames] in the app's saved cookies.
     */
    protected open fun loginStatus(): Boolean? {
        if (authCookieNames.isEmpty() && !supportsToken) return null
        if (manualCookies.isNotEmpty() || (supportsToken && authToken.isNotEmpty())) return true
        return hasAuthCookie()
    }

    /** Extra text under the login status, for example why the login failed. */
    protected open fun loginDetails(): String? = null

    /** Parts of cookie names that only exist after logging in. */
    protected open val authCookieNames: List<String> = emptyList()

    protected fun hasAuthCookie(vararg extraUrls: String): Boolean = try {
        (listOf(baseUrl) + extraUrls).mapNotNull { it.toHttpUrlOrNull() }.any { url ->
            client.cookieJar.loadForRequest(url).any { cookie ->
                authCookieNames.any { cookie.name.contains(it, ignoreCase = true) } && cookie.value.isNotBlank()
            }
        }
    } catch (_: Exception) {
        false
    }

    /** Sources add their own interceptors here. */
    protected open fun configureClient(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")

    /** Adds pasted cookies and the token to requests that go to the site or its API. */
    private fun authInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val siteHost = baseUrl.toHttpUrlOrNull()?.host?.removePrefix("www.").orEmpty()
        val host = request.url.host
        val isSite = siteHost.isNotEmpty() && (host == siteHost || host.endsWith(".$siteHost") || isOwnHost(host))
        if (!isSite) return chain.proceed(request)

        val builder = request.newBuilder()
        val cookies = manualCookies
        if (cookies.isNotEmpty()) {
            val existing = request.header("Cookie")
            builder.header("Cookie", if (existing.isNullOrBlank()) cookies else "$existing; $cookies")
        }
        val token = authToken
        if (supportsToken && token.isNotEmpty() && request.header("Authorization") == null) {
            builder.header("Authorization", "Bearer $token")
        }
        return chain.proceed(builder.build())
    }

    /** Extra hosts (API domains, CDNs) that should also get the login data. */
    protected open fun isOwnHost(host: String): Boolean = false

    protected inline fun <reified T> Response.parseAs(): T = use {
        json.decodeFromString(it.body.string())
    }

    protected inline fun <reified T> String.parseAs(): T = json.decodeFromString(this)

    protected fun apiGet(url: String, headers: Headers = this.headers): Request = GET(url, headers)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val context = screen.context

        loginStatus()?.let { loggedIn ->
            EditTextPreference(context).apply {
                key = "ru_login_status"
                title = if (loggedIn) "Статус входа: ✅ вы вошли" else "Статус входа: ❌ вход не выполнен"
                summary = loginHelp + "\n\nСтатус обновится, когда вы снова откроете эти настройки." +
                    (loginDetails()?.let { "\n\nПодробности: $it" } ?: "")
                setEnabled(false)
            }.let(screen::addPreference)
        }

        EditTextPreference(context).apply {
            key = PREF_COOKIES
            title = "Cookie из браузера (запасной вариант)"
            dialogTitle = "Вставьте строку Cookie (необязательно)"
            summary = cookieSummary(manualCookies.isNotEmpty())
            setDefaultValue("")
            setOnPreferenceChangeListener { pref, value ->
                pref.summary = cookieSummary((value as String).isNotBlank())
                true
            }
        }.let(screen::addPreference)

        if (mirrors.size > 1) {
            ListPreference(context).apply {
                key = PREF_DOMAIN
                title = "Зеркало сайта"
                entries = mirrors.map { "${it.first} (${it.second.removePrefix("https://")})" }.toTypedArray()
                entryValues = mirrors.map { it.second }.toTypedArray()
                setDefaultValue(mirrors.first().second)
                summary = "%s\nПосле смены перезапустите приложение."
            }.let(screen::addPreference)
        }

        EditTextPreference(context).apply {
            key = PREF_CUSTOM_DOMAIN
            title = "Свой домен"
            dialogTitle = "Свой домен (например, https://example.org)"
            summary = summaryOr(PREF_CUSTOM_DOMAIN, "Если сайт переехал на новый адрес. Оставьте пустым, чтобы использовать зеркало выше.")
            setDefaultValue("")
            setOnPreferenceChangeListener { pref, value ->
                pref.summary = (value as String).ifBlank { "Не задан" }
                true
            }
        }.let(screen::addPreference)

        if (supportsToken) {
            EditTextPreference(context).apply {
                key = PREF_TOKEN
                title = "Токен доступа (запасной вариант)"
                dialogTitle = "Вставьте токен (без слова Bearer)"
                summary = if (authToken.isEmpty()) "Не задан" else "Задан"
                setDefaultValue("")
                setOnPreferenceChangeListener { pref, value ->
                    pref.summary = if ((value as String).isBlank()) "Не задан" else "Задан"
                    true
                }
            }.let(screen::addPreference)
        }

        setupExtraPreferences(screen)
    }

    /** Source-specific settings go here. */
    protected open fun setupExtraPreferences(screen: PreferenceScreen) {}

    private fun cookieSummary(set: Boolean) =
        (if (loginStatus() == null) loginHelp + "\n\n" else "Если вход через WebView 🌐 не сработал. ") +
            "Cookie: " + if (set) "заданы" else "не заданы"

    private fun summaryOr(key: String, fallback: String): String =
        preferences.getString(key, "")?.takeIf { it.isNotBlank() } ?: fallback

    protected fun normalizeDomain(raw: String): String {
        var url = raw.trim().trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        return url
    }

    companion object {
        const val PREF_DOMAIN = "ru_domain"
        const val PREF_CUSTOM_DOMAIN = "ru_custom_domain"
        const val PREF_COOKIES = "ru_manual_cookies"
        const val PREF_TOKEN = "ru_auth_token"
        private val MIRROR_DOWN_CODES = setOf(502, 503, 504, 520, 521, 522, 523, 524, 525, 526)
    }
}
