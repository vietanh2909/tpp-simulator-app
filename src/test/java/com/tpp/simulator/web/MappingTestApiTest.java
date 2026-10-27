package com.tpp.simulator.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tpp.simulator.support.AbstractIntegrationTest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** W20, BR18.x, NFR1.28: thử mapping dùng cùng engine, không ghi gì, không lặp nội dung mẫu. */
class MappingTestApiTest extends AbstractIntegrationTest {

  private ObjectNode request(String format, String pattern, String sample) {
    ObjectNode body = json.createObjectNode();
    body.put("transactionIdPath", "$.transId");
    body.put("statusPath", "$.code");
    body.put("timestampPath", "$.time");
    body.put("timestampFormat", format);
    if (pattern != null) {
      body.put("timestampPattern", pattern);
    }
    body.put("sampleJson", sample);
    return body;
  }

  private HttpResponse<String> test(ObjectNode body) {
    return postJson("/api/mapping-tests", body.toString());
  }

  private Set<String> fieldNames(HttpResponse<String> response) {
    Set<String> names = new HashSet<>();
    jsonBody(response).get("fieldErrors").forEach(e -> names.add(e.get("field").asText()));
    return names;
  }

  @Test
  void anIsoSampleGivesThreeIndependentResults() {
    HttpResponse<String> response =
        test(
            request(
                "ISO_8601",
                null,
                "{\"transId\":\"T1\",\"code\":\"00\",\"time\":\"2026-10-19T10:00:00+07:00\"}"));

    assertEquals(200, response.statusCode(), response.body());
    JsonNode body = jsonBody(response);
    assertTrue(body.get("sampleJsonValid").asBoolean());
    assertEquals("T1", body.get("transactionId").get("value").asText());
    assertEquals("00", body.get("status").get("value").asText());
    assertEquals("2026-10-19T03:00:00Z", body.get("timestamp").get("value").asText());
    assertFalse(body.has("sampleJsonError"));
  }

  @Test
  void everyTimestampFormatIsSupported() {
    JsonNode seconds =
        jsonBody(
            test(
                request(
                    "UNIX_SECONDS",
                    null,
                    "{\"transId\":\"A\",\"code\":\"1\",\"time\":1760850000}")));
    JsonNode millis =
        jsonBody(
            test(
                request(
                    "UNIX_MILLIS",
                    null,
                    "{\"transId\":\"A\",\"code\":\"1\",\"time\":\"1760850000123\"}")));
    JsonNode pattern =
        jsonBody(
            test(
                request(
                    "PATTERN",
                    "yyyy-MM-dd HH:mm:ss",
                    "{\"transId\":\"A\",\"code\":\"1\",\"time\":\"2026-10-19 10:00:00\"}")));

    assertEquals("2025-10-19T05:00:00Z", seconds.get("timestamp").get("value").asText());
    assertEquals("2025-10-19T05:00:00.123Z", millis.get("timestamp").get("value").asText());
    assertEquals("2026-10-19T03:00:00Z", pattern.get("timestamp").get("value").asText());
  }

  @Test
  void aBadTimestampIsAFieldErrorNotARequestError() {
    JsonNode body =
        jsonBody(
            test(
                request(
                    "UNIX_SECONDS",
                    null,
                    "{\"transId\":\"A\",\"code\":\"1\",\"time\":\"1760850000.\"}")));

    assertEquals("UNPARSEABLE", body.get("timestamp").get("errorCode").asText());
    assertEquals("A", body.get("transactionId").get("value").asText());
  }

  @Test
  void theTimestampResultIsAbsentWhenNoTimestampPathIsConfigured() {
    ObjectNode body = request("ISO_8601", null, "{\"transId\":\"A\",\"code\":\"1\"}");
    body.remove("timestampPath");

    JsonNode result = jsonBody(test(body));

    assertFalse(result.has("timestamp"));
    assertEquals("A", result.get("transactionId").get("value").asText());
  }

  @Test
  void aMissingFieldOnlyFailsThatField() {
    JsonNode body = jsonBody(test(request("ISO_8601", null, "{\"transId\":\"A\"}")));

    assertEquals("NOT_FOUND", body.get("status").get("errorCode").asText());
    assertEquals("A", body.get("transactionId").get("value").asText());
  }

  @Test
  void aNonObjectRootFailsAllThreeFields() {
    JsonNode body = jsonBody(test(request("ISO_8601", null, "[1,2,3]")));

    assertTrue(body.get("sampleJsonValid").asBoolean());
    for (String field : new String[] {"transactionId", "status", "timestamp"}) {
      assertEquals("PAYLOAD_NOT_OBJECT", body.get(field).get("errorCode").asText(), field);
    }
  }

  @Test
  void aBrokenSampleIs200WithAnErrorThatNeverEchoesTheContent() {
    for (String sample :
        new String[] {
          "{\"secret\":\"TOPSECRETVALUE\"",
          "TOPSECRETVALUE",
          "{\"a\":1} TOPSECRETVALUE",
          "{'a':'TOPSECRETVALUE'}"
        }) {
      HttpResponse<String> response = test(request("ISO_8601", null, sample));

      assertEquals(200, response.statusCode(), sample);
      JsonNode body = jsonBody(response);
      assertFalse(body.get("sampleJsonValid").asBoolean(), sample);
      assertFalse(body.get("sampleJsonError").asText().isEmpty());
      assertFalse(response.body().contains("TOPSECRETVALUE"), response.body());
      assertFalse(body.has("transactionId"));
    }
  }

  @Test
  void anEmptyOrBlankSampleIsInvalidButNotAnError() {
    for (String sample : new String[] {"", "   "}) {
      JsonNode body = jsonBody(test(request("ISO_8601", null, sample)));

      assertFalse(body.get("sampleJsonValid").asBoolean());
    }
  }

  @Test
  void aSampleOverOneMebibyteIs400OnSampleJsonButExactlyOneMebibyteIsFine() {
    String exact = "{\"transId\":\"" + "x".repeat(1_048_576 - 14) + "\"}";
    assertEquals(1_048_576, exact.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);

    HttpResponse<String> ok = test(request("ISO_8601", null, exact));
    HttpResponse<String> tooBig = test(request("ISO_8601", null, exact + " "));

    assertEquals(200, ok.statusCode());
    assertTrue(jsonBody(ok).get("sampleJsonValid").asBoolean());
    assertEquals(400, tooBig.statusCode());
    assertTrue(fieldNames(tooBig).contains("sampleJson"));
  }

  @Test
  void configurationErrorsAreReportedTogetherAndNothingRuns() {
    ObjectNode body = json.createObjectNode();
    body.put("transactionIdPath", "transId");
    body.put("statusPath", "$.a[0]");
    body.put("timestampFormat", "PATTERN");
    body.put("timestampPattern", "yyyy-MM-dd");
    body.put("defaultTimeZone", "Mars/Phobos");

    HttpResponse<String> response = test(body);

    assertEquals(400, response.statusCode());
    assertEquals(
        "VALIDATION_FAILED", response.headers().firstValue("X-Simulator-Error").orElse(null));
    assertEquals(
        Set.of(
            "transactionIdPath", "statusPath", "timestampPattern", "defaultTimeZone", "sampleJson"),
        fieldNames(response));
  }

  @Test
  void requiredFieldsAreReportedWhenMissing() {
    HttpResponse<String> response = postJson("/api/mapping-tests", "{}");

    assertEquals(400, response.statusCode());
    assertEquals(
        Set.of("transactionIdPath", "statusPath", "timestampFormat", "sampleJson"),
        fieldNames(response));
  }

  @Test
  void wrongContentTypeIs415AndABrokenBodyIs400() {
    HttpResponse<String> wrongType =
        send(
            "POST", "/api/mapping-tests", "text/plain", request("ISO_8601", null, "{}").toString());
    HttpResponse<String> broken = send("POST", "/api/mapping-tests", JSON, "{oops");
    HttpResponse<String> wrongKind =
        send("POST", "/api/mapping-tests", JSON, "{\"sampleJson\":123}");

    assertEquals(415, wrongType.statusCode());
    assertEquals(400, broken.statusCode());
    assertEquals(400, wrongKind.statusCode());
  }

  @Test
  void itNeverWritesAnythingToTheDatabase() {
    test(request("ISO_8601", null, "{\"transId\":\"T\",\"code\":\"00\"}"));
    test(request("ISO_8601", null, "not json"));
    test(json.createObjectNode());

    for (String table :
        new String[] {"services", "service_config_versions", "callbacks", "transactions"}) {
      assertEquals(0, count(table), table);
    }
  }

  @Test
  void theResultEqualsWhatARealCallbackWithTheSameConfigurationProduces() {
    String[][] cases = {
      {"ISO_8601", null, "\"2026-10-19T10:00:00+07:00\""},
      {"UNIX_SECONDS", null, "1760850000.5"},
      {"UNIX_MILLIS", null, "1760850000000"},
      {"PATTERN", "dd/MM/yyyy HH:mm", "\"19/10/2026 10:00\""},
      {"PATTERN", "dd/MM/yyyy HH:mm", "\"19/13/2026 10:00\""}
    };
    int n = 0;
    for (String[] c : cases) {
      String code = "EQ" + n++;
      ObjectNode service = json.createObjectNode();
      service.put("serviceCode", code);
      service.put("name", "n");
      service.put("authType", "NONE");
      service.put("transactionIdPath", "$.transId");
      service.put("statusPath", "$.code");
      service.put("timestampPath", "$.time");
      service.put("timestampFormat", c[0]);
      if (c[1] != null) {
        service.put("timestampPattern", c[1]);
      }
      service.put("responseHttpStatus", 200);
      service.set("responseBody", json.createObjectNode().put("ok", true));
      assertEquals(201, postJson("/api/services", service.toString()).statusCode());
      String sample = "{\"transId\":\"T1\",\"code\":\"00\",\"time\":" + c[2] + "}";

      JsonNode tested = jsonBody(test(request(c[0], c[1], sample)));
      assertEquals(200, callback(code, sample).statusCode());
      JsonNode stored =
          jsonBody(
                  get(
                      "/api/callbacks/"
                          + jdbc.queryForObject(
                              "SELECT max(record_id) FROM callbacks WHERE service_code = ?",
                              Long.class,
                              code)))
              .get("mappingFieldResults");

      assertEquals(stored.get("transactionId"), tested.get("transactionId"), c[0]);
      assertEquals(stored.get("status"), tested.get("status"), c[0]);
      assertEquals(stored.get("timestamp"), tested.get("timestamp"), c[0] + c[2]);
    }
  }
}
