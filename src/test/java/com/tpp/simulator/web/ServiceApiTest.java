package com.tpp.simulator.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.tpp.simulator.support.AbstractIntegrationTest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** C2 (phần U1) qua HTTP thật: POST /api/services và GET /api/services/{serviceCode}. */
class ServiceApiTest extends AbstractIntegrationTest {

  private static void assertValidationError(HttpResponse<String> response) {
    assertEquals(400, response.statusCode(), response.body());
    assertEquals(
        "VALIDATION_FAILED", response.headers().firstValue("X-Simulator-Error").orElse(null));
  }

  private Set<String> fieldErrorNames(HttpResponse<String> response) {
    Set<String> names = new HashSet<>();
    for (JsonNode e : jsonBody(response).get("fieldErrors")) {
      names.add(e.get("field").asText());
    }
    return names;
  }

  @Test
  void createReturns201WithCallbackPathAndNeverTheApiKeySecret() {
    HttpResponse<String> response = postJson("/api/services", serviceJson("THU_HO"));

    assertEquals(201, response.statusCode(), response.body());
    assertTrue(
        response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
    JsonNode body = jsonBody(response);
    assertEquals("THU_HO", body.get("serviceCode").asText());
    assertEquals("/callbacks/THU_HO", body.get("callbackPath").asText());
    assertFalse(body.get("apiKeyConfigured").asBoolean());
    assertTrue(body.get("enabled").asBoolean());
    assertFalse(body.get("deleted").asBoolean());
    assertEquals(1, body.get("currentVersionNumber").asInt());
    assertEquals("NONE", body.get("authType").asText());
    assertEquals("Asia/Ho_Chi_Minh", body.get("defaultTimeZone").asText());
    assertEquals(201, body.get("responseHttpStatus").asInt());
    assertTrue(body.get("responseBody").get("ok").asBoolean());
    assertFalse(body.has("apiKeySecret"));
    assertFalse(response.body().toLowerCase().contains("secret"));
    Instant.parse(body.get("createdAt").asText());
    assertTrue(
        response.body().contains("Z\""),
        "thời điểm phải là UTC kết thúc bằng Z: " + response.body());
    assertTrue(response.headers().firstValue("X-Simulator-Error").isEmpty());
  }

  @Test
  void responseBodyKeepsTheExactNumericTextOnCreateAndRead() {
    String body =
        "{\"amount\":100000.00,\"rate\":1e3,\"x\":0.1000000000000000055511151231257827,"
            + "\"big\":123456789012345678901234567890,\"list\":[1.50,-0.0,2E-3]}";
    String request = serviceJson("NUM", "$.transId", "$.code", "$.time", 200, body, null);

    HttpResponse<String> created = postJson("/api/services", request);

    assertEquals(201, created.statusCode(), created.body());
    assertTrue(created.body().contains("\"responseBody\":" + body + ","), created.body());
    HttpResponse<String> read = get("/api/services/NUM");
    assertTrue(read.body().contains("\"responseBody\":" + body + ","), read.body());
    assertEquals(
        body,
        jdbc.queryForObject(
            "SELECT response_body FROM service_config_versions WHERE service_code = 'NUM'",
            String.class));
  }

  @Test
  void responseBodyWhitespaceIsTrimmedButNumbersAreUntouched() {
    String request =
        serviceJson(
            "NUM2",
            "$.transId",
            "$.code",
            "$.time",
            200,
            "{ \"a\" : 1.10 ,\n \"b\" : [ 2e0 ] }",
            null);

    assertEquals(201, postJson("/api/services", request).statusCode());

    assertEquals(
        "{\"a\":1.10,\"b\":[2e0]}",
        jdbc.queryForObject(
            "SELECT response_body FROM service_config_versions WHERE service_code = 'NUM2'",
            String.class));
  }

  @Test
  void apiKeyFieldsAreIgnoredForAuthTypeNoneAndNeverStoredOrReturned() {
    String withSecret =
        serviceJson("THU_HO")
            .replaceFirst(
                "\\{", "{\"apiKeySecret\":\"super-secret-key\",\"apiKeyHeaderName\":\"X-Key\",");

    HttpResponse<String> response = postJson("/api/services", withSecret);

    assertEquals(201, response.statusCode(), response.body());
    assertFalse(response.body().contains("super-secret-key"));
    assertNull(jdbc.queryForObject("SELECT api_key_secret FROM services", String.class));
    assertNull(jdbc.queryForObject("SELECT api_key_header_name FROM services", String.class));
    assertFalse(get("/api/services/THU_HO").body().contains("super-secret-key"));
  }

  @Test
  void getIsCaseInsensitiveAndReturnsTheOriginalCode() {
    createService("Thu_Ho");

    HttpResponse<String> response = get("/api/services/THU_HO");

    assertEquals(200, response.statusCode());
    JsonNode body = jsonBody(response);
    assertEquals("Thu_Ho", body.get("serviceCode").asText());
    assertEquals("/callbacks/Thu_Ho", body.get("callbackPath").asText());
    assertFalse(body.has("apiKeySecret"));
  }

  @Test
  void getUnknownServiceIs404WithErrorHeader() {
    HttpResponse<String> response = get("/api/services/NOPE");

    assertEquals(404, response.statusCode());
    assertEquals(
        "SERVICE_NOT_FOUND", response.headers().firstValue("X-Simulator-Error").orElse(null));
    assertEquals("SERVICE_NOT_FOUND", jsonBody(response).get("error").asText());
  }

  @Test
  void aServiceCodeWithAnEncodedNulInThePathIsRejectedByTheContainerNotAServerError() {
    // Đã đo: Tomcat trả 400 cho %00 trong đường dẫn trước khi tới ứng dụng, nên không bao giờ
    // thành 500. Lớp danh mục tự bảo vệ riêng (ServiceCatalogTest).
    for (String method : new String[] {"GET", "DELETE"}) {
      assertEquals(400, send(method, "/api/services/a%00b", null, null).statusCode(), method);
    }
    assertEquals(
        400, send("PUT", "/api/services/a%00b/enabled", JSON, "{\"enabled\":true}").statusCode());
  }

  @Test
  void emptyObjectReportsEveryMandatoryFieldAtOnce() {
    HttpResponse<String> response = postJson("/api/services", "{}");

    assertValidationError(response);
    assertEquals(
        Set.of(
            "serviceCode",
            "name",
            "authType",
            "transactionIdPath",
            "statusPath",
            "timestampFormat",
            "responseHttpStatus",
            "responseBody"),
        fieldErrorNames(response));
  }

  @Test
  void apiKeyAuthAndEveryTimestampFormatAreAcceptedFromU2() {
    String body =
        serviceJson("THU_HO")
            .replace(
                "\"authType\":\"NONE\"",
                "\"authType\":\"API_KEY\",\"apiKeyHeaderName\":\"X-Api-Key\",\"apiKeySecret\":\"k-123\"")
            .replace("ISO_8601", "UNIX_SECONDS");

    HttpResponse<String> response = postJson("/api/services", body);

    assertEquals(201, response.statusCode(), response.body());
    JsonNode created = jsonBody(response);
    assertTrue(created.get("apiKeyConfigured").asBoolean());
    assertEquals("X-Api-Key", created.get("apiKeyHeaderName").asText());
    assertEquals("UNIX_SECONDS", created.get("timestampFormat").asText());
    assertFalse(response.body().contains("k-123"));
    assertEquals("k-123", jdbc.queryForObject("SELECT api_key_secret FROM services", String.class));
  }

  @Test
  void apiKeyServiceWithoutHeaderAndSecretIsRejectedWithBothFieldNames() {
    String body =
        serviceJson("THU_HO").replace("\"authType\":\"NONE\"", "\"authType\":\"API_KEY\"");

    HttpResponse<String> response = postJson("/api/services", body);

    assertValidationError(response);
    assertEquals(Set.of("apiKeyHeaderName", "apiKeySecret"), fieldErrorNames(response));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM services", Integer.class));
  }

  @ParameterizedTest
  @ValueSource(ints = {204, 205, 304, 199, 600})
  void forbiddenHttpStatusesAreRejected(int status) {
    String body =
        serviceJson("THU_HO", "$.transId", "$.code", "$.time", status, "{\"ok\":true}", null);

    HttpResponse<String> response = postJson("/api/services", body);

    assertValidationError(response);
    assertEquals(Set.of("responseHttpStatus"), fieldErrorNames(response));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{",
        "",
        "[]",
        "\"text\"",
        "null",
        "{\"serviceCode\":",
        "not json",
        "{\"serviceCode\":\"A\"} trailing"
      })
  void brokenJsonBodiesAre400NeverServerErrors(String body) {
    HttpResponse<String> response = postJson("/api/services", body);

    assertValidationError(response);
    assertFalse(response.body().contains("Exception"));
    assertFalse(response.body().contains("at com."));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "\"responseHttpStatus\":201|\"responseHttpStatus\":\"201\"",
        "\"responseHttpStatus\":201|\"responseHttpStatus\":200.5",
        "\"responseHttpStatus\":201|\"responseHttpStatus\":[1]",
        "\"authType\":\"NONE\"|\"authType\":5",
        "\"name\":\"Dich vu THU_HO\"|\"name\":123",
        "\"transactionIdPath\":\"$.transId\"|\"transactionIdPath\":{}",
        "\"serviceCode\":\"THU_HO\"|\"serviceCode\":true"
      })
  void wrongTypedFieldsAre400WithTheFieldName(String replacement) {
    String[] parts = replacement.split("\\|");
    String body = serviceJson("THU_HO").replace(parts[0], parts[1]);

    HttpResponse<String> response = postJson("/api/services", body);

    assertValidationError(response);
    assertFalse(jsonBody(response).get("fieldErrors").isEmpty());
  }

  @Test
  void duplicateCodeInAnotherCaseIs409() {
    createService("THU_HO");

    HttpResponse<String> response = postJson("/api/services", serviceJson("thu_ho"));

    assertEquals(409, response.statusCode());
    assertEquals(
        "SERVICE_CODE_DUPLICATE", response.headers().firstValue("X-Simulator-Error").orElse(null));
    assertEquals("SERVICE_CODE_DUPLICATE", jsonBody(response).get("error").asText());
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM services", Integer.class));
  }

  @Test
  void createdServiceCanBeDisabledAtCreation() {
    String body = serviceJson("OFFLINE", "$.transId", "$.code", null, 200, "{}", false);

    HttpResponse<String> response = postJson("/api/services", body);

    assertEquals(201, response.statusCode(), response.body());
    assertFalse(jsonBody(response).get("enabled").asBoolean());
    assertTrue(jsonBody(response).get("timestampPath").isNull());
  }

  @Test
  void createRequiresAJsonContentType() {
    HttpResponse<String> response =
        send("POST", "/api/services", "text/plain", serviceJson("THU_HO"));

    assertEquals(415, response.statusCode());
    assertEquals(
        "UNSUPPORTED_MEDIA_TYPE", response.headers().firstValue("X-Simulator-Error").orElse(null));
  }

  @Test
  void responseBodyOver64KbIsRejected() {
    String big = "\"" + "x".repeat(70_000) + "\"";
    String body = serviceJson("BIG", "$.transId", "$.code", "$.time", 200, big, null);

    HttpResponse<String> response = postJson("/api/services", body);

    assertValidationError(response);
    assertEquals(Set.of("responseBody"), fieldErrorNames(response));
  }

  @Test
  void invalidServiceCodeCharactersAreRejectedBeforeAnySql() {
    String body = serviceJson("A'; DROP TABLE services; --");

    HttpResponse<String> response = postJson("/api/services", body);

    assertValidationError(response);
    assertEquals(Set.of("serviceCode"), fieldErrorNames(response));
    assertFalse(response.body().contains("DROP TABLE"));
  }
}
