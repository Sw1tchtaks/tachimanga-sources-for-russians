package ru.tachimanga.common

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.RequestBody

// Own request helpers. Tachimanga doesn't ship every helper from the Tachiyomi
// library (for example GET with an HttpUrl), so sources use these instead.

fun GET(url: String, headers: Headers): Request = Request.Builder().url(url).headers(headers).build()

fun GET(url: HttpUrl, headers: Headers): Request = GET(url.toString(), headers)

fun POST(url: String, headers: Headers, body: RequestBody): Request =
    Request.Builder().url(url).headers(headers).post(body).build()
