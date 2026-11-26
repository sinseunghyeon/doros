import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.sinseunghyeon.doros"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.sinseunghyeon.doros"
        // 무선 디버깅 ADB 페어링은 Android 11+(런타임 체크). 표시 전용은 그 이하도 가능.
        minSdk = 24
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName

        // android/local.properties 의 sentry.dsn (git 제외). 비어 있으면 전송하지 않는다.
        val sentryDsn = sentryDsnFromLocalProperties()
        buildConfigField("String", "SENTRY_DSN", "\"${sentryDsn.escapeForBuildConfig()}\"")
        manifestPlaceholders["sentryDsn"] = sentryDsn
        manifestPlaceholders["sentryAutoInit"] = if (sentryDsn.isEmpty()) "false" else "true"
    }

    buildTypes {
        release {
            // 친구 배포용: 디버그 키로 서명 (Play 스토어에 올릴 거면 별도 keystore로 교체)
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

// Kotlin 2.2 부터 kotlinOptions{} 는 에러 → compilerOptions 사용
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 앱 내 무선 디버깅(ADB) — 셸 권한으로 시간대 변경
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    // 숨김 API(IAlarmManager 트랜잭션 코드, SystemProperties) 리플렉션 허용
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    // 진단 로그·미처리 예외. 위젯 프로세스에서도 초기화되도록 네이티브 SDK.
    implementation("io.sentry:sentry-android:8.65.0")
}

/** android/local.properties (SDK 경로와 함께 두는 로컬 파일). 없으면 빈 문자열. */
fun sentryDsnFromLocalProperties(): String {
    val file = rootProject.file("local.properties")
    if (!file.exists()) return ""
    val props = Properties()
    file.inputStream().use { props.load(it) }
    return (props.getProperty("sentry.dsn") ?: "").trim()
}

fun String.escapeForBuildConfig(): String = replace("\\", "\\\\").replace("\"", "\\\"")

flutter {
    source = "../.."
}
