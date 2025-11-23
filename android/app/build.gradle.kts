plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.aboutLibraries)
    id("kotlin-parcelize")
}

android {
    namespace = "me.kavishdevar.librepods"
    compileSdk = 36

    defaultConfig {
        applicationId = "me.kavishdevar.librepods"
        minSdk = 33
        targetSdk = 36
        versionCode = 9
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        viewBinding = true
    }
    androidResources {
        generateLocaleConfig = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    sourceSets {
        getByName("main") {
            res.srcDirs("src/main/res", "src/main/res-apple")
        }
    }
}

dependencies {
    implementation(libs.accompanist.permissions)
    implementation(libs.hiddenapibypass)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.annotations)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.haze)
    implementation(libs.haze.materials)
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.androidx.compose.ui)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.aboutlibraries)
    implementation(libs.aboutlibraries.compose.m3)
    // compileOnly(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    // implementation(fileTree(mapOf("dir" to "lib", "include" to listOf("*.aar"))))
    compileOnly(files("libs/libxposed-api-100.aar"))
    debugImplementation(files("libs/backdrop-debug.aar"))
    releaseImplementation(files("libs/backdrop-release.aar"))
}

aboutLibraries {
    export{
        prettyPrint = true
        excludeFields = listOf("generated")
        outputFile = file("src/main/res/raw/aboutlibraries.json")
    }
}
