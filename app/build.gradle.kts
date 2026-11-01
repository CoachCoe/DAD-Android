plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing credentials live in keystore.properties (gitignored); see
// keystore.properties.example. Absent (CI, local debug builds) the release
// signing config is simply not wired, so an unsigned release build fails loudly
// rather than silently shipping a debug-signed artifact.
val keystoreProperties: Map<String, String> =
    rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.readLines()
        ?.mapNotNull { line ->
            line.trim().takeUnless { it.isEmpty() || it.startsWith("#") }
                ?.split("=", limit = 2)
                ?.takeIf { it.size == 2 }
                ?.let { it[0].trim() to it[1].trim() }
        }
        ?.toMap()
        ?: emptyMap()

android {
    namespace = "org.dad.foundation"
    compileSdk = 37
    defaultConfig {
        applicationId = "org.dad.foundation"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val apiOrigin = providers.gradleProperty("dadApiOrigin").orElse("").get()
        buildConfigField("String", "DAD_API_ORIGIN", groovy.json.JsonOutput.toJson(apiOrigin))
        // Optional sign-in methods stay off until the owner supplies these (see README). Blank = off.
        buildConfigField("String", "DAD_GOOGLE_WEB_CLIENT_ID", groovy.json.JsonOutput.toJson(providers.gradleProperty("dadGoogleWebClientId").orElse("").get()))
        buildConfigField("String", "DAD_PASSKEY_RP_ID", groovy.json.JsonOutput.toJson(providers.gradleProperty("dadPasskeyRpId").orElse("").get()))
        buildConfigField("String", "DAD_PUBLIC_ORIGIN", groovy.json.JsonOutput.toJson(providers.gradleProperty("dadPublicOrigin").orElse("").get()))
    }
    signingConfigs {
        if (keystoreProperties["storeFile"] != null) {
            create("release") {
                storeFile = file(keystoreProperties.getValue("storeFile"))
                storePassword = keystoreProperties["storePassword"]
                keyAlias = keystoreProperties["keyAlias"]
                keyPassword = keystoreProperties["keyPassword"]
            }
        }
    }
    buildTypes {
        debug {
            // Distinct id so a debug build installs alongside a release build.
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets["main"].assets.directories.add(layout.buildDirectory.dir("generated/assets").get().asFile.path)
    sourceSets["test"].resources.directories.add(layout.buildDirectory.dir("generated/assets").get().asFile.path)
    if (providers.gradleProperty("dadIntegrationFixture").isPresent) {
        sourceSets["test"].kotlin.directories.add("src/integrationTest/kotlin")
    }
    sourceSets.named("main") {
        kotlin.directories.add(layout.buildDirectory.dir("generated/openapi/src/main/kotlin").get().asFile.path)
        kotlin.directories.add(layout.buildDirectory.dir("generated/tokens").get().asFile.path)
    }
    lint {
        abortOnError = true
        warningsAsErrors = true
        // These advisories flag that a newer upstream release exists, not a code
        // defect, so with warningsAsErrors they break CI whenever any dependency
        // (or Gradle) ships a new version. Disable them; real lint warnings still
        // fail the build.
        disable += setOf("NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

val prepareContract = tasks.register<Exec>("prepareContract") {
    workingDir(rootProject.projectDir)
    commandLine("node", "scripts/prepare-client.mjs")
    inputs.dir(rootProject.file("contracts/foundation"))
    inputs.file(rootProject.file("foundation.lock.json"))
    inputs.file(rootProject.file("scripts/prepare-client.mjs"))
    outputs.file(layout.buildDirectory.file("generated/contract/openapi.json"))
}
val codegenDependencies = configurations.dependencyScope("codegenDependencies")
val codegen = configurations.resolvable("codegen") { extendsFrom(codegenDependencies.get()) }
val generateClient = tasks.register<JavaExec>("generateClient") {
    dependsOn(prepareContract)
    classpath = codegen.get()
    mainClass.set("org.openapitools.codegen.OpenAPIGenerator")
    val spec = layout.buildDirectory.file("generated/contract/openapi.json").get().asFile
    val output = layout.buildDirectory.dir("generated/openapi").get().asFile
    inputs.file(spec)
    outputs.dir(output)
    args("generate", "-g", "kotlin", "--library", "jvm-retrofit2", "-i", spec.path, "-o", output.path,
        "--api-package", "org.dad.foundation.generated.api", "--model-package", "org.dad.foundation.generated.model",
        "--package-name", "org.dad.foundation.generated", "--schema-mappings", "LogoutAcknowledgement=org.dad.foundation.LogoutAcknowledgement",
        "--additional-properties", "useCoroutines=true,serializationLibrary=gson,dateLibrary=java8,hideGenerationTimestamp=true",
        "--global-property", "apis,models,supportingFiles=CollectionFormats.kt,apiDocs=false,modelDocs=false,apiTests=false,modelTests=false")
    doFirst { check(!output.exists() || output.deleteRecursively()) { "Cannot replace generated client" } }
}
val generateTokens = tasks.register<Copy>("generateTokens") {
    from(rootProject.file("contracts/foundation/DADTokens.kt"))
    into(layout.buildDirectory.dir("generated/tokens"))
}
val generateLinks = tasks.register<Copy>("generateLinks") {
    from(rootProject.file("contracts/foundation/links.json"))
    into(layout.buildDirectory.dir("generated/assets"))
}
tasks.named("preBuild") { dependsOn(generateClient, generateTokens, generateLinks) }

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    providers.gradleProperty("dadIntegrationFixture").orNull?.let {
        systemProperty("dad.integration.fixture", it)
    }
}

dependencies {
    add("codegenDependencies", "org.openapitools:openapi-generator-cli:7.20.0") { isTransitive = false }
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.browser:browser:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("com.google.code.gson:gson:2.15.0")
    // R-1/R-2: QR generation for the IRL invite code. Pure-JVM core so the encode/decode
    // round-trip is a unit test (no emulator).
    implementation("com.google.zxing:core:3.5.3")
    // E-8: live QR scanning for event check-in. CameraX drives the viewfinder; ML Kit's BUNDLED barcode model
    // (not the Play Services one) decodes, so the first scan works offline with no model download.
    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
