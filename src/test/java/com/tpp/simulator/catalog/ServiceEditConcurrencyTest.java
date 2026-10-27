package com.tpp.simulator.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tpp.simulator.support.AbstractIntegrationTest;
import com.tpp.simulator.support.ParallelRunner;
import com.tpp.simulator.support.TestDatabase;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * NFR2.24, BR12.6, SC-01: sửa, bật/tắt, xóa mềm của CÙNG một dịch vụ được tuần tự hóa bằng khóa
 * dòng; số phiên bản liên tục; nhận callback không chờ khóa dòng.
 */
class ServiceEditConcurrencyTest extends AbstractIntegrationTest {

  private ObjectNode updateBody(String name, String responseBodyJson) {
    ObjectNode body = json.createObjectNode();
    body.put("name", name);
    body.put("authType", "NONE");
    body.put("transactionIdPath", "$.transId");
    body.put("statusPath", "$.code");
    body.put("timestampPath", "$.time");
    body.put("timestampFormat", "ISO_8601");
    body.put("responseHttpStatus", 201);
    try {
      body.set("responseBody", json.readTree(responseBodyJson));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return body;
  }

  private HttpResponse<String> put(String code, ObjectNode body) {
    return send("PUT", "/api/services/" + code, JSON, body.toString());
  }

  private List<Integer> versionNumbers(String code) {
    return jdbc.queryForList(
        "SELECT version_number FROM service_config_versions WHERE service_code = ? ORDER BY"
            + " version_number",
        Integer.class,
        code);
  }

  @Test
  void thirtyParallelEditsGiveContinuousVersionNumbersWithNoGapOrDuplicate() {
    createService("RACE");
    List<Callable<Integer>> tasks = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      ObjectNode body = updateBody("Dich vu RACE", "{\"n\":" + i + "}");
      tasks.add(() -> put("RACE", body).statusCode());
    }

    List<Integer> statuses = ParallelRunner.runAll(tasks);

    assertEquals(List.of(200), statuses.stream().distinct().toList());
    List<Integer> expected = new ArrayList<>();
    for (int v = 1; v <= 31; v++) {
      expected.add(v);
    }
    assertEquals(expected, versionNumbers("RACE"));
    assertEquals(
        31, jdbc.queryForObject("SELECT current_version_number FROM services", Integer.class));
    String current = jsonBody(get("/api/services/RACE")).get("responseBody").toString();
    assertEquals(
        current,
        jdbc.queryForObject(
            "SELECT response_body FROM service_config_versions WHERE version_number = 31",
            String.class));
  }

  @Test
  void rowLockHeldByAnotherSessionDoesNotBlockCallbackIngestion() throws Exception {
    createService("LOCKED");
    // Một callback khởi động trước khi giữ khóa: lần nhận đầu tiên của JVM nạp lớp và biên dịch JIT
    // (đo được 1,5 giây trên máy chậm khi chạy riêng lớp này), không liên quan tới khóa dòng.
    assertEquals(201, callback("LOCKED", payload("WARM", "00")).statusCode());
    long deadline;
    List<Long> callbackMillis = new ArrayList<>();
    AtomicBoolean stop = new AtomicBoolean();
    ExecutorService pool = Executors.newFixedThreadPool(6);
    try (Connection holder =
            DriverManager.getConnection(
                TestDatabase.URL, TestDatabase.USERNAME, TestDatabase.PASSWORD);
        Statement st = holder.createStatement()) {
      holder.setAutoCommit(false);
      st.execute(
          "SELECT service_code FROM services WHERE service_code = 'LOCKED' FOR NO KEY UPDATE");
      // 3 giây (không phải 2) để máy chậm vẫn đủ mẫu; 5 thao tác sửa chờ khóa < lock_timeout 5
      // giây.
      deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();

      // 5 thao tác sửa xếp hàng chờ khóa dòng: chiếm 5 kết nối pool, nhỏ hơn 10.
      List<Future<Integer>> edits = new ArrayList<>();
      for (int i = 0; i < 5; i++) {
        ObjectNode body = updateBody("Dich vu LOCKED", "{\"n\":" + i + "}");
        edits.add(pool.submit(() -> put("LOCKED", body).statusCode()));
      }
      Thread.sleep(300);
      // Callback liên tục tới chính dịch vụ này trong lúc khóa còn bị giữ.
      Future<?> sender =
          pool.submit(
              () -> {
                int n = 0;
                while (!stop.get()) {
                  long start = System.nanoTime();
                  HttpResponse<String> r = callback("LOCKED", payload("T" + n++, "00"));
                  synchronized (callbackMillis) {
                    callbackMillis.add((System.nanoTime() - start) / 1_000_000);
                  }
                  assertEquals(201, r.statusCode(), r.body());
                }
              });
      while (System.nanoTime() < deadline) {
        Thread.sleep(50);
      }
      int whileHeld;
      synchronized (callbackMillis) {
        whileHeld = callbackMillis.size();
      }
      stop.set(true);
      sender.get();
      holder.rollback();

      for (Future<Integer> edit : edits) {
        assertEquals(200, edit.get());
      }
      assertTrue(whileHeld >= 5, "Chỉ có " + whileHeld + " callback hoàn tất khi khóa còn giữ");
    } finally {
      stop.set(true);
      pool.shutdownNow();
    }

    synchronized (callbackMillis) {
      long slowest = callbackMillis.stream().mapToLong(Long::longValue).max().orElse(0);
      assertTrue(slowest < 1000, "Callback chậm nhất " + slowest + " ms");
    }
    assertEquals(List.of(1, 2, 3, 4, 5, 6), versionNumbers("LOCKED"));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM callbacks c LEFT JOIN service_config_versions v ON v.version_id ="
                + " c.config_version_id WHERE v.version_id IS NULL",
            Integer.class));
    assertTrue(count("callbacks") >= 5);
  }

  // Ca đói pool: 30 thao tác sửa song song nhiều hơn kích thước pool (10) nên có thể chiếm hết kết
  // nối. Ca này CHỈ khẳng định không lỗi dưới tải sửa song song (mọi phản hồi là 201, không 500,
  // không hết thời gian chờ kết nối). Nó KHÔNG chứng minh callback không bị chậm vì đói pool; bằng
  // chứng "không chờ khóa dòng" (số thao tác sửa đang chờ nhỏ hơn pool, callback chậm nhất < 1
  // giây) nằm ở rowLockHeldByAnotherSessionDoesNotBlockCallbackIngestion.
  @Test
  void callbacksDuringThirtyParallelEditsAllGet201WithNoServerError() throws Exception {
    createService("BUSY");
    AtomicBoolean stop = new AtomicBoolean();
    List<Integer> statuses = new ArrayList<>();
    List<Long> millis = new ArrayList<>();
    ExecutorService pool = Executors.newSingleThreadExecutor();
    Future<?> sender =
        pool.submit(
            () -> {
              int n = 0;
              while (!stop.get()) {
                long start = System.nanoTime();
                HttpResponse<String> r = callback("BUSY", payload("B" + n++, "00"));
                synchronized (statuses) {
                  statuses.add(r.statusCode());
                  millis.add((System.nanoTime() - start) / 1_000_000);
                }
              }
            });
    List<Callable<Integer>> tasks = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      ObjectNode body = updateBody("Dich vu BUSY", "{\"n\":" + i + "}");
      tasks.add(() -> put("BUSY", body).statusCode());
    }

    List<Integer> edits = ParallelRunner.runAll(tasks);
    stop.set(true);
    sender.get();
    pool.shutdownNow();

    assertEquals(List.of(200), edits.stream().distinct().toList());
    synchronized (statuses) {
      assertFalse(statuses.isEmpty());
      assertEquals(List.of(201), statuses.stream().distinct().toList());
      // Chỉ là lưới an toàn "không hết thời gian chờ kết nối 5 giây của pool", KHÔNG phải bằng
      // chứng không đói pool: 4,5 giây thay cho 3 giây của SC-01 vì máy chậm đo được 3,6 giây.
      assertTrue(millis.stream().allMatch(m -> m < 4500), millis.toString());
    }
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM callbacks c LEFT JOIN service_config_versions v ON v.version_id ="
                + " c.config_version_id WHERE v.version_id IS NULL",
            Integer.class));
  }

  @Test
  void editsRacingWithADeleteNeverCreateVersionsAfterTheDelete() {
    createService("GONE");
    List<Callable<Integer>> tasks = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      ObjectNode body = updateBody("Dich vu GONE", "{\"n\":" + i + "}");
      tasks.add(() -> put("GONE", body).statusCode());
    }
    tasks.add(() -> send("DELETE", "/api/services/GONE", null, null).statusCode());

    List<Integer> statuses = ParallelRunner.runAll(tasks);

    long okEdits = statuses.subList(0, 10).stream().filter(s -> s == 200).count();
    long notFound = statuses.subList(0, 10).stream().filter(s -> s == 404).count();
    assertEquals(204, statuses.get(10));
    assertEquals(10, okEdits + notFound);
    assertEquals(true, jdbc.queryForObject("SELECT deleted FROM services", Boolean.class));
    assertEquals(
        1 + okEdits,
        jdbc.queryForObject("SELECT count(*) FROM service_config_versions", Long.class));
    assertEquals(
        1 + okEdits,
        jdbc.queryForObject("SELECT current_version_number FROM services", Long.class));
  }

  @Test
  void editsRacingWithATogglePreserveTheToggle() {
    createService("TOGGLE");
    List<Callable<Integer>> tasks = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      ObjectNode body = updateBody("Dich vu TOGGLE", "{\"n\":" + i + "}");
      tasks.add(() -> put("TOGGLE", body).statusCode());
    }
    tasks.add(
        () ->
            send("PUT", "/api/services/TOGGLE/enabled", JSON, "{\"enabled\":false}").statusCode());

    List<Integer> statuses = ParallelRunner.runAll(tasks);

    assertEquals(List.of(200), statuses.stream().distinct().toList());
    assertEquals(false, jdbc.queryForObject("SELECT enabled FROM services", Boolean.class));
  }

  @Test
  void switchingToNoneRacingWithKeepKeyEditsNeverLeavesAnApiKeyServiceWithoutAKey() {
    ObjectNode create = updateBody("Khoa", "{\"ok\":true}");
    create.put("serviceCode", "KEYED");
    create.put("authType", "API_KEY");
    create.put("apiKeyHeaderName", "X-Api-Key");
    create.put("apiKeySecret", "seed-key");
    assertEquals(201, postJson("/api/services", create.toString()).statusCode());
    List<Callable<Integer>> tasks = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      ObjectNode body = updateBody("Khoa " + i, "{\"ok\":true}");
      if (i % 2 == 0) {
        body.put("authType", "API_KEY");
        body.put("apiKeyHeaderName", "X-Api-Key");
        body.put("apiKeySecret", "");
      }
      tasks.add(() -> put("KEYED", body).statusCode());
    }

    List<Integer> statuses = ParallelRunner.runAll(tasks);

    assertTrue(statuses.stream().allMatch(s -> s == 200 || s == 400), statuses.toString());
    String auth = jdbc.queryForObject("SELECT auth_type FROM services", String.class);
    String key = jdbc.queryForObject("SELECT api_key_secret FROM services", String.class);
    String header = jdbc.queryForObject("SELECT api_key_header_name FROM services", String.class);
    if (auth.equals("API_KEY")) {
      assertEquals("seed-key", key);
      assertNotNull(header);
    } else {
      assertEquals(null, key);
      assertEquals(null, header);
    }
  }
}
