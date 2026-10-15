plugins {
    `java-library`
    id("com.gradleup.shadow") version "8.3.10"
}

group = "pt.flawless"
version = "1.0"

repositories { mavenLocal(); mavenCentral() }

dependencies {
    compileOnly("pt.flawless:fapi:1.0")
    compileOnly("org.spigotmc:spigot:1.8-R0.1-SNAPSHOT")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("com.mysql:mysql-connector-j:8.4.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.spigotmc:spigot:1.8-R0.1-SNAPSHOT")
    testImplementation("pt.flawless:fapi:1.0")
}

tasks.withType<JavaCompile> { options.release = 17; options.encoding = "UTF-8" }

tasks.test {
    useJUnitPlatform()
    // Os testes de integração usam sempre a base de testes, seja qual for o ambiente do utilizador.
    environment("FLAWLESS_DB_NAME", "flawless_test")
}

// O jar "normal" fica desativado: o plugin é distribuído como jar sombreado (com as libs relocadas).
tasks.jar { enabled = false }

tasks.shadowJar {
    archiveFileName = "${project.name}.jar"
    archiveClassifier = ""
    relocate("com.zaxxer.hikari", "pt.flawless.fdatabase.lib.hikari")
    relocate("com.mysql", "pt.flawless.fdatabase.lib.mysql")
    relocate("com.google.protobuf", "pt.flawless.fdatabase.lib.protobuf")
    relocate("org.slf4j", "pt.flawless.fdatabase.lib.slf4j")
    mergeServiceFiles()
}

tasks.build { dependsOn(tasks.shadowJar) }

val pluginsDirFile = providers.gradleProperty("pluginsDir").map { layout.projectDirectory.dir(it).asFile }

// O Gradle cria a pasta de destino de um Copy antes do doFirst, por isso a verificação
// tem de ser uma tarefa à parte de que o deploy depende.
val checkPluginsDir = tasks.register("checkPluginsDir") {
    doLast {
        val dir = pluginsDirFile.get()
        if (!dir.isDirectory) throw GradleException("pluginsDir não existe: $dir")
    }
}

tasks.register<Copy>("deploy") {
    dependsOn(tasks.shadowJar, checkPluginsDir)
    from(tasks.shadowJar)
    into(pluginsDirFile)
}
