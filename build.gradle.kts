plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.intellij.platform)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

dependencies {
    intellijPlatform {
        create(providers.gradleProperty("platformType").get(), providers.gradleProperty("platformVersion").get())
        bundledPlugin("JavaScript")
        // The IDE already ships kotlinx-coroutines. Reuse it instead of bundling a
        // second copy, which would risk classloading skew against the platform.
        bundledModule("intellij.libraries.kotlinx.coroutines.core")
    }

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.apache5)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.logging)
    implementation(libs.flexmark.all)
    implementation(libs.slf4j.api)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
}

intellijPlatform {
    pluginConfiguration {
        id = providers.gradleProperty("pluginGroup").get()
        name = providers.gradleProperty("pluginName").get()
        version = providers.gradleProperty("pluginVersion").get()

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }

    pluginVerification {
        ides {
            // Verify against the WebStorm build this plugin is compiled for.
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.WebStorm, "2026.1")
        }
    }
}

// kotlinx-serialization pulls coroutines in transitively; the platform provides them.
configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-slf4j")
}

// Indexing searchable options needs a headless IDE launch on every build and the
// plugin has no indexed settings, so both tasks are switched off.
tasks.buildSearchableOptions {
    enabled = false
}

tasks.prepareJarSearchableOptions {
    enabled = false
}

tasks.test {
    useJUnitPlatform()
}
