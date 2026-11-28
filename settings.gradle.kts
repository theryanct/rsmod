import java.nio.file.Files
import java.nio.file.Path

rootProject.name = "rsmod"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://repo.openrs2.org/repository/openrs2-snapshots")
    }
}

include(
    "api",
    "content",
    "engine",
    "server"
)

// Rev 243: modules whose interfaces were redesigned in the 243 cache, disabled until ported
// (skill guides -> skill_guide_v2, canoes -> per-station canoe_map_* interfaces).
val disabledProjects = setOf("content:interfaces:skill-guides", "content:travel:canoe")

includeProjects(project(":api"))
includeProjects(project(":content"))
includeProjects(project(":engine"))
includeProjects(project(":server"))

fun includeProjects(pluginProject: ProjectDescriptor) {
    val projectPath = pluginProject.projectDir.toPath()
    Files.walk(projectPath).forEach {
        if (!Files.isDirectory(it)) {
            return@forEach
        }
        searchProject(pluginProject.name, projectPath, it)
    }
}

fun searchProject(parentName: String, root: Path, currentPath: Path) {
    val hasBuildFile = Files.exists(currentPath.resolve("build.gradle.kts"))
    if (!hasBuildFile) {
        return
    }
    val relativePath = root.relativize(currentPath)
    val projectName = relativePath.toString().replace(File.separator, ":")
    val path = "$parentName:$projectName"
    if (disabledProjects.any { path == it || path.startsWith("$it:") }) {
        return
    }
    include(path)
}
