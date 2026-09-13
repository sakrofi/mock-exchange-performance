plugins {
    id("java")
    id("me.champeau.jmh") version "0.7.2"
}

group = "com.trading.matching.engine"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")

    implementation("org.agrona:agrona:2.5.0")
}




// Convenience task to run the BenchmarkRunner
tasks.register<JavaExec>("runBenchmarks") {
    group = "benchmark"
    description = "Runs custom JMH benchmark suite"

    dependsOn("jmhClasses")

    mainClass.set("exchange.benchmark.BenchmarkRunner")
    classpath = sourceSets["jmh"].runtimeClasspath
}

// Test settings
tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
