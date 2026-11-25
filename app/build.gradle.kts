import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Release signing: keystore.properties (not in git) names the key and its passwords.
// Without it the release build is left unsigned, so anyone can still build HandTop.
val releaseSigning = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
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
        versionCode = 3
        versionName = "0.3.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (releaseSigning != null) create("release") {
            storeFile = file(releaseSigning.getProperty("storeFile"))
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            if (releaseSigning != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // The same key, so a development build installs over a release and keeps its data.
            if (releaseSigning != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // JVM unit tests touch plain Kotlin (themes, icon tables); Android stubs answer defaults.
    testOptions {
        unitTests.isReturnDefaultValues = true
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

// GPU drivers for Adreno (native/mesa), shipped inside the APK: one build per C library (glibc
// for Debian, Ubuntu and Fedora; musl for Alpine). A new package build means a new version and
// SHA-256 here (the package is in native/mesa/prebuilt/ or the project's release).
fun bundleMesa(libc: String, packageVersion: String, packageSha256: String) =
    tasks.register<BundleMesaTask>("bundleMesa${libc.replaceFirstChar { it.uppercase() }}") {
        this.libc.set(libc)
        version.set(packageVersion)
        sha256.set(packageSha256)
        prebuiltDirectory.set(rootProject.layout.projectDirectory.dir("native/mesa/prebuilt"))
        releaseUrl.set("https://github.com/nokiastore9/handtop/releases/download/mesa-{version}")
        outputDirectory.set(layout.buildDirectory.dir("generated/mesa-$libc/assets"))
    }

val bundleMesaGlibc = bundleMesa("glibc", "26.3.1-2", "2a6799b9dc457b3012ca2959ceac6754a1d633b927174b565a46cef66e8f7dab")
val bundleMesaMusl = bundleMesa("musl", "26.3.1-2", "aceb7a3a8834a6517a20056fe561bb15021f8260962a4f0691a85dbc6055ae76")

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildProot, BuildProotTask::outputDirectory)
        variant.sources.assets?.addGeneratedSourceDirectory(bundleMesaGlibc, BundleMesaTask::outputDirectory)
        variant.sources.assets?.addGeneratedSourceDirectory(bundleMesaMusl, BundleMesaTask::outputDirectory)
    }
}

dependencies {
    implementation(project(":terminal-view"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidsvg)
    testImplementation(libs.junit)
}
