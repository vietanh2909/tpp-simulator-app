package com.tpp.simulator.loadtool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tpp.simulator.catalog.ServiceCreateRequest;
import com.tpp.simulator.catalog.ServiceValidator;
import com.tpp.simulator.loadtool.LoadServiceDefinitions.Kind;
import org.junit.jupiter.api.Test;

/** PD-08: bảng gán cấu hình dịch vụ LOAD_xx cố định theo số thứ tự, chu kỳ 3. */
class LoadServiceDefinitionsTest {

  @Test
  void codesArePaddedToTwoDigitsAndGrowBeyond() {
    assertEquals("LOAD_01", LoadServiceDefinitions.serviceCode(1));
    assertEquals("LOAD_10", LoadServiceDefinitions.serviceCode(10));
    assertEquals("LOAD_100", LoadServiceDefinitions.serviceCode(100));
  }

  @Test
  void theAssignmentCyclesEveryThreeServices() {
    assertEquals(Kind.ISO_NONE, LoadServiceDefinitions.kind(1));
    assertEquals(Kind.PATTERN_API_KEY, LoadServiceDefinitions.kind(2));
    assertEquals(Kind.MILLIS_NONE, LoadServiceDefinitions.kind(3));
    assertEquals(Kind.ISO_NONE, LoadServiceDefinitions.kind(4));
    assertEquals(Kind.PATTERN_API_KEY, LoadServiceDefinitions.kind(8));
    assertEquals(Kind.MILLIS_NONE, LoadServiceDefinitions.kind(9));
    assertFalse(LoadServiceDefinitions.requiresApiKey(1));
    assertTrue(LoadServiceDefinitions.requiresApiKey(2));
    assertFalse(LoadServiceDefinitions.requiresApiKey(3));
  }

  @Test
  void everyDefinitionPassesTheRealServiceValidation() {
    ServiceValidator validator = new ServiceValidator();
    for (int n = 1; n <= 9; n++) {
      ServiceCreateRequest request = LoadServiceDefinitions.request(n);

      ServiceValidator.NewService service = validator.validateCreate(request);

      assertEquals(LoadServiceDefinitions.serviceCode(n), service.serviceCode());
    }
  }

  @Test
  void definitionsMatchTheTable() {
    ServiceCreateRequest iso = LoadServiceDefinitions.request(1);
    ServiceCreateRequest keyed = LoadServiceDefinitions.request(2);
    ServiceCreateRequest millis = LoadServiceDefinitions.request(3);

    assertEquals("NONE", iso.authType());
    assertEquals("ISO_8601", iso.timestampFormat());
    assertNull(iso.apiKeyHeaderName());
    assertEquals("API_KEY", keyed.authType());
    assertEquals("X-Api-Key", keyed.apiKeyHeaderName());
    assertEquals("PATTERN", keyed.timestampFormat());
    assertEquals("yyyy-MM-dd HH:mm:ss", keyed.timestampPattern());
    assertEquals("NONE", millis.authType());
    assertEquals("UNIX_MILLIS", millis.timestampFormat());
    assertEquals(200, millis.responseHttpStatus());
  }
}
