import net.bichal.gradle.BichalModExtension

plugins {
    id("bichal.neoforge-mod-example")
}

val modProps = extensions.getByType(BichalModExtension::class.java).props

val exampleSourceSet = sourceSets.main.get()

neoForge {
    runs {
        all {
            val runDir = rootProject.file("versions/${modProps.currentVersion}/run-example")
            if (!runDir.exists()) runDir.mkdirs()

            sourceSet = exampleSourceSet
            gameDirectory = runDir
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
