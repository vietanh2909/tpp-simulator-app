package com.tpp.simulator.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tpp.simulator.support.AbstractIntegrationTest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * W12 đến W15, BR12.x, BR13.x: sửa, bật/tắt, xóa mềm, liệt kê và thứ tự kiểm tra 415 → 400 → 404 →
 * lỗi trường, trên ứng dụng thật và PostgreSQL thật.
 */
class ServiceEditTest extends AbstractIntegrationTest {

  private static final Instant T0 = Instant.parse("2026-10-19T03:00:00Z");

  private ObjectNode updateBody() {
    ObjectNode body = json.createObjectNode();
    body.put("name", "Thu hộ mới");
    body.put("authType", "NONE");
    body.put("transactionIdPath", "$.transId");
    body.put("statusPath", "$.code");
    body.put("timestampPath", "$.time");
    body.put("timestampFormat", "ISO_8601");
    body.put("responseHttpStatus", 201);
    body.set("responseBody", body("{\"ok\":true}"));
    return body;
  }

  private JsonNode body(String text) {
    try {
      return json.readTree(text);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private HttpResponse<String> put(String code, ObjectNode body) {
    return send("PUT", "/api/services/" + code, JSON, body.toString());
  }

  private HttpResponse<String> putEnabled(String code, String raw) {
    return send("PUT", "/api/services/" + code + "/enabled", JSON, raw);
  }

  private void createApiKeyService(String code, String key) {
    ObjectNode body = updateBody();
    body.put("serviceCode", code);
    body.put("authType", "API_KEY");
    body.put("apiKeyHeaderName", "X-Api-Key");
    body.put("apiKeySecret", key);
    assertEquals(201, postJson("/api/services", body.toString()).statusCode());
  }

  private int version(String code) {
    return jdbc.queryForObject(
        "SELECT current_version_number FROM services WHERE service_code = ?", Integer.class, code);
  }

  private int versionRows(String code) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM service_config_versions WHERE service_code = ?", Integer.class, code);
  }

  private Instant updatedAt(String code) {
    return jdbc.queryForObject(
            "SELECT updated_at FROM services WHERE service_code = ?",
            java.sql.Timestamp.class,
            code)
        .toInstant();
  }

  // ---------- BR12.4, BR12.5: khi nào sinh phiên bản ----------

  @Test
  void changingAVersionFieldCreatesTheNextVersionAndUpdatesTheService() {
    createService("THU_HO");
    clock.set(T0.plusSeconds(60));
    ObjectNode body = updateBody();
    body.put("statusPath", "$.state");
    body.put("responseHttpStatus", 202);
    body.set("responseBody", body("{\"ok\":false}"));

    HttpResponse<String> response = put("THU_HO", body);

    assertEquals(200, response.statusCode(), response.body());
    JsonNode view = jsonBody(response);
    assertEquals(2, view.get("currentVersionNumber").asInt());
    assertEquals("$.state", view.get("statusPath").asText());
    assertEquals(202, view.get("responseHttpStatus").asInt());
    assertEquals("Thu hộ mới", view.get("name").asText());
    assertEquals(2, versionRows("THU_HO"));
    assertEquals(
        "$.code",
        jdbc.queryForObject(
            "SELECT status_path FROM service_config_versions WHERE version_number = 1",
            String.class));
  }

  @Test
  void changingOnlyNameOrDescriptionDoesNotCreateAVersion() {
    createService("THU_HO");
    ObjectNode body = updateBody();
    body.put("name", "Tên khác");
    body.put("description", "Mô tả mới");

    HttpResponse<String> response = put("THU_HO", body);

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(1, jsonBody(response).get("currentVersionNumber").asInt());
    assertEquals(1, versionRows("THU_HO"));
    assertEquals("Tên khác", jdbc.queryForObject("SELECT name FROM services", String.class));
  }

  @Test
  void anUnchangedUpdateWritesNothingAndKeepsUpdatedAt() {
    clock.set(T0);
    createService("THU_HO");
    clock.set(T0.plusSeconds(600));
    ObjectNode body = updateBody();
    body.put("name", "Dich vu THU_HO");

    HttpResponse<String> response = put("THU_HO", body);

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(T0, updatedAt("THU_HO"));
    assertEquals(1, versionRows("THU_HO"));
  }

  @Test
  void aResponseBodyThatOnlyDiffersByWhitespaceOrKeyOrderIsNotAChange() {
    createServiceFromJson(
        serviceJson(
            "THU_HO", "$.transId", "$.code", "$.time", 201, "{\"a\":1,\"b\":[1, 2]}", null));
    ObjectNode body = updateBody();
    body.put("name", "Dich vu THU_HO");
    body.set("responseBody", body("{ \"b\" : [1,2], \"a\" : 1 }"));

    HttpResponse<String> response = put("THU_HO", body);

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(1, versionRows("THU_HO"));
    assertEquals(
        "{\"a\":1,\"b\":[1,2]}",
        jdbc.queryForObject("SELECT response_body FROM service_config_versions", String.class));
  }

  @Test
  void numbersOneAndOnePointZeroAreDifferentResponseBodies() {
    createServiceFromJson(
        serviceJson("THU_HO", "$.transId", "$.code", "$.time", 201, "{\"a\":1}", null));
    ObjectNode body = updateBody();
    body.put("name", "Dich vu THU_HO");
    body.set("responseBody", body("{\"a\":1.0}"));

    HttpResponse<String> response = put("THU_HO", body);

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(2, versionRows("THU_HO"));
  }

  @Test
  void patternIsOnlyComparedWhenTheFormatIsPattern() {
    createService("THU_HO");
    ObjectNode body = updateBody();
    body.put("name", "Dich vu THU_HO");
    body.put("timestampPattern", "ignored for ISO");

    assertEquals(200, put("THU_HO", body).statusCode());

    assertEquals(1, versionRows("THU_HO"));
    assertNull(
        jdbc.queryForObject("SELECT timestamp_pattern FROM service_config_versions", String.class));
  }

  @Test
  void aTimeZoneChangeAndAFormatChangeEachCreateAVersion() {
    createService("THU_HO");
    ObjectNode zone = updateBody();
    zone.put("defaultTimeZone", "UTC");
    assertEquals(200, put("THU_HO", zone).statusCode());
    ObjectNode format = updateBody();
    format.put("defaultTimeZone", "UTC");
    format.put("timestampFormat", "PATTERN");
    format.put("timestampPattern", "yyyy-MM-dd HH:mm:ss");
    assertEquals(200, put("THU_HO", format).statusCode());

    assertEquals(3, version("THU_HO"));
    assertEquals(3, versionRows("THU_HO"));
  }

  @Test
  void updateNeverWritesEnabledOrDeletedAndIgnoresThoseFieldsInTheBody() {
    createService("THU_HO");
    assertEquals(200, putEnabled("THU_HO", "{\"enabled\":false}").statusCode());
    ObjectNode body = updateBody();
    body.put("enabled", true);
    body.put("deleted", true);
    body.put("serviceCode", "OTHER");
    body.put("unknownField", 1);
    body.put("name", "Tên khác");

    HttpResponse<String> response = put("THU_HO", body);

    assertEquals(200, response.statusCode(), response.body());
    JsonNode view = jsonBody(response);
    assertFalse(view.get("enabled").asBoolean());
    assertFalse(view.get("deleted").asBoolean());
    assertEquals("THU_HO", view.get("serviceCode").asText());
    assertEquals(1, count("services"));
  }

  @Test
  void callbacksAlreadyStoredAreNotRecomputedAfterAnEdit() {
    createService("THU_HO");
    assertEquals(201, callback("THU_HO", payload("T1", "00")).statusCode());
    String before =
        jdbc.queryForObject(
            "SELECT mapping_field_results::text || response_body_returned::text FROM callbacks",
            String.class);
    ObjectNode body = updateBody();
    body.put("transactionIdPath", "$.other");
    body.put("responseHttpStatus", 202);
    body.set("responseBody", body("{\"v\":2}"));
    assertEquals(200, put("THU_HO", body).statusCode());

    assertEquals(202, callback("THU_HO", "{\"other\":\"T2\",\"code\":\"00\"}").statusCode());

    List<Integer> versions =
        jdbc.queryForList(
            "SELECT v.version_number FROM callbacks c JOIN service_config_versions v ON"
                + " v.version_id = c.config_version_id ORDER BY c.record_id",
            Integer.class);
    assertEquals(List.of(1, 2), versions);
    assertEquals(
        before,
        jdbc.queryForObject(
            "SELECT mapping_field_results::text || response_body_returned::text FROM callbacks"
                + " ORDER BY record_id LIMIT 1",
            String.class));
    assertEquals(
        201,
        jdbc.queryForObject(
            "SELECT http_status_returned FROM callbacks ORDER BY record_id LIMIT 1",
            Integer.class));
  }

  // ---------- BR12.3: khóa API ----------

  @Test
  void switchingToApiKeyWithASecretStoresItAndNeverReturnsIt() {
    createService("THU_HO");
    ObjectNode body = updateBody();
    body.put("authType", "API_KEY");
    body.put("apiKeyHeaderName", "X-Api-Key");
    body.put("apiKeySecret", "top-secret-key");

    HttpResponse<String> response = put("THU_HO", body);

    assertEquals(200, response.statusCode(), response.body());
    JsonNode view = jsonBody(response);
    assertTrue(view.get("apiKeyConfigured").asBoolean());
    assertEquals("X-Api-Key", view.get("apiKeyHeaderName").asText());
    assertFalse(response.body().contains("top-secret-key"));
    assertFalse(get("/api/services/THU_HO").body().contains("top-secret-key"));
    assertFalse(get("/api/services").body().contains("top-secret-key"));
    assertEquals(
        "top-secret-key", jdbc.queryForObject("SELECT api_key_secret FROM services", String.class));
    assertEquals(1, version("THU_HO"));
  }

  @Test
  void aBlankSecretKeepsTheOldKeyAndAllowsRenamingTheHeader() {
    createApiKeyService("CHI_HO", "old-key");
    ObjectNode body = updateBody();
    body.put("authType", "API_KEY");
    body.put("apiKeyHeaderName", "X-New-Key");
    body.put("apiKeySecret", "");

    HttpResponse<String> response = put("CHI_HO", body);

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(
        "old-key", jdbc.queryForObject("SELECT api_key_secret FROM services", String.class));
    assertEquals(
        "X-New-Key", jdbc.queryForObject("SELECT api_key_header_name FROM services", String.class));
  }

  @Test
  void aNewSecretReplacesTheOldKey() {
    createApiKeyService("CHI_HO", "old-key");
    ObjectNode body = updateBody();
    body.put("authType", "API_KEY");
    body.put("apiKeyHeaderName", "X-Api-Key");
    body.put("apiKeySecret", "new-key");

    assertEquals(200, put("CHI_HO", body).statusCode());

    assertEquals(
        "new-key", jdbc.queryForObject("SELECT api_key_secret FROM services", String.class));
  }

  @Test
  void switchingToNoneClearsTheHeaderAndTheKey() {
    createApiKeyService("CHI_HO", "old-key");

    HttpResponse<String> response = put("CHI_HO", updateBody());

    assertEquals(200, response.statusCode(), response.body());
    assertFalse(jsonBody(response).get("apiKeyConfigured").asBoolean());
    assertNull(jdbc.queryForObject("SELECT api_key_secret FROM services", String.class));
    assertNull(jdbc.queryForObject("SELECT api_key_header_name FROM services", String.class));

    ObjectNode back = updateBody();
    back.put("authType", "API_KEY");
    back.put("apiKeyHeaderName", "X-Api-Key");
    HttpResponse<String> noKey = put("CHI_HO", back);
    assertEquals(400, noKey.statusCode());
    assertTrue(noKey.body().contains("\"field\":\"apiKeySecret\""), noKey.body());
  }

  @Test
  void switchingFromNoneToApiKeyWithoutASecretIs400AndChangesNothing() {
    createService("THU_HO");
    ObjectNode body = updateBody();
    body.put("authType", "API_KEY");
    body.put("apiKeyHeaderName", "X-Api-Key");
    body.put("name", "Không được lưu");

    HttpResponse<String> response = put("THU_HO", body);

    assertEquals(400, response.statusCode());
    assertEquals(
        "VALIDATION_FAILED", response.headers().firstValue("X-Simulator-Error").orElse(null));
    assertEquals("Dich vu THU_HO", jdbc.queryForObject("SELECT name FROM services", String.class));
    assertEquals(1, versionRows("THU_HO"));
  }

  // ---------- BR13.2, BR13.4, BR13.6: thứ tự kiểm tra ----------

  @Test
  void checkOrderIs415ThenBrokenBody400Then404ThenFieldErrors() {
    HttpResponse<String> wrongType =
        send("PUT", "/api/services/NOPE", "text/plain", updateBody().toString());
    HttpResponse<String> broken = send("PUT", "/api/services/NOPE", JSON, "{not json");
    HttpResponse<String> notObject = send("PUT", "/api/services/NOPE", JSON, "[1,2]");
    HttpResponse<String> wrongKind =
        send("PUT", "/api/services/NOPE", JSON, "{\"responseHttpStatus\":\"abc\"}");
    ObjectNode badFields = updateBody();
    badFields.put("statusPath", "bad");
    HttpResponse<String> unknownGood = put("NOPE", updateBody());
    HttpResponse<String> unknownBadFields = put("NOPE", badFields);

    assertEquals(415, wrongType.statusCode());
    assertEquals(
        "UNSUPPORTED_MEDIA_TYPE", wrongType.headers().firstValue("X-Simulator-Error").orElse(null));
    assertEquals(400, broken.statusCode());
    assertEquals(400, notObject.statusCode());
    assertEquals(400, wrongKind.statusCode());
    assertEquals(404, unknownGood.statusCode());
    assertEquals(
        "SERVICE_NOT_FOUND", unknownGood.headers().firstValue("X-Simulator-Error").orElse(null));
    assertEquals(404, unknownBadFields.statusCode());

    createService("THU_HO");
    HttpResponse<String> existingBad = put("THU_HO", badFields);
    assertEquals(400, existingBad.statusCode());
    assertTrue(existingBad.body().contains("statusPath"));
  }

  @Test
  void everyFieldErrorIsReportedTogetherAndNothingIsSaved() {
    createService("THU_HO");

    HttpResponse<String> response = send("PUT", "/api/services/THU_HO", JSON, "{}");

    assertEquals(400, response.statusCode());
    List<String> fields = new ArrayList<>();
    jsonBody(response).get("fieldErrors").forEach(e -> fields.add(e.get("field").asText()));
    assertTrue(
        fields.containsAll(
            List.of(
                "name",
                "authType",
                "transactionIdPath",
                "statusPath",
                "timestampFormat",
                "responseHttpStatus",
                "responseBody")),
        fields.toString());
    assertEquals(1, versionRows("THU_HO"));
  }

  @Test
  void aCaseInsensitiveCodeInThePathFindsTheService() {
    createService("Thu_Ho");
    ObjectNode body = updateBody();
    body.put("name", "Khác");

    HttpResponse<String> response = put("thu_ho", body);

    assertEquals(200, response.statusCode(), response.body());
    assertEquals("Thu_Ho", jsonBody(response).get("serviceCode").asText());
  }

  // ---------- BR13.2: bật/tắt ----------

  @Test
  void enabledCanBeToggledAndRepeatedWithoutChangingAnythingElse() {
    clock.set(T0);
    createService("THU_HO");
    clock.set(T0.plusSeconds(10));

    HttpResponse<String> off = putEnabled("THU_HO", "{\"enabled\":false}");
    Instant afterOff = updatedAt("THU_HO");
    clock.set(T0.plusSeconds(20));
    HttpResponse<String> offAgain = putEnabled("THU_HO", "{\"enabled\":false}");

    assertEquals(200, off.statusCode(), off.body());
    assertFalse(jsonBody(off).get("enabled").asBoolean());
    assertEquals(200, offAgain.statusCode());
    assertEquals(T0.plusSeconds(10), afterOff);
    assertEquals(afterOff, updatedAt("THU_HO"));
    assertEquals(1, versionRows("THU_HO"));
    assertTrue(jsonBody(putEnabled("THU_HO", "{\"enabled\":true}")).get("enabled").asBoolean());
  }

  @Test
  void enabledChecksFollowTheCommonOrder() {
    HttpResponse<String> wrongType = send("PUT", "/api/services/NOPE/enabled", "text/plain", "{}");
    HttpResponse<String> broken = putEnabled("NOPE", "{oops");
    HttpResponse<String> wrongKind = putEnabled("NOPE", "{\"enabled\":\"yes\"}");
    HttpResponse<String> notFoundBeforeMissingField = putEnabled("NOPE", "{}");
    createService("THU_HO");
    HttpResponse<String> missing = putEnabled("THU_HO", "{}");

    assertEquals(415, wrongType.statusCode());
    assertEquals(400, broken.statusCode());
    assertEquals(400, wrongKind.statusCode());
    assertEquals(404, notFoundBeforeMissingField.statusCode());
    assertEquals(400, missing.statusCode());
    assertTrue(missing.body().contains("\"field\":\"enabled\""), missing.body());
  }

  // ---------- BR13.3, BR13.4: xóa mềm ----------

  @Test
  void softDeleteKeepsEverythingAndIsOneWay() {
    createService("THU_HO");
    assertEquals(201, callback("THU_HO", payload("T1", "00")).statusCode());

    HttpResponse<String> deleted = send("DELETE", "/api/services/THU_HO", null, null);

    assertEquals(204, deleted.statusCode());
    assertEquals("", deleted.body());
    JsonNode view = jsonBody(get("/api/services/THU_HO"));
    assertTrue(view.get("deleted").asBoolean());
    assertTrue(view.get("enabled").asBoolean());
    assertEquals(1, count("callbacks"));
    assertEquals(1, count("transactions"));
    assertEquals(1, versionRows("THU_HO"));

    assertEquals(404, send("DELETE", "/api/services/THU_HO", null, null).statusCode());
    assertEquals(404, put("THU_HO", updateBody()).statusCode());
    assertEquals(404, putEnabled("THU_HO", "{\"enabled\":false}").statusCode());
    assertEquals(404, callback("THU_HO", payload("T2", "00")).statusCode());
    HttpResponse<String> recreate = postJson("/api/services", serviceJson("thu_ho"));
    assertEquals(409, recreate.statusCode());
    assertEquals(1, versionRows("THU_HO"));
  }

  @Test
  void deletingAnUnknownServiceIs404() {
    HttpResponse<String> response = send("DELETE", "/api/services/NOPE", null, null);

    assertEquals(404, response.statusCode());
    assertEquals(
        "SERVICE_NOT_FOUND", response.headers().firstValue("X-Simulator-Error").orElse(null));
  }

  // ---------- BR13.1: liệt kê ----------

  @Test
  void listIsSortedIgnoringCaseAndHidesDeletedByDefault() {
    createService("beta");
    createService("Alpha");
    createService("CHARLIE");
    send("DELETE", "/api/services/beta", null, null);

    List<String> active = codes(get("/api/services"));
    List<String> all = codes(get("/api/services?includeDeleted=true"));
    List<String> emptyParam = codes(get("/api/services?includeDeleted="));
    List<String> falseParam = codes(get("/api/services?includeDeleted=false"));

    assertEquals(List.of("Alpha", "CHARLIE"), active);
    assertEquals(List.of("Alpha", "beta", "CHARLIE"), all);
    assertEquals(active, emptyParam);
    assertEquals(active, falseParam);
  }

  @Test
  void anEmptyDatabaseListsAnEmptyArray() {
    HttpResponse<String> response = get("/api/services");

    assertEquals(200, response.statusCode());
    assertEquals("[]", response.body());
  }

  @Test
  void aBadIncludeDeletedValueIs400() {
    for (String value : List.of("yes", "1", "TRUE", " ")) {
      HttpResponse<String> response = get("/api/services?includeDeleted=" + enc(value));
      assertEquals(400, response.statusCode(), value);
      assertTrue(response.body().contains("\"field\":\"includeDeleted\""), response.body());
    }
  }

  private List<String> codes(HttpResponse<String> response) {
    assertEquals(200, response.statusCode(), response.body());
    List<String> result = new ArrayList<>();
    jsonBody(response).forEach(s -> result.add(s.get("serviceCode").asText()));
    return result;
  }
}
