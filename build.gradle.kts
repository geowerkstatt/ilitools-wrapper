plugins {
    id("java")
    id("application")
    id("checkstyle")
}

group = "ch.geowerkstatt.ilitoolswrapper"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

application {
    mainClass = "Main"
}

checkstyle {
    toolVersion = "13.7.0"
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
