pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Naver Maps Android SDK(F005 지도). Maven Central에는 없고 Naver가 운영하는 저장소만 제공한다.
        maven { url = uri("https://repository.map.naver.com/archive/maven") }
    }
}

rootProject.name = "gilpick"
include(":app")
