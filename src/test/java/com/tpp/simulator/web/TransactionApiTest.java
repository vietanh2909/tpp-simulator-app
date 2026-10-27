package com.tpp.simulator.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.tpp.simulator.support.AbstractIntegrationTest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * C4 (phần U1): GET /api/transactions và GET /api/transactions/detail, AC1.14.1, AC1.1.3, AC1.10.x.
 */
class TransactionApiTest extends AbstractIntegrationTest {

  private static final Instant T0 = Instant.parse("2026-10-19T03:00:00Z");

  @BeforeEach
  void service() {
    createService("THU_HO");
  }

  private void receive(
      String serviceCode, String transactionId, String status, int secondsAfterT0) {
    clock.set(T0.plusSeconds(secondsAfterT0));
    HttpResponse<String> response =
        callback(
            serviceCode,
            "{\"transId\":\""
                + transactionId
                + "\",\"code\":\""
                + status
                + "\",\"time\":\"2026-10-19T10:00:00+07:00\"}");
    assertEquals(201, response.statusCode(), response.body());
  }

  private Set<String> fieldNames(HttpResponse<String> response) {
    Set<String> names = new HashSet<>();
    for (JsonNode e : jsonBody(response).get("fieldErrors")) {
      names.add(e.get("field").asText());
    }
    return names;
  }

  // ---------- Danh sách ----------

  @Test
  void ac1141_listShowsTheTransactionRowWithStatusTimestampLastReceivedAndCount() {
    receive("THU_HO", "T1", "00", 0);
    receive("THU_HO", "T1", "01", 5);

    HttpResponse<String> response = get("/api/transactions");

    assertEquals(200, response.statusCode());
    JsonNode page = jsonBody(response);
    assertEquals(1, page.get("page").asInt());
    assertEquals(20, page.get("size").asInt());
    assertEquals(1, page.get("totalItems").asInt());
    assertEquals(1, page.get("totalPages").asInt());
    JsonNode row = page.get("items").get(0);
    assertEquals("THU_HO", row.get("serviceCode").asText());
    assertEquals("T1", row.get("transactionId").asText());
    assertEquals("01", row.get("latestStatus").asText());
    assertEquals("2026-10-19T03:00:00Z", row.get("latestBusinessTimestamp").asText());
    assertEquals("2026-10-19T03:00:05Z", row.get("latestReceivedAt").asText());
    assertEquals(2, row.get("callbackCount").asInt());
    assertFalse(row.get("latestHasMappingError").asBoolean());
    assertFalse(row.get("serviceDeleted").asBoolean());
    assertTrue(row.get("latestCallbackId").asLong() > 0);
  }

  @Test
  void listPaginatesNewestFirstWithDefaultsPageOneSizeTwenty() {
    for (int i = 0; i < 25; i++) {
      receive("THU_HO", "T" + i, "00", i);
    }

    JsonNode first = jsonBody(get("/api/transactions"));
    JsonNode second = jsonBody(get("/api/transactions?page=2&size=20"));
    JsonNode big = jsonBody(get("/api/transactions?size=100"));

    assertEquals(25, first.get("totalItems").asInt());
    assertEquals(2, first.get("totalPages").asInt());
    assertEquals(20, first.get("items").size());
    assertEquals("T24", first.get("items").get(0).get("transactionId").asText());
    assertEquals("T5", first.get("items").get(19).get("transactionId").asText());
    assertEquals(5, second.get("items").size());
    assertEquals("T4", second.get("items").get(0).get("transactionId").asText());
    assertEquals(25, big.get("items").size());
    assertEquals(1, big.get("totalPages").asInt());
    assertEquals(25, jsonBody(get("/api/transactions?size=50")).get("items").size());
  }

  @Test
  void emptyListIsAnEmptyPage() {
    JsonNode page = jsonBody(get("/api/transactions"));

    assertEquals(0, page.get("items").size());
    assertEquals(0, page.get("totalItems").asInt());
    assertEquals(0, page.get("totalPages").asInt());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "page=0",
        "page=-1",
        "page=abc",
        "page=1.5",
        "size=10",
        "size=0",
        "size=abc",
        "size=21",
        "size=-20",
        "size=101"
      })
  void invalidPageOrSizeIs400ValidationFailedNamingTheParameter(String query) {
    HttpResponse<String> response = get("/api/transactions?" + query);

    assertEquals(400, response.statusCode(), response.body());
    assertEquals(
        "VALIDATION_FAILED", response.headers().firstValue("X-Simulator-Error").orElse(null));
    assertTrue(fieldNames(response).contains(query.substring(0, query.indexOf('='))));
  }

  @Test
  void filterParametersAreAcceptedFromU2AndBadOnesAreReportedTogether() {
    HttpResponse<String> ok =
        get(
            "/api/transactions?serviceCode=THU_HO&transactionId=T1&status=00&receivedFrom=2026-10-19T00:00:00Z&receivedTo=2026-10-20T00:00:00Z&page=1");
    HttpResponse<String> bad =
        get("/api/transactions?receivedFrom=abc&receivedTo=2026-10-20T00:00:00Z&page=0&size=30");

    assertEquals(200, ok.statusCode(), ok.body());
    assertEquals(400, bad.statusCode());
    assertEquals(Set.of("receivedFrom", "page", "size"), fieldNames(bad));
  }

  @Test
  void aCallbackWithoutAValidTransactionIdIsNotInTheList() {
    clock.set(T0);
    callback("THU_HO", "{\"code\":\"00\"}");

    assertEquals(0, jsonBody(get("/api/transactions")).get("totalItems").asInt());
  }

  @Test
  void latestMappingErrorShowsEmptyStatusAndTheErrorFlag() {
    receive("THU_HO", "T1", "00", 0);
    clock.set(T0.plusSeconds(3));
    callback("THU_HO", "{\"transId\":\"T1\",\"time\":\"2026-10-19T10:00:00+07:00\"}");

    JsonNode row = jsonBody(get("/api/transactions")).get("items").get(0);

    assertTrue(row.get("latestStatus").isNull());
    assertTrue(row.get("latestHasMappingError").asBoolean());
    assertEquals(2, row.get("callbackCount").asInt());
  }

  // ---------- Chi tiết ----------

  @Test
  void ac113_detailShowsEveryCallbackOldestFirstWithMaskedHeadersMappingResponseAndConfigVersion() {
    receive("THU_HO", "T1", "A", 0);
    clock.set(T0.plusSeconds(1));
    callback(
        "THU_HO",
        payload("T1", "B"),
        "Authorization",
        "Bearer TOP-SECRET",
        "X-Request-Id",
        "req-7");

    HttpResponse<String> response =
        get("/api/transactions/detail?serviceCode=THU_HO&transactionId=T1");

    assertEquals(200, response.statusCode(), response.body());
    assertFalse(response.body().contains("TOP-SECRET"));
    JsonNode body = jsonBody(response);
    assertEquals("B", body.get("transaction").get("latestStatus").asText());
    assertEquals(2, body.get("transaction").get("callbackCount").asInt());
    JsonNode callbacks = body.get("callbacks");
    assertEquals(2, callbacks.size());
    assertEquals("A", callbacks.get(0).get("status").asText());
    assertEquals("B", callbacks.get(1).get("status").asText());
    JsonNode second = callbacks.get(1);
    assertEquals("2026-10-19T03:00:01Z", second.get("receivedAt").asText());
    assertEquals("SUCCESS", second.get("outcome").asText());
    assertEquals(1, second.get("configVersionNumber").asInt());
    assertEquals(201, second.get("responseStatusReturned").asInt());
    assertEquals(201, second.get("httpStatusReturned").asInt());
    assertTrue(second.get("responseBodyReturned").get("ok").asBoolean());
    assertEquals("***", headerIgnoreCase(second.get("requestHeaders"), "Authorization"));
    assertEquals("req-7", headerIgnoreCase(second.get("requestHeaders"), "X-Request-Id"));
    assertEquals("T1", second.get("payloadJson").get("transId").asText());
    assertEquals(
        "T1", second.get("mappingFieldResults").get("transactionId").get("value").asText());
    assertEquals("B", second.get("mappingFieldResults").get("status").get("value").asText());
    assertEquals(
        "2026-10-19T03:00:00Z",
        second.get("mappingFieldResults").get("timestamp").get("value").asText());
    assertEquals("2026-10-19T03:00:00Z", second.get("businessTimestamp").asText());
    assertTrue(second.get("rejectionReason").isNull());
  }

  @Test
  void detailMatchesServiceCodeIgnoringCaseButTransactionIdExactly() {
    receive("THU_HO", "Abc", "00", 0);

    assertEquals(
        200, get("/api/transactions/detail?serviceCode=thu_ho&transactionId=Abc").statusCode());
    HttpResponse<String> wrongCase =
        get("/api/transactions/detail?serviceCode=THU_HO&transactionId=abc");
    assertEquals(404, wrongCase.statusCode());
    assertEquals(
        "TRANSACTION_NOT_FOUND", wrongCase.headers().firstValue("X-Simulator-Error").orElse(null));
    assertEquals(
        404, get("/api/transactions/detail?serviceCode=OTHER&transactionId=Abc").statusCode());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2026/10/001",
        "a?b#c%d e",
        "x y",
        "+plus+",
        "a&b=c",
        "ngày/ký tự",
        "100%",
        "'; DROP TABLE transactions; --",
        "..\\..\\etc"
      })
  void transactionIdsWithSpecialCharactersWorkWhenPercentEncoded(String transactionId) {
    clock.set(T0);
    String body =
        "{\"transId\":"
            + toJsonString(transactionId)
            + ",\"code\":\"00\",\"time\":\"2026-10-19T10:00:00Z\"}";
    assertEquals(201, callback("THU_HO", body).statusCode());

    HttpResponse<String> response =
        get(
            "/api/transactions/detail?serviceCode="
                + enc("THU_HO")
                + "&transactionId="
                + enc(transactionId));

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(
        transactionId, jsonBody(response).get("transaction").get("transactionId").asText());
    assertEquals(1, jsonBody(response).get("callbacks").size());
  }

  private String toJsonString(String value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void transactionIdWithTrailingSpaceIsKeptAndDistinct() {
    clock.set(T0);
    callback("THU_HO", "{\"transId\":\"T1 \",\"code\":\"00\"}");

    assertEquals(
        200,
        get("/api/transactions/detail?serviceCode=THU_HO&transactionId=" + enc("T1 "))
            .statusCode());
    assertEquals(
        404, get("/api/transactions/detail?serviceCode=THU_HO&transactionId=T1").statusCode());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "?serviceCode=THU_HO",
        "?transactionId=T1",
        "?serviceCode=&transactionId=T1",
        "?serviceCode=THU_HO&transactionId="
      })
  void detailWithMissingParametersIs400(String query) {
    HttpResponse<String> response = get("/api/transactions/detail" + query);

    assertEquals(400, response.statusCode(), response.body());
    assertEquals(
        "VALIDATION_FAILED", response.headers().firstValue("X-Simulator-Error").orElse(null));
    assertFalse(fieldNames(response).isEmpty());
  }

  @Test
  void unknownTransactionIs404() {
    HttpResponse<String> response =
        get("/api/transactions/detail?serviceCode=THU_HO&transactionId=NOPE");

    assertEquals(404, response.statusCode());
    assertEquals("TRANSACTION_NOT_FOUND", jsonBody(response).get("error").asText());
  }

  @Test
  void historyIsNotCappedAndKeepsReceiveOrder() {
    for (int i = 0; i < 60; i++) {
      receive("THU_HO", "MANY", "S" + i, i);
    }

    JsonNode body = jsonBody(get("/api/transactions/detail?serviceCode=THU_HO&transactionId=MANY"));

    assertEquals(60, body.get("callbacks").size());
    assertEquals(60, body.get("transaction").get("callbackCount").asInt());
    assertEquals("S0", body.get("callbacks").get(0).get("status").asText());
    assertEquals("S59", body.get("callbacks").get(59).get("status").asText());
  }

  @Test
  void deletedServiceHistoryRemainsReadableAndIsMarkedDeleted() {
    receive("THU_HO", "T1", "00", 0);
    jdbc.update("UPDATE services SET deleted = true WHERE service_code = 'THU_HO'");

    JsonNode body = jsonBody(get("/api/transactions/detail?serviceCode=THU_HO&transactionId=T1"));

    assertTrue(body.get("transaction").get("serviceDeleted").asBoolean());
    assertTrue(body.get("callbacks").get(0).get("serviceDeleted").asBoolean());
    assertTrue(
        jsonBody(get("/api/transactions")).get("items").get(0).get("serviceDeleted").asBoolean());
  }

  @Test
  void noResponseEverContainsAnApiKeySecret() {
    receive("THU_HO", "T1", "00", 0);

    for (String path :
        new String[] {
          "/api/transactions",
          "/api/transactions/detail?serviceCode=THU_HO&transactionId=T1",
          "/api/services/THU_HO"
        }) {
      assertFalse(get(path).body().toLowerCase().contains("apikeysecret"), path);
    }
  }
}
