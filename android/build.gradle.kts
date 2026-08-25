plugins {
    id("com.android.application") version "9.3.2" apply false
    // AGP 9부터 Kotlin 지원이 내장되므로 kotlin-android plugin은 적용하지 않는다.
    kotlin("plugin.serialization") version "2.4.10" apply false
    kotlin("plugin.compose") version "2.4.10" apply false
}
