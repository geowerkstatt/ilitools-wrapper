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
val ilitoolsHome = layout.projectDirectory.dir("ilitools")
val ili2gpkgHome = ilitoolsHome.dir("ili2gpkg")

// Downloads ili2gpkg into ./ilitools for local development.
// The version is taken from the ili2gpkgVersion project property (see gradle.properties).
tasks.register("downloadIli2gpkg") {
    group = "ilitools"
    description = "Downloads ili2gpkg into ./ilitools for local development"

    val targetDir = ili2gpkgHome
    val jarExists = ili2gpkgVersion.map { version -> targetDir.file("ili2gpkg-$version.jar").asFile.exists() }

    inputs.property("version", ili2gpkgVersion)
    outputs.dir(targetDir)

    // Skip when the matching version is already present
    onlyIf { !jarExists.getOrElse(false) }

    doLast {
        val version = ili2gpkgVersion.orNull ?: throw GradleException("Set -Pili2gpkgVersion=<version>.")

        val downloadUrl = uri("https://downloads.interlis.ch/ili2gpkg/ili2gpkg-$version.zip")
        val zipFile = temporaryDir.resolve("ili2gpkg-$version.zip")

        logger.lifecycle("Downloading $downloadUrl")
        downloadUrl.toURL().openStream().use { input ->
            zipFile.outputStream().use { output -> input.copyTo(output) }
        }

        val dir = targetDir.asFile
        delete(dir)
        copy {
            from(zipTree(zipFile))
            into(dir)
        }
        logger.lifecycle("Extracted ili2gpkg $version into $dir")
    }
}

// Automatically download and set up the ilitools on `gradlew run` and `gradlew test`.
listOf(tasks.run, tasks.test).forEach { task ->
    task.configure {
        dependsOn("downloadIli2gpkg")
        environment("ILI2GPKG_HOME", ili2gpkgHome.asFile.absolutePath)
        environment("ILI2GPKG_VERSION", ili2gpkgVersion.get())
    }
}
