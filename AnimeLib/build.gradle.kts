// Release version (semver). Used for the GitHub release tag.
val versionName = "0.1.0"

// CloudStream requires an integer version: MAJOR * 10000 + MINOR * 100 + PATCH
version = versionName.split(".").map { it.toInt() }.let { (major, minor, patch) ->
    major * 10000 + minor * 100 + patch
}

cloudstream {
    description = "AnimeLib (animelib.org): каталог, пошук, озвучки та субтитри через Kodik"
    authors = listOf("liubquanti")

    /**
     * 0: Down, 1: Ok, 2: Slow, 3: Beta-only
     */
    status = 1

    tvTypes = listOf("Anime", "AnimeMovie", "OVA")

    requiresResources = false
    language = "ru"

    iconUrl = "https://www.google.com/s2/favicons?domain=animelib.org&sz=%size%"
}
