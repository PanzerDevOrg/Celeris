@file:Suppress("AvoidDuplicateDependencies")

import com.panzer.gradle.PanzerModExtension

plugins {
    id("panzer.neoforge-mod")
    `maven-publish`
    idea
}

val modProps = extensions.getByType(PanzerModExtension::class.java).props

val ringBufferCap: Long = modProps.req(project, "ring_buffer.default_capacity_pow2").toLong()
val ringBufferCacheLine: Long = modProps.req(project, "ring_buffer.cache_line_bytes").toLong()
val netCompressThreshold: Long = modProps.req(project, "network.compression_threshold_bytes").toLong()
val netCompressAlgo: String = modProps.req(project, "network.compression_algo")

val mainSourceSet = sourceSets.main.get()

neoForge {
    runs {
        all {
            val runDir = rootProject.file("versions/${modProps.currentVersion}/run")
            if (!runDir.exists()) runDir.mkdirs()

            sourceSet = mainSourceSet
            gameDirectory = runDir

            systemProperty("celeris.ringbuffer.capacity", ringBufferCap.toString())
            systemProperty("celeris.ringbuffer.cacheline", ringBufferCacheLine.toString())
            systemProperty("celeris.network.threshold", netCompressThreshold.toString())
            systemProperty("celeris.network.algo", netCompressAlgo)
        }

        register("client") {
            client()
            systemProperty("neoforge.enabledGameTestNamespaces", modProps.modId)
        }

        register("server") {
            server()
            programArgument("--nogui")
            systemProperty("neoforge.enabledGameTestNamespaces", modProps.modId)
        }
    }
}

sourceSets.main {
    resources {
        srcDir("src/generated/resources")
    }
}

val nativesDir = layout.buildDirectory.dir("generated/natives")

// Every native library in the jar, by file name per OS. zstd is a committed
// prebuilt; celeris_physics is built from native/ (see [natives.celeris_physics]
// in mod.stonecutter.properties.toml and buildNativeCelerisPhysics from
// panzer-build-logic) and committed per platform the same way.
data class NativeLib(val windows: String, val macos: String, val linux: String, val renameLinux: String? = null,
                     val renameWindows: String? = null, val required: Boolean = true)

val nativeLibs = listOf(
    NativeLib("zstd.dll", "libzstd.dylib", "libzstd.so", renameLinux = "libzstd.so.1", renameWindows = "libzstd.dll"),
    // Optional per platform: without it the physics engine runs its Java kernel.
    NativeLib("celeris_physics.dll", "libceleris_physics.dylib", "libceleris_physics.so", required = false),
)

data class NativeTarget(val os: String, val arch: String) {
    val classifier: String = "$os-$arch"
    val relativeSourcePath: String = "$os/$arch"
    fun fileName(lib: NativeLib): String = when (os) {
        "windows" -> lib.windows
        "macos" -> lib.macos
        else -> lib.linux
    }
}

fun target(os: String, vararg architectures: String) =
    architectures.map { arch -> NativeTarget(os, arch) }

val nativeTargets = listOf(
    target("windows", "x86_64", "aarch64"),
    target("linux", "x86_64", "aarch64", "ppc64le", "riscv64"),
    target("macos", "x86_64", "aarch64")
).flatten()

val explicitTarget = providers.gradleProperty("celeris.native.target").orElse("").get()

// fat, auto, off
val nativeMode = run {
    val requestedTasks = gradle.startParameter.taskNames
    val collectRequested = requestedTasks.any { it.endsWith("buildAndCollect") }
    when {
        collectRequested -> "fat"
        else -> project.findProperty("celeris.native.mode")?.toString() ?: "fat"
    }
}

val collectNatives = tasks.register<Copy>("collectNatives") {
    group = "celeris"
    description = "Stages native libraries into /natives, renaming into the flat <os>-<arch> layout the runtime expects"

    val sourceDir = rootProject.projectDir.resolve("natives")

    val targetsToCopy = when {
        explicitTarget.isNotBlank() -> {
            val resolved = when {
                explicitTarget.contains("pc-windows") -> "windows-x86_64"
                explicitTarget.contains("linux") && explicitTarget.startsWith("aarch64") -> "linux-aarch64"
                explicitTarget.contains("linux") -> "linux-x86_64"
                else -> explicitTarget.replace("_", "-")
            }
            nativeTargets.filter { it.classifier == resolved }
        }

        nativeMode == "off" -> emptyList()
        nativeMode == "auto" -> {
            val os = if (System.getProperty("os.name").lowercase().contains("win")) "windows"
            else if (System.getProperty("os.name").lowercase().contains("mac")) "macos"
            else "linux"
            val arch = if (System.getProperty("os.arch").lowercase()
                    .let { it.contains("aarch64") || it.contains("arm64") }
            ) "aarch64" else "x86_64"
            nativeTargets.filter { it.classifier == "$os-$arch" }
        }

        else -> nativeTargets // fat (fallback)
    }

    if (sourceDir.exists()) {
        targetsToCopy.forEach { target ->
            nativeLibs.forEach { lib ->
                val srcFile = sourceDir.resolve(target.relativeSourcePath).resolve(target.fileName(lib))
                if (srcFile.exists()) {
                    from(srcFile) {
                        into("natives/${target.classifier}")
                        when {
                            target.os == "linux" && lib.renameLinux != null -> rename { lib.renameLinux }
                            target.os == "windows" && lib.renameWindows != null -> rename { lib.renameWindows }
                        }
                    }
                } else if (lib.required) {
                    logger.warn("Celeris: missing native binary for ${target.classifier} at $srcFile")
                }
            }
        }
    } else if (nativeMode != "off") {
        logger.warn("Celeris: natives/ directory not found at $sourceDir -- no natives will be bundled")
    }

    into(nativesDir)
}

sourceSets.main {
    resources.srcDir(nativesDir)
}

tasks {
    val generateRuntimeRequirements = register<Task>("generateRuntimeRequirements") {
        group = "build"
        description = "Emits META-INF/celeris-runtime-requirements.properties describing the " +
                "JVM flags any consumer of this jar's FFM/vector code must pass to their own runs."

        val outputFile =
            layout.buildDirectory.file("generated/celerisRuntimeRequirements/META-INF/celeris-runtime-requirements.properties")
        outputs.file(outputFile)

        val runtimeArgs = modProps.jvmModules.values
            .filter { it.addToRuntime }
            .flatMap { it.jvmRunArgs() }
            .distinct()
        inputs.property("runtimeArgs", runtimeArgs)

        doLast {
            val file = outputFile.get().asFile
            file.parentFile.mkdirs()
            file.writeText(buildString {
                appendLine("# Generated by Celeris's build -- do not edit by hand.")
                appendLine("# Consumed by NerdSoftBuildLogic's collectPropagatedJvmArgs for any")
                appendLine("# project that depends on this jar, so its own NeoForge runs get the")
                appendLine("# flags this jar's FFM/vector code needs, without declaring them by hand.")
                appendLine("jvm.args=${runtimeArgs.joinToString(" ")}")
            })
        }
    }

    processResources {
        dependsOn(collectNatives, generateRuntimeRequirements)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        from(generateRuntimeRequirements.map { it.outputs.files })

        val props = buildMap {
            fun registerProp(targetKey: String, tomlKey: String = targetKey) {
                val value = modProps.req(project, tomlKey)
                inputs.property(targetKey, value)
                put(targetKey, value)
            }

            registerProp("loader_version_range", "neoforge.loader_version_range")
            registerProp("mod_license", "mod.license")
            registerProp("mod_id", "mod.id")
            registerProp("mod_version", "mod.version")
            registerProp("mod_name", "mod.name")
            registerProp("mod_authors", "mod.authors")
            registerProp("mod_banner", "mod.banner")
            registerProp("mod_side", "mod.side")
            registerProp("mod_description", "mod.description")
            registerProp("mod_issues", "mod.issues")
            registerProp("neo_version_range")
            registerProp("minecraft_version_range")
        }

        filesMatching("META-INF/neoforge.mods.toml") { expand(props) }

        val mixinJava = "JAVA_${modProps.requiredJava.majorVersion}"
        filesMatching("*.mixins.json") { expand("java" to mixinJava) }
    }

    withType<Jar>().configureEach {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        entryCompression = ZipEntryCompression.DEFLATED
        dependsOn(processResources)

        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        exclude("META-INF/maven/**")
        exclude("**/*.kotlin_module")
        exclude("META-INF/DEPENDENCIES")
        exclude("META-INF/INDEX.LIST")
        exclude("**/*.kotlin_builtins")
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Build the mod and sources jars (plus per-system jars) into `build/libs/{mod version}/`"

        dependsOn("jar")
        dependsOn(publishToMavenLocal)
        from(project.tasks.named("jar"))
        from(project.tasks.named("sourcesJar"))
        inputs.property("version", modProps.modVersion)
        into(rootProject.layout.buildDirectory.file("libs/${modProps.modVersion}"))
    }
}

tasks.named("processTestResources") {
    dependsOn(collectNatives)
}

tasks.withType<Test>().configureEach {
    dependsOn("processResources")
    outputs.upToDateWhen { false }
}

tasks.named("build") {
    dependsOn("publishToMavenLocal")
}

idea {
    module {
        testSources.from(rootProject.file("src/test/java"))
    }
}

sourceSets {
    test {
        resources.srcDir(nativesDir)
        val generatedTestDir = project.layout.buildDirectory.dir("generated/stonecutter/test/java").get().asFile
        java.setSrcDirs(listOf(generatedTestDir))
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            // C header of the physics kernel, for native code linking against
            // the same ABI (classifier: native-headers).
            artifact(rootProject.tasks.named("nativeHeadersCelerisPhysics"))
            groupId = modProps.modGroup
            artifactId = "celeris-${modProps.currentVersion}"
            version = modProps.modVersion
        }
    }
    repositories {
        mavenLocal()
        // Static Maven tree that CI pushes to gh-pages/maven on v* tags, served at
        // https://panzerdevorg.github.io/Celeris/maven. Root build dir, so every
        // Stonecutter version publishes into the same tree.
        maven {
            name = "GitHubPages"
            url = uri(rootProject.layout.buildDirectory.dir("publish-repo"))
        }
    }
}
