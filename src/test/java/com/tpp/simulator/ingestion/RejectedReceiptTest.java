package com.tpp.simulator.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.tpp.simulator.support.AbstractIntegrationTest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** BR15.2 đến BR15.5: hình dạng bản ghi lần nhận bị từ chối và hình thức lưu payload. */
class RejectedReceiptTest extends AbstractIntegrationTest {

  @BeforeEach
  void service() {
    createService("OPEN");
    jdbc.update("UPDATE services SET enabled = false WHERE service_code = 'OPEN'");
  }

  private Map<String, Object> lastRow() {
    return jdbc.queryForMap("SELECT * FROM callbacks ORDER BY record_id DESC LIMIT 1");
  }

  private String payloadJsonText() {
    return jdbc.queryForObject(
        "SELECT payload_json::text FROM callbacks ORDER BY record_id DESC LIMIT 1", String.class);
  }

  private String rawText() {
    return jdbc.queryForObject(
        "SELECT payload_raw_text FROM callbacks ORDER BY record_id DESC LIMIT 1", String.class);
  }

  @Test
  void aRejectedReceiptHasTheFixedShape() {
    clock.set(java.time.Instant.parse("2026-10-19T03:00:00.250000Z"));

    HttpResponse<String> response =
        callback("OPEN", payload("TX-1", "00"), "X-Request-Id", "req-9");

    assertEquals(503, response.statusCode());
    Map<String, Object> row = lastRow();
    assertEquals("REJECTED", row.get("outcome"));
    assertEquals("SERVICE_DISABLED", row.get("rejection_reason"));
    assertEquals(503, row.get("http_status_returned"));
    assertEquals(503, row.get("response_status_returned"));
    assertNull(row.get("transaction_id"));
    assertNull(row.get("status"));
    assertNull(row.get("business_timestamp_utc"));
    assertEquals(
        "{}",
        jdbc.queryForObject("SELECT mapping_field_results::text FROM callbacks", String.class));
    assertEquals(
        "SERVICE_DISABLED",
        jsonText("SELECT response_body_returned::text FROM callbacks").get("error").asText());
    assertEquals(0, count("transactions"));
  }

  private JsonNode jsonText(String sql) {
    try {
      return json.readTree(jdbc.queryForObject(sql, String.class));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void validJsonIsStoredAsPayloadJsonEvenWhenTheContentTypeWasWrong() {
    send("POST", "/callbacks/OPEN", "text/plain", "{\"transId\": \"X\",  \"n\": 1e3}");

    assertEquals("{\"n\": 1000, \"transId\": \"X\"}", payloadJsonText());
    assertNull(rawText());
  }

  @Test
  void aJsonNullBodyIsStoredAsJsonNullNotAbsent() {
    callback("OPEN", "null");

    assertEquals("null", payloadJsonText());
    assertNull(rawText());
    assertEquals(
        false,
        jdbc.queryForObject(
            "SELECT payload_json IS NULL FROM callbacks ORDER BY record_id DESC LIMIT 1",
            Boolean.class));
  }

  @Test
  void aByteOrderMarkBeforeValidJsonStillCountsAsJson() {
    sendBytes("POST", "/callbacks/OPEN", JSON, "\uFEFF{\"a\":1}".getBytes(StandardCharsets.UTF_8));

    assertEquals("{\"a\": 1}", payloadJsonText());
    assertNull(rawText());
  }

  @Test
  void brokenJsonIsKeptAsRawTextExactly() {
    callback("OPEN", "{\"a\": [1, 2,}");

    assertNull(payloadJsonText());
    assertEquals("{\"a\": [1, 2,}", rawText());
  }

  @Test
  void anEmptyBodyIsAnEmptyRawString() {
    callback("OPEN", "");

    assertNull(payloadJsonText());
    assertEquals("", rawText());
  }

  @Test
  void jsonWithAnEscapedNulOrALoneSurrogateIsKeptAsRawText() {
    for (String body :
        new String[] {"{\"a\":\"x\\u0000y\"}", "{\"a\":\"\\uD83D\"}", "{\"\\u0000\":1}"}) {
      callback("OPEN", body);

      assertNull(payloadJsonText(), body);
      assertEquals(body, rawText());
    }
    assertEquals(3, count("callbacks"));
  }

  @Test
  void aNumberBeyondTheNumericDomainIsKeptAsRawText() {
    callback("OPEN", "{\"n\":1e200000}");

    assertNull(payloadJsonText());
    assertEquals("{\"n\":1e200000}", rawText());
  }

  @Test
  void theLargestNumberTheStorageAcceptsStaysJson() {
    callback("OPEN", "{\"n\":1e100000}");

    assertEquals(1, count("callbacks"));
    assertEquals("{\"n\": 1" + "0".repeat(100000) + "}", payloadJsonText());
    assertNull(rawText());
  }

  @Test
  void aBodyOfExactlyOneMebibyteIsStoredWhole() {
    String body = "{\"pad\":\"" + "x".repeat(1_048_576 - 10) + "\"}";
    assertEquals(1_048_576, body.getBytes(StandardCharsets.UTF_8).length);

    HttpResponse<String> response = callback("OPEN", body);

    assertEquals(503, response.statusCode());
    assertEquals(
        1_048_576 - 10,
        jdbc.queryForObject("SELECT length(payload_json->>'pad') FROM callbacks", Integer.class));
  }

  @Test
  void unicodeInRawTextSurvivesAndBadBytesBecomeReplacementCharacters() {
    callback("OPEN", "xin chào {ế}");
    assertEquals("xin chào {ế}", rawText());

    sendBytes("POST", "/callbacks/OPEN", JSON, new byte[] {'a', (byte) 0xFF, 'b'});
    assertEquals("a\uFFFDb", rawText());
  }

  @Test
  void theDetailApiShowsExactlyOnePayloadForm() {
    callback("OPEN", "{\"a\":1}");
    long jsonId = ((Number) lastRow().get("record_id")).longValue();
    callback("OPEN", "not json");
    long rawId = ((Number) lastRow().get("record_id")).longValue();
    callback("OPEN", "null");
    long nullId = ((Number) lastRow().get("record_id")).longValue();
    callback("OPEN", "");
    long emptyId = ((Number) lastRow().get("record_id")).longValue();

    JsonNode asJson = jsonBody(get("/api/callbacks/" + jsonId));
    JsonNode asRaw = jsonBody(get("/api/callbacks/" + rawId));
    JsonNode asNull = jsonBody(get("/api/callbacks/" + nullId));
    JsonNode asEmpty = jsonBody(get("/api/callbacks/" + emptyId));

    assertEquals(1, asJson.get("payloadJson").get("a").asInt());
    assertFalse(asJson.has("payloadRawText"));
    assertFalse(asRaw.has("payloadJson"));
    assertEquals("not json", asRaw.get("payloadRawText").asText());
    assertTrue(asNull.has("payloadJson"));
    assertTrue(asNull.get("payloadJson").isNull());
    assertFalse(asNull.has("payloadRawText"));
    assertFalse(asEmpty.has("payloadJson"));
    assertEquals("", asEmpty.get("payloadRawText").asText());
    assertEquals("REJECTED", asJson.get("outcome").asText());
    assertEquals("SERVICE_DISABLED", asJson.get("rejectionReason").asText());
    assertEquals(1, asJson.get("configVersionNumber").asInt());
    assertEquals(0, asJson.get("mappingFieldResults").size());
    assertEquals(503, asJson.get("responseStatusReturned").asInt());
    assertEquals("SERVICE_DISABLED", asJson.get("responseBodyReturned").get("error").asText());
  }
}
