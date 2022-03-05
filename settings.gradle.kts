rootProject.name = "spring-boot-kotlin-starter"

pluginManagement {
  val benManesVersionsVersion: String by settings
  val kotlinVersion: String by settings
  val springBootVersion: String by settings
  plugins {
    id("com.github.ben-manes.versions") version benManesVersionsVersion
    id("io.spring.dependency-management") version "1.0.11.RELEASE"
    id("org.springframework.boot") version springBootVersion
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.spring") version kotlinVersion
  }
}
