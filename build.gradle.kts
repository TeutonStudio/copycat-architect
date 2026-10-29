plugins {
    kotlin("jvm") version "2.2.20"
    id("net.neoforged.moddev") version "2.0.143"
}
version = "0.5.1"
group = "de.teutonstudio.copycatarchitect"
base { archivesName.set("copycat_architect") }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
kotlin { jvmToolchain(21) }
// Give the client run its own runtime dependencies while reusing the main mod.
val clientSourceSet = sourceSets.create("client") {
    runtimeClasspath += sourceSets.main.get().runtimeClasspath
}
repositories {
    mavenCentral()
    maven("https://maven.createmod.net")
    maven("https://maven.blamejared.com")
    maven("https://maven.firstdark.dev/snapshots")
    maven("https://thedarkcolour.github.io/KotlinForForge/")
    maven("https://maven.squiddev.cc")
    maven("https://api.modrinth.com/maven")
}
neoForge {
    version = "21.1.251"
    runs {
        create("client") {
            client()
            sourceSet = clientSourceSet
            if(project.hasProperty("printerSmoke")) systemProperty("architect.printerSmoke", "true")
            if(project.hasProperty("panelBuildingSmoke")) systemProperty("architect.panelBuildingSmoke", "true")
            if(project.hasProperty("clientSmoke") || project.hasProperty("graphSmoke") || project.hasProperty("buildingGraphSmoke") || project.hasProperty("kilaGraphSmoke")) systemProperty("architect.smoke", "true")
            if(project.hasProperty("buildingGraphSmoke")) systemProperty("architect.buildingGraphSmoke", "true")
            if(project.hasProperty("graphSmoke")) systemProperty("architect.graphSmoke", "true")
            if(project.hasProperty("kilaGraphSmoke")) systemProperty("architect.kilaGraphSmoke", "true")
        }
        create("server") { server(); programArgument("--nogui") }
        create("gameTestServer") {
            type = "gameTestServer"; systemProperty("neoforge.enabledGameTestNamespaces", "copycat_architect")
            if(project.hasProperty("graphWorldSmoke")) systemProperty("architect.graphWorldSmoke", "true")
        }
    }
    mods { create("copycat_architect") { sourceSet(sourceSets.main.get()) } }
}
dependencies {
    compileOnly("maven.modrinth:create:6.0.10+mc1.21.1") { isTransitive = false }
    compileOnly("net.createmod.ponder:ponder-neoforge:1.0.82+mc1.21.1") { isTransitive = false }
    compileOnly("mezz.jei:jei-1.21.1-common-api:19.39.0.372") { isTransitive = false }
    compileOnly("mezz.jei:jei-1.21.1-neoforge-api:19.39.0.372") { isTransitive = false }

    implementation("com.lowdragmc.ldlib2:ldlib2-neoforge-1.21.1:2.2.44:all")
    implementation("com.lowdragmc.kilagraph:kilagraph-neoforge-1.21.1:21.1.0.16")
    implementation("thedarkcolour:kotlinforforge-neoforge:5.10.0")
    implementation("cc.tweaked:cc-tweaked-1.21.1-forge:1.120.0")
    runtimeOnly("maven.modrinth:create:6.0.10+mc1.21.1") { isTransitive = false }
    runtimeOnly("maven.modrinth:copycats:3.0.9+mc.1.21.1-neoforge") { isTransitive = false }
    // Development helpers for runClient only; pin the NeoForge files by version ID.
    add(clientSourceSet.runtimeOnlyConfigurationName, "maven.modrinth:jei:iv3KiVkE") { isTransitive = false } // 19.64.0.455
    add(clientSourceSet.runtimeOnlyConfigurationName, "maven.modrinth:neat:kALoScYM") { isTransitive = false } // 1.21.1-47-neoforge
    add(clientSourceSet.runtimeOnlyConfigurationName, "maven.modrinth:jade:eYz2YBGT") { isTransitive = false } // 15.10.6+neoforge
    testImplementation(kotlin("test-junit5"))
    testImplementation("com.google.code.gson:gson:2.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}
tasks.test { useJUnitPlatform() }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}
