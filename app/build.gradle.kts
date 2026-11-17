plugins {
    alias(libs.plugins.android.application)
}

val hasKeystore = !System.getenv("RELEASE_STORE_FILE").isNullOrEmpty()

android {
    namespace = "alzwded.poochgame"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "alzwded.poochgame"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        manifestPlaceholders["appName"] = "Pooch Runner"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = file(System.getenv("RELEASE_STORE_FILE") ?: "/dev/null")
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["appName"] = "Pooch Runner (Debug)"
        }
        release {
            manifestPlaceholders["appName"] = "Pooch Runner"
            applicationIdSuffix = ".gold"
            //isDebuggable = true

            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }

            // don't sign by default assembleRelease
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    androidResources {
        noCompress.addAll(listOf("m4a", "wav", "mp3", "ogg"))
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
