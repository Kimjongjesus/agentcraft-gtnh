
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

tasks.register<JavaExec>("captureBudgetProbe") {
    group = "verification"
    description = "Non-live 500-machine charged-cost tick-budget acceptance gate"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.robertsnest.aifactory.telemetry.CaptureBudgetProbe")
}

tasks.register<JavaExec>("clientRenderProbe") {
    group = "verification"
    description = "World-free real GL/GuiScreen to local synthetic Python Oracle probe"
    dependsOn(tasks.named("testClasses"), tasks.named("extractNatives2"))
    classpath = sourceSets["test"].runtimeClasspath + sourceSets["main"].compileClasspath
    mainClass.set("com.robertsnest.aifactory.client.ClientRenderProbe")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(8)) })
    systemProperty("java.library.path", tasks.named("extractNatives2").get().outputs.files.singleFile.absolutePath)
    args(providers.gradleProperty("oracleProbeUrl").getOrElse(""),
        providers.gradleProperty("oracleProbeTokenFile").getOrElse(""),
        providers.gradleProperty("oracleProbeProposal").getOrElse(""),
        providers.gradleProperty("oracleProbeOutput").getOrElse(""))
}
