package com.tpp.simulator.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tpp.simulator.catalog.ServiceRuntimeSnapshot;
import com.tpp.simulator.ingestion.ApiKeyVerifier.Result;
import com.tpp.simulator.mapping.TimestampFormat;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** W17, BR14.4, SD-02: ma trận so khớp khóa API. */
class ApiKeyVerifierTest {

  private final ApiKeyVerifier verifier = new ApiKeyVerifier();

  private static ServiceRuntimeSnapshot service(String header, String secret) {
    return new ServiceRuntimeSnapshot(
        "CHI_HO",
        true,
        false,
        "API_KEY",
        header,
        secret,
        1,
        1,
        "$.a",
        "$.b",
        null,
        TimestampFormat.ISO_8601,
        null,
        ZoneId.of("UTC"),
        200,
        "{}");
  }

  private static Map<String, List<String>> headers(Object... pairs) {
    Map<String, List<String>> map = new LinkedHashMap<>();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      map.put((String) pairs[i], (List<String>) pairs[i + 1]);
    }
    return map;
  }

  @Test
  void theExactValueIsValidAndHeaderNameCaseDoesNotMatter() {
    ServiceRuntimeSnapshot svc = service("X-Api-Key", "k1");

    assertEquals(Result.VALID, verifier.verify(svc, headers("X-Api-Key", List.of("k1"))));
    assertEquals(Result.VALID, verifier.verify(svc, headers("x-api-key", List.of("k1"))));
    assertEquals(Result.VALID, verifier.verify(svc, headers("X-API-KEY", List.of("k1"))));
  }

  @Test
  void theValueIsCaseSensitiveAndExact() {
    ServiceRuntimeSnapshot svc = service("X-Api-Key", "k1");

    assertEquals(Result.INVALID, verifier.verify(svc, headers("X-Api-Key", List.of("K1"))));
    assertEquals(Result.INVALID, verifier.verify(svc, headers("X-Api-Key", List.of("k"))));
    assertEquals(Result.INVALID, verifier.verify(svc, headers("X-Api-Key", List.of("k1 "))));
    assertEquals(Result.INVALID, verifier.verify(svc, headers("X-Api-Key", List.of("k12"))));
    assertEquals(Result.INVALID, verifier.verify(svc, headers("X-Api-Key", List.of("sai"))));
  }

  @Test
  void aMissingOrEmptyHeaderIsMissing() {
    ServiceRuntimeSnapshot svc = service("X-Api-Key", "k1");

    assertEquals(Result.MISSING, verifier.verify(svc, headers()));
    assertEquals(Result.MISSING, verifier.verify(svc, headers("X-Other", List.of("k1"))));
    assertEquals(Result.MISSING, verifier.verify(svc, headers("X-Api-Key", List.of(""))));
    assertEquals(Result.MISSING, verifier.verify(svc, headers("X-Api-Key", List.of("   "))));
    assertEquals(Result.MISSING, verifier.verify(svc, headers("X-Api-Key", List.of())));
    assertEquals(Result.MISSING, verifier.verify(svc, headers("X-Api-Key", null)));
  }

  @Test
  void aRepeatedHeaderIsInvalidEvenWhenOneValueIsRight() {
    ServiceRuntimeSnapshot svc = service("X-Api-Key", "k1");

    assertEquals(Result.INVALID, verifier.verify(svc, headers("X-Api-Key", List.of("k1", "bad"))));
    assertEquals(Result.INVALID, verifier.verify(svc, headers("X-Api-Key", List.of("k1", "k1"))));
    assertEquals(
        Result.INVALID,
        verifier.verify(svc, headers("X-Api-Key", List.of("k1"), "x-api-key", List.of("k1"))));
  }

  @Test
  void aKeyWithInnerSpacesAndNonLetterCharactersMatchesExactly() {
    ServiceRuntimeSnapshot svc = service("Authorization", "Bearer abc-123_~");

    assertEquals(
        Result.VALID, verifier.verify(svc, headers("Authorization", List.of("Bearer abc-123_~"))));
    assertEquals(
        Result.INVALID,
        verifier.verify(svc, headers("Authorization", List.of("bearer abc-123_~"))));
  }

  @Test
  void aNullHeaderNameInTheRequestIsSkipped() {
    ServiceRuntimeSnapshot svc = service("X-Api-Key", "k1");

    assertEquals(Result.MISSING, verifier.verify(svc, headers(null, List.of("k1"))));
  }
}
