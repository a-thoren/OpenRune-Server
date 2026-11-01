plugins {
    id("base-conventions")
}

dependencies {
    implementation(projects.api.pluginCommons)
    implementation(projects.api.attr)
    implementation(projects.api.bosses)
    implementation(projects.api.bossHpBarPlugin)
    implementation(projects.api.dropTable)
    implementation(projects.api.dropTablePlugin)
    implementation(projects.api.instances)
    implementation(projects.api.invtx)
    implementation(projects.api.player)
    implementation(projects.api.playerOutput)
    implementation(projects.api.shops)
    implementation(projects.api.specials)
    implementation(projects.engine.routefinder)
    implementation(projects.content.drops)
    implementation(projects.content.raids.party)
    implementation(projects.content.interfaces.collectionLog)
    implementation(libs.kotlin.inline.logger)
}
