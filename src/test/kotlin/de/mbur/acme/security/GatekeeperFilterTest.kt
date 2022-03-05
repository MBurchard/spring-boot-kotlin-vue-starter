package de.mbur.acme.security

import mu.KotlinLogging
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

private val log = KotlinLogging.logger {}

@WebMvcTest
@Import(TestConfiguration::class)
@DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)
internal class GatekeeperFilterTest {

  @Autowired
  private lateinit var mockMvc: MockMvc

  @Test
  fun `test PhP blocking`() {
    // given
    val urls = listOf(
      "/api/test.php",
      "/api/test.php3",
      "/api/test.php4",
      "/api/something/test>.php",
      "/api/test.php#",
      "/api/test.php/something",
      "/api/test.php3/something",
      "/api/test.php4/something",
      "/api/test.php3/",
    )
    // when
    urls.forEach {
      mockMvc.get(it)
        .andExpect {
          status { isBadRequest() }
          content {
            contentType(MediaType.TEXT_PLAIN)
            string("are you kidding")
          }
        }
      log.debug { "'$it' was blocked" }
    }
  }

  @Test
  fun `test complete blocking`() {
    // given
    for (i in 1..10) {
      mockMvc.get("/api/test.php")
        .andExpect {
          status { isBadRequest() }
          content {
            contentType(MediaType.TEXT_PLAIN)
            string("are you kidding")
          }
        }
    }
    // when
    mockMvc.get("/api/test.php")
      .andExpect {
        status { isBadRequest() }
        content {
          contentType(MediaType.TEXT_PLAIN)
          string("blocked, go away")
        }
      }

    // then

  }
}
