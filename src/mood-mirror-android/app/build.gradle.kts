import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val prepareNotices by tasks.registering(Sync::class) {
    from(rootProject.file("NOTICE.md"))
    from(rootProject.file("../../THIRD-PARTY-NOTICES.md"))
    from(rootProject.file("../../licenses")) { into("licenses") }
    into(layout.buildDirectory.dir("generated/noticeAssets"))
}

android {
    namespace = "com.kentaurverk.moodmirror"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kentaurverk.moodmirror"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    val signingFile = rootProject.file("keystore.properties")
    if (signingFile.exists()) {
        val signing = Properties().apply { FileInputStream(signingFile).use(::load) }
        signingConfigs {
            create("localRelease") {
                storeFile = rootProject.file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("localRelease")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    // Both build types package the shared assets from the full repository checkout.
    sourceSets["main"].assets.srcDirs(
        rootProject.file("app/src/main/assets"),
        rootProject.file("../mood-mirror-flask/models"),
        rootProject.file("../mood-mirror-flask/static/images/dog"),
        layout.buildDirectory.dir("generated/noticeAssets"),
    )
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.runtime:runtime-livedata")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")


    implementation("com.google.mediapipe:tasks-vision:0.10.26")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

val verifiedModelAssets = mapOf(
    "../mood-mirror-flask/models/face_landmarker.task" to "64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff",
    "../mood-mirror-flask/models/enet_b2_7.onnx" to "7863032d8c0c0bc259aa0dd7b8ebf6b607af454e7a0ea82be2b37767914ecd0f",
)

val verifyModelAssets by tasks.registering {
    doLast {
        verifiedModelAssets.forEach { (relativePath, expectedHash) ->
            val asset = rootProject.file(relativePath)
            check(asset.isFile) {
                "Missing required model asset: ${asset.absolutePath}. " +
                    "Run `python ../../scripts/download_models.py` from the Android project directory."
            }
            val actualHash = MessageDigest.getInstance("SHA-256").digest(asset.readBytes())
                .joinToString("") { byte -> "%02x".format(byte) }
            check(actualHash == expectedHash) {
                "Model asset hash mismatch for ${asset.name}: expected $expectedHash, got $actualHash"
            }
        }
    }
}

tasks.named("preBuild") { dependsOn(verifyModelAssets, prepareNotices) }
