import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: reads a properties file kept OUTSIDE the repo
// (storeFile / storePassword / keyAlias / keyPassword). Override the path with
// the JEV_KEYSTORE_PROPS env var. Without it a local release build falls back to
// the debug key (see buildTypes.release); a build meant to be published sets
// JEV_REQUIRE_RELEASE_KEY=1 and then fails instead of falling back (gate below).
val releaseProps = Properties().apply {
    val f = File(System.getenv("JEV_KEYSTORE_PROPS") ?: "H:/android/keys/jev-release.properties")  // plain File: Gradle file() rejects "H:/..." on Linux CI
    if (f.exists()) FileInputStream(f).use { load(it) }
}
val requireReleaseKey = System.getenv("JEV_REQUIRE_RELEASE_KEY") == "1"

android {
    namespace = "com.jev.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jev.probe"
        minSdk = 30
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"

        // ML Kit's bundled Chinese recognizer ships native libs for every ABI.
        // The target phone (and every phone this can run on: minSdk 30) is
        // arm64, so keep only that one — the other three are dead weight.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (releaseProps.isNotEmpty()) {
            create("release") {
                storeFile = file(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Prefer the real release key; fall back to the debug key when the
            // props file is unavailable (e.g. CI/dev machines without H:), so
            // `pm install -r` still matches the debug-signed build on devices.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.findByName("debug")
        }
    }

    // Uncompressed, page-aligned .so files: required for the 16 KB page-size
    // devices Android 15+ ships, and it lets the loader mmap the ML Kit natives
    // instead of unpacking them at install time.
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Publish gate: with JEV_REQUIRE_RELEASE_KEY=1, building a release artifact without the
// real release key is an error, never a silent debug-signed "release".
gradle.taskGraph.whenReady {
    val releaseArtifact = allTasks.any {
        it.project == project && Regex("^(assemble|bundle|package)Release$").matches(it.name)
    }
    if (requireReleaseKey && releaseArtifact && android.signingConfigs.findByName("release") == null) {
        throw GradleException(
            "JEV_REQUIRE_RELEASE_KEY=1 but no release signing config: set JEV_KEYSTORE_PROPS to a " +
                "properties file with storeFile/storePassword/keyAlias/keyPassword. Refusing to build a " +
                "debug-signed release."
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // On-device OCR. The *bundled* Chinese model (not the play-services variant):
    // it works on phones with no Google Play services and needs no model download.
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (the android.jar stub throws on use).
    testImplementation("org.json:json:20231013")
}
