package de.mbur.acme.security

import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.*

private val log = KotlinLogging.logger {}

@Configuration
class TestConfiguration {

  @Bean
  @ConditionalOnMissingBean(BuildProperties::class)
  fun buildProperties(): BuildProperties = BuildProperties(Properties()).also {
    log.info { "BuildProperties bean did not auto-load, creating mock BuildProperties" }
  }

}
