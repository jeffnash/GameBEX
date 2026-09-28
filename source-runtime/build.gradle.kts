plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    `java-library`
    `maven-publish`
}

group = "com.gamebex"
version = "1.0.0"
kotlin { jvmToolchain(17) }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

java { withSourcesJar() }
publishing {
    publications {
        create<MavenPublication>("runtime") {
            from(components["java"])
            artifactId = "source-runtime"
        }
    }
}

tasks.register<JavaExec>("checkSource") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.gamebex.source.FixtureRunnerKt")
    providers.gradleProperty("sourceDir").orNull?.let { args(it) }
    // Authoring aid only: stage missing expected files for review. CI never sets this.
    providers.gradleProperty("dumpMissing").orNull?.let { systemProperty("dumpMissing", it) }
}
