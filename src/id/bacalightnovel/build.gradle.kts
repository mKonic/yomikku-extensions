plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Baca Light Novel"
    className = ".BacaLightNovel"
    versionCode = 1
    theme("lightnovelwp")
    source(name = "Baca Light Novel", lang = "id", baseUrl = "https://bacalightnovel.co")
}
