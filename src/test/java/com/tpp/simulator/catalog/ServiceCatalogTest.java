package com.tpp.simulator.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpp.simulator.config.ApiException;
import com.tpp.simulator.config.ErrorCode;
import com.tpp.simulator.mapping.TimestampFormat;
import com.tpp.simulator.support.AbstractIntegrationTest;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

/** W1, W7: tạo và đọc dịch vụ trên PostgreSQL thật; BR1.2 (trùng mã), BR1.8, NFR2.1. */
class ServiceCatalogTest extends AbstractIntegrationTest {

  @Autowired private ServiceCatalog catalog;
  @Autowired private ObjectMapper mapper;

  private ServiceCreateRequest request(String code) throws Exception {
    return mapper.readValue(serviceJson(code), ServiceCreateRequest.class);
  }

  @Test
  void createWritesServiceAndConfigVersionOneWithDefaults() throws Exception {
    clock.set(Instant.parse("2026-10-19T03:00:00.123456Z"));

    ServiceView view = catalog.create(request("THU_HO"));

    assertEquals("THU_HO", view.serviceCode());
    assertTrue(view.enabled());
    assertFalse(view.deleted());
    assertFalse(view.apiKeyConfigured());
    assertEquals("/callbacks/THU_HO", view.callbackPath());
    assertEquals(1, view.currentVersionNumber());
    assertEquals("Asia/Ho_Chi_Minh", view.defaultTimeZone());
    assertEquals(Instant.parse("2026-10-19T03:00:00.123456Z"), view.createdAt());

    Map<String, Object> service = jdbc.queryForMap("SELECT * FROM services");
    assertEquals(true, service.get("enabled"));
    assertEquals(false, service.get("deleted"));
    assertEquals(1, service.get("current_version_number"));
    assertNull(service.get("api_key_secret"));

    Map<String, Object> version = jdbc.queryForMap("SELECT * FROM service_config_versions");
    assertEquals(1, version.get("version_number"));
    assertEquals("$.transId", version.get("transaction_id_path"));
    assertEquals("$.code", version.get("status_path"));
    assertEquals("$.time", version.get("timestamp_path"));
    assertEquals("ISO_8601", version.get("timestamp_format"));
    assertEquals("Asia/Ho_Chi_Minh", version.get("default_time_zone"));
    assertEquals(201, version.get("response_http_status"));
    assertEquals("{\"ok\":true}", version.get("response_body"));
  }

  @Test
  void duplicateCodeInAnyLetterCaseIsRejectedAndLeavesOneService() throws Exception {
    catalog.create(request("THU_HO"));

    ApiException lower = assertThrows(ApiException.class, () -> catalog.create(request("thu_ho")));
    ApiException mixed = assertThrows(ApiException.class, () -> catalog.create(request("Thu_Ho")));

    assertEquals(ErrorCode.SERVICE_CODE_DUPLICATE, lower.code());
    assertEquals(ErrorCode.SERVICE_CODE_DUPLICATE, mixed.code());
    assertEquals(1, count("services"));
    assertEquals(1, count("service_config_versions"));
  }

  @Test
  void concurrentCreatesOfTheSameCodeYieldExactlyOneSuccess() throws Exception {
    String[] variants = {"RACE", "race", "Race", "rAcE", "RACe", "raCE", "rACE", "RaCe"};
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(variants.length);
    List<Future<String>> results = new ArrayList<>();
    for (String variant : variants) {
      ServiceCreateRequest request = request(variant);
      Callable<String> task =
          () -> {
            start.await();
            try {
              catalog.create(request);
              return "CREATED";
            } catch (ApiException e) {
              return e.code().name();
            }
          };
      results.add(pool.submit(task));
    }
    start.countDown();

    int created = 0;
    int duplicates = 0;
    for (Future<String> result : results) {
      String outcome = result.get();
      if (outcome.equals("CREATED")) {
        created++;
      } else if (outcome.equals("SERVICE_CODE_DUPLICATE")) {
        duplicates++;
      }
    }
    pool.shutdownNow();

    assertEquals(1, created);
    assertEquals(variants.length - 1, duplicates);
    assertEquals(1, count("services"));
    assertEquals(1, count("service_config_versions"));
  }

  @Test
  void failureWhileWritingTheConfigVersionRollsBackTheService() throws Exception {
    jdbc.execute(
        "CREATE OR REPLACE FUNCTION tpp_test_fail() RETURNS trigger AS $$ BEGIN"
            + " RAISE EXCEPTION 'boom'; END; $$ LANGUAGE plpgsql");
    jdbc.execute(
        "CREATE TRIGGER tpp_test_fail_trg BEFORE INSERT ON service_config_versions"
            + " FOR EACH ROW EXECUTE FUNCTION tpp_test_fail()");
    try {
      assertThrows(DataAccessException.class, () -> catalog.create(request("THU_HO")));
    } finally {
      jdbc.execute("DROP TRIGGER IF EXISTS tpp_test_fail_trg ON service_config_versions");
    }

    assertEquals(0, count("services"));
    assertEquals(0, count("service_config_versions"));
    assertNotNull(catalog.create(request("THU_HO")));
  }

  @Test
  void getIsCaseInsensitiveAndKeepsTheOriginalCode() throws Exception {
    catalog.create(request("Thu_Ho"));

    ServiceView view = catalog.get("THU_HO");

    assertEquals("Thu_Ho", view.serviceCode());
    assertEquals("/callbacks/Thu_Ho", view.callbackPath());
    assertEquals(201, view.responseHttpStatus());
    assertEquals("{\"ok\":true}", view.responseBody());
  }

  @Test
  void aServiceCodeWithARealNulCharacterIsNeverFoundAndNeverReachesPostgres() throws Exception {
    catalog.create(request("THU_HO"));

    // PostgreSQL không nhận NUL trong tham số văn bản (22021): lớp danh mục coi mã như vậy là
    // "không có", đúng như một mã chưa từng tồn tại, thay vì để lỗi cơ sở dữ liệu thành 500.
    assertTrue(catalog.findSnapshot("THU_HO\u0000").isEmpty());
    for (Callable<Object> call :
        List.<Callable<Object>>of(
            () -> catalog.get("a\u0000b"),
            () -> catalog.setEnabled("a\u0000b", new ServiceEnabledRequest(true)),
            () -> {
              catalog.delete("a\u0000b");
              return null;
            })) {
      ApiException e = assertThrows(ApiException.class, call::call);
      assertEquals(ErrorCode.SERVICE_NOT_FOUND, e.code());
    }
    assertTrue(catalog.findSnapshot("THU_HO").isPresent());
  }

  @Test
  void getUnknownServiceIsNotFound() {
    ApiException e = assertThrows(ApiException.class, () -> catalog.get("NOPE"));

    assertEquals(ErrorCode.SERVICE_NOT_FOUND, e.code());
  }

  @Test
  void findSnapshotReturnsTheCurrentVersionIncludingZoneAndFlags() throws Exception {
    catalog.create(request("THU_HO"));
    jdbc.update("UPDATE services SET enabled = false WHERE service_code = 'THU_HO'");

    ServiceRuntimeSnapshot snapshot = catalog.findSnapshot("thu_ho").orElseThrow();

    assertEquals("THU_HO", snapshot.serviceCode());
    assertFalse(snapshot.enabled());
    assertFalse(snapshot.deleted());
    assertFalse(snapshot.requiresApiKey());
    assertEquals(1, snapshot.versionNumber());
    assertEquals(ZoneId.of("Asia/Ho_Chi_Minh"), snapshot.defaultTimeZone());
    assertEquals("$.time", snapshot.timestampPath());
    assertEquals(TimestampFormat.ISO_8601, snapshot.timestampFormat());
    assertTrue(catalog.findSnapshot("missing").isEmpty());
  }

  @Test
  void snapshotKeepsTheKeyInMemoryButNeverPrintsIt() throws Exception {
    catalog.create(
        mapper.readValue(
            serviceJson("CHI_HO")
                .replace("\"authType\":\"NONE\"", "\"authType\":\"API_KEY\"")
                .replace(
                    "{\"serviceCode\"",
                    "{\"apiKeyHeaderName\":\"X-Api-Key\",\"apiKeySecret\":\"snap-secret\",\"serviceCode\""),
            ServiceCreateRequest.class));

    ServiceRuntimeSnapshot snapshot = catalog.findSnapshot("CHI_HO").orElseThrow();

    assertTrue(snapshot.requiresApiKey());
    assertEquals("X-Api-Key", snapshot.apiKeyHeaderName());
    assertEquals("snap-secret", snapshot.apiKeySecret());
    assertFalse(snapshot.toString().contains("snap-secret"));
    assertFalse(snapshot.withEnabled(false).toString().contains("snap-secret"));
    assertTrue(catalog.get("CHI_HO").apiKeyConfigured());
    assertFalse(mapper.writeValueAsString(catalog.get("CHI_HO")).contains("snap-secret"));
  }

  @Test
  void snapshotVariantsChangeOnlyTheRequestedState() throws Exception {
    catalog.create(request("THU_HO"));
    ServiceRuntimeSnapshot snapshot = catalog.findSnapshot("THU_HO").orElseThrow();

    ServiceRuntimeSnapshot disabled = snapshot.withEnabled(false);
    ServiceRuntimeSnapshot deleted = snapshot.withDeleted(true);
    ServiceRuntimeSnapshot keyed = snapshot.withApiKey("X-K", "v");

    assertFalse(disabled.enabled());
    assertTrue(snapshot.enabled());
    assertTrue(deleted.deleted());
    assertFalse(snapshot.deleted());
    assertTrue(keyed.requiresApiKey());
    assertEquals(snapshot.versionId(), keyed.versionId());
    assertEquals(snapshot.responseBody(), keyed.responseBody());
  }

  @Test
  void specialCharactersAreStoredLiterallyNotExecuted() throws Exception {
    String sneaky = "x'); DROP TABLE services; --";
    ServiceCreateRequest r = request("SAFE");
    ServiceCreateRequest withName =
        new ServiceCreateRequest(
            r.serviceCode(),
            sneaky,
            r.description(),
            r.enabled(),
            r.authType(),
            r.apiKeyHeaderName(),
            r.apiKeySecret(),
            r.transactionIdPath(),
            r.statusPath(),
            r.timestampPath(),
            r.timestampFormat(),
            r.timestampPattern(),
            r.defaultTimeZone(),
            r.responseHttpStatus(),
            r.responseBody());

    catalog.create(withName);

    assertEquals(sneaky, jdbc.queryForObject("SELECT name FROM services", String.class));
  }
}
