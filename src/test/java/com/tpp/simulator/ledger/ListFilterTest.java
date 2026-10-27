package com.tpp.simulator.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.tpp.simulator.support.AbstractIntegrationTest;
import com.tpp.simulator.support.Fixtures;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** BR19.1 đến BR19.5, PD-05: bộ lọc, thứ tự và ngữ nghĩa thời gian của hai danh sách. */
class ListFilterTest extends AbstractIntegrationTest {

  private static final Instant T = Instant.parse("2026-10-19T11:00:00Z");

  private long versionA;
  private long versionB;

  @BeforeEach
  void services() {
    versionA = insertServiceRow("Alpha_Svc");
    versionB = insertServiceRow("BETA");
  }

  private List<String> ids(HttpResponse<String> response, String field) {
    assertEquals(200, response.statusCode(), response.body());
    List<String> values = new ArrayList<>();
    jsonBody(response).get("items").forEach(i -> values.add(i.get(field).asText()));
    return values;
  }

  private JsonNode failed(HttpResponse<String> response, String... fields) {
    assertEquals(400, response.statusCode(), response.body());
    assertEquals(
        "VALIDATION_FAILED", response.headers().firstValue("X-Simulator-Error").orElse(null));
    JsonNode errors = jsonBody(response).get("fieldErrors");
    List<String> actual = new ArrayList<>();
    errors.forEach(e -> actual.add(e.get("field").asText()));
    for (String field : fields) {
      assertTrue(actual.contains(field), field + " không có trong " + actual);
    }
    assertEquals(fields.length, actual.size(), actual.toString());
    return errors;
  }

  private long cb(
      String code,
      long version,
      Instant at,
      String outcome,
      String reason,
      String txn,
      String status) {
    return Fixtures.callback(jdbc, code, version, at, outcome, reason, txn, status);
  }

  // ---------- thứ tự (BR19.4) ----------

  @Test
  void callbacksAreNewestFirstAndTiesBreakByLargerRecordId() {
    long a = cb("Alpha_Svc", versionA, T.minusSeconds(10), "SUCCESS", null, "T1", "OK");
    long b = cb("Alpha_Svc", versionA, T, "SUCCESS", null, "T2", "OK");
    long c = cb("Alpha_Svc", versionA, T, "SUCCESS", null, "T3", "OK");

    assertEquals(
        List.of(String.valueOf(c), String.valueOf(b), String.valueOf(a)),
        ids(get("/api/callbacks"), "recordId"));
  }

  @Test
  void transactionsAreNewestFirstAndTiesBreakByLargerCallbackId() {
    Fixtures.successWithTransaction(jdbc, "Alpha_Svc", versionA, T.minusSeconds(5), "OLD", "OK");
    Fixtures.successWithTransaction(jdbc, "Alpha_Svc", versionA, T, "TIE1", "OK");
    Fixtures.successWithTransaction(jdbc, "Alpha_Svc", versionA, T, "TIE2", "OK");

    assertEquals(List.of("TIE2", "TIE1", "OLD"), ids(get("/api/transactions"), "transactionId"));
  }

  // ---------- serviceCode ----------

  @Test
  void serviceCodeMatchesTheWholeCodeIgnoringCaseAndIncludesDeletedServices() {
    cb("Alpha_Svc", versionA, T, "SUCCESS", null, "A1", "OK");
    cb("BETA", versionB, T.plusSeconds(1), "SUCCESS", null, "B1", "OK");
    jdbc.update("UPDATE services SET deleted = true WHERE service_code = 'BETA'");

    assertEquals(List.of("A1"), ids(get("/api/callbacks?serviceCode=ALPHA_svc"), "transactionId"));
    assertEquals(List.of("B1"), ids(get("/api/callbacks?serviceCode=beta"), "transactionId"));
    assertEquals(List.of(), ids(get("/api/callbacks?serviceCode=Alpha"), "transactionId"));
    assertEquals(List.of(), ids(get("/api/callbacks?serviceCode=NOPE"), "transactionId"));
    JsonNode item = jsonBody(get("/api/callbacks?serviceCode=beta")).get("items").get(0);
    assertTrue(item.get("serviceDeleted").asBoolean());
    assertEquals("BETA", item.get("serviceCode").asText());
  }

  @Test
  void transactionsFilterByServiceCodeToo() {
    Fixtures.successWithTransaction(jdbc, "Alpha_Svc", versionA, T, "A1", "OK");
    Fixtures.successWithTransaction(jdbc, "BETA", versionB, T.plusSeconds(1), "B1", "OK");

    assertEquals(
        List.of("A1"), ids(get("/api/transactions?serviceCode=alpha_svc"), "transactionId"));
    assertEquals(List.of(), ids(get("/api/transactions?serviceCode=ghost"), "transactionId"));
  }

  // ---------- transactionId, status: chứa chuỗi ----------

  @Test
  void transactionIdAndStatusMatchSubstringsIgnoringCase() {
    Fixtures.successWithTransaction(jdbc, "Alpha_Svc", versionA, T, "ORDER-2026-001", "Success");
    Fixtures.successWithTransaction(
        jdbc, "Alpha_Svc", versionA, T.plusSeconds(1), "other", "FAILED");
    jdbc.update(
        "INSERT INTO transactions (service_code, transaction_id, latest_status, latest_received_at,"
            + " latest_callback_id, latest_has_mapping_error, callback_count)"
            + " SELECT 'Alpha_Svc', 'NOSTATUS', NULL, now(), min(record_id), true, 1 FROM callbacks");

    assertEquals(
        List.of("ORDER-2026-001"),
        ids(get("/api/transactions?transactionId=der-2026"), "transactionId"));
    assertEquals(
        List.of("ORDER-2026-001"), ids(get("/api/transactions?status=SUCC"), "transactionId"));
    assertEquals(List.of("other"), ids(get("/api/transactions?status=fail"), "transactionId"));
    assertEquals(List.of(), ids(get("/api/transactions?status=NOSTATUS"), "transactionId"));
    assertEquals(
        List.of("other"),
        ids(get("/api/transactions?transactionId=OTHER&status=failed"), "transactionId"));
    assertEquals(
        List.of(),
        ids(get("/api/transactions?transactionId=other&status=success"), "transactionId"));
  }

  @Test
  void callbackTransactionIdFilterNeverMatchesCallbacksWithoutAnId() {
    cb("Alpha_Svc", versionA, T, "SUCCESS", null, "TX-1", "OK");
    cb("Alpha_Svc", versionA, T.plusSeconds(1), "MAPPING_ERROR", null, null, null);
    cb("Alpha_Svc", versionA, T.plusSeconds(2), "REJECTED", "INVALID_JSON", null, null);

    assertEquals(List.of("TX-1"), ids(get("/api/callbacks?transactionId=tx"), "transactionId"));
    assertEquals(3, ids(get("/api/callbacks"), "recordId").size());
  }

  // ---------- outcome, rejectionReason ----------

  @Test
  void outcomeAndRejectionReasonFiltersAreExact() {
    cb("Alpha_Svc", versionA, T, "SUCCESS", null, "T1", "OK");
    cb("Alpha_Svc", versionA, T.plusSeconds(1), "MAPPING_ERROR", null, "T2", null);
    long invalidKey =
        cb("Alpha_Svc", versionA, T.plusSeconds(2), "REJECTED", "INVALID_API_KEY", null, null);
    long missingKey =
        cb("Alpha_Svc", versionA, T.plusSeconds(3), "REJECTED", "MISSING_API_KEY", null, null);

    assertEquals(
        List.of(String.valueOf(missingKey), String.valueOf(invalidKey)),
        ids(get("/api/callbacks?outcome=REJECTED"), "recordId"));
    assertEquals(
        List.of(String.valueOf(invalidKey)),
        ids(get("/api/callbacks?outcome=REJECTED&rejectionReason=INVALID_API_KEY"), "recordId"));
    assertEquals(
        List.of(String.valueOf(invalidKey)),
        ids(get("/api/callbacks?rejectionReason=INVALID_API_KEY"), "recordId"));
    assertEquals(List.of("T2"), ids(get("/api/callbacks?outcome=MAPPING_ERROR"), "transactionId"));
    assertEquals(
        List.of(),
        ids(get("/api/callbacks?outcome=SUCCESS&rejectionReason=INVALID_API_KEY"), "recordId"));
    JsonNode rejected =
        jsonBody(get("/api/callbacks?rejectionReason=MISSING_API_KEY")).get("items").get(0);
    assertEquals("REJECTED", rejected.get("outcome").asText());
    assertEquals(401, rejected.get("httpStatusReturned").asInt());
    JsonNode success = jsonBody(get("/api/callbacks?outcome=SUCCESS")).get("items").get(0);
    assertTrue(success.get("rejectionReason").isNull());
  }

  @Test
  void callbacksListContainsAllThreeOutcomesOfDeletedServicesToo() {
    cb("Alpha_Svc", versionA, T, "SUCCESS", null, "T1", "OK");
    cb("Alpha_Svc", versionA, T.plusSeconds(1), "MAPPING_ERROR", null, null, null);
    cb("Alpha_Svc", versionA, T.plusSeconds(2), "REJECTED", "SERVICE_DELETED", null, null);
    jdbc.update("UPDATE services SET deleted = true");

    JsonNode body = jsonBody(get("/api/callbacks"));

    assertEquals(3, body.get("totalItems").asInt());
    body.get("items").forEach(i -> assertTrue(i.get("serviceDeleted").asBoolean()));
  }

  @Test
  void invalidOutcomeAndRejectionReasonReportEveryErrorTogether() {
    failed(
        get("/api/callbacks?outcome=BOGUS&rejectionReason=NOPE&size=30"),
        "outcome",
        "rejectionReason",
        "size");
    failed(get("/api/callbacks?outcome=success"), "outcome");
    failed(get("/api/callbacks?outcome=%20"), "outcome");
  }

  @Test
  void aTextFilterWithAnEncodedNulIs400OnThatFieldNeverAServerError() {
    cb("Alpha_Svc", versionA, T, "SUCCESS", null, "A1", "OK");

    failed(get("/api/callbacks?transactionId=%00"), "transactionId");
    failed(get("/api/callbacks?serviceCode=a%00b"), "serviceCode");
    failed(get("/api/transactions?transactionId=x%00"), "transactionId");
    failed(get("/api/transactions?status=%00ok"), "status");
    failed(get("/api/transactions?serviceCode=%00&status=%00"), "serviceCode", "status");
    // Giá trị hợp lệ vẫn chạy bình thường sau đó.
    assertEquals(List.of("A1"), ids(get("/api/callbacks?transactionId=a1"), "transactionId"));
  }

  @Test
  void transactionDetailWithAnEncodedNulIs404NotAServerError() {
    HttpResponse<String> response =
        get("/api/transactions/detail?serviceCode=Alpha_Svc&transactionId=a%00b");

    assertEquals(404, response.statusCode(), response.body());
    assertEquals(
        "TRANSACTION_NOT_FOUND", response.headers().firstValue("X-Simulator-Error").orElse(null));
    assertEquals(
        404, get("/api/transactions/detail?serviceCode=a%00b&transactionId=T1").statusCode());
  }

  // ---------- thời gian (BR19.1) ----------

  private void boundaryCallbacks() {
    cb(
        "Alpha_Svc",
        versionA,
        Instant.parse("2026-10-19T10:59:59.999999Z"),
        "SUCCESS",
        null,
        "BEFORE",
        "OK");
    cb(
        "Alpha_Svc",
        versionA,
        Instant.parse("2026-10-19T11:00:00.000000Z"),
        "SUCCESS",
        null,
        "START",
        "OK");
    cb(
        "Alpha_Svc",
        versionA,
        Instant.parse("2026-10-19T11:00:00.400000Z"),
        "SUCCESS",
        null,
        "P400",
        "OK");
    cb(
        "Alpha_Svc",
        versionA,
        Instant.parse("2026-10-19T11:00:00.400001Z"),
        "SUCCESS",
        null,
        "P400_1",
        "OK");
    cb(
        "Alpha_Svc",
        versionA,
        Instant.parse("2026-10-19T11:00:01.000000Z"),
        "SUCCESS",
        null,
        "NEXT",
        "OK");
  }

  private List<String> txIds(String query) {
    List<String> list = ids(get("/api/callbacks?" + query), "transactionId");
    List<String> sorted = new ArrayList<>(list);
    java.util.Collections.sort(sorted);
    return sorted;
  }

  @Test
  void receivedToWithoutFractionIncludesTheWholeSecond() {
    boundaryCallbacks();

    assertEquals(
        List.of("BEFORE", "P400", "P400_1", "START"), txIds("receivedTo=2026-10-19T11:00:00Z"));
  }

  @Test
  void receivedToWithAFractionIsComparedAsSentInMicroseconds() {
    boundaryCallbacks();

    assertEquals(List.of("BEFORE", "P400", "START"), txIds("receivedTo=2026-10-19T11:00:00.400Z"));
    assertEquals(
        List.of("BEFORE", "P400", "P400_1", "START"),
        txIds("receivedTo=2026-10-19T11:00:00.400001Z"));
  }

  @Test
  void receivedFromWithoutFractionStartsAtTheBeginningOfTheSecond() {
    boundaryCallbacks();

    assertEquals(
        List.of("NEXT", "P400", "P400_1", "START"), txIds("receivedFrom=2026-10-19T11:00:00Z"));
    assertEquals(
        List.of("NEXT", "P400", "P400_1"), txIds("receivedFrom=2026-10-19T11:00:00.000001Z"));
  }

  @Test
  void sameSecondForBothBoundsIsValidAndSelectsThatWholeSecond() {
    boundaryCallbacks();

    assertEquals(
        List.of("P400", "P400_1", "START"),
        txIds("receivedFrom=2026-10-19T11:00:00Z&receivedTo=2026-10-19T11:00:00Z"));
  }

  @Test
  void aLowerBoundAfterTheUpperBoundIsReportedOnReceivedTo() {
    failed(
        get("/api/callbacks?receivedFrom=2026-10-19T11:00:01Z&receivedTo=2026-10-19T11:00:00Z"),
        "receivedTo");
    failed(
        get(
            "/api/transactions?receivedFrom=2026-10-19T11:00:00.5Z&receivedTo=2026-10-19T11:00:00.4Z"),
        "receivedTo");
  }

  @Test
  void offsetsAreHonouredWhenThePlusSignIsEncodedAndAnUnencodedPlusIsRejected() {
    boundaryCallbacks();

    assertEquals(
        List.of("BEFORE", "P400", "P400_1", "START"),
        txIds("receivedTo=" + enc("2026-10-19T18:00:00+07:00")));
    failed(get("/api/callbacks?receivedTo=2026-10-19T18:00:00+07:00"), "receivedTo");
  }

  @Test
  void transactionsUseTheSameTimeSemanticsOnLatestReceivedAt() {
    Fixtures.successWithTransaction(
        jdbc, "Alpha_Svc", versionA, Instant.parse("2026-10-19T11:00:00.400Z"), "IN", "OK");
    Fixtures.successWithTransaction(
        jdbc, "Alpha_Svc", versionA, Instant.parse("2026-10-19T11:00:01Z"), "OUT", "OK");
    Fixtures.successWithTransaction(
        jdbc, "Alpha_Svc", versionA, Instant.parse("2026-10-19T10:59:59.999999Z"), "EARLY", "OK");

    assertEquals(
        List.of("IN", "EARLY"),
        ids(get("/api/transactions?receivedTo=2026-10-19T11:00:00Z"), "transactionId"));
    assertEquals(
        List.of("OUT", "IN"),
        ids(get("/api/transactions?receivedFrom=2026-10-19T11:00:00Z"), "transactionId"));
  }

  @Test
  void hugeYearsWithBoundaryOffsetsAreClampedAndNeverA500() {
    cb("Alpha_Svc", versionA, T, "SUCCESS", null, "T1", "OK");
    String hugeTop = enc("+999999999-12-31T23:59:59-18:00");
    String hugeBottom = enc("-999999999-01-01T00:00:00+18:00");

    assertEquals(1, ids(get("/api/callbacks?receivedTo=" + hugeTop), "recordId").size());
    assertEquals(0, ids(get("/api/callbacks?receivedFrom=" + hugeTop), "recordId").size());
    assertEquals(1, ids(get("/api/callbacks?receivedFrom=" + hugeBottom), "recordId").size());
    failed(
        get("/api/callbacks?receivedFrom=" + hugeTop + "&receivedTo=2026-10-19T00:00:00Z"),
        "receivedTo");
    failed(get("/api/callbacks?receivedFrom=2026-02-30T00:00:00Z"), "receivedFrom");
    assertEquals(0, ids(get("/api/transactions?receivedTo=" + hugeTop), "transactionId").size());
    assertEquals(
        0, ids(get("/api/transactions?receivedFrom=" + hugeBottom), "transactionId").size());
  }

  @Test
  void malformedTimestampsAreRejectedOnTheRightField() {
    failed(
        get("/api/callbacks?receivedFrom=abc&receivedTo=2026-10-19"), "receivedFrom", "receivedTo");
    failed(get("/api/transactions?receivedFrom=2026-10-19T10:00:00"), "receivedFrom");
    failed(get("/api/transactions?receivedTo=%20"), "receivedTo");
    failed(get("/api/callbacks?receivedTo=" + enc("2026-10-19T10:00:00+99:00")), "receivedTo");
  }

  // ---------- giá trị rỗng, lặp, lạ ----------

  @Test
  void emptyValuesAreTreatedAsAbsent() {
    cb("Alpha_Svc", versionA, T, "SUCCESS", null, "T1", "OK");
    cb("BETA", versionB, T.plusSeconds(1), "REJECTED", "INVALID_JSON", null, null);

    HttpResponse<String> all = get("/api/callbacks");
    HttpResponse<String> empty =
        get(
            "/api/callbacks?serviceCode=&transactionId=&outcome=&rejectionReason=&receivedFrom=&receivedTo=&page=&size=");

    assertEquals(200, empty.statusCode(), empty.body());
    assertEquals(all.body(), empty.body());
    assertEquals(
        jsonBody(get("/api/transactions")).toString(),
        jsonBody(
                get(
                    "/api/transactions?serviceCode=&transactionId=&status=&receivedFrom=&receivedTo=&page=&size="))
            .toString());
  }

  @Test
  void repeatedParametersAreRejectedAndUnknownOnesAreIgnored() {
    cb("Alpha_Svc", versionA, T, "SUCCESS", null, "T1", "OK");

    failed(get("/api/callbacks?outcome=SUCCESS&outcome=REJECTED"), "outcome");
    failed(get("/api/callbacks?page=1&page=2&size=20&size=50"), "page", "size");
    assertEquals(200, get("/api/callbacks?sort=asc&foo=bar").statusCode());
    assertEquals(200, get("/api/transactions?outcome=BOGUS").statusCode());
  }

  @Test
  void filtersCombineWithAnd() {
    cb("Alpha_Svc", versionA, T, "SUCCESS", null, "X1", "OK");
    cb("Alpha_Svc", versionA, T.plusSeconds(1), "REJECTED", "INVALID_JSON", null, null);
    cb("BETA", versionB, T.plusSeconds(2), "SUCCESS", null, "X2", "OK");

    assertEquals(
        List.of("X1"),
        ids(
            get("/api/callbacks?serviceCode=alpha_svc&outcome=SUCCESS&transactionId=x"),
            "transactionId"));
    assertEquals(
        List.of(), ids(get("/api/callbacks?serviceCode=beta&outcome=REJECTED"), "transactionId"));
  }

  @Test
  void listItemsHaveTheContractShape() {
    long id = cb("Alpha_Svc", versionA, T, "SUCCESS", null, "T1", "OK");

    JsonNode item = jsonBody(get("/api/callbacks")).get("items").get(0);

    assertEquals(id, item.get("recordId").asLong());
    assertEquals("Alpha_Svc", item.get("serviceCode").asText());
    assertEquals(false, item.get("serviceDeleted").asBoolean());
    assertEquals("2026-10-19T11:00:00Z", item.get("receivedAt").asText());
    assertEquals("SUCCESS", item.get("outcome").asText());
    assertEquals(200, item.get("httpStatusReturned").asInt());
    assertEquals("T1", item.get("transactionId").asText());
    assertEquals("OK", item.get("status").asText());
    assertTrue(item.has("businessTimestamp"));
    assertNull(item.get("payloadJson"));
  }
}
