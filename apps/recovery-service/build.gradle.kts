plugins {
    kotlin("jvm") version "2.0.21"
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.abandonedcart.recovery.MainKt")
}

dependencies {
    implementation("com.google.inject:guice:7.0.0")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.flywaydb:flyway-core:10.20.1")
    implementation("org.flywaydb:flyway-database-postgresql:10.20.1")
    implementation("org.slf4j:slf4j-simple:2.0.16")
    implementation("io.opentelemetry:opentelemetry-api:1.47.0")
    implementation("io.opentelemetry:opentelemetry-sdk:1.47.0")
    implementation("io.opentelemetry:opentelemetry-exporter-prometheus:1.47.0-alpha")
    implementation("org.postgresql:postgresql:42.7.4")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

tasks.test {
    useJUnitPlatform()
}
