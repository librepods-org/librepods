import java.util.Properties

val appVersionName = "0.3.0"

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.aboutLibraries)
//    alias(libs.plugins.hilt)
    id("kotlin-parcelize")
}

val localPropsFile = rootProject.file("local.properties")
val props = Properties().apply {
    if (localPropsFile.exists()) {
        load(localPropsFile.inputStream())
    }
}

val releaseSigningAvailable = listOf(
    "RELEASE_STORE_FILE",
    "RELEASE_STORE_PASSWORD",
    "RELEASE_KEY_ALIAS",
    "RELEASE_KEY_PASSWORD"
).all { props[it]?.toString()?.isNotBlank() == true }

android {
    signingConfigs {
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = file(props["RELEASE_STORE_FILE"] as String)
                storePassword = props["RELEASE_STORE_PASSWORD"] as String
                keyAlias = props["RELEASE_KEY_ALIAS"] as String
                keyPassword = props["RELEASE_KEY_PASSWORD"] as String
            }
        }
    }
    namespace = "me.kavishdevar.librepods"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.kavishdevar.librepods"
        targetSdk = 37
        versionCode = 56
        versionName = appVersionName
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
            externalNativeBuild {
                cmake {
                    arguments += "-DCMAKE_BUILD_TYPE=Release"
                }
            }
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("release")
            }
            defaultConfig {
                minSdk = 33
            }
        }
        debug {
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("release")
            }
            versionNameSuffix = "-debug"
            defaultConfig {
                minSdk = 33
            }
        }
    }
    productFlavors {
        create("foss") {
            dimension = "env"
            buildConfigField("Boolean", "PLAY_BUILD", "false")
        }
        create("play") {
            dimension = "env"
            buildConfigField("Boolean", "PLAY_BUILD", "true")
            versionNameSuffix = "-play"
            minSdk = 36
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
            res.directories += "src/main/res-apple"
        }
    }

    ndkVersion = "30.0.14904198"

    flavorDimensions += "env"
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
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
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.play.review)
    implementation(libs.play.review.ktx)
}

aboutLibraries {
    export {
        prettyPrint = true
        excludeFields = listOf("generated")
        outputFile = file("src/main/res/raw/aboutlibraries.json")
    }
}

val rootModuleDir = rootProject.file("../root-module-manual")
val releaseDir = rootProject.file("../release")

fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

fun registerRootModuleZipTask(
    name: String,
    flavor: String,
    buildType: String
) = tasks.register<Zip>(name) {

    val variantTask = "assemble${cap(flavor)}${cap(buildType)}"
    dependsOn(variantTask)

    val apkPath = "outputs/apk/$flavor/$buildType/app-$flavor-$buildType.apk"

    from(rootModuleDir)

    duplicatesStrategy = DuplicatesStrategy.WARN

    from(layout.buildDirectory.file(apkPath)) {
        into("system/priv-app/LibrePods")
        rename { "LibrePods.apk" }
    }

    delete(layout.buildDirectory.dir("outputs/rootModuleZips"))

    archiveFileName.set("LibrePods-FOSS-v$appVersionName-$buildType.zip")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/rootModuleZips"))
}

val zipRelease = registerRootModuleZipTask(
    "zipReleaseModule",
    "foss",
    "release"
)

val zipDebug = registerRootModuleZipTask(
    "zipDebugModule",
    "foss",
    "debug"
)

val collect = tasks.register<Copy>("collectReleaseArtifacts") {

    dependsOn(
        zipRelease,
        zipDebug,
        "bundlePlayRelease"
    )

    into(releaseDir)

    from(layout.buildDirectory.dir("outputs/apk/foss/release")) {
        include("*.apk")
        rename(".*", "LibrePods-FOSS-v$appVersionName-release.apk")
    }

    from(layout.buildDirectory.dir("outputs/apk/foss/debug")) {
        include("*.apk")
        rename(".*", "LibrePods-FOSS-v$appVersionName-debug.apk")
    }

    from(layout.buildDirectory.dir("outputs/bundle/playRelease")) {
        include("*.aab")
    }

    from(layout.buildDirectory.dir("outputs/rootModuleZips")) {
        include("*.zip")
    }
}

tasks.register("packageReleaseArtifacts") {
    dependsOn(collect)
}
