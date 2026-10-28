plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.deckmate.shot"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.deckmate.shot"
        minSdk = 29
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    val ksPath = System.getenv("KEYSTORE_PATH")
    if (ksPath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(ksPath)
                storeType = "pkcs12"
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "deckmate"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    flavorDimensions += "mode"
    productFlavors {
        create("basic") { dimension = "mode" }
        create("fast") { dimension = "mode" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (ksPath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
