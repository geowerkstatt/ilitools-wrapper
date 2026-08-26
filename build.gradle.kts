import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("java")
    id("application")
    id("checkstyle")
    id("com.google.protobuf") version "0.9.5"
    id("net.ltgt.errorprone") version "5.1.0"
}

group = "ch.geowerkstatt.ilitoolswrapper"

repositories {
    mavenCentral()
}

val grpcVersion = "1.82.1"
val protobufVersion = "3.25.9"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

application {
    mainClass = "ch.geowerkstatt.ilitoolswrapper.Main"
}

checkstyle {
    toolVersion = "13.7.0"
}

dependencies {
    implementation(platform("io.grpc:grpc-bom:${grpcVersion}"))
    implementation("io.grpc:grpc-protobuf")
    implementation("io.grpc:grpc-services")
    implementation("io.grpc:grpc-stub")
    implementation("org.jspecify:jspecify:1.0.0")

    runtimeOnly("io.grpc:grpc-netty-shaded")

    errorprone("com.google.errorprone:error_prone_core:2.50.0")
    errorprone("com.uber.nullaway:nullaway:0.13.7")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.53.2.1")
    testImplementation("org.xmlunit:xmlunit-core:2.12.0")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${protobufVersion}" }
    plugins {
        create("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:${grpcVersion}" }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                create("grpc")
            }
        }
    }
}

sourceSets {
    main {
        proto {
            srcDir("./proto")
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        excludedPaths = ".*/generated/.*"

        option("NullAway:JSpecifyMode", "true")
        option("NullAway:AnnotatedPackages", "ch.geowerkstatt.ilitoolswrapper")
        error("NullAway")
    }
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = application.mainClass
        attributes["Class-Path"] = configurations.runtimeClasspath.get().joinToString(" ") { file -> file.name }
        attributes["Implementation-Version"] = version
    }
}

tasks.test {
    useJUnitPlatform()
}

val ili2gpkgVersion = providers.gradleProperty("ili2gpkgVersion")
val ilivalidatorVersion = providers.gradleProperty("ilivalidatorVersion")
// The offered set is the default plus the comma- or whitespace-separated additional versions; the default
// stays first. Whitespace is also accepted so a Dockerfile ARG value (space separated) works unchanged here.
val ili2gpkgVersions = ili2gpkgVersion.zip(providers.gradleProperty("ili2gpkgAdditionalVersions").orElse("")) { default, additional ->
    listOf(default) + additional.split(Regex("[,\\s]+")).map(String::trim).filter(String::isNotEmpty)
}
val ilivalidatorVersions = ilivalidatorVersion.zip(providers.gradleProperty("ilivalidatorAdditionalVersions").orElse("")) { default, additional ->
    listOf(default) + additional.split(Regex("[,\\s]+")).map(String::trim).filter(String::isNotEmpty)
}
val ilitoolsHome = layout.projectDirectory.dir("ilitools")
val ili2gpkgHome = ilitoolsHome.dir("ili2gpkg")
val ilivalidatorHome = ilitoolsHome.dir("ilivalidator")

// Downloads every offered version of a tool into ./ilitools/<toolName>/<version>/ for local development. A
// version is a whole directory: the distribution manifest pins its exact libs/, so versions cannot share.
fun registerIlitoolDownload(taskName: String, toolName: String, versions: Provider<List<String>>, toolHome: Directory) = tasks.register(taskName) {
    group = "ilitools"
    description = "Downloads the offered $toolName versions into ./ilitools for local development"

    inputs.property("versions", versions)
    outputs.dir(toolHome)

    doLast {
        val offered = versions.get()
        // Anything below the tool home that is not an offered version is stale (a removed version or the
        // old flat layout) and would otherwise still be offered by the scan, diverging from the image.
        toolHome.asFile.listFiles()?.filter { child -> child.name !in offered }?.forEach { child -> project.delete(child) }

        offered.forEach { version ->
            val versionDir = toolHome.dir(version).asFile
            if (versionDir.resolve("$toolName-$version.jar").exists()) {
                return@forEach
            }

            val downloadUrl = project.uri("https://downloads.interlis.ch/$toolName/$toolName-$version.zip")
            val zipFile = temporaryDir.resolve("$toolName-$version.zip")

            logger.lifecycle("Downloading $downloadUrl")
            downloadUrl.toURL().openStream().use { input ->
                zipFile.outputStream().use { output -> input.copyTo(output) }
            }

            project.delete(versionDir)
            project.copy {
                from(project.zipTree(zipFile))
                into(versionDir)
            }
            logger.lifecycle("Extracted $toolName $version into $versionDir")
        }
    }
}

registerIlitoolDownload("downloadIli2gpkg", "ili2gpkg", ili2gpkgVersions, ili2gpkgHome)
registerIlitoolDownload("downloadIlivalidator", "ilivalidator", ilivalidatorVersions, ilivalidatorHome)

// A minimal ilivalidator plugin, built here instead of pulled from a release of a real function library, so the
// tests prove the --plugins mechanism without depending on that library's evolution and without an external
// artifact in CI. It compiles against the downloaded ilivalidator distribution and is packed into the catalog
// layout the plugin tests point at: <catalog root>/<plugin id>/<jar>.
val testPluginSourceSet = sourceSets.create("testPlugin")
val testPluginCatalog = layout.buildDirectory.dir("test-plugins")

// The classpath is assigned on the task instead of declared as a dependency, so it is resolved when the task
// runs. Declaring it holds handles on the distribution jars, which makes the re-extraction in
// downloadIlivalidator fail on Windows.
tasks.named<JavaCompile>("compileTestPluginJava") {
    dependsOn("downloadIlivalidator")
    // Compile against the default version only: with several versions below the home, the whole tree would
    // put the same classes on the classpath twice.
    classpath = files(provider { fileTree(ilivalidatorHome.dir(ilivalidatorVersion.get())) { include("**/*.jar") } })
}

val testPluginJar = tasks.register<Jar>("testPluginJar") {
    group = "verification"
    description = "Packs the minimal test plugin into the catalog layout the plugin tests point at"

    from(testPluginSourceSet.output)
    archiveFileName = "test-functions.jar"
    destinationDirectory = testPluginCatalog.map { catalog -> catalog.dir("test-functions") }
}

// Automatically download and set up the ilitools on `gradlew run` and `gradlew test`.
listOf(tasks.run, tasks.test).forEach { task ->
    task.configure {
        dependsOn("downloadIli2gpkg", "downloadIlivalidator")
        environment("ILI2GPKG_HOME", ili2gpkgHome.asFile.absolutePath)
        environment("ILI2GPKG_VERSION", ili2gpkgVersion.get())
        environment("ILIVALIDATOR_HOME", ilivalidatorHome.asFile.absolutePath)
        environment("ILIVALIDATOR_VERSION", ilivalidatorVersion.get())
        // Keep the INTERLIS model cache inside the build directory instead of the user home.
        environment("ILI_CACHE", layout.buildDirectory.dir("ilicache").get().asFile.absolutePath)
    }
}

// The plugin catalog of the tests is built, not shipped, so the test task builds it and tells the tests where
// it is. Only `test` gets this; a real deployment configures ILIVALIDATOR_PLUGINS_DIR instead.
tasks.test {
    dependsOn(testPluginJar)
    environment("TEST_PLUGIN_CATALOG", testPluginCatalog.get().asFile.absolutePath)
}
