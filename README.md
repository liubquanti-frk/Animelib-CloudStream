# AnimeLib for CloudStream

A [CloudStream](https://github.com/recloudstream/cloudstream) extension for [AnimeLib](https://animelib.org): catalog, search, dubs and subtitles via Kodik.

## Installation

1. Open CloudStream → **Settings** → **Extensions** → **Add repository**.
2. Paste the URL:
   ```
   https://raw.githubusercontent.com/liubquanti-frk/Animelib-CloudStream/main/repo.json
   ```
3. Open the added repository and install **AnimeLib**.

You can also download `AnimeLib.cs3` manually from the [Releases](https://github.com/liubquanti-frk/Animelib-CloudStream/releases) page.

## Building

Requires JDK 17 and the Android SDK (set `ANDROID_HOME` or `sdk.dir` in `local.properties`).

```bash
# build the plugin -> AnimeLib/build/AnimeLib.cs3
./gradlew AnimeLib:make

# build and install it on a connected device via ADB
./gradlew AnimeLib:deployWithAdb
```

On Windows use `gradlew.bat`.

## Releases

Releases are published automatically by GitHub Actions ([release.yml](.github/workflows/release.yml)) on push to `main` when there is no release yet for `versionName` in [`AnimeLib/build.gradle.kts`](AnimeLib/build.gradle.kts) (semver, e.g. `0.1.0`; the integer version required by CloudStream is derived from it). The workflow can also be run manually (Actions → *Run workflow*).
