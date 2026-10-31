package app.yomikku.lib.anubis

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Passes an Anubis proof-of-work check (`/.within.website/x/cmd/anubis`) the way a browser does. Until the client
 * holds the Anubis cookie, every page is the check page with HTTP 200, so the check is found in the body: the nonce
 * is solved here, the answer sent, and Anubis sets its cookie and redirects to the page that was asked for.
 */
class AnubisInterceptor : Interceptor {

    @Serializable
    private class Challenge(val rules: Rules, val challenge: Data)

    @Serializable
    private class Rules(val algorithm: String, val difficulty: Int)

    @Serializable
    private class Data(val id: String, val randomData: String)

    private val json = Json { ignoreUnknownKeys = true }

    // One check at a time, so requests queued behind it reuse its cookie instead of solving again.
    private val lock = Any()

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isCheckPage()) return response

        synchronized(lock) {
            response.close()
            // A check finished while this one waited: its cookie may already let the request through.
            val retried = chain.proceed(chain.request())
            if (!retried.isCheckPage()) return retried
            val html = retried.body.string()
            val passed = pass(chain, html)
            if (passed.isCheckPage()) {
                passed.close()
                throw IOException(BOT_CHECK_ERROR)
            }
            return passed
        }
    }

    private fun Response.isCheckPage(): Boolean {
        if (code != 200 || body.contentType()?.subtype != "html") return false
        return peekBody(PEEK_BYTES).string().contains(CHALLENGE_SCRIPT)
    }

    private fun pass(chain: Interceptor.Chain, html: String): Response {
        val challenge = readJsonScript(html, "anubis_challenge")
            ?.let { runCatching { json.decodeFromString<Challenge>(it) }.getOrNull() }
            ?: throw IOException(BOT_CHECK_ERROR)
        val rules = challenge.rules
        if (rules.algorithm !in setOf("fast", "slow") || rules.difficulty !in 0..MAX_DIFFICULTY) {
            throw IOException(BOT_CHECK_ERROR)
        }
        val basePrefix = readJsonScript(html, "anubis_base_prefix")
            ?.let { runCatching { json.decodeFromString<String>(it) }.getOrNull() }
            .orEmpty()

        val start = System.currentTimeMillis()
        val (nonce, hash) = ProofOfWork.solve(challenge.challenge.randomData, rules.difficulty)
        val url = chain.request().url
        val passUrl = HttpUrl.Builder()
            .scheme(url.scheme)
            .host(url.host)
            .port(url.port)
            .encodedPath("$basePrefix/.within.website/x/cmd/anubis/api/pass-challenge")
            .addQueryParameter("id", challenge.challenge.id)
            .addQueryParameter("response", hash)
            .addQueryParameter("nonce", nonce.toString())
            .addQueryParameter("redir", url.toString())
            .addQueryParameter("elapsedTime", (System.currentTimeMillis() - start).toString())
            .build()
        return chain.proceed(
            Request.Builder().url(passUrl).headers(chain.request().headers).build(),
        )
    }

    private fun readJsonScript(html: String, id: String): String? {
        return Regex("""<script id="$id" type="application/json">([\s\S]*?)</script>""").find(html)?.groupValues?.get(1)
    }

    companion object {
        private const val CHALLENGE_SCRIPT = "<script id=\"anubis_challenge\""

        // The check page is small; the script sits near its top.
        private const val PEEK_BYTES = 64L * 1024

        // Difficulty 4 is about 65,000 hashes. Each step up is 16 times more work: past that, WebView instead.
        private const val MAX_DIFFICULTY = 4

        const val BOT_CHECK_ERROR = "Bot check could not be passed. Open the site in WebView, wait for it to load, then try again."
    }
}
