package com.tpp.simulator.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MaskedHeadersTest {

  @Test
  void masksTheThreeSensitiveHeadersCaseInsensitively() {
    Map<String, List<String>> raw = new LinkedHashMap<>();
    raw.put("Authorization", List.of("Bearer secret-token"));
    raw.put("proxy-authorization", List.of("Basic abc"));
    raw.put("COOKIE", List.of("session=xyz"));
    raw.put("Content-Type", List.of("application/json"));

    Map<String, String> masked = new MaskedHeaders(raw).asMap();

    assertEquals("***", masked.get("Authorization"));
    assertEquals("***", masked.get("proxy-authorization"));
    assertEquals("***", masked.get("COOKIE"));
    assertEquals("application/json", masked.get("Content-Type"));
  }

  @Test
  void joinsMultipleValuesOfTheSameHeaderName() {
    Map<String, String> masked =
        new MaskedHeaders(Map.of("Accept", List.of("text/plain", "application/json"))).asMap();

    assertEquals("text/plain, application/json", masked.get("Accept"));
  }

  @Test
  void masksSensitiveHeaderEvenWhenItHasSeveralValues() {
    Map<String, String> masked = new MaskedHeaders(Map.of("Cookie", List.of("a=1", "b=2"))).asMap();

    assertEquals("***", masked.get("Cookie"));
  }

  @Test
  void keepsHeaderNamesAsReceivedAndIgnoresNullNames() {
    Map<String, List<String>> raw = new LinkedHashMap<>();
    raw.put("X-Request-Id", List.of("abc"));
    raw.put(null, List.of("ignored"));
    raw.put("X-Empty", null);

    Map<String, String> masked = new MaskedHeaders(raw).asMap();

    assertEquals(2, masked.size());
    assertEquals("abc", masked.get("X-Request-Id"));
    assertEquals("", masked.get("X-Empty"));
  }

  @Test
  void masksTheServiceApiKeyHeaderIgnoringCase() {
    Map<String, List<String>> raw = new LinkedHashMap<>();
    raw.put("x-api-key", List.of("k1"));
    raw.put("X-Request-Id", List.of("abc"));
    raw.put("Authorization", List.of("Bearer t"));

    Map<String, String> masked = new MaskedHeaders(raw, "X-Api-Key").asMap();

    assertEquals("***", masked.get("x-api-key"));
    assertEquals("abc", masked.get("X-Request-Id"));
    assertEquals("***", masked.get("Authorization"));
  }

  @Test
  void aRepeatedApiKeyHeaderBecomesASingleMask() {
    Map<String, String> masked =
        new MaskedHeaders(Map.of("X-Api-Key", List.of("good", "bad")), "x-api-key").asMap();

    assertEquals("***", masked.get("X-Api-Key"));
  }

  @Test
  void aNoneServiceDoesNotMaskACustomHeader() {
    Map<String, String> masked =
        new MaskedHeaders(Map.of("X-Api-Key", List.of("visible")), null).asMap();

    assertEquals("visible", masked.get("X-Api-Key"));
  }

  @Test
  void resultIsReadOnly() {
    Map<String, String> masked = new MaskedHeaders(Map.of("A", List.of("1"))).asMap();

    assertThrows(UnsupportedOperationException.class, () -> masked.put("B", "2"));
  }
}
