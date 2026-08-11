pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.minecraftforge.net")
        maven("https://maven.architectury.dev/")
        maven("https://maven.valkyrienskies.org")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
        maven("https://maven.valkyrienskies.org")
        maven("https://thedarkcolour.github.io/KotlinForForge/")
    }
}

rootProject.name = "vs-kinetic"
include("common", "forge", "fabric")
