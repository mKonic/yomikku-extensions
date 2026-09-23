plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Bookhamster"
    className = ".Bookhamster"
    versionCode = 1
    theme("ifreedom")
    source(name = "Bookhamster", lang = "ru", baseUrl = "https://bookhamster.ru")
}
