plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Fester Signaturschlüssel aus GitHub-Secrets (siehe README). Nur mit immer demselben Schlüssel
// kann Android eine installierte App aktualisieren, statt sie neu installieren zu müssen.
val signingKeystore: String? = System.getenv("SIGNING_KEYSTORE_FILE")

// Versionscode steigt mit jedem GitHub-Build automatisch.
// Versionsname: Release-Builds genau die Version, alle anderen version.txt plus Build-Nummer.
val runNumber: Int? = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val baseVersion: String = rootProject.file("version.txt").readText().trim()
val tagName: String? = System.getenv("GITHUB_REF_NAME")
    ?.takeIf { System.getenv("GITHUB_REF")?.startsWith("refs/tags/") == true }
    ?.takeIf { it.matches(Regex("[vV]?\\d+(\\.\\d+)*")) }
val releaseVersion: String? = System.getenv("RELEASE_VERSION")?.takeIf { it.isNotBlank() }
    ?: tagName?.trimStart('v', 'V')

android {
    namespace = "de.zettelwirtschaft.kasse"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.zettelwirtschaft.kasse"
        minSdk = 26
        targetSdk = 35
        versionCode = if (runNumber != null) 100 + runNumber else 1
        versionName = releaseVersion ?: if (runNumber != null) "$baseVersion-build$runNumber" else baseVersion
    }

    signingConfigs {
        if (signingKeystore != null) {
            create("release") {
                storeFile = file(signingKeystore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (signingKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            if (signingKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        // org.json kommt in Unit-Tests aus der echten Bibliothek (siehe testImplementation unten).
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.room:room-runtime:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // QR-Codes (GiroCode, Beleg) ohne Google-Dienste
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
