plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Puffin Folio"
    className = ".PuffinFolio"
    versionCode = 1
    source(name = "Puffin Folio", lang = "en", baseUrl = "https://www.puffinfolio.com")
}
