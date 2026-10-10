plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.gamebex.client"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gamebex.client"
        minSdk = 26
        // Pinned on purpose; lint would otherwise flag the older target.
        //noinspection OldTargetApi
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing uses a local keystore that never enters git: GAMEBEX_RELEASE_KEYSTORE and
    // GAMEBEX_RELEASE_PASSWORD_FILE (Gradle properties or environment) name the keystore and a file
    // holding its password. Without them (CI) the release build is unsigned.
    val releaseKeystore = (findProperty("GAMEBEX_RELEASE_KEYSTORE") as String?) ?: System.getenv("GAMEBEX_RELEASE_KEYSTORE")
    val releasePasswordFile = (findProperty("GAMEBEX_RELEASE_PASSWORD_FILE") as String?) ?: System.getenv("GAMEBEX_RELEASE_PASSWORD_FILE")
    signingConfigs {
        if (releaseKeystore != null && releasePasswordFile != null) {
            create("release") {
                val password = file(releasePasswordFile).readText().trim()
                storeFile = file(releaseKeystore)
                storePassword = password
                keyAlias = "gamebex"
                keyPassword = password
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
        // IShellFiles: the Shizuku UserService interface.
        aidl = true
    }
    testOptions.unitTests.all { val nativeTmp = layout.buildDirectory.dir("tmp/unit-tests").get().asFile
            it.doFirst { nativeTmp.mkdirs() }
            it.systemProperty("java.io.tmpdir", nativeTmp.absolutePath) }

    // MigrationTestHelper reads exported schemas from test assets.
    sourceSets {
        getByName("androidTest") {
            assets.srcDir("$projectDir/schemas")
        }
    }
}

// Room schema export: migration tests validate against these snapshots.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    // Versions marked noinspection are pinned on purpose; lint would otherwise flag newer releases.
    //noinspection GradleDependency
    implementation("androidx.core:core-ktx:1.13.1")
    //noinspection GradleDependency
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    //noinspection GradleDependency
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    //noinspection GradleDependency
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    //noinspection GradleDependency
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.5")
    //noinspection GradleDependency
    implementation("androidx.navigation:navigation-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.jsoup:jsoup:1.17.2")
    // On-device 7z extraction (sites mislabel 7z as .zip; torrent7z too).
    //noinspection GradleDependency
    implementation("org.apache.commons:commons-compress:1.26.0")
    implementation("org.tukaani:xz:1.9")
    // Tar reader/writer needs commons-codec at runtime (name charsets).
    //noinspection GradleDependency
    implementation("commons-codec:commons-codec:1.16.1")
    implementation("io.coil-kt:coil-compose:2.6.0")
    //noinspection GradleDependency
    implementation("androidx.room:room-runtime:2.6.1")
    //noinspection GradleDependency
    implementation("androidx.room:room-ktx:2.6.1")
    // EncryptedSharedPreferences for ScreenScraper creds (current recommended line).
    //noinspection GradleDependency
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    //noinspection GradleDependency
    ksp("androidx.room:room-compiler:2.6.1")
    // Shizuku: shell-uid file access for other apps' Android/data (emulator
    // controller configs) on Android 11+. Optional at runtime.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.github.luben:zstd-jni:1.5.7-6@aar")
    testRuntimeOnly("com.github.luben:zstd-jni:1.5.7-6@jar")
    implementation(project(":source-runtime"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation(composeBom)
    //noinspection GradleDependency
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    //noinspection GradleDependency
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    //noinspection GradleDependency
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
