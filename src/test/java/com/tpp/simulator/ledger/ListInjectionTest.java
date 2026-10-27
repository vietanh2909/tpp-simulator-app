package com.tpp.simulator.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tpp.simulator.support.AbstractIntegrationTest;
import com.tpp.simulator.support.Fixtures;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** NFR3.27, SD-06: giá trị bộ lọc luôn là tham số có tên; ký tự đặc biệt là ký tự thường. */
class ListInjectionTest extends AbstractIntegrationTest {

  private static final Instant T = Instant.parse("2026-10-19T11:00:00Z");

  @BeforeEach
  void data() {
    long version = insertServiceRow("INJ");
    String[] ids = {"A%B", "AxB", "A_B", "A\\B", "plain", "it's", "semi;colon", "dash--x"};
    for (int i = 0; i < ids.length; i++) {
      Fixtures.successWithTransaction(jdbc, "INJ", version, T.plusSeconds(i), ids[i], "OK");
    }
  }

  private List<String> match(String path, String param, String value) {
    HttpResponse<String> response = get(path + "?" + param + "=" + enc(value));
    assertEquals(200, response.statusCode(), value + " -> " + response.body());
    List<String> found = new ArrayList<>();
    jsonBody(response).get("items").forEach(i -> found.add(i.get("transactionId").asText()));
    return found;
  }

  @Test
  void percentUnderscoreAndBackslashAreLiteralCharacters() {
    assertEquals(List.of("A%B"), match("/api/transactions", "transactionId", "%"));
    assertEquals(List.of("A_B"), match("/api/transactions", "transactionId", "_"));
    assertEquals(List.of("A\\B"), match("/api/transactions", "transactionId", "\\"));
    assertEquals(List.of("A%B"), match("/api/callbacks", "transactionId", "a%"));
    assertEquals(List.of("A_B"), match("/api/callbacks", "transactionId", "a_"));
  }

  @Test
  void quotesSemicolonsAndCommentMarkersAreMatchedAsText() {
    assertEquals(List.of("it's"), match("/api/transactions", "transactionId", "'"));
    assertEquals(List.of("semi;colon"), match("/api/transactions", "transactionId", ";"));
    assertEquals(List.of("dash--x"), match("/api/transactions", "transactionId", "--"));
  }

  @Test
  void classicInjectionStringsReturnNothingAndDamageNothing() {
    for (String attack :
        List.of(
            "' OR '1'='1",
            "'; DROP TABLE callbacks; --",
            "\" OR \"\"=\"",
            "1; DELETE FROM transactions",
            "') UNION SELECT NULL --")) {
      assertEquals(List.of(), match("/api/transactions", "transactionId", attack), attack);
      assertEquals(List.of(), match("/api/transactions", "status", attack), attack);
      assertEquals(List.of(), match("/api/callbacks", "transactionId", attack), attack);
      assertEquals(List.of(), match("/api/callbacks", "serviceCode", attack), attack);
    }

    assertEquals(8, count("transactions"));
    assertEquals(8, count("callbacks"));
  }

  @Test
  void enumFiltersRejectInjectionAttemptsWith400() {
    for (String param : List.of("outcome", "rejectionReason")) {
      HttpResponse<String> response =
          get("/api/callbacks?" + param + "=" + enc("SUCCESS' OR '1'='1"));
      assertEquals(400, response.statusCode());
    }
    assertEquals(8, count("callbacks"));
  }
}
