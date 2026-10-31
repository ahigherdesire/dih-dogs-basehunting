plugins {
    alias(libs.plugins.fabric.loom)
}

// One jar per Minecraft version: `gradlew build` makes the 26.2 jar, `gradlew build -Pmc=26.3` the 26.3 one.
// Each target pins the matching Fabric loader, Fabric API, cloth-config and DIH Client API (from mavenLocal:
// run `gradlew publishToMavenLocal` in the DIH checkout for that Minecraft version first).
data class Target(val minecraft: String, val loader: String, val fabricApi: String, val cloth: String, val dih: String)
val targets = mapOf(
    "26.2" to Target("26.2", "0.19.3", "0.152.2+26.2", "26.2.155", "5.0.1-26.2-dev"),
    "26.3" to Target("26.3", "0.19.5", "0.161.0+26.3", "26.3.159", "5.0.1-26.3-dev"),
)
val target = targets[(findProperty("mc") as String?) ?: "26.2"] ?: error("Unknown -Pmc, use one of ${targets.keys}")
val dihVersion = (findProperty("dih") as String?) ?: target.dih

// Synced folders lock files mid-build; -PdihBuildDir=C:/somewhere builds outside them (one folder per Minecraft version).
(findProperty("dihBuildDir") as String?)?.let { layout.buildDirectory.set(file("$it/${target.minecraft}")) }

// Version-specific code uses DIH's Stonecutter-style blocks:
//     //? if >=26.3 {
//     /*code for 26.3
//     *///?} else {
//     code for 26.2
//     //?}
// The source compiles as-is for 26.2. For 26.3, preprocessSources writes a copy with the 26.3 block switched on
// (the 26.2 block becomes blank lines, so line numbers in stack traces still match) plus the renames below.
val mcReplacements = mapOf(
    "26.3" to mapOf(
        "net.minecraft.client.renderer.rendertype.DihRenderTypes" to "dihclient.render.mc.DihRenderTypes",
        "RedStoneWireBlock" to "RedstoneWireBlock",
        "net.minecraft.world.entity.monster.EnderMan" to "net.minecraft.world.entity.monster.Enderman",
        "VanillaRegistries.createLookup()" to "VanillaRegistries.createWorldLookup()",
    ),
)

fun preprocess(text: String, mc: String): String {
    val newer = mc != "26.2"
    if (!newer) return text
    val out = StringBuilder()
    var state = 0 // 0 = outside, 1 = in the >=26.3 block, 2 = in the else block
    var firstLine = false
    for (line in text.split("\n")) {
        val t = line.trim()
        when {
            state == 0 && t == "//? if >=26.3 {" -> { state = 1; firstLine = true; out.append("\n") }
            state == 1 && t.startsWith("*///?} else {") -> { state = 2; out.append("\n") }
            state == 1 -> { out.append(if (firstLine) line.replaceFirst("/*", "") else line).append("\n"); firstLine = false }
            state == 2 && t == "//?}" -> { state = 0; out.append("\n") }
            state == 2 -> out.append("\n")
            else -> out.append(line).append("\n")
        }
    }
    check(state == 0) { "Unclosed //? block" }
    var result = out.toString().removeSuffix("\n")
    for ((from, to) in mcReplacements[mc].orEmpty()) result = result.replace(from, to)
    return result
}

val preprocessedDir = layout.buildDirectory.dir("preprocessed/${target.minecraft}/java")
val mc263Sources = file("src/mc263/java")
val preprocessSources by tasks.registering {
    val src = file("src/main/java")
    inputs.dir(src)
    if (target.minecraft == "26.3") inputs.files(fileTree(mc263Sources))
    inputs.property("mc", target.minecraft)
    outputs.dir(preprocessedDir)
    doLast {
        val out = preprocessedDir.get().asFile
        out.deleteRecursively()
        src.walkTopDown().filter { it.isFile }.forEach { f ->
            val relative = f.relativeTo(src)
            // Never compile the 26.2 predictor against guessed 26.3 worldgen APIs.
            if (target.minecraft == "26.3" && relative.invariantSeparatorsPath.startsWith("com/autism/seedcracker/spectre/chunks/seedscan/")) return@forEach
            val dest = out.resolve(relative)
            dest.parentFile.mkdirs()
            if (f.extension == "java") dest.writeText(preprocess(f.readText(), target.minecraft)) else f.copyTo(dest)
        }
        if (target.minecraft == "26.3" && mc263Sources.exists()) {
            mc263Sources.walkTopDown().filter { it.isFile }.forEach { f ->
                val dest = out.resolve(f.relativeTo(mc263Sources))
                dest.parentFile.mkdirs()
                if (f.extension == "java") dest.writeText(preprocess(f.readText(), target.minecraft)) else f.copyTo(dest)
            }
        }
    }
}
val preprocessedTestsDir = layout.buildDirectory.dir("preprocessed/${target.minecraft}/test-java")
val mc263Tests = file("src/mc263Test/java")
val preprocessTests by tasks.registering {
    val src = file("src/test/java")
    inputs.dir(src)
    if (target.minecraft == "26.3") inputs.files(fileTree(mc263Tests))
    inputs.property("mc", target.minecraft)
    outputs.dir(preprocessedTestsDir)
    doLast {
        val out = preprocessedTestsDir.get().asFile
        out.deleteRecursively()
        src.walkTopDown().filter { it.isFile }.forEach { f ->
            val relative = f.relativeTo(src)
            if (target.minecraft == "26.3" && relative.invariantSeparatorsPath.startsWith("com/autism/seedcracker/spectre/chunks/seedscan/")) return@forEach
            val dest = out.resolve(relative)
            dest.parentFile.mkdirs()
            if (f.extension == "java") dest.writeText(preprocess(f.readText(), target.minecraft)) else f.copyTo(dest)
        }
        if (target.minecraft == "26.3" && mc263Tests.exists()) {
            mc263Tests.walkTopDown().filter { it.isFile }.forEach { f ->
                val dest = out.resolve(f.relativeTo(mc263Tests))
                dest.parentFile.mkdirs()
                if (f.extension == "java") dest.writeText(preprocess(f.readText(), target.minecraft)) else f.copyTo(dest)
            }
        }
    }
}
if (target.minecraft != "26.2") {
    sourceSets.main { java.setSrcDirs(listOf(preprocessedDir)) }
    sourceSets.test { java.setSrcDirs(listOf(preprocessedTestsDir)) }
    tasks.named("compileJava") { dependsOn(preprocessSources) }
    tasks.named("compileTestJava") { dependsOn(preprocessTests) }
}

base {
    archivesName = properties["archives_base_name"] as String
    version = libs.versions.mod.version.get() + "-" + target.minecraft
    group = properties["maven_group"] as String
}

loom {
    accessWidenerPath.set(file("src/main/resources/seedcrackerx.aw"))
}

repositories {
    // DIH Client API, published to mavenLocal from the DIH checkout.
    mavenLocal()
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    mavenCentral()
    // SeedcrackerX dependency repositories
    maven("https://maven.shedaniel.me/") { name = "Shedaniel (cloth-config)" }
    maven("https://maven.latticg.com/") { name = "LattiCG" }
    maven("https://maven.seedfinding.com/") { name = "Seedfinding" }
    maven("https://maven-snapshots.seedfinding.com/") { name = "Seedfinding Snapshots" }
    maven("https://jitpack.io") { name = "Jitpack" }
}

dependencies {
    // Mirrors the DIH Client build: official Mojang mappings are implicit for the configured version, so there is no
    // explicit `mappings(...)` line and dependencies use plain `implementation`.
    minecraft("com.mojang:minecraft:${target.minecraft}")
    implementation("net.fabricmc:fabric-loader:${target.loader}")
    implementation("net.fabricmc.fabric-api:fabric-api:${target.fabricApi}")

    // The DIH Client API (published to mavenLocal from the DIH project).
    // Non-transitive: DIH's own runtime libraries (Baritone natives etc.) ship inside the DIH jar.
    // -PdihJar=<path> compiles against a released DIH jar instead (e.g. DIH-Client-5.0.1-26.3.jar).
    val dihJar = findProperty("dihJar") as String?
    if (dihJar != null) {
        compileOnly(files(dihJar))
        testImplementation(files(dihJar))
    } else {
        implementation("com.dihclient:dih:$dihVersion") { isTransitive = false }
    }

    // ---- SeedcrackerX dependencies (bundled jar-in-jar so the addon is self-contained) ----
    // Each library is both an `implementation` (compile classpath) and `include` (jar-in-jar).

    // cloth-config (config GUI). Exclude transitive fabric-api to avoid duplicates.
    val clothConfig = "me.shedaniel.cloth:cloth-config-fabric:${target.cloth}"
    implementation(clothConfig) { exclude(group = "net.fabricmc.fabric-api") }
    include(clothConfig) { exclude(group = "net.fabricmc.fabric-api") }

    // seedfinding libraries (non-transitive, matching the original SeedcrackerX build)
    val seedfinding = listOf(
        "com.seedfinding:mc_math:${property("seedfinding_math_version")}",
        "com.seedfinding:mc_seed:${property("seedfinding_seed_version")}",
        "com.seedfinding:mc_core:${property("seedfinding_core_version")}",
        "com.seedfinding:mc_noise:${property("seedfinding_noise_version")}",
        "com.seedfinding:mc_biome:${property("seedfinding_biome_version")}",
        "com.seedfinding:mc_terrain:${property("seedfinding_terrain_version")}",
        "com.seedfinding:mc_feature:${property("seedfinding_feature_version")}",
        "com.seedfinding:mc_reversal:${property("seedfinding_reversal_version")}"
    )
    seedfinding.forEach { dep ->
        implementation(dep) { isTransitive = false }
        include(dep) { isTransitive = false }
    }

    // SQLite (DonutSMP AH flipper market/trade persistence).
    val sqlite = "org.xerial:sqlite-jdbc:3.53.1.0"
    implementation(sqlite)
    include(sqlite)

    // JOCL (OpenCL bindings) for the in-game GPU bedrock cracker; natives self-extract at runtime.
    val jocl = "org.jocl:jocl:2.0.5"
    implementation(jocl)
    include(jocl)

    // latticg (Java Random reversal)
    val latticg = "com.seedfinding:latticg:${property("latticg_version")}"
    implementation(latticg)
    include(latticg)

    // Headless regressions and real-bytecode contracts; no game/client/server launch.
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.ow2.asm:asm-tree:9.9")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    val testHome = layout.buildDirectory.dir("test-home")
    val testScratch = layout.buildDirectory.dir("test-scratch")
    systemProperty("user.home", testHome.get().asFile.absolutePath)
    systemProperty("java.io.tmpdir", testScratch.get().asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
    systemProperty("dogs.minecraft", target.minecraft)
    doFirst {
        testHome.get().asFile.mkdirs()
        testScratch.get().asFile.mkdirs()
    }
}

// Turns an exact Minecraft version (e.g. "26.2") into a compatible range ("~26.2") so the addon
// keeps loading across patch releases instead of pinning one exact build.
fun toMinecraftCompat(version: String): String {
    val m = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?$""").matchEntire(version)
        ?: return version
    val (year, drop, _) = m.destructured
    return "~$year.$drop"
}

tasks {
    processResources {
        val propertyMap = mapOf(
            "version" to libs.versions.mod.version.get(),
            "mc_version" to target.minecraft,
            "mc_compat" to toMinecraftCompat(target.minecraft),
            "fabric_api_version" to target.fabricApi,
            // DIH's own apiVersion() handshake gates real API compatibility.
            "dih_api_version" to ">=5.0.0"
        )
        inputs.properties(propertyMap)
        filteringCharset = "UTF-8"
        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(25)
    }
}
