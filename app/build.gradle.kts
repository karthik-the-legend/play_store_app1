plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Google's official test IDs: https://developers.google.com/admob/android/test-ads
val testAdmobAppId = "ca-app-pub-3940256099942544~3347511713"
val testInterstitialUnit = "ca-app-pub-3940256099942544/1033173712"
val testRewardedUnit = "ca-app-pub-3940256099942544/5224354917"
val testNativeUnit = "ca-app-pub-3940256099942544/2247696110"

// Real AdMob IDs for release builds come from ~/.gradle/gradle.properties (never this repo).
val releaseAdmobAppId: String? = providers.gradleProperty("formkit.admob.appId").orNull
val releaseInterstitialUnit: String? = providers.gradleProperty("formkit.admob.interstitial").orNull
val releaseRewardedUnit: String? = providers.gradleProperty("formkit.admob.rewarded").orNull
val releaseNativeUnit: String? = providers.gradleProperty("formkit.admob.native").orNull
val hasReleaseAdIds = listOf(releaseAdmobAppId, releaseInterstitialUnit, releaseRewardedUnit, releaseNativeUnit)
    .all { !it.isNullOrBlank() }

fun quoted(value: String?) = "\"${value.orEmpty()}\""

android {
    namespace = "app.formkit"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.formkit"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            manifestPlaceholders["admobAppId"] = testAdmobAppId
            buildConfigField("boolean", "ADS_ENABLED", "true")
            buildConfigField("String", "AD_UNIT_INTERSTITIAL", quoted(testInterstitialUnit))
            buildConfigField("String", "AD_UNIT_REWARDED", quoted(testRewardedUnit))
            buildConfigField("String", "AD_UNIT_NATIVE", quoted(testNativeUnit))
            // The fake store lets purchases be tested without a Play Console app.
            // Build with -Pformkit.fakeStore=false to talk to Google Play instead.
            buildConfigField("boolean", "USE_FAKE_STORE", providers.gradleProperty("formkit.fakeStore").orElse("true").get())
            // -Pformkit.consentDebugEea=true shows the consent form as if the phone were in the EEA.
            buildConfigField("boolean", "CONSENT_DEBUG_EEA", providers.gradleProperty("formkit.consentDebugEea").orElse("false").get())
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Without every real ID, release builds show no ads at all rather than test ads.
            manifestPlaceholders["admobAppId"] = if (hasReleaseAdIds) releaseAdmobAppId!! else testAdmobAppId
            buildConfigField("boolean", "ADS_ENABLED", hasReleaseAdIds.toString())
            buildConfigField("String", "AD_UNIT_INTERSTITIAL", quoted(releaseInterstitialUnit))
            buildConfigField("String", "AD_UNIT_REWARDED", quoted(releaseRewardedUnit))
            buildConfigField("String", "AD_UNIT_NATIVE", quoted(releaseNativeUnit))
            buildConfigField("boolean", "USE_FAKE_STORE", "false")
            buildConfigField("boolean", "CONSENT_DEBUG_EEA", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // English ships first; Hindi and Kannada are the next translations. Filtering here
        // also drops the dozens of languages AndroidX bundles, which we can't use anyway.
        localeFilters += listOf("en", "hi", "kn")
        // MediaPipe memory-maps its model, which only works when the asset isn't compressed.
        noCompress += "tflite"
    }

    bundle {
        language { enableSplit = true }
        density { enableSplit = true }
        abi { enableSplit = true }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.exifinterface)
    implementation(libs.coil.compose)
    implementation(libs.mediapipe.tasks.vision)
    // BouncyCastle only serves certificate-encrypted PDFs and adds about 4.2 MB. Password
    // encryption works without it (see DECISIONS.md).
    implementation(libs.pdfbox.android) { exclude(group = "org.bouncycastle") }
    implementation(libs.play.services.ads)
    implementation(libs.user.messaging.platform)
    implementation(libs.billing.ktx)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.leakcanary)

    testImplementation(libs.junit)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // Compose's test rule idles through Espresso; the version it pulls in by default reflects on
    // a hidden InputManager method that no longer exists on Android 17.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
