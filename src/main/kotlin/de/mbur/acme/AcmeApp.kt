package de.mbur.acme

import mu.KotlinLogging
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import javax.annotation.PostConstruct

private val log = KotlinLogging.logger {}

@SpringBootApplication
@EnableScheduling
class AcmeApp(
  private val buildProperties: BuildProperties,
) {

  @PostConstruct
  fun init() {
    log.info { "Version: ${buildProperties.version}" }
  }
}

fun main(args: Array<String>) {
  runApplication<AcmeApp>(*args)
}
