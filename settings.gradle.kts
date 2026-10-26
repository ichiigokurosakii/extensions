rootProject.name = "CloudstreamPlugins"

val disabled = listOf<String>()
File(rootDir, ".").listFiles()?.filter { it.isDirectory }?.forEach { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}
