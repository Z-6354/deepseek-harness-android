plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The release version, taken from the git tag the release workflow is building.
 *
 * `DSH_VERSION_NAME` is the tag without its leading `v`. Hardcoding it here meant every tag after
 * the first shipped an APK still claiming to be the first — same `versionCode`, so Android saw no
 * upgrade at all. The code is derived from the name so it rises with semver on its own; the
 * fallback is what a local `assembleRelease` builds.
 */
val dshVersionName: String = System.getenv("DSH_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.12.2"

val dshVersionCode: Int = dshVersionName
    .substringBefore('-')
    .split('.')
    .mapNotNull { it.toIntOrNull() }
    .let { parts ->
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        major * 10_000 + minor * 100 + patch
    }
    .coerceAtLeast(1)

android {
    namespace = "com.labteto.dshmobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.labteto.dshmobile"
        minSdk = 28
        targetSdk = 35
        versionCode = dshVersionCode
        versionName = dshVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Optional release signing: provide DSH_KEYSTORE / DSH_KEYSTORE_PASSWORD /
    // DSH_KEY_ALIAS / DSH_KEY_PASSWORD (env vars, e.g. from GitHub secrets).
    // Signing activates only when the keystore file actually exists, so a
    // missing keystore silently falls back to an unsigned release APK.
    signingConfigs {
        val keystore = System.getenv("DSH_KEYSTORE")
        if (!keystore.isNullOrBlank() && file(keystore).exists()) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("DSH_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DSH_KEY_ALIAS")
                keyPassword = System.getenv("DSH_KEY_PASSWORD")
            }
            buildTypes.getByName("release") {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("main").java.setSrcDirs(listOf("src/shell/java"))
        getByName("main").assets.setSrcDirs(listOf("src/shell/assets"))
        getByName("test").java.setSrcDirs(listOf("src/shellTest/java"))
        getByName("androidTest").java.setSrcDirs(listOf("src/shellAndroidTest/java"))
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        disable += listOf("MissingTranslation")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)

    implementation("androidx.webkit:webkit:1.16.0")
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
}
