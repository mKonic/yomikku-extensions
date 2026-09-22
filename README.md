# Yomikku Extensions

Novel sources for [Yomikku](https://github.com/mKonic/yomikku), ported to Kotlin from the [LNReader plugins](https://github.com/LNReader/lnreader-plugins).

## Adding the store

In Yomikku, open Browse → Extensions → Extension repos and add:

```
https://raw.githubusercontent.com/mKonic/yomikku-extensions/repo/index.min.json
```

## Building

```
./gradlew assembleRelease
```

Each extension is a module under `src/<lang>/<name>`. Sites that share a theme are generated from `lib-multisrc/<theme>` with `scripts/gen-lnreader.py`.

## License

Apache 2.0, see [LICENSE](LICENSE). The themes, filters and icons ported from the LNReader plugins keep their MIT license, see [LICENSE-lnreader-plugins](LICENSE-lnreader-plugins).
