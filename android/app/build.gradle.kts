import com.android.build.api.variant.BuildConfigField
import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ---------------------------------------------------------------------------
// Server configuration, fixed at build time.
//
//   beconscient.serverUrl      Gradle property, or env BECONSCIENT_SERVER_URL
//   beconscient.publicKeyFile  Gradle property, or env BECONSCIENT_PUBLIC_KEY_FILE
//                              (a JSON file shaped like contract/keys/test-public.json)
//
// Debug builds fall back to the test key and the emulator host address.
// Release builds refuse to build without an https URL and a non-test key.
// ---------------------------------------------------------------------------

val contractDir = rootProject.layout.projectDirectory.dir("../contract")
val testKeyFile = contractDir.file("keys/test-public.json").asFile

fun setting(property: String, env: String): String? =
    providers.gradleProperty(property).orNull?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() }

data class ServerKey(val kid: String, val publicKey: String, val testOnly: Boolean, val source: String)

fun readKey(file: File): ServerKey? {
    if (!file.isFile) return null
    val text = providers.fileContents(rootProject.layout.projectDirectory.file(file.absolutePath)).asText.get()
    val json = JsonSlurper().parseText(text) as Map<*, *>
    return ServerKey(
        kid = json["kid"]?.toString().orEmpty(),
        publicKey = json["public_key"]?.toString().orEmpty(),
        testOnly = json["test_only"] == true,
        source = file.path,
    )
}

fun resolveKeyFile(path: String): File {
    val f = File(path)
    return if (f.isAbsolute) f else rootProject.file(path)
}

val configuredUrl = setting("beconscient.serverUrl", "BECONSCIENT_SERVER_URL")
val configuredKeyPath = setting("beconscient.publicKeyFile", "BECONSCIENT_PUBLIC_KEY_FILE")
val configuredKey = configuredKeyPath?.let { readKey(resolveKeyFile(it)) }
val testKey = readKey(testKeyFile)

val debugUrl = configuredUrl ?: "http://10.0.2.2:8080"
val debugKey = configuredKey ?: testKey
    ?: throw GradleException("Debug build needs ${testKeyFile.path} or -Pbeconscient.publicKeyFile")

// Release signing comes from ~/.gradle/gradle.properties or the environment, never from the repo.
val signingStore = setting("beconscient.signing.storeFile", "BECONSCIENT_SIGNING_STORE_FILE")

android {
    namespace = "org.beconscient.collector"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.beconscient.collector"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        if (signingStore != null) {
            create("release") {
                storeFile = file(signingStore)
                storePassword = setting("beconscient.signing.storePassword", "BECONSCIENT_SIGNING_STORE_PASSWORD")
                keyAlias = setting("beconscient.signing.keyAlias", "BECONSCIENT_SIGNING_KEY_ALIAS")
                keyPassword = setting("beconscient.signing.keyPassword", "BECONSCIENT_SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingStore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("beconscient.contractDir", contractDir.asFile.absolutePath)
                it.systemProperty(
                    "beconscient.writeVectors",
                    providers.gradleProperty("beconscient.writeVectors").orElse("false").get(),
                )
                it.maxHeapSize = "2g"
                // Robolectric's Android 16 sandbox reaches into FileDescriptor internals.
                it.jvmArgs(
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                )
            }
        }
    }

    lint {
        abortOnError = true
        checkDependencies = false
        // Version bumps are a deliberate choice: newer Compose and core need compileSdk 37.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

androidComponents {
    onVariants { variant ->
        val release = variant.buildType == "release"
        val url = if (release) configuredUrl.orEmpty() else debugUrl
        val key = if (release) configuredKey else debugKey
        variant.buildConfigFields?.apply {
            put("SERVER_URL", BuildConfigField("String", "\"${url.trimEnd('/')}\"", "Server base URL"))
            put("SERVER_KID", BuildConfigField("String", "\"${key?.kid.orEmpty()}\"", "Server key id"))
            put("SERVER_PUBLIC_KEY", BuildConfigField("String", "\"${key?.publicKey.orEmpty()}\"", "X25519, base64url"))
        }
    }
}

abstract class CheckReleaseServerConfig : DefaultTask() {
    @get:Input abstract val serverUrl: Property<String>
    @get:Input abstract val keyPath: Property<String>
    @get:Input abstract val kid: Property<String>
    @get:Input abstract val publicKey: Property<String>
    @get:Input abstract val testOnly: Property<Boolean>

    @TaskAction
    fun check() {
        val problems = mutableListOf<String>()
        val url = serverUrl.get()
        when {
            url.isBlank() -> problems += "the server URL is missing: set -Pbeconscient.serverUrl=https://... " +
                "(or BECONSCIENT_SERVER_URL)"
            !url.startsWith("https://") -> problems += "the server URL must use https, got '$url'"
        }
        if (keyPath.get().isBlank()) {
            problems += "the server public key is missing: set -Pbeconscient.publicKeyFile=/path/to/server-public.json " +
                "(or BECONSCIENT_PUBLIC_KEY_FILE)"
        } else if (publicKey.get().isBlank() || kid.get().isBlank()) {
            problems += "the key file '${keyPath.get()}' has no kid or public_key"
        }
        if (testOnly.get() || kid.get() == "test-0001") {
            problems += "the key '${kid.get()}' in '${keyPath.get()}' is the TEST-ONLY key; " +
                "production refuses it and its private half is public"
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "beConscient release build refused:\n" + problems.joinToString("\n") { "  - $it" },
            )
        }
    }
}

val checkReleaseServerConfig = tasks.register<CheckReleaseServerConfig>("checkReleaseServerConfig") {
    group = "verification"
    description = "Fails the release build when the server URL or key is missing, not https, or test-only."
    serverUrl.set(configuredUrl.orEmpty())
    keyPath.set(configuredKeyPath?.let { if (configuredKey == null) "$it (not found)" else it }.orEmpty())
    kid.set(configuredKey?.kid.orEmpty())
    publicKey.set(configuredKey?.publicKey.orEmpty())
    testOnly.set(configuredKey?.testOnly ?: false)
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkReleaseServerConfig) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.bouncycastle)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
}
