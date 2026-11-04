package ru.tachimanga.ext.readmanga

import ru.tachimanga.multisrc.grouple.GroupLe

class ReadManga : GroupLe() {
    override val name = "ReadManga"

    // Same id as the original Tachiyomi extension, so library entries carry over.
    override val id: Long = 5

    override val siteId: Int = 1

    override val mirrors = listOf(
        "Основной" to "https://a.zazaza.me",
        "readmanga.me" to "https://readmanga.me",
    )
}
