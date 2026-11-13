plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.protobuf)
    id("kotlin-parcelize")
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }

    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java")
                create("kotlin")
            }
        }
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "kotlinx.coroutines.FlowPreview",
            "kotlinx.serialization.ExperimentalSerializationApi"
        )
    }
}

android {
    namespace = "me.kavishdevar.librepods.core"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 33
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    sourceSets {
        getByName("main") {
            res.directories += "src/main/res-apple"
        }
    }

    lint {
        checkDependencies = false
        checkGeneratedSources = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.protobuf.kotlin)
}
