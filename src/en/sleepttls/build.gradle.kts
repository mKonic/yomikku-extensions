plugins {
    id("yomikku.extension")
}

yomikku {
    name = "SleepyTranslations"
    className = ".SleepyTranslations"
    versionCode = 1
    theme("madara")
    source(name = "SleepyTranslations", lang = "en", baseUrl = "https://sleepytranslations.com")
}
