package com.tpp.simulator.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tpp.simulator.config.MaskedHeaders;
import com.tpp.simulator.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CallbackLedger trên PostgreSQL thật: ghi nguyên tử Callback và Transaction (BR4.x), AC1.10.1 đến
 * AC1.10.5, thứ tự "gần nhất", đọc danh sách và chi tiết (BR5.x), che header trong dòng đã lưu
 * (NFR3.3).
 */
class CallbackLedgerTest extends AbstractIntegrationTest {

  static final Instant T0 = Instant.parse("2026-10-19T03:00:00Z");

  @Autowired CallbackLedger ledger;
  @Autowired PlatformTransactionManager txManager;

  long versionId;

  @BeforeEach
  void service() {
    versionId = insertServiceRow("THU_HO");
  }

  static MaskedHeaders headers() {
    return new MaskedHeaders(Map.of("Content-Type", List.of("application/json")));
  }

  NewCallback callback(
      String service, Instant receivedAt, String transactionId, String status, boolean error) {
    return new NewCallback(
        service,
        service.equals("THU_HO") ? versionId : otherVersionId(service),
        receivedAt,
        error ? "MAPPING_ERROR" : "SUCCESS",
        headers(),
        "{}",
        transactionId,
        status,
        null,
        "{\"transactionId\":{},\"status\":{},\"timestamp\":{}}",
        201,
        "{\"ok\":true}");
  }

  long otherVersionId(String service) {
    return jdbc.queryForObject(
        "SELECT version_id FROM service_config_versions WHERE service_code = ?",
        Long.class,
        service);
  }

  long append(NewCallback callback) {
    return new TransactionTemplate(txManager).execute(s -> ledger.append(callback));
  }

  Map<String, Object> transaction(String service, String transactionId) {
    return jdbc.queryForMap(
        "SELECT * FROM transactions WHERE service_code = ? AND transaction_id = ?",
        service,
        transactionId);
  }

  @Test
  void ac1101_threeCallbacksGiveLatestStatusAndCountThree() {
    append(callback("THU_HO", T0, "T1", "A", false));
    append(callback("THU_HO", T0.plusSeconds(1), "T1", "B", false));
    long third = append(callback("THU_HO", T0.plusSeconds(2), "T1", "C", false));

    Map<String, Object> t = transaction("THU_HO", "T1");
    assertEquals("C", t.get("latest_status"));
    assertEquals(3, t.get("callback_count"));
    assertEquals(third, t.get("latest_callback_id"));
    assertEquals(3, count("callbacks"));
    assertEquals(1, count("transactions"));
  }

  @Test
  void ac1102_sameTransactionIdInTwoServicesAreTwoTransactions() {
    insertServiceRow("CHI_HO");

    append(callback("THU_HO", T0, "T1", "A", false));
    append(callback("CHI_HO", T0.plusSeconds(1), "T1", "B", false));

    assertEquals(2, count("transactions"));
    assertEquals("A", transaction("THU_HO", "T1").get("latest_status"));
    assertEquals("B", transaction("CHI_HO", "T1").get("latest_status"));
  }

  @Test
  void ac1103_latestIsDecidedByReceivedAtNotByBusinessTimestamp() {
    NewCallback first =
        new NewCallback(
            "THU_HO",
            versionId,
            T0,
            "SUCCESS",
            headers(),
            "{}",
            "T1",
            "A",
            Instant.parse("2026-10-19T09:00:00Z"),
            "{}",
            201,
            "{}");
    NewCallback second =
        new NewCallback(
            "THU_HO",
            versionId,
            T0.plusSeconds(5),
            "SUCCESS",
            headers(),
            "{}",
            "T1",
            "B",
            Instant.parse("2026-10-19T01:00:00Z"),
            "{}",
            201,
            "{}");
    append(first);
    append(second);

    Map<String, Object> t = transaction("THU_HO", "T1");
    assertEquals("B", t.get("latest_status"));
    assertEquals(
        Instant.parse("2026-10-19T01:00:00Z"),
        ((java.sql.Timestamp) t.get("latest_business_ts")).toInstant());
  }

  @Test
  void ac1104_latestCallbackMissingStatusShowsEmptyStatusWithErrorNotTheOldOne() {
    append(callback("THU_HO", T0, "T1", "A", false));
    append(callback("THU_HO", T0.plusSeconds(1), "T1", "B", false));
    append(callback("THU_HO", T0.plusSeconds(2), "T1", "C", false));
    append(callback("THU_HO", T0.plusSeconds(3), "T1", null, true));

    Map<String, Object> t = transaction("THU_HO", "T1");
    assertNull(t.get("latest_status"));
    assertEquals(true, t.get("latest_has_mapping_error"));
    assertEquals(4, t.get("callback_count"));
  }

  @Test
  void ac1105_callbackWithoutTransactionIdIsStoredButBelongsToNoTransaction() {
    long id = append(callback("THU_HO", T0, null, null, true));

    assertEquals(1, count("callbacks"));
    assertEquals(0, count("transactions"));
    assertEquals(
        id,
        jdbc.queryForObject(
            "SELECT record_id FROM callbacks WHERE transaction_id IS NULL", Long.class));
  }

  @Test
  void callbackFinishingOutOfOrderIsCountedButDoesNotReplaceTheNewerState() {
    // Callback nhận sau (receivedAt lớn hơn) hoàn tất trước, callback nhận trước đến muộn.
    long newer = append(callback("THU_HO", T0.plusSeconds(10), "T1", "NEW", false));
    append(callback("THU_HO", T0, "T1", "OLD", false));

    Map<String, Object> t = transaction("THU_HO", "T1");
    assertEquals("NEW", t.get("latest_status"));
    assertEquals(newer, t.get("latest_callback_id"));
    assertEquals(2, t.get("callback_count"));
  }

  @Test
  void sameReceivedAtIsBrokenByTheHigherRecordId() {
    append(callback("THU_HO", T0, "T1", "FIRST", false));
    long second = append(callback("THU_HO", T0, "T1", "SECOND", false));

    Map<String, Object> t = transaction("THU_HO", "T1");
    assertEquals("SECOND", t.get("latest_status"));
    assertEquals(second, t.get("latest_callback_id"));
  }

  @Test
  void allLatestColumnsAreReplacedTogetherFromTheSameCallback() {
    NewCallback old =
        new NewCallback(
            "THU_HO",
            versionId,
            T0,
            "SUCCESS",
            headers(),
            "{}",
            "T1",
            "OLD",
            Instant.parse("2026-10-19T01:00:00Z"),
            "{}",
            201,
            "{}");
    NewCallback newer =
        new NewCallback(
            "THU_HO",
            versionId,
            T0.plusSeconds(1),
            "MAPPING_ERROR",
            headers(),
            "{}",
            "T1",
            null,
            null,
            "{}",
            201,
            "{}");
    append(old);
    long newerId = append(newer);

    Map<String, Object> t = transaction("THU_HO", "T1");
    assertNull(t.get("latest_status"));
    assertNull(t.get("latest_business_ts"));
    assertEquals(true, t.get("latest_has_mapping_error"));
    assertEquals(newerId, t.get("latest_callback_id"));
    assertEquals(T0.plusSeconds(1), ((java.sql.Timestamp) t.get("latest_received_at")).toInstant());
  }

  @Test
  void appendRequiresAnExistingTransaction() {
    assertThrows(
        IllegalTransactionStateException.class,
        () -> ledger.append(callback("THU_HO", T0, "T1", "A", false)));
    assertEquals(0, count("callbacks"));
  }

  @Test
  void failureWritingTheTransactionRollsBackTheCallbackToo() {
    jdbc.execute(
        "CREATE OR REPLACE FUNCTION tpp_test_fail() RETURNS trigger AS $$ BEGIN"
            + " RAISE EXCEPTION 'boom'; END; $$ LANGUAGE plpgsql");
    jdbc.execute(
        "CREATE TRIGGER tpp_test_fail_trg BEFORE INSERT ON transactions"
            + " FOR EACH ROW EXECUTE FUNCTION tpp_test_fail()");
    try {
      assertThrows(
          DataAccessException.class, () -> append(callback("THU_HO", T0, "T1", "A", false)));
    } finally {
      jdbc.execute("DROP TRIGGER IF EXISTS tpp_test_fail_trg ON transactions");
    }

    assertEquals(0, count("callbacks"));
    assertEquals(0, count("transactions"));
  }

  @Test
  void headersAreStoredMaskedAndNeverInTheClear() {
    MaskedHeaders raw =
        new MaskedHeaders(
            Map.of(
                "Authorization", List.of("Bearer TOP-SECRET"),
                "Proxy-Authorization", List.of("Basic PROXY-SECRET"),
                "Cookie", List.of("sid=COOKIE-SECRET"),
                "X-Request-Id", List.of("abc-123")));
    NewCallback withHeaders =
        new NewCallback(
            "THU_HO", versionId, T0, "SUCCESS", raw, "{}", "T1", "A", null, "{}", 201, "{}");

    append(withHeaders);

    String stored =
        jdbc.queryForObject("SELECT request_headers_masked::text FROM callbacks", String.class);
    assertFalse(stored.contains("TOP-SECRET"));
    assertFalse(stored.contains("PROXY-SECRET"));
    assertFalse(stored.contains("COOKIE-SECRET"));
    assertTrue(stored.contains("abc-123"));
    assertEquals(
        "***",
        jdbc.queryForObject(
            "SELECT request_headers_masked->>'Authorization' FROM callbacks", String.class));
  }

  @Test
  void listIsNewestFirstWithTieBreakByLatestCallbackIdAndPaged() {
    for (int i = 0; i < 25; i++) {
      append(callback("THU_HO", T0.plusSeconds(i), "T" + i, "S", false));
    }
    append(callback("THU_HO", T0.plusSeconds(24), "TIE", "S", false));

    PageResult<TransactionSummary> first = ledger.listTransactions(1, 20);
    PageResult<TransactionSummary> second = ledger.listTransactions(2, 20);
    PageResult<TransactionSummary> beyond = ledger.listTransactions(9, 20);

    assertEquals(26, first.totalItems());
    assertEquals(2, first.totalPages());
    assertEquals(20, first.items().size());
    assertEquals(6, second.items().size());
    assertTrue(beyond.items().isEmpty());
    assertEquals("TIE", first.items().get(0).transactionId());
    assertEquals("T24", first.items().get(1).transactionId());
    List<String> all = new ArrayList<>();
    first.items().forEach(i -> all.add(i.transactionId()));
    second.items().forEach(i -> all.add(i.transactionId()));
    assertEquals(26, all.stream().distinct().count());
    assertEquals("T0", second.items().get(5).transactionId());
  }

  @Test
  void emptyLedgerGivesAnEmptyPage() {
    PageResult<TransactionSummary> page = ledger.listTransactions(1, 20);

    assertTrue(page.items().isEmpty());
    assertEquals(0, page.totalItems());
    assertEquals(0, page.totalPages());
  }

  @Test
  void findTransactionReturnsCallbacksOldestFirstWithConfigVersionNumber() {
    append(callback("THU_HO", T0.plusSeconds(2), "T1", "C", false));
    append(callback("THU_HO", T0, "T1", "A", false));
    append(callback("THU_HO", T0.plusSeconds(1), "T1", "B", false));

    TransactionDetail detail = ledger.findTransaction("thu_ho", "T1").orElseThrow();

    assertEquals("THU_HO", detail.transaction().serviceCode());
    assertEquals(3, detail.transaction().callbackCount());
    assertEquals(
        List.of("A", "B", "C"), detail.callbacks().stream().map(CallbackDetail::status).toList());
    assertTrue(detail.callbacks().stream().allMatch(c -> c.configVersionNumber() == 1));
    assertTrue(detail.callbacks().stream().noneMatch(CallbackDetail::serviceDeleted));
    assertEquals("{\"ok\": true}", detail.callbacks().get(0).responseBodyReturned());
  }

  @Test
  void findTransactionMatchesTransactionIdExactlyAndServiceCodeIgnoringCase() {
    append(callback("THU_HO", T0, "Abc", "A", false));

    assertTrue(ledger.findTransaction("THU_HO", "Abc").isPresent());
    assertTrue(ledger.findTransaction("thu_ho", "Abc").isPresent());
    assertTrue(ledger.findTransaction("THU_HO", "abc").isEmpty());
    assertTrue(ledger.findTransaction("THU_HO", "Abc ").isEmpty());
    assertTrue(ledger.findTransaction("OTHER", "Abc").isEmpty());
  }

  @Test
  void findTransactionReportsDeletedServicesWithoutHidingHistory() {
    append(callback("THU_HO", T0, "T1", "A", false));
    jdbc.update("UPDATE services SET deleted = true WHERE service_code = 'THU_HO'");

    TransactionDetail detail = ledger.findTransaction("THU_HO", "T1").orElseThrow();

    assertTrue(detail.transaction().serviceDeleted());
    assertTrue(detail.callbacks().get(0).serviceDeleted());
  }

  @Test
  void transactionIdWithSpecialCharactersRoundTrips() {
    String tricky = "2026/10/001 ?#% +&=ngày'\"\\";
    append(callback("THU_HO", T0, tricky, "A", false));

    assertEquals(
        tricky,
        ledger.findTransaction("THU_HO", tricky).orElseThrow().transaction().transactionId());
  }
}
