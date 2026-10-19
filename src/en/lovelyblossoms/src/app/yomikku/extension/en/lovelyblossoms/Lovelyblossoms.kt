package app.yomikku.extension.en.lovelyblossoms

import app.yomikku.multisrc.madara.Madara

class Lovelyblossoms : Madara("Lovelyblossoms", "https://lovelyblossoms.com", "en", useNewChapterEndpoint = true) {
    // The site's bot check turns away requests without a browser's Accept headers, clearance cookie or not.
    override fun headersBuilder() = super.headersBuilder()
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .set("Accept-Language", "en-US,en;q=0.9")
}
