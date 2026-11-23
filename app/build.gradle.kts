plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.handtop.desktop"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt())
    }
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "org.handtop.desktop"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 2
        versionName = "0.2.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Native helpers of the Wayland compositor (sockets with fd passing, shm, blits).
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/Android.mk")
        }
    }

    packaging {
        jniLibs {
            // Extract native libs to nativeLibraryDir on install: proot and its loader are
            // executables shipped as lib*.so, and that directory is the only place an app
            // may execve() from (W^X, targetSdk >= 29).
            useLegacyPackaging = true
        }
    }
}

// proot + loader for the Linux environment, built from native/ with the NDK.
val buildProot = tasks.register<BuildProotTask>("buildProot") {
    prootSourceDir.set(rootProject.layout.projectDirectory.dir("native/proot/src"))
    tallocSourceDir.set(rootProject.layout.projectDirectory.dir("native/talloc"))
    ndkVersion.set(libs.versions.ndk)
    ndkDirectory.set(androidComponents.sdkComponents.sdkDirectory.map { it.dir("ndk/${libs.versions.ndk.get()}") })
    minSdk.set(libs.versions.minSdk.map { it.toInt() })
    prootVersion.set("5.1.107-handtop")
    outputDirectory.set(layout.buildDirectory.dir("generated/proot/jniLibs"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildProot, BuildProotTask::outputDirectory)
    }
}

dependencies {
    implementation(project(":terminal-view"))
    implementation(libs.androidx.core.ktx)
}
