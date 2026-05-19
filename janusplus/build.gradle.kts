plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.janusplus"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.janusplus"
        minSdk = 26
        targetSdk = 34
        versionCode = 23
        versionName = "0.23"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    applicationVariants.all {
        outputs.all {
            val abi = filters.find { it.filterType == "ABI" }?.identifier ?: "universal"
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "janusplus-$versionName-${abi}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
}
