package de.mbur.acme.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.benmanes.caffeine.cache.Caffeine
import mu.KotlinLogging
import org.apache.catalina.connector.ClientAbortException
import org.springframework.beans.BeanInstantiationException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.web.firewall.RequestRejectedException
import org.springframework.security.web.util.matcher.AntPathRequestMatcher
import org.springframework.security.web.util.matcher.OrRequestMatcher
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.NestedServletException
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import javax.annotation.PreDestroy
import javax.servlet.FilterChain
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

private val log = KotlinLogging.logger {}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class GatekeeperFilter(
  private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
  private val response = "are you kidding".toByteArray()
  private val responseBlocked = "blocked, go away".toByteArray()
  private val responseInternalServerError = "Internal Server Error".toByteArray()
  private val responseNotFound = "not found".toByteArray()

  private val blockedIPs =
    Caffeine.newBuilder().maximumSize(10000).expireAfterWrite(60, TimeUnit.MINUTES).build<String, Boolean>()
  private val blockedIPsCounter =
    Caffeine.newBuilder().maximumSize(10000).expireAfterWrite(60, TimeUnit.MINUTES).build<String, Long> { 0 }
  private val blockedRequests =
    Caffeine.newBuilder().maximumSize(10000).expireAfterWrite(60, TimeUnit.MINUTES).build<String, Long> { 0 }
  private val conspicuousIPs =
    Caffeine.newBuilder().maximumSize(10000).expireAfterWrite(5, TimeUnit.MINUTES).build<String, Long> { 0 }
  private val mutex = ReentrantLock()

  private val allowedUrls = OrRequestMatcher(
    AntPathRequestMatcher("/"),
    AntPathRequestMatcher("/actuator/**"),
    AntPathRequestMatcher("/api/**"),
    AntPathRequestMatcher("/error"),
    AntPathRequestMatcher("/index.html"),
    AntPathRequestMatcher("/knock"),
  )

  private val blockList = listOf(
    Regex(
      "^.+\\.(action|asp|aspx|bat|cfm|cgi|conf|dat|dll|do|env|exe|git|go|[jpsx]?html?|ini|jsa|json" +
        "|jsp|key|nsf|ntf|php|pl|sql|svn|swf|tpl|xml|xsl).*?", RegexOption.IGNORE_CASE
    ),
    Regex(
      "^.*?(/\\.ssh|/cgi/|/cgi-bin/|/dns-query|/etc/|id_dsa|id_rsa|/inc/|/includes|/modules?|/moin_static" +
        "|/phpmyadmin|/qualys|/sites/|script|/themes?/|/typo3/|/twiki|/webapp|/wordpress).*?$", RegexOption.IGNORE_CASE
    ),
    Regex(
      "^(/cluster|/core|/lib/|/libraries|/login|/management|/misc|/styles|/system|/ui/|/uploads).*?\$",
      RegexOption.IGNORE_CASE
    ),
  )

  private fun buildLog(req: HttpServletRequest, reason: String) =
    "Request: ${req.remoteAddr} ${req.method} ${getFullURL(req)}\n" +
      "\t\tUseragent: ${req.getHeader(HttpHeaders.USER_AGENT).orEmpty()}\n" +
      "\t\tReferer: ${req.getHeader(HttpHeaders.REFERER).orEmpty()}\n" +
      "\t\tReason: $reason"

  private fun buildResponse(
    resp: HttpServletResponse,
    content: ByteArray = response,
    status: Int = HttpServletResponse.SC_BAD_REQUEST,
  ) {
    try {
      resp.reset()
      resp.status = status
      resp.contentType = MediaType.TEXT_PLAIN.toString()
      resp.setContentLength(content.size)
      resp.outputStream.use {
        it.write(content)
      }
    } catch (_: Throwable) {
    }
  }

  private fun getFullURL(req: HttpServletRequest) = "${req.requestURI}${
    when (req.queryString) {
      null -> ""
      else -> "?${req.queryString}"
    }
  }"

  private fun increaseBlockedRequests(key: String, ip: String) {
    CompletableFuture.runAsync {
      val lKey = key.lowercase()
      try {
        mutex.lock()
        blockedRequests.put(lKey, blockedRequests.get(lKey) + 1)
        if (ip.isNotEmpty()) {
          val count = conspicuousIPs.get(ip) + 1
          conspicuousIPs.put(ip, count)
          if (count >= 10) {
            blockedIPs.put(ip, true)
          }
        }
      } finally {
        mutex.unlock()
      }
    }
  }

  private fun isBlocked(ip: String): Boolean {
    if (ip.isEmpty()) {
      return false
    }
    return try {
      mutex.lock()
      if (blockedIPs.getIfPresent(ip) == null) {
        false
      } else {
        blockedIPsCounter.put(ip, blockedIPsCounter.get(ip) + 1)
        true
      }
    } finally {
      mutex.unlock()
    }
  }

  @PreDestroy
  @Scheduled(cron = "0 0 * * * *")
  private fun showStatistic() {
    try {
      mutex.lock()
      val countAll = AtomicLong(0)
      blockedRequests.asMap().forEach { (key, value) ->
        log.info { "'$key' was blocked $value times within the last hour" }
        countAll.set(countAll.get() + value)
      }
      if (countAll.get() > 0) {
        log.info { "${countAll.get()} requests blocked at all" }
      }
      blockedRequests.invalidateAll()
      countAll.set(0)
      blockedIPsCounter.asMap().forEach { (key, value) ->
        log.info { "IP $key was blocked $value times within the last hour" }
        countAll.set(countAll.get() + value)
      }
      if (countAll.get() > 0) {
        log.info { "${countAll.get()} requests blocked by IP" }
      }
      blockedIPsCounter.invalidateAll()
    } finally {
      mutex.unlock()
    }
  }

  override fun doFilterInternal(req: HttpServletRequest, resp: HttpServletResponse, filterChain: FilterChain) {
    val ip = req.remoteAddr.orEmpty()
    if (isBlocked(ip)) {
      buildResponse(resp, responseBlocked)
      return
    }
    if (!allowedUrls.matches(req)) {
      buildResponse(resp, responseNotFound, HttpServletResponse.SC_NOT_FOUND)
      return
    }
    val path = req.requestURI.orEmpty()
    if (path != "/index.html") {
      var doBlock = false
      blockList.forEach { pattern ->
        val result = pattern.matchEntire(path)
        if (result != null && result.groups[1] != null) {
          increaseBlockedRequests(result.groups[1]!!.value, ip)
          doBlock = true
        }
      }
      if (doBlock) {
        buildResponse(resp)
        return
      }
    }
    val query = req.queryString.orEmpty()
    val referer = req.getHeader(HttpHeaders.REFERER).orEmpty()
    val userAgent = req.getHeader(HttpHeaders.USER_AGENT).orEmpty()
    val search = "jndi:"
    if (path.contains(search) || query.contains(search) || referer.contains(search) || userAgent.contains(search)) {
      increaseBlockedRequests("jndi", ip)
      buildResponse(resp)
      return
    }
    try {
      filterChain.doFilter(req, resp)
    } catch (th: Throwable) {
      when (th) {
        is ClientAbortException -> {
          log.debug { buildLog(req, th.message.orEmpty()) }
          buildResponse(resp)
        }
        is RequestRejectedException -> {
          if (Regex("^The request was rejected because the HTTP method \".+?\" was not included within the list of allowed HTTP methods.+?$").matches(
              th.message.orEmpty()
            )
          ) {
            increaseBlockedRequests("malicious HTTP Method test", ip)
          }
          log.debug { buildLog(req, th.message.orEmpty()) }
          buildResponse(resp)
        }
        is NestedServletException -> {
          if (th.cause is RequestRejectedException || th.cause is BeanInstantiationException) {
            log.debug { buildLog(req, th.cause!!.message.orEmpty()) }
            buildResponse(resp)
          } else {
            log.info { buildLog(req, th.message.orEmpty()) }
            buildResponse(resp, responseInternalServerError, HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
          }
        }
        is IOException -> {
          val reason = th.message.orEmpty()
          if (reason.contains("Broken Pipe") || reason.contains("Connection reset by peer")) {
            log.debug { buildLog(req, reason) }
            buildResponse(resp)
          } else {
            log.info { buildLog(req, reason) }
            log.error(th) { "unhandled IOException" }
            buildResponse(resp, responseInternalServerError, HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
          }
        }
        else -> {
          log.warn { buildLog(req, th.message.orEmpty()) }
          log.error(th) { "unexpected error in filter chain" }
          val accept = req.getHeader(HttpHeaders.ACCEPT).orEmpty()
          try {
            if (accept.contains(MediaType.APPLICATION_JSON_VALUE)) {
              buildResponse(
                resp,
                objectMapper.writeValueAsBytes(
                  mapOf(
                    "message" to "Internal Server Error",
                    "status" to HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                  )
                ),
                HttpServletResponse.SC_INTERNAL_SERVER_ERROR
              )
            } else {
              resp.sendRedirect("/fehler")
            }
          } catch (_: Throwable) {
          }
        }
      }
    }
  }
}
