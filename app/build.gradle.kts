import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("androidx.room")
}

val releaseSigningVariables = listOf(
    "POCKETPAAS_KEYSTORE_PATH",
    "POCKETPAAS_KEYSTORE_PASSWORD",
    "POCKETPAAS_KEY_ALIAS",
    "POCKETPAAS_KEY_PASSWORD",
)
val releaseSigningValues = releaseSigningVariables.associateWith { name ->
    providers.environmentVariable(name).orNull
}
val missingSigningVariables = releaseSigningValues.filterValues { it.isNullOrBlank() }.keys
val releaseSigningConfigured = missingSigningVariables.isEmpty()
val releaseKeyStore = releaseSigningValues["POCKETPAAS_KEYSTORE_PATH"]
    ?.takeIf(String::isNotBlank)
    ?.let(::file)

android {
    namespace = "dev.pocketpaas"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.pocketpaas"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.6.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

    androidResources {
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~:*.pyc:__pycache__"
    }

    if (releaseSigningConfigured) {
        signingConfigs {
            create("release") {
                storeFile = requireNotNull(releaseKeyStore)
                storePassword = releaseSigningValues.getValue("POCKETPAAS_KEYSTORE_PASSWORD")
                keyAlias = releaseSigningValues.getValue("POCKETPAAS_KEY_ALIAS")
                keyPassword = releaseSigningValues.getValue("POCKETPAAS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "META-INF/INDEX.LIST"
            merges += setOf(
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/DEPENDENCIES",
                "META-INF/io.netty.versions.properties",
                "META-INF/services/**",
            )
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    testOptions {
        unitTests.all {
            it.systemProperty("pocketpaas.production.root", rootProject.projectDir.absolutePath)
            providers.environmentVariable("POCKETPAAS_TEST_PYTHON").orNull?.let { path ->
                it.systemProperty("pocketpaas.test.python", path)
            }
            providers.environmentVariable("POCKETPAAS_TEST_NGINX").orNull?.let { path ->
                it.systemProperty("pocketpaas.test.nginx", path)
            }
            it.inputs.files(
                file("src/main/assets/pocketpaas_agent.py"),
                file("src/main/assets/pocketpaas_files.py"),
                file("src/main/assets/pocketpaas_deploy.py"),
                file("src/main/assets/site_file_manager.html"),
                file("src/main/assets/site_file_manager.css"),
                file("src/main/assets/site_file_manager.js"),
            )
            it.testLogging.events("passed", "skipped", "failed")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

hilt {
    enableAggregatingTask = true
}

// Both packaging an APK and bundling an AAB must have the user's release key.
// Debug builds use the Android debug key and never need these environment variables.
val verifyReleaseSigning by tasks.registering {
    group = "verification"
    description = "Validates release signing without printing credentials."
    inputs.property("missingSigningVariables", missingSigningVariables.joinToString(","))
    inputs.property("keystoreExists", releaseKeyStore?.isFile == true)
    doLast {
        check(releaseSigningConfigured) {
            "Release signing is not configured. Set: ${missingSigningVariables.joinToString(", ")}."
        }
        check(releaseKeyStore?.isFile == true) {
            "POCKETPAAS_KEYSTORE_PATH must point to an existing keystore file."
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyReleaseSigning)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.00")
    val lifecycleVersion = "2.9.4"
    val roomVersion = "2.8.3"
    val hiltVersion = "2.57.2"
    val androidxHiltVersion = "1.3.0"
    val ktorVersion = "3.3.3"
    val coroutinesVersion = "1.10.2"

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycleVersion")

    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("com.google.dagger:hilt-android:$hiltVersion")
    ksp("com.google.dagger:hilt-compiler:$hiltVersion")
    implementation("androidx.hilt:hilt-lifecycle-viewmodel-compose:$androidxHiltVersion")
    implementation("androidx.hilt:hilt-work:$androidxHiltVersion")
    ksp("androidx.hilt:hilt-compiler:$androidxHiltVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
    implementation("androidx.work:work-runtime-ktx:2.10.5")

    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion") {
        // Android uses Java NIO. Linux/glibc and macOS native transports cannot run here.
        exclude(group = "io.netty", module = "netty-transport-native-epoll")
        exclude(group = "io.netty", module = "netty-transport-native-kqueue")
    }
    // Ktor references these availability checks. Keep their Java classes while
    // omitting desktop native binaries so the checks safely fall back to NIO.
    implementation("io.netty:netty-transport-classes-epoll:4.2.7.Final")
    implementation("io.netty:netty-transport-classes-kqueue:4.2.7.Final")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-gson-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")
    implementation("com.google.code.gson:gson:2.13.2")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")

    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.3.0")
    androidTestImplementation("androidx.room:room-testing:$roomVersion")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
}
