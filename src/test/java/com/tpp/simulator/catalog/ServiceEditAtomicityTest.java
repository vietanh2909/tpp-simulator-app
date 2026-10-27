package com.tpp.simulator.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tpp.simulator.support.AbstractIntegrationTest;
import com.tpp.simulator.support.FailureTrigger;
import com.tpp.simulator.support.LogCapture;
import com.tpp.simulator.support.TestDatabase;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/**
 * NFR2.30, RD-01, SD-05: lỗi giữa chừng một lần sửa không để lại phiên bản mồ côi hay dịch vụ nửa
 * vời, không lộ khóa; hết thời gian chờ khóa dòng là 500 chung.
 */
class ServiceEditAtomicityTest extends AbstractIntegrationTest {

  private ObjectNode updateBody(String name, String responseBodyJson, String key) {
    ObjectNode body = json.createObjectNode();
    body.put("name", name);
    body.put("authType", key == null ? "NONE" : "API_KEY");
    if (key != null) {
      body.put("apiKeyHeaderName", "X-Api-Key");
      body.put("apiKeySecret", key);
    }
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

  @Test
  void aFailureWhileInsertingTheNewVersionLeavesTheServiceUntouched() {
    createService("ATOM");
    String nameBefore = jdbc.queryForObject("SELECT name FROM services", String.class);

    try (FailureTrigger trigger = FailureTrigger.on("service_config_versions", "INSERT", "ATOM");
        LogCapture logs = LogCapture.root()) {
      HttpResponse<String> response = put("ATOM", updateBody("Tên mới", "{\"n\":2}", null));

      assertEquals(500, response.statusCode(), response.body());
      assertEquals(
          "INTERNAL_ERROR", response.headers().firstValue("X-Simulator-Error").orElse(null));
      assertFalse(response.body().contains("test failure"), response.body());
      assertFalse(logs.allText().contains("test failure"), logs.allText());
    }

    assertEquals(nameBefore, jdbc.queryForObject("SELECT name FROM services", String.class));
    assertEquals(
        1, jdbc.queryForObject("SELECT current_version_number FROM services", Integer.class));
    assertEquals(1, count("service_config_versions"));
    assertEquals(200, put("ATOM", updateBody("Tên mới", "{\"n\":2}", null)).statusCode());
    assertEquals(2, count("service_config_versions"));
  }

  @Test
  void aFailureWhileUpdatingTheServiceRowCreatesNoOrphanVersion() {
    createService("ATOM");

    try (FailureTrigger trigger = FailureTrigger.on("services", "UPDATE", "ATOM")) {
      HttpResponse<String> response = put("ATOM", updateBody("Tên mới", "{\"n\":2}", null));

      assertEquals(500, response.statusCode(), response.body());
    }

    assertEquals(1, count("service_config_versions"));
    assertEquals("Dich vu ATOM", jdbc.queryForObject("SELECT name FROM services", String.class));
  }

  @Test
  void aWriteFailureWhileChangingTheApiKeyNeverLeaksTheKeyAndKeepsTheOldOne() {
    createService("ATOM");
    String secret = "LEAK-CANARY-" + java.util.UUID.randomUUID();

    try (FailureTrigger trigger = FailureTrigger.on("services", "UPDATE", "ATOM");
        LogCapture logs = LogCapture.root()) {
      HttpResponse<String> response = put("ATOM", updateBody("Khoá", "{\"ok\":true}", secret));

      assertEquals(500, response.statusCode());
      assertFalse(response.body().contains(secret));
      assertFalse(response.headers().map().toString().contains(secret));
      assertFalse(logs.allText().contains(secret), logs.allText());
    }

    assertEquals(null, jdbc.queryForObject("SELECT api_key_secret FROM services", String.class));
  }

  @Test
  void aFailureWhileCreatingAnApiKeyServiceNeverLeaksTheKey() {
    String secret = "LEAK-CANARY-" + java.util.UUID.randomUUID();
    ObjectNode create = updateBody("Khoá", "{\"ok\":true}", secret);
    create.put("serviceCode", "NEWKEY");

    try (FailureTrigger trigger = FailureTrigger.on("services", "INSERT", "NEWKEY");
        LogCapture logs = LogCapture.root()) {
      HttpResponse<String> response = postJson("/api/services", create.toString());

      assertEquals(500, response.statusCode());
      assertFalse(response.body().contains(secret));
      assertFalse(logs.allText().contains(secret), logs.allText());
    }

    assertEquals(0, count("services"));
  }

  @Test
  void waitingLongerThanTheLockTimeoutIsAGenericInternalError() throws Exception {
    createService("SLOW");
    try (Connection holder =
            DriverManager.getConnection(
                TestDatabase.URL, TestDatabase.USERNAME, TestDatabase.PASSWORD);
        Statement st = holder.createStatement();
        LogCapture logs = LogCapture.root()) {
      holder.setAutoCommit(false);
      st.execute("SELECT service_code FROM services WHERE service_code = 'SLOW' FOR NO KEY UPDATE");
      long start = System.nanoTime();

      HttpResponse<String> response = put("SLOW", updateBody("Chờ", "{\"n\":2}", null));

      long millis = (System.nanoTime() - start) / 1_000_000;
      holder.rollback();
      assertEquals(500, response.statusCode(), response.body());
      assertEquals(
          "INTERNAL_ERROR", response.headers().firstValue("X-Simulator-Error").orElse(null));
      assertTrue(millis >= 4500 && millis < 9000, "Chờ khóa " + millis + " ms");
      assertTrue(logs.allText().contains("sqlState=55P03"), logs.allText());
    }

    assertEquals(200, put("SLOW", updateBody("Chờ", "{\"n\":2}", null)).statusCode());
  }
}
