pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "tachimanga-sources-for-russians"

// Every folder in src/<lang>/<name> is one extension.
File(rootDir, "src").listFiles()?.filter { it.isDirectory }?.sorted()?.forEach { langDir ->
    langDir.listFiles()?.filter { File(it, "build.gradle").exists() }?.sorted()?.forEach { extDir ->
        val name = ":${langDir.name}.${extDir.name}"
        include(name)
        project(name).projectDir = extDir
    }
}
