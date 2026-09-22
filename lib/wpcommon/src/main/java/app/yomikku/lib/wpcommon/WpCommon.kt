package app.yomikku.lib.wpcommon

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Helpers the WordPress novel themes share: challenge detection, lazy-loaded images and release dates.
 */
object WpCommon {

    private val CHALLENGE_TITLES = setOf(
        "Bot Verification", "You are being redirected...", "Un instant...", "Just a moment...", "Redirecting...",
        "Making sure you're not a bot!",
    )

    /**
     * Throws when the site answered with a challenge page, or redirected to another host, instead of content. Opening
     * the page in the app's WebView usually clears a challenge.
     */
    fun Document.checkBlocked(response: Response, baseUrl: String): Document {
        val siteHost = baseUrl.toHttpUrl().host.removePrefix("www.")
        val finalHost = response.request.url.host.removePrefix("www.")
        if (title().trim() in CHALLENGE_TITLES || !finalHost.endsWith(siteHost)) {
            throw Exception("Captcha error, please open in WebView")
        }
        return this
    }

    /** The real source of a possibly lazy-loaded image. */
    fun Element.imageUrl(): String? {
        val url = absUrl("data-lazy-src").ifEmpty { absUrl("data-src") }
            .ifEmpty { attr("data-lazy-srcset").substringBefore(' ') }
            .ifEmpty { absUrl("src") }
        return url.ifEmpty { null }
    }

    private val RELATIVE_UNITS = mapOf(
        Calendar.SECOND to listOf("detik", "segundo", "second", "saniye", "วินาที", "ثانية"),
        Calendar.MINUTE to listOf("menit", "dakika", "min", "minute", "minuto", "นาที", "دقائق", "دقيقة"),
        Calendar.HOUR to listOf("jam", "saat", "heure", "hora", "hour", "ชั่วโมง", "giờ", "ore", "ساعة", "ساعات", "小时"),
        Calendar.DAY_OF_YEAR to listOf("hari", "gün", "jour", "día", "dia", "day", "วัน", "ngày", "giorni", "أيام", "يوم", "天"),
        Calendar.WEEK_OF_YEAR to listOf("week", "semana", "hafta", "minggu", "أسبوع"),
        Calendar.MONTH to listOf("month", "mes", "mês", "ay", "bulan", "mois", "شهر"),
        Calendar.YEAR to listOf("year", "año", "ano", "yıl", "tahun", "an", "سنة"),
    )

    private val DATE_FORMATS = listOf(
        "MMMM d, yyyy", "MMMM dd, yyyy", "MMM d, yyyy", "dd/MM/yyyy", "MM/dd/yyyy", "yyyy-MM-dd", "d MMMM yyyy",
        "d 'de' MMMM 'de' yyyy",
    )

    /**
     * A release date, either absolute ("March 3, 2024", in English or [locale]) or relative ("3 days ago"). 0 when
     * it can't be read.
     */
    fun parseDate(text: String, locale: Locale = Locale.ENGLISH): Long {
        val trimmed = text.trim()
        val number = Regex("\\d+").find(trimmed)?.value?.toIntOrNull()
        val unit = RELATIVE_UNITS.entries
            .firstOrNull { (_, words) -> words.any { Regex("(?i)(^|\\P{L})${Regex.escape(it)}").containsMatchIn(trimmed) } }
            ?.key
        if (number != null && unit != null && !Regex("\\d{4}").containsMatchIn(trimmed)) {
            return Calendar.getInstance().apply { add(unit, -number) }.timeInMillis
        }
        val locales = listOf(locale, Locale.ENGLISH).distinct()
        return locales.firstNotNullOfOrNull { loc ->
            DATE_FORMATS.firstNotNullOfOrNull { format ->
                try {
                    SimpleDateFormat(format, loc).apply { isLenient = false }.parse(trimmed)?.time
                } catch (_: Exception) {
                    null
                }
            }
        } ?: 0L
    }
}
