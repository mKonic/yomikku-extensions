plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Faq Wiki"
    className = ".FaqWiki"
    versionCode = 1
    source(name = "Faq Wiki", lang = "en", baseUrl = "https://faqwiki.xyz")
}
