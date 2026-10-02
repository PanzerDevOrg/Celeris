val commonToml = File(rootDir, "../panzer-build-logic/common.stonecutter.properties.toml")
val modToml = File(rootDir, "mod.stonecutter.properties.toml")
val mergedToml = File(rootDir, "stonecutter.properties.toml")

// Declare both source TOMLs as configuration-cache inputs. Reads through plain
// File APIs here are not reliably fingerprinted, which let an edited TOML build
// with stale values from the cache; providers.fileContents() is always tracked.
listOf(modToml, commonToml).filter { it.exists() }.forEach {
    providers.fileContents(layout.rootDirectory.file(it.relativeTo(rootDir).invariantSeparatorsPath)).asBytes.get()
}

if (!commonToml.exists()) {
    error("Strict Configuration Error: shared 'common.stonecutter.properties.toml' was not found at '${commonToml.path}'. Check that '../panzer-build-logic' exists next to this project.")
}
if (!modToml.exists()) {
    error("Strict Configuration Error: 'mod.stonecutter.properties.toml' was not found in root project directory.")
}

fun splitTomlBlocks(lines: List<String>): LinkedHashMap<String, MutableList<String>> {
    val tableHeaderRegex = Regex("""^\[(.+)]\s*$""")
    val blocks = LinkedHashMap<String, MutableList<String>>()
    var currentKey = ""
    blocks[currentKey] = mutableListOf()

    for (line in lines) {
        val match = tableHeaderRegex.find(line.trim())
        if (match != null) {
            currentKey = match.groupValues[1].trim()
        }
        blocks.getOrPut(currentKey) { mutableListOf() }.add(line)
    }

    if (blocks[""]?.all { it.isBlank() } == true) {
        blocks.remove("")
    }
    return blocks
}

val commonBlocks = splitTomlBlocks(commonToml.readLines())
val modBlocks = splitTomlBlocks(modToml.readLines())

val orderedKeys = LinkedHashSet<String>()
orderedKeys.addAll(commonBlocks.keys)
orderedKeys.addAll(modBlocks.keys)

val mergedBuilder = StringBuilder()
for (key in orderedKeys) {
    // Entire block: if the mod declares [key], it completely replaces the common block (without mixing
    // individual keys). This is what allows Celeris to override [stonecutter] entirely,
    // and it is also what causes a mod's own [stonecutter.profiles.<name>] to override
    // a common one with the same name.
    val chosen = modBlocks[key] ?: commonBlocks[key] ?: continue
    mergedBuilder.append(chosen.joinToString("\n"))
    mergedBuilder.append("\n")
}

mergedToml.writeText(mergedBuilder.toString().trimEnd() + "\n")

pluginManagement {
    includeBuild("../panzer-build-logic")

    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
    }

    // pluginManagement runs before the rest of this script, so the merged
    // stonecutter.properties.toml generated below may not exist yet (fresh
    // clone, CI). Read [plugins] from the source TOMLs: the mod's own first,
    // then the shared one from ../panzer-build-logic.
    fun requirePluginVersion(key: String): String {
        val sources = listOf(file("mod.stonecutter.properties.toml"),
            file("../panzer-build-logic/common.stonecutter.properties.toml"))
        for (source in sources.filter { it.exists() }) {
            val value = source.readLines().map { it.trim() }
                .dropWhile { it != "[plugins]" }.drop(1)
                .takeWhile { !it.startsWith("[") }
                .firstOrNull { it.startsWith("$key =") || it.startsWith("$key=") }
                ?.split("=", limit = 2)?.get(1)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")
            if (value != null) return value
        }
        error("Plugin version '$key' is missing under [plugins] in mod.stonecutter.properties.toml " +
                "and ../panzer-build-logic/common.stonecutter.properties.toml.")
    }

    plugins {
        id("dev.kikugie.stonecutter") version requirePluginVersion("stonecutter")
        id("org.gradle.toolchains.foojay-resolver-convention") version requirePluginVersion("foojay")
        id("net.neoforged.moddev") version requirePluginVersion("moddev")
    }
}

plugins {
    id("dev.kikugie.stonecutter")
    id("org.gradle.toolchains.foojay-resolver-convention")
}

val tomlFile = file("stonecutter.properties.toml")
val tomlLines = tomlFile.readLines().map { it.trim() }

fun getTomlString(key: String): String {
    return tomlLines.firstOrNull { it.startsWith("$key =") || it.startsWith("$key=") }
        ?.split("=")?.get(1)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")
        ?: error("Strict Configuration Error: Key '$key' is missing in 'stonecutter.properties.toml'.")
}

fun getTomlList(key: String): List<String> {
    val line = tomlLines.firstOrNull { it.startsWith("$key =") || it.startsWith("$key=") }
        ?: error("Strict Configuration Error: Array key '$key' is missing in 'stonecutter.properties.toml'.")
    val content = line.substringAfter("[").substringBefore("]").trim()
    if (content.isEmpty()) error("Strict Configuration Error: Array key '$key' cannot be empty in 'stonecutter.properties.toml'.")
    return content.split(",").map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
}

// --- Version resolution: supports explicit "versions" (as before) or ---
// --- "profile" + "extra_versions"/"exclude_versions" pointing to a   ---
// --- [stonecutter.profiles.<name>] (new, optional).                 ---

val mergedBlocks = splitTomlBlocks(mergedToml.readLines())

fun blockOrNull(header: String): List<String>? = mergedBlocks[header]

fun getTomlListInBlock(block: List<String>, key: String): List<String>? {
    val line = block.map { it.trim() }.firstOrNull { it.startsWith("$key =") || it.startsWith("$key=") }
        ?: return null
    val content = line.substringAfter("[").substringBefore("]").trim()
    if (content.isEmpty()) return null
    return content.split(",").map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
}

fun getTomlStringInBlock(block: List<String>, key: String): String? {
    return block.map { it.trim() }.firstOrNull { it.startsWith("$key =") || it.startsWith("$key=") }
        ?.split("=", limit = 2)?.get(1)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")
}

/**
 * Applies -Pstonecutter.versions=a,b,c if passed via the command line: narrows down the
 * resolved list to only those versions (which must exist in the list), allowing fast local
 * builds with a subset without modifying any TOML. Without this property, nothing changes.
 */
fun applyCliOverride(resolved: List<String>): List<String> {
    val raw = startParameter.projectProperties["stonecutter.versions"] ?: return resolved
    val requested = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val unknown = requested.filterNot { it in resolved }
    if (unknown.isNotEmpty()) {
        error(
            "Strict Configuration Error: -Pstonecutter.versions requested ${unknown} but only " +
                    "$resolved are available (check 'versions'/'profile' in stonecutter.properties.toml)."
        )
    }
    if (requested.size != resolved.size) {
        logger.lifecycle("[stonecutter] Building subset $requested of $resolved (-Pstonecutter.versions override).")
    }
    return requested
}

fun resolveVersions(): List<String> {
    val stonecutterBlock = blockOrNull("stonecutter")
        ?: error("Strict Configuration Error: [stonecutter] block is missing in 'stonecutter.properties.toml'.")

    getTomlListInBlock(stonecutterBlock, "versions")?.let { return applyCliOverride(it) }

    val profileName = getTomlStringInBlock(stonecutterBlock, "profile")
        ?: error(
            "Strict Configuration Error: [stonecutter] must declare either 'versions' or 'profile' " +
                    "in stonecutter.properties.toml."
        )
    val profileBlock = blockOrNull("stonecutter.profiles.$profileName")
        ?: error(
            "Strict Configuration Error: [stonecutter] declares profile = \"$profileName\" but no " +
                    "[stonecutter.profiles.$profileName] block was found (checked this mod's own TOML and " +
                    "panzer-build-logic's common.stonecutter.properties.toml)."
        )
    val base = getTomlListInBlock(profileBlock, "versions")
        ?: error("Strict Configuration Error: [stonecutter.profiles.$profileName] has no 'versions' array.")

    val extra = getTomlListInBlock(stonecutterBlock, "extra_versions").orEmpty()
    val exclude = getTomlListInBlock(stonecutterBlock, "exclude_versions").orEmpty()
    val resolved = (base + extra).distinct().filterNot { it in exclude }
    if (resolved.isEmpty()) {
        error(
            "Strict Configuration Error: resolved version list for profile \"$profileName\" is empty " +
                    "after applying 'exclude_versions' -- nothing left to build."
        )
    }
    return applyCliOverride(resolved)
}

val scVersions = resolveVersions()
val scVcsVersion = getTomlString("vcs_version")
val modName = getTomlString("name")

include(":example")

stonecutter {
    create(
        rootProject,
        project(":example")
    ) {
        versions(scVersions)
        vcsVersion = scVcsVersion
    }
}

rootProject.name = modName
