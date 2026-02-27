import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
    java
    application
    id("com.gradleup.shadow") version "9.3.1"
    id("com.diffplug.spotless") version "8.2.1"
}

group = "fr.bl.drit.jacoco"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jacoco:org.jacoco.report:0.8.14")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

application {
    mainClass = "fr.bl.drit.jacoco.report.MinimalReportGenerator"
}

// === Formatting code

spotless {
    java {
        googleJavaFormat()
    }
}

tasks.named("build") {
  dependsOn("spotlessApply")
}

// === Packaging jar with dependencies

tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named("startScripts") {
    dependsOn("shadowJar")
}

tasks.withType<ShadowJar> {
    archiveBaseName.set(rootProject.name)
    archiveClassifier.set("")
    archiveVersion.set("")
}

// === Documenting

tasks.withType<Javadoc>().configureEach {
    val stdOpts = options as StandardJavadocDocletOptions

    // Add links to dependency javadocs
    stdOpts.links("https://javadoc.io/doc/org.jacoco/org.jacoco.core/0.8.14/")
    stdOpts.links("https://javadoc.io/doc/com.fasterxml.jackson.core/jackson-databind/2.15.2/")

    stdOpts.locale = "en_US"
    stdOpts.encoding = "UTF-8"
}
