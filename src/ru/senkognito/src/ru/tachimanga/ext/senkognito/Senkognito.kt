package ru.tachimanga.ext.senkognito

import ru.tachimanga.multisrc.senkuro.Senkuro

class Senkognito : Senkuro() {
    override val name = "Senkognito"
    override val appId = "5033164800100"
    override val mirrors = listOf(
        "Россия" to "https://senkuro.me",
        "Основной" to "https://senkognito.com",
    )
}
