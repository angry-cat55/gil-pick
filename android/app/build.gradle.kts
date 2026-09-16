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
 * Naver Maps NCP Key ID(F005 지도). 저장소에 두지 않고 `~/.gradle/gradle.properties`나 `-P`로
 * 주입한다. 값이 없어도 debug build·test는 그대로 돌아가야 하므로 빈 값을 허용한다. 빈
 * 값이면 지도 인증이 실패해 지도 영역만 비어 보이고 나머지 화면은 정상 동작한다.
 */
val naverMapsClientId: String = providers.gradleProperty("GILPICK_NAVER_MAPS_CLIENT_ID").orNull.orEmpty()

/**
 * F012 설정 화면이 Custom Tabs로 여는 정책 문서 위치.
 *
 * 승인된 문서 URL은 환경마다 다르고 확정 시점도 저장소 밖이라 `~/.gradle/gradle.properties`나
 * `-P`로 주입한다. 값이 없어도 build·test가 그대로 돌아가야 하므로 빈 값을 허용한다. 빈 값이면
 * 앱이 문서를 열지 않고 "지금은 열 수 없다"고 안내한다(F012 FR-008). 지어낸 URL을 기본값으로
 * 두면 사용자를 잘못된 문서로 보내므로 기본값을 주지 않는다.
 */
val privacyPolicyUrl: String = providers.gradleProperty("GILPICK_PRIVACY_POLICY_URL").orNull.orEmpty()
val termsOfServiceUrl: String = providers.gradleProperty("GILPICK_TERMS_OF_SERVICE_URL").orNull.orEmpty()

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

/**
 * F011 FCM 설정 파일. G001 Firebase 프로젝트에서 받아 `app/google-services.json`에 두며 저장소에는
 * 넣지 않는다(.gitignore). 파일이 없어도 debug 빌드·test는 그대로 돌아가야 하므로 있을 때만
 * google-services 플러그인을 적용한다. 없으면 Firebase가 초기화되지 않아 푸시만 오지 않고
 * 나머지 화면은 정상 동작한다(F005 지도 키와 같은 방식).
 */
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

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
        manifestPlaceholders["naverMapsClientId"] = naverMapsClientId
        buildConfigField("String", "APP_LINK_HOST", "\"$appLinkHost\"")
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"$privacyPolicyUrl\"")
        buildConfigField("String", "TERMS_OF_SERVICE_URL", "\"$termsOfServiceUrl\"")
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

/** APK·AAB를 실제로 만들어 내보내는 task 이름 앞부분. 컴파일·lint만 하는 release task는 검사하지 않는다. */
val releasePackagingPrefixes = listOf("assemble", "bundle", "package", "install")

/** `gradle.properties`에 커밋된 자리표시자 API 도메인. 이 값으로는 어떤 요청도 성공하지 않는다. */
val placeholderApiHost = "api.gilpick.example"

/**
 * 제출용 release 빌드가 자리표시자·빈 값으로 만들어지는 것을 막는다.
 *
 * debug 빌드와 CI는 값이 없어도 돌아가야 해서 위 property들이 모두 선택값이다. 그래서 release도
 * 조용히 성공해 버리는데, 실제로는 서버에 못 붙거나(`GILPICK_API_BASE_URL` 기본값이 존재하지 않는
 * `api.gilpick.example`) 지도가 비거나 무서명 APK가 되어 App Link 검증을 할 수 없다. 사람이 기억하는
 * 대신 build가 막는다(#670).
 *
 * 검사는 APK·AAB를 실제로 만드는 task에만 건다. `compileReleaseKotlin`·`lintRelease`처럼 산출물을
 * 내보내지 않는 release task는 그대로 둔다. task graph가 정해지는 시점에 확인해 컴파일을 시작하기 전에 멈춘다.
 */
gradle.taskGraph.whenReady {
    val packagingRelease = allTasks.any { task ->
        task.project == project &&
            task.name.endsWith("Release") &&
            releasePackagingPrefixes.any { task.name.startsWith(it) }
    }
    if (!packagingRelease) return@whenReady

    val missing = buildList {
        if (apiBaseUrl.isBlank() || apiBaseUrl.contains(placeholderApiHost)) {
            add("GILPICK_API_BASE_URL (현재 값: \"$apiBaseUrl\" — 제출 서버 주소로 바꿔야 한다)")
        }
        if (naverMapsClientId.isBlank()) add("GILPICK_NAVER_MAPS_CLIENT_ID (없으면 지도 화면이 모두 비어 보인다)")
        if (!hasReleaseSigning) {
            add(
                "GILPICK_KEYSTORE_PATH·GILPICK_KEYSTORE_PASSWORD·GILPICK_KEY_ALIAS·GILPICK_KEY_PASSWORD " +
                    "(넷 다 있어야 서명한다. 무서명 APK로는 App Link 검증을 할 수 없다)",
            )
        }
    }
    check(missing.isEmpty()) {
        buildString {
            appendLine("release 빌드에 필요한 값이 빠졌다(#670):")
            missing.forEach { appendLine("  - $it") }
            appendLine("`~/.gradle/gradle.properties`에 넣거나 `-P<이름>=<값>`으로 주입한 뒤 다시 실행한다.")
            append("debug 빌드와 unit test는 이 값들이 없어도 그대로 실행된다.")
        }
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
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    // 목록·생성·상세 세 화면이 되면서 상태 하나로 고르던 방식이 한계에 닿았다.
    // type-safe route는 kotlin("plugin.serialization")이 이미 적용돼 있어 그대로 쓴다.
    implementation("androidx.navigation:navigation-compose:2.10.0")

    // 장소 썸네일은 원격 URL이므로 Coil로 비동기 로드한다. 이미 쓰는 OkHttp를
    // network layer로 재사용하려고 coil-network-okhttp를 함께 선언한다.
    implementation("io.coil-kt.coil3:coil-compose:3.6.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.0")

    // F005 날짜별 경로 지도. 3.21부터 NCP Key ID(`NCP_KEY_ID` meta-data)로 인증하며 minSdk 21 이상.
    implementation("com.naver.maps:map-sdk:3.23.3")

    // F006 진행 시작 시 FusedLocationProviderClient로 현재 위치를 1회 얻는다(research.md 결정 7).
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // F011 푸시 알림 수신·기기 토큰. BOM이 firebase 계열 버전을 맞춘다(research R11).
    implementation(platform("com.google.firebase:firebase-bom:34.4.0"))
    implementation("com.google.firebase:firebase-messaging")

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
    // Compose test의 전이 버전(3.5.0)은 API 37에서 제거된 InputManager API를 사용한다.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.work:work-testing:2.11.2")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver3-junit4:5.5.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
