import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.aboutLibraries)
//    alias(libs.plugins.hilt)
    id("kotlin-parcelize")
}

val props = Properties().apply {
    load(rootProject.file("local.properties").inputStream())
}

android {
    signingConfigs {
        create("release") {
            storeFile = file(props["RELEASE_STORE_FILE"] as String)
            storePassword = props["RELEASE_STORE_PASSWORD"] as String
            keyAlias = props["RELEASE_KEY_ALIAS"] as String
            keyPassword = props["RELEASE_KEY_PASSWORD"] as String
        }
    }
    namespace = "me.kavishdevar.librepods"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.kavishdevar.librepods"
        minSdk = 33
        targetSdk = 37
        versionCode = 28
        versionName = "0.2.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            externalNativeBuild {
                cmake {
                    arguments += "-DCMAKE_BUILD_TYPE=Release"
                }
            }
            buildConfigField("Boolean", "PLAY_BUILD", "false")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            buildConfigField("Boolean", "PLAY_BUILD", "false")
            signingConfig = signingConfigs.getByName("release")
        }
        create("playRelease") {
            initWith(getByName("release"))
            buildConfigField("Boolean", "PLAY_BUILD", "true")
        }
        create("playDebug") {
            initWith(getByName("debug"))
            buildConfigField("Boolean", "PLAY_BUILD", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
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
            res.directories+="src/main/res-apple"
        }
    }

    ndkVersion = "30.0.14904198"

    flavorDimensions += "env"

    productFlavors {
        create("normal") {
            dimension = "env"
            externalNativeBuild {
                cmake {
                    arguments += "-DIS_XPOSED=OFF"
                }
            }
        }
        create("xposed") {
            dimension = "env"
            externalNativeBuild {
                cmake {
                    arguments += "-DIS_XPOSED=ON"
                }
            }
            versionNameSuffix = "-xposed"
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
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
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.billing)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.aboutlibraries)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.backdrop)
//    implementation(libs.hilt)
//    implementation(libs.hilt.compiler)
    add("xposedCompileOnly", libs.libxposed.api)
    add("xposedImplementation", libs.libxposed.service)
    add("playReleaseImplementation", libs.billing)
}

aboutLibraries {
    export{
        prettyPrint = true
        excludeFields = listOf("generated")
        outputFile = file("src/main/res/raw/aboutlibraries.json")
    }
}
