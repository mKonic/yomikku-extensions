package app.yomikku.lib.paced

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Fetching many pages of one list from sites that answer a burst with 429.
 */
object Paced {

    /** Pause between the pages of one list. */
    const val PAGE_DELAY_MS = 150L

    private const val MAX_ATTEMPTS = 4

    /**
     * Runs [request], and on 429 waits as long as the site asks (Retry-After), or a little longer each time, before
     * trying again. Any other failure throws [HttpException].
     */
    suspend fun fetch(client: OkHttpClient, request: Request): Response {
        repeat(MAX_ATTEMPTS) { attempt ->
            val response = client.newCall(request).await()
            if (response.code != 429) {
                if (!response.isSuccessful) {
                    response.close()
                    throw HttpException(response.code)
                }
                return response
            }
            // A Cloudflare challenge only clears in a browser, and asking again makes it last longer.
            if (response.header("cf-mitigated") == "challenge") {
                response.close()
                throw Exception("Captcha error, please open in WebView")
            }
            val wait = response.header("Retry-After")?.toLongOrNull()?.times(1000) ?: (3000L * (attempt + 1))
            response.close()
            delay(wait)
        }
        throw HttpException(429)
    }
}
