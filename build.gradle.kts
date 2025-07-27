plugins {
    kotlin("jvm") version "2.2.0"
    id("io.github.goooler.shadow") version "8.1.8"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.18"
    id("net.minecrell.plugin-yml.paper") version "0.6.0"
    id("net.minecrell.plugin-yml.bukkit") version "0.6.0"
}

group = "gg.hjk.domination"
version = "0.5-SNAPSHOT"
description = "Display an embarrassing chain of deaths to your friends and rivals."
project.ext["author"] = "hjk321"
project.ext["url"] = "https://hangar.papermc.io/hjk321/Domination"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    paperweight.paperDevBundle("1.21.7-R0.1-SNAPSHOT")
    implementation("io.papermc:paper-trail:1.0.1")
    implementation("org.bstats:bstats-bukkit:3.1.0")
}

kotlin {
    jvmToolchain(21)
}

paper {
    apiVersion = "1.21.7"
    main = "gg.hjk.domination.Domination"
}

bukkit {
    apiVersion = "1.13"
    main = "gg.hjk.domination.papertrail.RequiresPaperPlugins"
}

tasks.shadowJar {
    mergeServiceFiles()

    exclude("org/intellij/**")
    exclude("org/slf4j/**")
    exclude("org/jetbrains/annotations/**")

    relocate("kotlin", "gg.hjk.domination.kotlin")
    relocate("io.papermc.papertrail", "gg.hjk.domination.papertrail")
    relocate("org.bstats", "gg.hjk.domination.bstats")
}
