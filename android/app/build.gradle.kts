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

/**
 * 제출용 release 서명 정보.
 *
 * App Link 검증은 APK 서명 인증서의 SHA-256 fingerprint를 `assetlinks.json`과 대조하므로,
 * release fingerprint를 얻으려면 release 서명이 필요하다. keystore와 비밀번호는 저장소에
 * 두지 않고 `~/.gradle/gradle.properties`나 CI 비밀값에서 주입한다.
 *
 * 네 값이 모두 있을 때만 release 서명을 구성한다. 값이 없는 개발자도 debug 빌드와 test를
 * 그대로 실행할 수 있어야 하기 때문이다.
 */
val releaseKeystorePath: String? = providers.gradleProperty("GILPICK_KEYSTORE_PATH").orNull
val releaseKeystorePassword: String? = providers.gradleProperty("GILPICK_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias: String? = providers.gradleProperty("GILPICK_KEY_ALIAS").orNull
val releaseKeyPassword: String? = providers.gradleProperty("GILPICK_KEY_PASSWORD").orNull
val hasReleaseSigning: Boolean = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

/**
 * 팀 공용 debug keystore 경로.
 *
 * debug keystore는 PC마다 자동 생성되어 서명 fingerprint가 서로 다르다. 팀이 하나를
 * 공유하면 `assetlinks.json`에 fingerprint 하나만 등록해도 모두가 App Link 검증을
 * 통과한다. 비밀번호와 alias는 Android가 정한 고정 공개값이라 여기에 그대로 둔다.
 *
 * 값을 주지 않으면 각자 PC의 자동 생성 keystore를 그대로 쓴다. 이 경우 본인 fingerprint를
 * `assetlinks.json`에 따로 등록해야 App Link 검증이 통과한다.
 */
val debugKeystorePath: String? = providers.gradleProperty("GILPICK_DEBUG_KEYSTORE_PATH").orNull

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

    signingConfigs {
        if (!debugKeystorePath.isNullOrBlank()) {
            getByName("debug") {
                storeFile = file(debugKeystorePath)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }

        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // 서명 정보가 주입되지 않은 환경에서는 서명 없이 빌드한다. 이 APK로는 App Link
            // 검증을 할 수 없으므로 제출용 빌드는 반드시 네 property를 주입해 만든다.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    // AppBar back 등 화면이 쓰는 표준 아이콘. BOM이 버전을 맞춘다.
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    // 목록·생성·상세 세 화면이 되면서 상태 하나로 고르던 방식이 한계에 닿았다.
    // type-safe route는 kotlin("plugin.serialization")이 이미 적용돼 있어 그대로 쓴다.
    implementation("androidx.navigation:navigation-compose:2.10.0")

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
    androidTestImplementation("com.squareup.okhttp3:mockwebserver3-junit4:5.5.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
