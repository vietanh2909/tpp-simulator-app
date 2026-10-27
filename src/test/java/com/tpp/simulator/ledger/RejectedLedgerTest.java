package com.tpp.simulator.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.tpp.simulator.config.MaskedHeaders;
import com.tpp.simulator.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** BR15.2 đến BR15.5, BR19.6: ghi lần nhận bị từ chối và đọc chi tiết một lần nhận. */
class RejectedLedgerTest extends AbstractIntegrationTest {

  private static final Instant T = Instant.parse("2026-10-19T03:00:00.123456Z");

  @Autowired private CallbackLedger ledger;
  @Autowired private PlatformTransactionManager txManager;

  private RejectedCallback rejected(long versionId, String payloadJson, String rawText) {
    return new RejectedCallback(
        "REJ",
        versionId,
        T,
        "INVALID_API_KEY",
        401,
        new MaskedHeaders(
            Map.of("X-Api-Key", List.of("wrong"), "X-Request-Id", List.of("r-1")), "X-Api-Key"),
        payloadJson,
        rawText,
        "{\"error\":\"UNAUTHORIZED\",\"message\":\"m\"}");
  }

  private long append(RejectedCallback callback) {
    return new TransactionTemplate(txManager).execute(s -> ledger.appendRejected(callback));
  }

  @Test
  void aRejectedReceiptHasTheFixedShapeAndNeverTouchesTransactions() throws Exception {
    long version = insertServiceRow("REJ");

    long id = append(rejected(version, "{\"transId\":\"X1\"}", null));

    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM callbacks WHERE record_id = ?", id);
    assertEquals("REJECTED", row.get("outcome"));
    assertEquals("INVALID_API_KEY", row.get("rejection_reason"));
    assertEquals(401, row.get("http_status_returned"));
    assertEquals(401, row.get("response_status_returned"));
    assertNull(row.get("transaction_id"));
    assertNull(row.get("status"));
    assertNull(row.get("business_timestamp_utc"));
    assertNull(row.get("payload_raw_text"));
    assertEquals(version, ((Number) row.get("config_version_id")).longValue());
    assertEquals(
        "{}",
        jdbc.queryForObject("SELECT mapping_field_results::text FROM callbacks", String.class));
    JsonNode headers =
        json.readTree(
            jdbc.queryForObject(
                "SELECT request_headers_masked::text FROM callbacks", String.class));
    assertEquals("***", headers.get("X-Api-Key").asText());
    assertEquals("r-1", headers.get("X-Request-Id").asText());
    assertEquals(0, count("transactions"));
  }

  @Test
  void aJsonNullPayloadIsDifferentFromAnAbsentOne() {
    long version = insertServiceRow("REJ");

    append(rejected(version, "null", null));
    append(rejected(version, null, "not json {"));

    assertEquals(
        List.of("null", ""),
        jdbc.query(
            "SELECT payload_json::text AS p, payload_json IS NULL AS missing FROM callbacks ORDER BY"
                + " record_id",
            (rs, i) -> rs.getBoolean("missing") ? "" : rs.getString("p")));
    assertEquals(
        List.of(false, true),
        jdbc.query(
            "SELECT payload_raw_text IS NOT NULL AS has_raw FROM callbacks ORDER BY record_id",
            (rs, i) -> rs.getBoolean("has_raw")));
  }

  @Test
  void anEmptyRawTextIsStoredAsAnEmptyStringNotNull() {
    long version = insertServiceRow("REJ");

    long id = append(rejected(version, null, ""));

    assertEquals(
        "",
        jdbc.queryForObject(
            "SELECT payload_raw_text FROM callbacks WHERE record_id = ?", String.class, id));
  }

  @Test
  void theReceiptMustRunInsideATransactionOfTheCaller() {
    long version = insertServiceRow("REJ");

    assertThrows(
        IllegalTransactionStateException.class,
        () -> ledger.appendRejected(rejected(version, "{}", null)));
  }

  @Test
  void aFailedWriteRollsBackOnlyItsOwnTransaction() {
    long version = insertServiceRow("REJ");
    append(rejected(version, "{}", null));

    assertThrows(DataAccessException.class, () -> append(rejected(version + 999, "{}", null)));

    assertEquals(1, count("callbacks"));
  }

  @Test
  void detailOfARejectedReceiptCarriesVersionNumberAndExactlyOnePayloadForm() {
    long version = insertServiceRow("REJ");
    long jsonId = append(rejected(version, "{\"a\":1}", null));
    long rawId = append(rejected(version, null, "raw\uFFFDtext"));
    long nullId = append(rejected(version, "null", null));

    CallbackDetail json = ledger.findCallback(jsonId).orElseThrow();
    CallbackDetail raw = ledger.findCallback(rawId).orElseThrow();
    CallbackDetail nullJson = ledger.findCallback(nullId).orElseThrow();

    assertEquals(1, json.configVersionNumber());
    assertEquals("{\"a\": 1}", json.payloadJson());
    assertNull(json.payloadRawText());
    assertNull(raw.payloadJson());
    assertEquals("raw\uFFFDtext", raw.payloadRawText());
    assertEquals("null", nullJson.payloadJson());
    assertNull(nullJson.payloadRawText());
    assertEquals("{}", json.mappingFieldResults());
    assertEquals(401, json.responseStatusReturned());
    assertTrue(json.responseBodyReturned().contains("UNAUTHORIZED"));
    assertFalse(json.serviceDeleted());
    assertEquals("REJ", json.serviceCode());
  }

  @Test
  void findCallbackIsEmptyForUnknownIds() {
    assertTrue(ledger.findCallback(1).isEmpty());
    assertTrue(ledger.findCallback(0).isEmpty());
    assertTrue(ledger.findCallback(-5).isEmpty());
    assertTrue(ledger.findCallback(Long.MAX_VALUE).isEmpty());
  }
}
