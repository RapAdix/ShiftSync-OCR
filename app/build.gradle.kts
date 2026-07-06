import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.workflowocr"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.workflowocr"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    packaging {
        jniLibs {
            pickFirsts += listOf("**/libc++_shared.so")
        }
    }

    flavorDimensions.add("version")

    productFlavors {
        create("internal") {
            dimension = "version"
            applicationIdSuffix = ".internal"
            versionNameSuffix = "-internal"
            manifestPlaceholders += mapOf("manifestAppName" to "ShiftSync INTERNAL")
        }

        create("production") {
            dimension = "version"
            manifestPlaceholders += mapOf("manifestAppName" to "ShiftSync")
        }
    }

    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localProperties.load(FileInputStream(localPropertiesFile))
    }

    signingConfigs {
        create("releaseConfig") {
            // Read values from local.properties safely
            storeFile = localProperties.getProperty("RELEASE_KEYSTORE_PATH")?.let { file(it) }
            storePassword = localProperties.getProperty("RELEASE_KEYSTORE_PASSWORD")
            keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
            keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("releaseConfig")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        viewBinding = true
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a") // Only include modern phone types
            isUniversalApk = false // Do not generate the giant 200MB combined file
        }
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.datastore)
    implementation(libs.coil.compose)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.camerax)
    implementation(libs.apache.poi)
    implementation(libs.apache.poi.ooxml)
    implementation(project(":opencv"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Guardrail: Sabotage the build if trying to compile the internal flavor as a Release
gradle.taskGraph.whenReady {
    val targetedTasks = allTasks.map { it.name.lowercase() }
    val internals = targetedTasks.filter { it.contains("internal") }
    val internalsNotTest = internals.filterNot { it.contains("unittest") || it.contains("androidtest")}
    val isInternalAndRelease = internalsNotTest.any { it.contains("release") || it.contains("production")}

    if (isInternalAndRelease) {
        throw GradleException(
            "\n\n🚨 COMPILER BLOCKED! 🚨\n" +
                    "You are attempting to build a Release APK using the 'internal' flavor.\n" +
                    "This variant contains your super-secret testing images!\n\n" +
                    "👉 FIX: Switch your Active Build Variant to 'releaseRelease' before building.\n"
        )
    }
}

tasks.register<Copy>("copyLicenseToAssets") {
    description = "Copies the root LICENSE file into the app assets folder so it gets bundled into the APK."
    from(rootProject.file("LICENSE"))
    into(layout.projectDirectory.dir("src/main/assets"))
}

// Ensure this copy task runs BEFORE anything tries to compile or analyze project assets
tasks.configureEach {
    // 1. Catch asset compilation tasks
    if (name.startsWith("generate") && name.endsWith("Assets")) {
        dependsOn("copyLicenseToAssets")
    }

    // 2. Catch ALL Lint-related tasks (analysis, report models, verification, etc.)
    if (name.contains("lint", ignoreCase = true)) {
        // Enforce an explicit execution order sequence so they never conflict
        mustRunAfter("copyLicenseToAssets")
        dependsOn("copyLicenseToAssets")
    }
}