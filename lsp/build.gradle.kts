// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
import org.aya.gradle.CommonTasks

val mainClassQName = "org.aya.lsp.LspMain"
CommonTasks.fatJar(project, mainClassQName)

dependencies {
  val deps: java.util.Properties by rootProject.ext
  api(project(":cli"))
  api("org.aya-prover.upstream", "javacs-protocol", version = deps.getProperty("version.aya-upstream"))
  annotationProcessor("info.picocli", "picocli-codegen", version = deps.getProperty("version.picocli"))
  testImplementation("org.junit.jupiter", "junit-jupiter", version = deps.getProperty("version.junit"))
  testImplementation("org.hamcrest", "hamcrest", version = deps.getProperty("version.hamcrest"))
}

plugins {
  id("org.beryx.jlink")
}

tasks.withType<JavaCompile>().configureEach {
  doFirst {
    options.compilerArgs.addAll(listOf("--module-path", classpath.asPath))
  }
}

tasks.named<Test>("test") {
  testLogging.showStandardStreams = true
  testLogging.showCauses = true
  inputs.dir(projectDir.resolve("src/test/resources"))
}

val ayaImageDir = buildDir.resolve("image")
val jlinkImageDir = ayaImageDir.resolve("jre")
jlink {
  addOptions("--strip-debug", "--compress", "2", "--no-header-files", "--no-man-pages")
  addExtraDependencies("jline-terminal-jansi")
  imageDir.set(jlinkImageDir)
  mergedModule {
    additive = true
    uses("org.jline.terminal.spi.JansiSupport")
  }
  launcher {
    mainClass.set(mainClassQName)
    name = "aya-lsp"
    jvmArgs = mutableListOf("--enable-preview")
  }
  secondaryLauncher {
    this as org.beryx.jlink.data.SecondaryLauncherData
    name = "aya"
    mainClass = "org.aya.cli.Main"
    moduleName = "aya.cli"
    jvmArgs = mutableListOf("--enable-preview")
  }
}

val jlinkTask = tasks.named("jlink")

@Suppress("unsupported")
val copyAyaExecutables = tasks.register<Copy>("copyAyaExecutables") {
  dependsOn(jlinkTask)
  from(file("src/main/shell")) {
    // https://ss64.com/bash/chmod.html
    fileMode = "755".toInt(8)
    rename { it.removeSuffix(".sh") }
  }
  into(ayaImageDir.resolve("bin"))
}

val prepareMergedJarsDirTask = tasks.named("prepareMergedJarsDir")
prepareMergedJarsDirTask.configure {
  rootProject.subprojects
    .map { ":${it.name}:jar" }
    .mapNotNull(tasks::findByPath)
    .forEach {
      dependsOn(it)
      inputs.files(it.outputs.files)
    }
}

tasks.withType<AbstractCopyTask>().configureEach {
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

if (rootProject.hasProperty("installDir")) {
  val destDir = file(rootProject.property("installDir")!!)
  // val dbi = tasks.register<Delete>("deleteBeforeInstall") {
  //   delete(File.listFiles(destDir))
  // }
  tasks.register<Copy>("install") {
    dependsOn(jlinkTask, copyAyaExecutables, prepareMergedJarsDirTask)
    from(ayaImageDir)
    into(destDir)
  }
}

tasks.withType<JavaCompile>().configureEach { CommonTasks.picocli(this) }
