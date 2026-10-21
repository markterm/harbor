import java.io.ByteArrayOutputStream
import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val ndkVer = "29.0.14206865"
val sdkDir: String = localProps.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: ""
val cargoBin: String = localProps.getProperty("cargo")
    ?: "${System.getProperty("user.home")}/.cargo/bin/cargo"
val rustOut = layout.buildDirectory.dir("rustJniLibs")

android {
    namespace = "com.markwhite.harbor"
    compileSdk = 37
    ndkVersion = ndkVer

    defaultConfig {
        // the id on Play and on devices; the code keeps its own package (namespace)
        applicationId = "app.harborsynth"
        minSdk = 31
        targetSdk = 37
        versionCode = 8
        versionName = "0.1.7"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }

    // Play's upload key, from ~/.gradle/gradle.properties (never in the repo): HARBOR_UPLOAD_STORE,
    // HARBOR_UPLOAD_STORE_PASSWORD, HARBOR_UPLOAD_KEY_ALIAS, HARBOR_UPLOAD_KEY_PASSWORD
    val upload = providers.gradleProperty("HARBOR_UPLOAD_STORE").orNull
    signingConfigs {
        if (upload != null) create("upload") {
            storeFile = file(upload)
            storePassword = providers.gradleProperty("HARBOR_UPLOAD_STORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("HARBOR_UPLOAD_KEY_ALIAS").get()
            keyPassword = providers.gradleProperty("HARBOR_UPLOAD_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        debug {
            // the emulator is x86_64 (interpreted); phones are arm64 (JIT)
            ndk { abiFilters += "x86_64" }
            buildConfigField("boolean", "PLAY_CATALOG", "false")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // unsigned without the upload key's properties (Play won't take it then)
            if (upload != null) signingConfig = signingConfigs.getByName("upload")
            // the catalogue Play's content rating (Everyone) was given for: see Catalog.PLAY_HIDDEN
            buildConfigField("boolean", "PLAY_CATALOG", "true")
        }
        // release code (R8, not debuggable) signed with the debug key: what to judge speed on a
        // phone with, since a debuggable build runs the UI's Kotlin far slower
        create("profile") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            isProfileable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true; buildConfig = true }

    sourceSets["main"].jniLibs.directories += rustOut.get().asFile.path
}

/**
 * Runs cargo-ndk, showing its output only when it fails (fm1-emu's own warnings would otherwise
 * fill every build log). Cargo's incremental build decides what to redo, so it always runs.
 */
abstract class CargoNdk @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Input abstract val command: ListProperty<String>
    @get:Input abstract val ndkHome: Property<String>
    @get:Internal abstract val workDir: DirectoryProperty

    @TaskAction
    fun build() {
        val log = ByteArrayOutputStream()
        val result = exec.exec {
            workingDir = this@CargoNdk.workDir.get().asFile
            environment("ANDROID_NDK_HOME", ndkHome.get())
            commandLine(command.get())
            standardOutput = log
            errorOutput = log
            isIgnoreExitValue = true
        }
        if (result.exitValue != 0) {
            logger.error(log.toString())
            throw GradleException("cargo ndk failed (exit ${result.exitValue})")
        }
    }
}

/** libharbor.so for both ABIs, from native/. */
val cargoNdk = tasks.register<CargoNdk>("cargoNdk") {
    group = "build"
    description = "Builds the native engine (native/harbor-jni) with cargo-ndk"
    workDir.set(rootProject.layout.projectDirectory.dir("native"))
    ndkHome.set("$sdkDir/ndk/$ndkVer")
    command.set(listOf(
        cargoBin, "ndk", "-t", "arm64-v8a", "-t", "x86_64", "-P", "31",
        "-o", rustOut.get().asFile.absolutePath, "build", "--release", "-p", "harbor-jni",
    ))
}
tasks.named("preBuild") { dependsOn(cargoNdk) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
