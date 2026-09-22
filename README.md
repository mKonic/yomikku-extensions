# Yomikku Extensions

Novel sources for [Yomikku](https://github.com/mKonic/yomikku), ported to Kotlin from the [LNReader plugins](https://github.com/LNReader/lnreader-plugins).

## Adding the store

Yomikku adds this store on its own. To add it again after removing it, open Browse → Extensions → ⋮ → Extension stores, tap Add extension store and enter:

```
https://raw.githubusercontent.com/mKonic/yomikku-extensions/repo/index.min.json
```

## Building

```
./gradlew assembleRelease
```

Each extension is a module under `src/<lang>/<name>`. Sites that share a theme extend `lib-multisrc/<theme>` and are generated from an LNReader plugins checkout with `scripts/gen-lnreader.py`.

## License

Apache 2.0, see [LICENSE](LICENSE). The themes, filters and icons ported from the LNReader plugins keep their MIT license, see [LICENSE-lnreader-plugins](LICENSE-lnreader-plugins).
