package com.tpp.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tpp.simulator.support.AbstractIntegrationTest;
import com.tpp.simulator.support.TestClock;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;

/** NFR3.1, NFR3.7, NFR5.3, NFR5.4: cấu hình an toàn mặc định, Actuator chỉ có health, log UTC. */
@ExtendWith(OutputCaptureExtension.class)
class ConfigurationTest extends AbstractIntegrationTest {

  @Autowired private ServerProperties serverProperties;
  @Autowired private ApplicationContext context;

  @Test
  void listensOnLoopbackByDefault() {
    assertNotNull(serverProperties.getAddress());
    assertTrue(
        serverProperties.getAddress().isLoopbackAddress(),
        serverProperties.getAddress().toString());
    assertEquals("127.0.0.1", serverProperties.getAddress().getHostAddress());
  }

  @Test
  void healthIsUpWithoutDetails() {
    HttpResponse<String> response = get("/actuator/health");

    assertEquals(200, response.statusCode());
    assertEquals("UP", jsonBody(response).get("status").asText());
    assertFalse(response.body().contains("components"), response.body());
    assertFalse(response.body().contains("jdbc"), response.body());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "env",
        "beans",
        "heapdump",
        "metrics",
        "mappings",
        "configprops",
        "loggers",
        "threaddump"
      })
  void otherActuatorEndpointsAreNotExposed(String endpoint) {
    HttpResponse<String> response = get("/actuator/" + endpoint);

    assertEquals(404, response.statusCode());
    assertEquals("NOT_FOUND", response.headers().firstValue("X-Simulator-Error").orElse(null));
    assertEquals("NOT_FOUND", jsonBody(response).get("error").asText());
  }

  @Test
  void clockIsAReplaceableBean() {
    assertTrue(context.getBean(Clock.class) instanceof TestClock);
  }

  @Test
  void logLinesCarryAnExplicitUtcTimestampLevelAndLoggerName(CapturedOutput output) {
    createService("LOGFMT");

    Optional<String> line =
        output.getAll().lines().filter(l -> l.contains("Service created")).findFirst();
    assertTrue(line.isPresent(), output.getAll());
    Pattern format =
        Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z INFO\\s+com\\.tpp\\.simulator\\.catalog\\.ServiceCatalog - .*$");
    assertTrue(format.matcher(line.get()).matches(), line.get());
  }
}
