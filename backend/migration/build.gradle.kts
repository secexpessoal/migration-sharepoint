plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.spotless)
}

group = "org.migration"
version = "0.0.1-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

sourceSets {
    main {
        java { setSrcDirs(listOf("main/java")) }
        resources { setSrcDirs(listOf("main/resources")) }
    }
    test {
        java { setSrcDirs(listOf("test/java")) }
    }
}

dependencies {
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.hibernate.community.dialects)
    implementation(libs.bucket4j.core)
    implementation(libs.spring.boot.starter.quartz)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.springdoc.openapi.starter.webmvc.ui)
    compileOnly(libs.lombok)
    developmentOnly(libs.spring.boot.devtools)
    runtimeOnly(libs.sqlite.jdbc)
    runtimeOnly(libs.mysql.connector.j)
    runtimeOnly(libs.postgresql)
    implementation(libs.mongodb.driver.sync)
    annotationProcessor(libs.lombok)
    testImplementation(libs.spring.boot.starter.actuator.test)
    testImplementation(libs.spring.boot.starter.data.jpa.test)
    testImplementation(libs.spring.boot.starter.quartz.test)
    testImplementation(libs.spring.boot.starter.validation.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testCompileOnly(libs.lombok)
    testRuntimeOnly(libs.junit.platform.launcher)
    testAnnotationProcessor(libs.lombok)
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<org.springframework.boot.gradle.tasks.run.BootRun> {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

spotless {
    java {
        target("main/java/**/*.java", "test/java/**/*.java")
        palantirJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
