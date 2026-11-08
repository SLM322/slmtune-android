import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release key: keystore.properties next to the project (kept out of the sources; see README).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.slmtune.app"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.slmtune.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 410
        versionName = "4.1 beta"
        base.archivesName.set("SLMtune-$versionName".replace(' ', '-'))
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    // "full": sing-box plus the separate cores (Xray, Hysteria 2, Tor, TrustTunnel on ARM64) from
    // tools/fetch_cores.py in src/full/jniLibs. "lite": sing-box only, about half the size.
    flavorDimensions += "edition"
    productFlavors {
        create("full") { dimension = "edition" }
        create("lite") { dimension = "edition"; versionNameSuffix = " lite" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without keystore.properties (someone building from the sources) the debug key is used.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            vcsInfo.include = false
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

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // The separate cores ship exactly as their projects built them (checked by SHA-256): no stripping.
            keepDebugSymbols += listOf("**/libxray.so", "**/libhysteria.so", "**/libtor.so", "**/liblyrebird.so", "**/libtrusttunnel.so")
        }
    }

    // The core is ~75 MB per CPU type: one APK per type, plus a universal one for sharing.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    dependenciesInfo {
        includeInApk = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // sing-box core (GPL-3.0), built from source: G:\android-toolchain\sing-box-src
    implementation(files("libs/libbox.aar"))

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // QR codes: camera preview + ZXing decoding (Apache-2.0, no Google services)
    implementation("androidx.camera:camera-camera2:1.6.1")
    implementation("androidx.camera:camera-lifecycle:1.6.1")
    implementation("androidx.camera:camera-view:1.6.1")
    implementation("com.google.zxing:core:3.5.4")
    // "Мои серверы": SSH (Apache-2.0) with the full Bouncy Castle (MIT) for ed25519 and certificates
    implementation("com.hierynomus:sshj:0.40.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.81")

    testImplementation("junit:junit:4.13.2")
}
