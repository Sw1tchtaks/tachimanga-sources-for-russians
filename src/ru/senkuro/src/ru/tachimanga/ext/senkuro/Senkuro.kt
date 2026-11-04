package ru.tachimanga.ext.senkuro

import ru.tachimanga.multisrc.senkuro.Senkuro

class Senkuro : Senkuro() {
    override val name = "Senkuro"
    override val appId = "4026531840100"
    override val mirrors = listOf(
        "Основной" to "https://senkuro.com",
        "Россия" to "https://senkuro.me",
    )
}
