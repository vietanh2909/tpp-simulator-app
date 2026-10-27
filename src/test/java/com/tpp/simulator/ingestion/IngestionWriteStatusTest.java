package com.tpp.simulator.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tpp.simulator.catalog.ServiceCatalog;
import com.tpp.simulator.catalog.ServiceRuntimeSnapshot;
import com.tpp.simulator.config.ErrorCode;
import com.tpp.simulator.ingestion.IngestionResult.Kind;
import com.tpp.simulator.mapping.MappingOutcome;
import com.tpp.simulator.support.AbstractIntegrationTest;
import com.tpp.simulator.support.FailureTrigger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * RD-02, LC-03: {@code CallbackIngestionService} là nơi DUY NHẤT bắt lỗi kho và luôn trả một {@code
 * WriteStatus} tường minh, ở cả hai lối vào ({@code receive} và {@code receiveForLoad}).
 */
class IngestionWriteStatusTest extends AbstractIntegrationTest {

  private static final Instant T = Instant.parse("2026-10-19T03:00:00Z");
  private static final String KEY = "good-key";

  @Autowired private CallbackIngestionService ingestion;
  @Autowired private ServiceCatalog catalog;

  @BeforeEach
  void services() {
    createServiceFromJson(
        serviceJson("KEYED")
            .replace("\"authType\":\"NONE\"", "\"authType\":\"API_KEY\"")
            .replace(
                "{\"serviceCode\"",
                "{\"apiKeyHeaderName\":\"X-Api-Key\",\"apiKeySecret\":\""
                    + KEY
                    + "\",\"serviceCode\""));
  }

  private static byte[] body(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static Map<String, List<String>> key(String value) {
    return Map.of("X-Api-Key", List.of(value));
  }

  private IngestionResult http(String contentType, Map<String, List<String>> headers, String body) {
    return ingestion.receive("KEYED", contentType, headers, body(body), T);
  }

  private IngestionResult load(String contentType, Map<String, List<String>> headers, String body) {
    ServiceRuntimeSnapshot snapshot = catalog.findSnapshot("KEYED").orElseThrow();
    return ingestion.receiveForLoad(snapshot, contentType, headers, body(body), T);
  }

  private interface Entry {
    IngestionResult call(String contentType, Map<String, List<String>> headers, String body);
  }

  private List<Entry> entries() {
    return List.of(this::http, this::load);
  }

  @Test
  void bothEntriesReportWrittenForAcceptedAndRejectedReceipts() {
    for (Entry entry : entries()) {
      IngestionResult accepted = entry.call(JSON, key(KEY), payload("T", "00"));
      IngestionResult rejected = entry.call(JSON, key("bad"), payload("T", "00"));

      assertEquals(Kind.ACCEPTED, accepted.kind());
      assertEquals(WriteStatus.WRITTEN, accepted.writeStatus());
      assertNotNull(accepted.recordId());
      assertEquals(MappingOutcome.SUCCESS, accepted.outcome());
      assertEquals(201, accepted.response().status());
      assertEquals(Kind.REJECTED, rejected.kind());
      assertEquals(WriteStatus.WRITTEN, rejected.writeStatus());
      assertEquals(RejectionReason.INVALID_API_KEY, rejected.rejectionReason());
      assertEquals(ErrorCode.UNAUTHORIZED, rejected.errorCode());
      assertNull(rejected.failureClass());
    }
    assertEquals(4, count("callbacks"));
  }

  @Test
  void aWriteFailureOfAnAcceptedCallbackIsAnExplicitStatusNotAnException() {
    for (Entry entry : entries()) {
      try (FailureTrigger trigger = FailureTrigger.on("callbacks", "INSERT", "KEYED", "SUCCESS")) {
        IngestionResult result = entry.call(JSON, key(KEY), payload("T", "00"));

        assertEquals(Kind.ACCEPTED, result.kind());
        assertEquals(WriteStatus.ACCEPTED_WRITE_FAILED, result.writeStatus());
        assertNull(result.recordId());
        assertNull(result.response());
        assertEquals(ErrorCode.INTERNAL_ERROR, result.errorCode());
        assertNotNull(result.failureClass());
        assertFalse(result.written());
        assertEquals("P0001", result.sqlState());
        assertFalse(result.toString().contains("test failure"), result.toString());
      }
    }
    assertEquals(0, count("callbacks"));
    assertEquals(0, count("transactions"));
  }

  @Test
  void aWriteFailureOfARejectedReceiptStillCarriesTheOriginalRejectionCode() {
    for (Entry entry : entries()) {
      try (FailureTrigger trigger = FailureTrigger.on("callbacks", "INSERT", "KEYED", "REJECTED")) {
        IngestionResult result = entry.call(JSON, key("bad"), payload("T", "00"));

        assertEquals(Kind.REJECTED, result.kind());
        assertEquals(WriteStatus.REJECTED_RECORD_FAILED, result.writeStatus());
        assertEquals(ErrorCode.UNAUTHORIZED, result.errorCode());
        assertEquals(RejectionReason.INVALID_API_KEY, result.rejectionReason());
        assertNull(result.recordId());
        assertNotNull(result.failureClass());
      }
    }
    assertEquals(0, count("callbacks"));
  }

  @Test
  void afterTheTriggerIsClosedBothEntriesWriteAgain() {
    try (FailureTrigger trigger = FailureTrigger.on("callbacks", "INSERT", "KEYED")) {
      assertEquals(
          WriteStatus.ACCEPTED_WRITE_FAILED,
          http(JSON, key(KEY), payload("T", "00")).writeStatus());
    }

    assertEquals(WriteStatus.WRITTEN, http(JSON, key(KEY), payload("T", "00")).writeStatus());
    assertEquals(WriteStatus.WRITTEN, load(JSON, key(KEY), payload("T", "00")).writeStatus());
    assertEquals(2, count("callbacks"));
  }

  @Test
  void aServiceCodeWithARealNulCharacterIsAnUnknownServiceNotASnapshotReadFailure() {
    // Tomcat chặn %00 ở đường dẫn trước khi tới ứng dụng, nên lớp dịch vụ tự bảo vệ: PostgreSQL
    // không nhận NUL trong tham số văn bản, mã như vậy không bao giờ trùng dịch vụ nào.
    IngestionResult result = ingestion.receive("KEYED\u0000", JSON, Map.of(), body("{}"), T);

    assertEquals(Kind.UNKNOWN_SERVICE, result.kind());
    assertNull(result.writeStatus());
    assertEquals(ErrorCode.SERVICE_NOT_FOUND, result.errorCode());
    assertEquals(0, count("callbacks"));
  }

  @Test
  void anUnknownServiceHasNoWriteStatusAndWritesNothing() {
    IngestionResult result = ingestion.receive("NOPE", JSON, Map.of(), body("{}"), T);

    assertEquals(Kind.UNKNOWN_SERVICE, result.kind());
    assertNull(result.writeStatus());
    assertEquals(ErrorCode.SERVICE_NOT_FOUND, result.errorCode());
    assertEquals(0, count("callbacks"));
  }

  @Test
  void receiveForLoadUsesTheGivenReceivedAtAndTheGivenSnapshotState() {
    ServiceRuntimeSnapshot snapshot = catalog.findSnapshot("KEYED").orElseThrow();
    Instant when = Instant.parse("2026-09-01T00:00:00.5Z");

    IngestionResult disabled =
        ingestion.receiveForLoad(snapshot.withEnabled(false), JSON, key(KEY), body("{}"), when);
    IngestionResult deleted =
        ingestion.receiveForLoad(snapshot.withDeleted(true), JSON, key(KEY), body("{}"), when);

    assertEquals(RejectionReason.SERVICE_DISABLED, disabled.rejectionReason());
    assertEquals(RejectionReason.SERVICE_DELETED, deleted.rejectionReason());
    assertEquals(2, count("callbacks"));
    assertTrue(
        jdbc.queryForObject(
            "SELECT bool_and(received_at = ?::timestamptz) FROM callbacks",
            Boolean.class,
            when.toString()));
    // Bản ghi dịch vụ trong cơ sở dữ liệu vẫn bật.
    assertTrue(jdbc.queryForObject("SELECT enabled AND NOT deleted FROM services", Boolean.class));
  }

  @Test
  void aSnapshotVariantWithApiKeyMakesANoneServiceDemandAKey() {
    createService("OPEN");
    ServiceRuntimeSnapshot none = catalog.findSnapshot("OPEN").orElseThrow();
    ServiceRuntimeSnapshot keyed = none.withApiKey("X-Api-Key", "k");

    assertEquals(
        WriteStatus.WRITTEN,
        ingestion.receiveForLoad(none, JSON, Map.of(), body(payload("A", "00")), T).writeStatus());
    IngestionResult missing =
        ingestion.receiveForLoad(keyed, JSON, Map.of(), body(payload("A", "00")), T);
    assertEquals(RejectionReason.MISSING_API_KEY, missing.rejectionReason());
    assertEquals(
        "NONE",
        jdbc.queryForObject(
            "SELECT auth_type FROM services WHERE service_code = 'OPEN'", String.class));
  }
}
