package eu.kanade.tachiyomi.network

import okhttp3.OkHttpClient

@Suppress("unused")
open class NetworkHelper private constructor() {

    open val cookieJar: AndroidCookieJar = throw Exception("Stub!")

    open val client: OkHttpClient = throw Exception("Stub!")

    @Deprecated("The regular client handles Cloudflare by default")
    open val cloudflareClient: OkHttpClient = throw Exception("Stub!")

    fun defaultUserAgentProvider(): String = throw Exception("Stub!")
}
