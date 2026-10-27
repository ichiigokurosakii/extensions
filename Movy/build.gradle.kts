version = 5

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

cloudstream {
    description = "Browse movies, TV shows, anime, and available streaming sources from Movy"
    authors = listOf("ichiigokurosakii")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime")
    language = "en"
}
