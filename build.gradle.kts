plugins {
    java
}

group = "com.electionsplugin"
version = "0.1.0-SNAPSHOT"

dependencies {
    compileOnly("dev.folia:folia-api:26.1.2.build.+")
    compileOnly("net.luckperms:api:5.5")
    compileOnly("net.dv8tion:JDA:6.4.1")
    compileOnly("org.xerial:sqlite-jdbc:3.49.1.0")
    compileOnly("com.google.code.gson:gson:2.13.1")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
