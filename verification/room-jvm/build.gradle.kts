import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.21"
    id("com.google.devtools.ksp") version "2.2.21-2.0.5"
}

repositories {
    google()
    mavenCentral()
}

val androidApiJar = providers.environmentVariable("POCKETPAAS_TEST_ANDROID_JAR").orNull
val androidHiltJar = providers.environmentVariable("POCKETPAAS_TEST_HILT_JAR").orNull
val hiltCoreJar = providers.environmentVariable("POCKETPAAS_TEST_HILT_CORE_JAR").orNull

val productionSources = tasks.register<Sync>("syncProductionSources") {
    from("../../app/src/main/java") {
        include(
            "dev/pocketpaas/core/config/**",
            "dev/pocketpaas/core/di/Qualifiers.kt",
            "dev/pocketpaas/domain/**",
            "dev/pocketpaas/data/local/**",
            "dev/pocketpaas/data/repository/**",
            "dev/pocketpaas/data/process/**",
            "dev/pocketpaas/data/network/**",
            "dev/pocketpaas/data/runtime/**",
            "dev/pocketpaas/data/deployment/**",
            "dev/pocketpaas/data/remote/**",
            "dev/pocketpaas/data/dashboard/**",
            "dev/pocketpaas/data/setup/**",
            "dev/pocketpaas/data/ssh/**",
            "dev/pocketpaas/data/services/**",
            "dev/pocketpaas/data/backup/**",
            "dev/pocketpaas/presentation/dashboard/DashboardUiState.kt",
            "dev/pocketpaas/presentation/dashboard/SiteFileManagerUiState.kt",
        )
        exclude("dev/pocketpaas/data/local/DatabaseMigrations.kt")
        if (androidApiJar == null) exclude("dev/pocketpaas/data/process/termux/android/**")
        if (androidApiJar == null || androidHiltJar == null) exclude("dev/pocketpaas/data/deployment/android/**")
        if (androidApiJar == null) exclude("dev/pocketpaas/data/network/android/**")
        if (androidApiJar == null || androidHiltJar == null || hiltCoreJar == null) exclude("dev/pocketpaas/data/remote/android/**")
        if (androidApiJar == null || androidHiltJar == null || hiltCoreJar == null) exclude("dev/pocketpaas/data/dashboard/android/**")
        if (androidApiJar == null || androidHiltJar == null) exclude("dev/pocketpaas/data/setup/android/**")
    }
    into(layout.buildDirectory.dir("production-sources"))
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
    sourceSets {
        main {
            kotlin.srcDir(productionSources.map { it.destinationDir })
        }
        test {
            kotlin.srcDir("../../app/src/test/java")
            // ViewModel/Compose tests use the Android app's real lifecycle dependencies.
            kotlin.exclude("dev/pocketpaas/presentation/**")
        }
    }
}

tasks.withType<Test>().configureEach {
    inputs.files(
        file("../../app/src/main/assets/pocketpaas_agent.py"),
        file("../../app/src/main/assets/pocketpaas_files.py"),
        file("../../app/src/main/assets/pocketpaas_deploy.py"),
        file("../../app/src/main/assets/site_file_manager.html"),
        file("../../app/src/main/assets/site_file_manager.css"),
        file("../../app/src/main/assets/site_file_manager.js"),
    )
    testLogging.events("passed", "skipped", "failed")
    systemProperty("pocketpaas.production.root", file("../..").absolutePath)
    providers.environmentVariable("POCKETPAAS_TEST_PYTHON").orNull?.let {
        systemProperty("pocketpaas.test.python", it)
    }
    providers.environmentVariable("POCKETPAAS_TEST_NGINX").orNull?.let {
        systemProperty("pocketpaas.test.nginx", it)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    if (androidApiJar != null) compileOnly(files(androidApiJar))
    if (androidHiltJar != null) compileOnly(files(androidHiltJar))
    if (hiltCoreJar != null) compileOnly(files(hiltCoreJar))
    implementation("javax.inject:javax.inject:1")
    implementation("androidx.room:room-runtime-jvm:2.8.3")
    implementation("androidx.sqlite:sqlite-bundled-jvm:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.google.code.gson:gson:2.13.2")
    val ktorVersion = "3.3.3"
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-gson-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
    ksp("androidx.room:room-compiler:2.8.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
}
