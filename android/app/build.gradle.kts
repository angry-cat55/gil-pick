plugins {
    id("com.android.application")
    kotlin("plugin.serialization")
    kotlin("plugin.compose")
}

/**
 * Non-secret build inputs. The contest App Link host and API base URL differ per
 * environment, so they come from `gradle.properties` (or `-P` / CI overrides)
 * rather than being hardcoded. See specs/001-kakao-auth/quickstart.md.
 */
val appLinkHost: String = providers.gradleProperty("GILPICK_ANDROID_APP_LINK_HOST").get()
val apiBaseUrl: String = providers.gradleProperty("GILPICK_API_BASE_URL").get()

android {
    namespace = "com.gilpick"

    // Compose BOM 2026.08.00(Compose 1.12.0)과 core-ktx 1.19.0, okhttp 5.5.0이
    // compileSdk 37 이상을 요구한다. compileSdk는 compile 시점에 쓸 수 있는 API 집합일
    // 뿐이므로 런타임 동작 계약인 targetSdk는 36으로 유지하고 compileSdk만 37로 둔다.
    // 근거와 검토한 대안은 specs/001-kakao-auth/research.md 2절에 있다.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.gilpick"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Claimed by the verified App Link intent filter in AndroidManifest.xml.
        manifestPlaceholders["appLinkHost"] = appLinkHost
        buildConfigField("String", "APP_LINK_HOST", "\"$appLinkHost\"")
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")

    implementation("androidx.browser:browser:1.10.0")
    implementation("androidx.datastore:datastore:1.2.1")
    // okio 저장소를 직접 사용하므로 transitive 의존이 아니라 명시적으로 선언한다.
    implementation("androidx.datastore:datastore-core-okio:1.2.1")
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.11.0")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:3.0.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver3-junit4:5.5.0")

    androidTestImplementation(platform("androidx.compose:compose-bom:2026.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.work:work-testing:2.11.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
