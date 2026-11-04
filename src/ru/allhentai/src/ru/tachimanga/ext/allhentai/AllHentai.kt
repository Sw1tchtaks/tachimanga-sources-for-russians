package ru.tachimanga.ext.allhentai

import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Request
import org.jsoup.nodes.Document
import ru.tachimanga.multisrc.grouple.GroupLe

class AllHentai : GroupLe() {
    override val name = "AllHentai"

    // Same id as the original Tachiyomi extension, so library entries carry over.
    override val id: Long = 1809051393403180443L

    override val siteId: Int get() = if (baseUrl == "https://x.ahen.me") 11 else 1

    override val authApiUrl: String = "https://z.qawa.org"

    override val mirrors = listOf(
        "Основной" to "https://20.allhen.online",
        "x.ahen.me" to "https://x.ahen.me",
    )

    override val tagsSelector: String = ".cr-tags .cr-tags__item:not(.cr-tags__item--misc) span:not(.text-secondary)"

    override fun authGuard(document: Document) {
        val scripts = document.select("script:containsData(UI.ViewContext)").joinToString()
        if (scripts.contains("useContext('blockedForAnonymous") && document.selectFirst(".user-avatar") == null) {
            throw Exception("Этот тайтл доступен только после входа. Войдите через WebView 🌐 или включите автоматический вход в настройках источника.")
        }
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        if (baseUrl == "https://20.allhen.online" && manga.url.matches(OLD_URL)) {
            throw Exception("Адрес тайтла изменился. Найдите его заново через поиск (миграция).")
        }
        return super.mangaDetailsRequest(manga)
    }

    // The site still uses the old search page, so the lists are built in.
    override fun fetchFilters(): FiltersData = FiltersData(
        sortType = listOf(
            "По популярности" to "RATING",
            "По алфавиту" to "NAME",
            "Популярность сейчас" to "POPULARITY",
            "По рейтингу" to "USER_RATING",
            "Новинки" to "DATE_CREATE",
            "По дате обновления" to "DATE_UPDATE",
        ),
        productionStatus = listOf(
            "Запланирован" to "PLANNED", "Продолжается" to "PROGRESS", "Приостановлен" to "POSTPONED",
            "Отменён" to "CANCELED", "Завершён" to "FINISHED", "Не окончен" to "NON_FINISHED",
        ),
        translationStatus = listOf(
            "Отсутствует" to "NONE", "Начат" to "STARTED", "Продолжается" to "PROGRESS",
            "Приостановлен" to "POSTPONED", "Завершён" to "FINISHED", "Нет необходимости" to "NO_NEED",
        ),
        searchFilters = listOf(
            "Высокий рейтинг" to "HIGH_RATE", "Сингл" to "SINGLE", "Для взрослых" to "MATURE",
            "Переведено" to "TRANSLATED", "Заброшен перевод" to "ABANDONED_POPULAR",
            "Длинная" to "MANY_CHAPTERS", "Ожидает загрузки" to "WAIT_UPLOAD",
        ),
        genre = GENRES,
        category = listOf(
            "3D" to "626", "Анимация" to "5777", "Без текста" to "3157", "Комикс" to "1003",
            "Манга" to "6449", "Манхва" to "1104", "Маньхуа" to "5902", "Руманга" to "5896",
        ),
        tags = runCatching { fetchTags() }.getOrDefault(emptyList()),
    )

    companion object {
        private val OLD_URL = """^/\d+$""".toRegex()

        private val GENRES = listOf(
            "Ahegao" to "855", "Анал" to "828", "Бдсм" to "78", "Без цензуры" to "888",
            "Большая грудь" to "837", "Большая попка" to "3156", "Большой член" to "884", "Бондаж" to "5754",
            "В первый раз" to "811", "В цвете" to "290", "Гарем" to "87", "Гендерная интрига" to "89",
            "Групповой секс" to "88", "Драма" to "95", "Зрелые женщины" to "5679", "Измена" to "291",
            "Изнасилование" to "124", "Инцест" to "85", "Исторический" to "93", "Комедия" to "73",
            "Маленькая грудь" to "870", "Научная фантастика" to "76", "Нетораре" to "303", "Оральный секс" to "853",
            "Романтика" to "74", "Тентакли" to "69", "Трагедия" to "1321", "Ужасы" to "75",
            "Футанари" to "77", "Фэнтези" to "70", "Чикан" to "1059", "Этти" to "798",
        )
    }
}
