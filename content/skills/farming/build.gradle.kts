plugins {
    id("base-conventions")
}

dependencies {
    implementation(projects.api.pluginCommons)
    implementation(projects.content.interfaces.skillGuides)
    implementation(projects.content.skills.woodcutting)
}
