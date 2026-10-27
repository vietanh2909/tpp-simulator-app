package com.tpp.simulator.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.tpp.simulator.support.AbstractIntegrationTest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** W17, BR14.4, BR14.5, BR16.1, NFR3.22, NFR3.23: xác thực khóa API và che header. */
class ApiKeyAuthTest extends AbstractIntegrationTest {

  private static final String KEY = "k1";

  @BeforeEach
  void services() {
    createServiceFromJson(
        serviceJson("CHI_HO")
            .replace("\"authType\":\"NONE\"", "\"authType\":\"API_KEY\"")
            .replace(
                "{\"serviceCode\"",
                "{\"apiKeyHeaderName\":\"X-Api-Key\",\"apiKeySecret\":\""
                    + KEY
                    + "\",\"serviceCode\""));
    createService("OPEN");
  }

  private String lastReason() {
    List<String> reasons =
        jdbc.queryForList(
            "SELECT coalesce(rejection_reason, outcome) FROM callbacks ORDER BY record_id DESC LIMIT 1",
            String.class);
    return reasons.isEmpty() ? null : reasons.get(0);
  }

  private void assertQualifies(HttpResponse<String> response) {
    assertEquals(201, response.statusCode(), response.body());
    assertNull(response.headers().firstValue("X-Simulator-Error").orElse(null));
  }

  private void assertUnauthorized(HttpResponse<String> response, String reason) {
    assertEquals(401, response.statusCode(), response.body());
    assertEquals("UNAUTHORIZED", response.headers().firstValue("X-Simulator-Error").orElse(null));
    assertEquals(reason, lastReason());
  }

  @Test
  void theMatrixOfTheKeyHeader() {
    assertQualifies(callback("CHI_HO", payload("T1", "00"), "X-Api-Key", KEY));
    assertQualifies(callback("CHI_HO", payload("T2", "00"), "x-api-key", KEY));
    assertQualifies(callback("CHI_HO", payload("T3", "00"), "X-API-KEY", KEY));
    assertUnauthorized(
        callback("CHI_HO", payload("T4", "00"), "X-Api-Key", "K1"), "INVALID_API_KEY");
    assertUnauthorized(
        callback("CHI_HO", payload("T5", "00"), "X-Api-Key", "sai"), "INVALID_API_KEY");
    assertUnauthorized(callback("CHI_HO", payload("T6", "00")), "MISSING_API_KEY");
    assertUnauthorized(callback("CHI_HO", payload("T7", "00"), "X-Api-Key", ""), "MISSING_API_KEY");
    assertUnauthorized(
        callback("CHI_HO", payload("T8", "00"), "X-Api-Key", KEY, "X-Api-Key", "sai"),
        "INVALID_API_KEY");
    assertUnauthorized(
        callback("CHI_HO", payload("T9", "00"), "X-Api-Key", "sai", "X-Api-Key", KEY),
        "INVALID_API_KEY");
    assertUnauthorized(
        callback("CHI_HO", payload("T10", "00"), "X-Other-Key", KEY), "MISSING_API_KEY");
    assertEquals(3, count("transactions"));
  }

  @Test
  void aNoneServiceIgnoresTheKeyHeaderCompletely() {
    assertQualifies(callback("OPEN", payload("T1", "00")));
    assertQualifies(callback("OPEN", payload("T2", "00"), "X-Api-Key", "anything"));
  }

  @Test
  void the401BodyIsTheSameForMissingAndWrongKeysAndNeverEchoesAnything() {
    HttpResponse<String> missing = callback("CHI_HO", payload("T1", "00"));
    HttpResponse<String> wrong =
        callback("CHI_HO", payload("T1", "00"), "X-Api-Key", "WRONG-VALUE");

    assertEquals(missing.body(), wrong.body());
    assertFalse(wrong.body().contains("WRONG-VALUE"));
    assertFalse(missing.body().contains(KEY));
  }

  @Test
  void aKeyRenamedBySavingTheServiceAgainIsUsedFromTheNextRequest() {
    String body =
        "{\"name\":\"n\",\"authType\":\"API_KEY\",\"apiKeyHeaderName\":\"X-New\","
            + "\"transactionIdPath\":\"$.transId\",\"statusPath\":\"$.code\","
            + "\"timestampFormat\":\"ISO_8601\",\"responseHttpStatus\":201,\"responseBody\":{\"ok\":true}}";

    assertEquals(200, send("PUT", "/api/services/CHI_HO", JSON, body).statusCode());

    assertUnauthorized(
        callback("CHI_HO", payload("T1", "00"), "X-Api-Key", KEY), "MISSING_API_KEY");
    assertQualifies(callback("CHI_HO", payload("T2", "00"), "X-New", KEY));
  }

  // ---------- che header (BR16.1) ----------

  private JsonNode storedHeaders() throws Exception {
    return json.readTree(
        jdbc.queryForObject(
            "SELECT request_headers_masked::text FROM callbacks ORDER BY record_id DESC LIMIT 1",
            String.class));
  }

  private String value(JsonNode headers, String name) {
    return headerIgnoreCase(headers, name);
  }

  private static final String[] SENSITIVE = {
    "Authorization", "Bearer SECRET-AUTH", "Proxy-Authorization", "Basic SECRET-PROXY",
    "Cookie", "session=SECRET-COOKIE", "X-Request-Id", "visible-1"
  };

  private void assertMasked(JsonNode headers) {
    assertEquals("***", value(headers, "Authorization"));
    // JDK HttpClient không gửi Proxy-Authorization tới máy chủ không qua proxy; việc che header đó
    // được kiểm ở MaskedHeadersTest.
    assertEquals("***", value(headers, "Cookie"));
    assertEquals("visible-1", value(headers, "X-Request-Id"));
    assertFalse(headers.toString().contains("SECRET"), headers.toString());
  }

  @Test
  void sensitiveHeadersAreMaskedForEveryKindOfReceipt() throws Exception {
    List<String> headers = new ArrayList<>(List.of(SENSITIVE));
    headers.add("X-Api-Key");
    headers.add(KEY);

    // SUCCESS
    callback("CHI_HO", payload("T1", "00"), headers.toArray(new String[0]));
    assertMasked(storedHeaders());
    assertEquals("***", value(storedHeaders(), "X-Api-Key"));
    // MAPPING_ERROR
    callback("CHI_HO", "{\"x\":1}", headers.toArray(new String[0]));
    assertEquals("***", value(storedHeaders(), "X-Api-Key"));
    assertMasked(storedHeaders());
    // INVALID_JSON, UNSUPPORTED_CONTENT_TYPE, INVALID_API_KEY (khóa sai cũng bị che)
    callback("CHI_HO", "{bad", headers.toArray(new String[0]));
    assertEquals("***", value(storedHeaders(), "X-Api-Key"));
    send("POST", "/callbacks/CHI_HO", "text/plain", "x", headers.toArray(new String[0]));
    assertEquals("***", value(storedHeaders(), "X-Api-Key"));
    List<String> wrong = new ArrayList<>(List.of(SENSITIVE));
    wrong.add("x-api-key");
    wrong.add("WRONG-KEY-VALUE");
    callback("CHI_HO", payload("T9", "00"), wrong.toArray(new String[0]));
    assertEquals("***", value(storedHeaders(), "x-api-key"));
    assertFalse(storedHeaders().toString().contains("WRONG-KEY-VALUE"));
    assertMasked(storedHeaders());
    // SERVICE_DISABLED và SERVICE_DELETED vẫn che header khóa của dịch vụ
    jdbc.update("UPDATE services SET enabled = false WHERE service_code = 'CHI_HO'");
    callback("CHI_HO", payload("T1", "00"), headers.toArray(new String[0]));
    assertEquals("***", value(storedHeaders(), "X-Api-Key"));
    jdbc.update("UPDATE services SET deleted = true WHERE service_code = 'CHI_HO'");
    callback("CHI_HO", payload("T1", "00"), headers.toArray(new String[0]));
    assertEquals("***", value(storedHeaders(), "X-Api-Key"));
    assertMasked(storedHeaders());
  }

  @Test
  void aNoneServiceKeepsAnApiKeyLikeHeaderButStillMasksTheThreeStandardOnes() throws Exception {
    callback(
        "OPEN",
        payload("T1", "00"),
        "X-Api-Key",
        "visible-key",
        "Authorization",
        "Bearer SECRET-AUTH");

    JsonNode headers = storedHeaders();

    assertEquals("visible-key", value(headers, "X-Api-Key"));
    assertEquals("***", value(headers, "Authorization"));
  }
}
