package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

@Suppress("unused")
class AndroidCookieJar private constructor() : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>): Unit = throw Exception("Stub!")

    override fun loadForRequest(url: HttpUrl): List<Cookie> = throw Exception("Stub!")

    fun get(url: HttpUrl): List<Cookie> = throw Exception("Stub!")

    fun remove(url: HttpUrl, cookieNames: List<String>? = null, maxAge: Int = -1): Int = throw Exception("Stub!")
}
