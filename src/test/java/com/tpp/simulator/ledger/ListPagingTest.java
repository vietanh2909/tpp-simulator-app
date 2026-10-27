package com.tpp.simulator.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.tpp.simulator.support.AbstractIntegrationTest;
import com.tpp.simulator.support.Fixtures;
import java.net.http.HttpResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** BR19.3, BR19.4, PD-04: phân trang phía server, trang vượt cuối, số 0 đứng đầu. */
class ListPagingTest extends AbstractIntegrationTest {

  private static final Instant T = Instant.parse("2026-10-19T11:00:00Z");

  private void transactions(int n) {
    long version = insertServiceRow("PAGED");
    for (int i = 0; i < n; i++) {
      Fixtures.successWithTransaction(jdbc, "PAGED", version, T.plusSeconds(i), "T" + i, "OK");
    }
  }

  private JsonNode page(String query) {
    HttpResponse<String> response = get("/api/transactions" + query);
    assertEquals(200, response.statusCode(), response.body());
    return jsonBody(response);
  }

  private void assertRejected(String query, String field) {
    for (String path : new String[] {"/api/transactions", "/api/callbacks"}) {
      HttpResponse<String> response = get(path + query);
      assertEquals(400, response.statusCode(), path + query + " " + response.body());
      assertTrue(response.body().contains("\"field\":\"" + field + "\""), response.body());
    }
  }

  @Test
  void twentyFiveTransactionsSplitIntoTwentyAndFive() {
    transactions(25);

    JsonNode first = page("");
    JsonNode second = page("?page=2");

    assertEquals(20, first.get("items").size());
    assertEquals(5, second.get("items").size());
    assertEquals(25, first.get("totalItems").asInt());
    assertEquals(2, first.get("totalPages").asInt());
    assertEquals(1, first.get("page").asInt());
    assertEquals(20, first.get("size").asInt());
    assertEquals("T24", first.get("items").get(0).get("transactionId").asText());
    assertEquals("T0", second.get("items").get(4).get("transactionId").asText());
  }

  @Test
  void sizesFiftyAndOneHundredAreAccepted() {
    transactions(120);

    assertEquals(50, page("?size=50").get("items").size());
    assertEquals(3, page("?size=50").get("totalPages").asInt());
    assertEquals(100, page("?size=100").get("items").size());
    assertEquals(20, page("?size=100&page=2").get("items").size());
  }

  @Test
  void aPageBeyondTheLastIsEmptyWithCorrectTotals() {
    transactions(25);

    JsonNode beyond = page("?page=9");

    assertEquals(0, beyond.get("items").size());
    assertEquals(25, beyond.get("totalItems").asInt());
    assertEquals(2, beyond.get("totalPages").asInt());
    assertEquals(9, beyond.get("page").asInt());
  }

  @Test
  void anEmptyDatabaseHasZeroPages() {
    for (String path : new String[] {"/api/transactions", "/api/callbacks"}) {
      JsonNode body = jsonBody(get(path));
      assertEquals(0, body.get("items").size());
      assertEquals(0, body.get("totalItems").asInt());
      assertEquals(0, body.get("totalPages").asInt());
    }
  }

  @Test
  void invalidPageAndSizeAreRejectedOnBothLists() {
    for (String bad : new String[] {"0", "-1", "abc", "1.5", " ", "1e3", "+1"}) {
      assertRejected("?page=" + enc(bad), "page");
    }
    for (String bad : new String[] {"0", "19", "30", "101", "-20", "abc", " ", "20.0"}) {
      assertRejected("?size=" + enc(bad), "size");
    }
  }

  @Test
  void pageAndSizeErrorsAreReportedTogether() {
    HttpResponse<String> response = get("/api/callbacks?page=0&size=30");

    assertEquals(400, response.statusCode());
    assertEquals(2, jsonBody(response).get("fieldErrors").size());
  }

  @Test
  void leadingZerosAreIgnoredBeforeTheLengthCheck() {
    transactions(25);

    assertEquals(1, page("?page=0000000000000000001").get("page").asLong());
    assertEquals(20, page("?page=0000000000000000001").get("items").size());
    assertEquals(20, page("?size=000000000000000000020").get("size").asInt());
    assertEquals(2, page("?page=02").get("page").asInt());
    assertRejected("?page=00000000000000000000000", "page");
  }

  @Test
  void aHugePageNumberIsAnEmptyPageNotAnError() {
    transactions(3);

    JsonNode huge = page("?page=99999999999999999999");
    JsonNode paddedHuge = page("?page=0000000000000000000099999999999999999999");

    assertEquals(0, huge.get("items").size());
    assertEquals(Long.MAX_VALUE, huge.get("page").asLong());
    assertEquals(0, paddedHuge.get("items").size());
    assertEquals(3, huge.get("totalItems").asInt());
  }

  @Test
  void callbacksPageTheSameWay() {
    long version = insertServiceRow("PAGED");
    for (int i = 0; i < 25; i++) {
      Fixtures.callback(jdbc, "PAGED", version, T.plusSeconds(i), "SUCCESS", null, "T" + i, "OK");
    }

    JsonNode first = jsonBody(get("/api/callbacks"));
    JsonNode second = jsonBody(get("/api/callbacks?page=2"));

    assertEquals(20, first.get("items").size());
    assertEquals(5, second.get("items").size());
    assertEquals(25, first.get("totalItems").asInt());
    assertEquals(0, jsonBody(get("/api/callbacks?page=3")).get("items").size());
  }
}
