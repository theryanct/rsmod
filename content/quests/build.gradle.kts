plugins {
    id("base-conventions")
}

dependencies {
    implementation(projects.api.pluginCommons)
    implementation(projects.api.registry)
    implementation(projects.content.interfaces.bank)
    implementation(projects.content.skills.mining)
    implementation(projects.content.skills.sailing)
    implementation(projects.content.travel.ships)
}
