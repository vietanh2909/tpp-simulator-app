package com.tpp.simulator.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.tpp.simulator.support.AbstractIntegrationTest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * W2 qua HTTP thật: AC1.1.1 đến AC1.1.4, thứ tự kiểm tra và mã từ chối (không ghi gì), response đã
 * cấu hình không bao giờ có X-Simulator-Error, che header, ghi nguyên tử, log an toàn, AC1.6.x đến
 * cơ sở dữ liệu.
 */
@ExtendWith(OutputCaptureExtension.class)
class CallbackIngestionTest extends AbstractIntegrationTest {

  private static final Pattern LOG_PREFIX =
      Pattern.compile(
          "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z (INFO|WARN|ERROR)\\s+com\\.tpp\\.simulator\\..*");

  private static final String SAMPLE = payload("T1", "00");

  private static String errorHeader(HttpResponse<String> response) {
    return response.headers().firstValue("X-Simulator-Error").orElse(null);
  }

  private void assertRejectedWithoutStoring(
      HttpResponse<String> response, int status, String code) {
    assertRejection(response, status, code);
    assertEquals(0, count("callbacks"), "mã dịch vụ chưa từng có thì không ghi gì");
    assertEquals(0, count("transactions"));
  }

  /** Từ U2, lần nhận bị từ chối của dịch vụ có thật được ghi thành đúng một Callback REJECTED. */
  private void assertRejectedAndRecorded(HttpResponse<String> response, int status, String code) {
    assertRejection(response, status, code);
    assertEquals(1, count("callbacks"));
    assertEquals("REJECTED", jdbc.queryForObject("SELECT outcome FROM callbacks", String.class));
    assertEquals(0, count("transactions"));
  }

  private void assertRejection(HttpResponse<String> response, int status, String code) {
    assertEquals(status, response.statusCode(), response.body());
    assertEquals(code, errorHeader(response));
    assertEquals(code, jsonBody(response).get("error").asText());
    assertNotNull(jsonBody(response).get("message"));
  }

  // ---------- AC1.1.x ----------

  @Test
  void ac111_acceptedCallbackGetsTheConfiguredStatusAndBodyWithoutErrorHeader() {
    createService("THU_HO");

    HttpResponse<String> response = callback("THU_HO", SAMPLE);

    assertEquals(201, response.statusCode());
    assertEquals("{\"ok\":true}", response.body());
    assertTrue(
        response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
    assertNull(errorHeader(response));
  }

  @Test
  void ac112_twoIdenticalCallsMakeTwoSeparateRecords() {
    createService("THU_HO");

    callback("THU_HO", SAMPLE);
    callback("THU_HO", SAMPLE);

    assertEquals(2, count("callbacks"));
    assertEquals(
        2, jdbc.queryForObject("SELECT count(DISTINCT record_id) FROM callbacks", Integer.class));
    assertEquals(1, count("transactions"));
    assertEquals(2, jdbc.queryForObject("SELECT callback_count FROM transactions", Integer.class));
  }

  @Test
  void ac113_storedRecordHasPayloadMaskedHeadersServerTimeMappingResultsAndResponse() {
    createService("THU_HO");
    clock.set(Instant.parse("2026-10-19T03:15:30.123456Z"));

    callback(
        "THU_HO",
        SAMPLE,
        "Authorization",
        "Bearer TOP-SECRET",
        "Cookie",
        "sid=COOKIE-SECRET",
        "Proxy-Authorization",
        "Basic PROXY-SECRET",
        "X-Request-Id",
        "req-42");

    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM callbacks");
    assertEquals("THU_HO", row.get("service_code"));
    assertEquals("SUCCESS", row.get("outcome"));
    assertEquals(201, row.get("http_status_returned"));
    assertEquals(201, row.get("response_status_returned"));
    assertEquals("T1", row.get("transaction_id"));
    assertEquals("00", row.get("status"));
    assertEquals(
        Instant.parse("2026-10-19T03:15:30.123456Z"),
        ((java.sql.Timestamp) row.get("received_at")).toInstant());
    assertEquals(
        Instant.parse("2026-10-19T03:00:00Z"),
        ((java.sql.Timestamp) row.get("business_timestamp_utc")).toInstant());
    assertEquals(
        jdbc.queryForObject("SELECT version_id FROM service_config_versions", Long.class),
        row.get("config_version_id"));
    assertEquals(
        "T1", jdbc.queryForObject("SELECT payload_json->>'transId' FROM callbacks", String.class));
    assertEquals(
        "true",
        jdbc.queryForObject("SELECT response_body_returned->>'ok' FROM callbacks", String.class));
    assertEquals(
        "T1",
        jdbc.queryForObject(
            "SELECT mapping_field_results->'transactionId'->>'value' FROM callbacks",
            String.class));
    assertNull(
        jdbc.queryForObject(
            "SELECT mapping_field_results->'status'->>'errorCode' FROM callbacks", String.class));

    String headers =
        jdbc.queryForObject("SELECT request_headers_masked::text FROM callbacks", String.class);
    assertFalse(headers.contains("TOP-SECRET"), headers);
    assertFalse(headers.contains("COOKIE-SECRET"), headers);
    assertFalse(headers.contains("PROXY-SECRET"), headers);
    assertTrue(headers.contains("req-42"), headers);
    // Tên header giữ nguyên như bên gửi (JDK HttpClient gửi chữ thường), nên tra cứu không phân
    // biệt hoa thường.
    // Proxy-Authorization không kiểm ở đây vì JDK HttpClient không gửi header này tới máy chủ không
    // qua proxy; việc che
    // header đó được kiểm ở MaskedHeadersTest và CallbackLedgerTest.
    for (String name : List.of("authorization", "cookie")) {
      assertEquals(
          "***",
          jdbc.queryForObject(
              "SELECT h.value FROM callbacks c, jsonb_each_text(c.request_headers_masked) h"
                  + " WHERE lower(h.key) = ?",
              String.class,
              name),
          name);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "application/json",
        "application/json; charset=UTF-8",
        "application/json;charset=utf-8",
        "APPLICATION/JSON",
        "Application/Json;Charset=UTF-8"
      })
  void ac114_contentTypeParametersAndCaseAreIgnored(String contentType) {
    createService("THU_HO");

    HttpResponse<String> response = send("POST", "/callbacks/THU_HO", contentType, SAMPLE);

    assertEquals(201, response.statusCode(), response.body());
    assertEquals(1, count("callbacks"));
  }

  @Test
  void serviceCodeInThePathIsMatchedIgnoringCaseAndStoredWithTheOriginalCode() {
    createService("THU_HO");

    assertEquals(201, callback("thu_ho", SAMPLE).statusCode());
    assertEquals(201, callback("Thu_Ho", SAMPLE).statusCode());

    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM callbacks WHERE service_code = 'THU_HO'", Integer.class));
    assertEquals(1, count("transactions"));
  }

  // ---------- Thứ tự kiểm tra và mã từ chối ----------

  @Test
  void aServiceCodeWithAnEncodedNulNeverBecomesAServerError() {
    insertServiceRow("THU_HO");

    // Qua HTTP, Tomcat từ chối %00 trong đường dẫn bằng 400 ngay từ container (đã đo): không bao
    // giờ tới PostgreSQL và không thành 500; không có gì được ghi.
    assertEquals(400, callback("a%00b", SAMPLE).statusCode());
    assertEquals(400, send("POST", "/callbacks/THU_HO%00", JSON, SAMPLE).statusCode());
    assertEquals(0, count("callbacks"));
  }

  @Test
  void unknownServiceIs404() {
    assertRejectedWithoutStoring(callback("NOPE", SAMPLE), 404, "SERVICE_NOT_FOUND");
  }

  @Test
  void deletedServiceIs404EvenWhenItIsAlsoDisabled() {
    createService("THU_HO");
    jdbc.update(
        "UPDATE services SET deleted = true, enabled = false WHERE service_code = 'THU_HO'");

    assertRejectedAndRecorded(callback("THU_HO", SAMPLE), 404, "SERVICE_NOT_FOUND");
  }

  @Test
  void disabledServiceIs503BeforeTheContentTypeIsChecked() {
    createServiceFromJson(serviceJson("OFF", "$.transId", "$.code", "$.time", 200, "{}", false));

    assertRejectedAndRecorded(
        send("POST", "/callbacks/OFF", "text/plain", "not json"), 503, "SERVICE_DISABLED");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "text/plain",
        "application/xml",
        "application/jsonx",
        "application/json-patch+json",
        "multipart/form-data",
        "garbage",
        "application/*"
      })
  void nonJsonContentTypeIs400(String contentType) {
    createService("THU_HO");

    assertRejectedAndRecorded(
        send("POST", "/callbacks/THU_HO", contentType, SAMPLE), 400, "UNSUPPORTED_CONTENT_TYPE");
  }

  @Test
  void missingContentTypeIs400() {
    createService("THU_HO");

    assertRejectedAndRecorded(
        send("POST", "/callbacks/THU_HO", null, SAMPLE), 400, "UNSUPPORTED_CONTENT_TYPE");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "{",
        "{\"a\":}",
        "not json",
        "{\"a\":1} {\"b\":2}",
        "{'a':1}",
        "[1,2",
        "NaN",
        "{\"a\":1}x"
      })
  void emptyOrBrokenJsonIs400(String body) {
    createService("THU_HO");

    assertRejectedAndRecorded(callback("THU_HO", body), 400, "INVALID_JSON");
  }

  @Test
  void invalidUtf8BodyIsInvalidJson() {
    createService("THU_HO");
    byte[] bytes = {'{', '"', 'a', '"', ':', '"', (byte) 0xC3, (byte) 0x28, '"', '}'};

    assertRejectedAndRecorded(
        sendBytes("POST", "/callbacks/THU_HO", JSON, bytes), 400, "INVALID_JSON");
  }

  @Test
  void wrongHttpMethodOnCallbackPathIs405WithErrorHeader() {
    createService("THU_HO");

    HttpResponse<String> response = get("/callbacks/THU_HO");

    assertEquals(405, response.statusCode());
    assertEquals("METHOD_NOT_ALLOWED", errorHeader(response));
  }

  // ---------- Response đã cấu hình ----------

  @ParameterizedTest
  @ValueSource(ints = {200, 201, 202, 400, 401, 404, 409, 500, 503, 599})
  void configuredResponseNeverCarriesTheErrorHeaderEvenWhenTheStatusLooksLikeAnError(int status) {
    createServiceFromJson(
        serviceJson(
            "CFG", "$.transId", "$.code", "$.time", status, "{\"reason\":\"configured\"}", null));

    HttpResponse<String> response = callback("CFG", SAMPLE);

    assertEquals(status, response.statusCode());
    assertEquals("{\"reason\":\"configured\"}", response.body());
    assertNull(errorHeader(response), "response đã cấu hình không được có X-Simulator-Error");
    assertEquals(1, count("callbacks"));
  }

  @Test
  void configuredBodyMayBeAnyJsonValueAndKeepsUnicode() {
    createServiceFromJson(
        serviceJson("UNI", "$.transId", "$.code", "$.time", 200, "\"Thành công\"", null));

    HttpResponse<String> response = callback("UNI", SAMPLE);

    assertEquals("\"Thành công\"", response.body());
    assertTrue(
        response.headers().firstValue("Content-Type").orElse("").toLowerCase().contains("utf-8"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"+999999999-12-31T23:59:59Z", "+10000-01-01T00:00:00Z", "-0001-01-01T00:00:00Z"})
  void timestampYearPostgresCannotStoreIsAFieldErrorNotA500(String timestamp) {
    createService("THU_HO");

    HttpResponse<String> response =
        callback("THU_HO", "{\"transId\":\"Y1\",\"code\":\"00\",\"time\":\"" + timestamp + "\"}");

    assertEquals(201, response.statusCode(), response.body());
    assertEquals("{\"ok\":true}", response.body());
    assertEquals(1, count("callbacks"));
    assertEquals(
        "MAPPING_ERROR", jdbc.queryForObject("SELECT outcome FROM callbacks", String.class));
    assertEquals(
        "UNPARSEABLE",
        jdbc.queryForObject(
            "SELECT mapping_field_results->'timestamp'->>'errorCode' FROM callbacks",
            String.class));
    assertEquals(
        Boolean.TRUE,
        jdbc.queryForObject("SELECT business_timestamp_utc IS NULL FROM callbacks", Boolean.class));
    assertEquals("Y1", jdbc.queryForObject("SELECT transaction_id FROM callbacks", String.class));
  }

  @Test
  void configuredBodyKeepsTheExactNumericTextForTheCaller() {
    String body = "{\"amount\":100000.00,\"rate\":1e3,\"x\":0.1000000000000000055511151231257827}";
    createServiceFromJson(serviceJson("NUM", "$.transId", "$.code", "$.time", 200, body, null));

    HttpResponse<String> response = callback("NUM", SAMPLE);

    assertEquals(200, response.statusCode(), response.body());
    assertEquals(body, response.body());
  }

  @Test
  void mappingErrorStillReturnsTheConfiguredResponseAndIsStoredWithoutATransaction() {
    createService("THU_HO");

    HttpResponse<String> response = callback("THU_HO", "{\"code\":\"00\"}");

    assertEquals(201, response.statusCode());
    assertEquals("{\"ok\":true}", response.body());
    assertNull(errorHeader(response));
    assertEquals(
        "MAPPING_ERROR", jdbc.queryForObject("SELECT outcome FROM callbacks", String.class));
    assertEquals(0, count("transactions"));
    assertEquals(
        "NOT_FOUND",
        jdbc.queryForObject(
            "SELECT mapping_field_results->'transactionId'->>'errorCode' FROM callbacks",
            String.class));
    assertEquals(
        "00",
        jdbc.queryForObject(
            "SELECT mapping_field_results->'status'->>'value' FROM callbacks", String.class));
  }

  // ---------- AC1.6.x đến cơ sở dữ liệu ----------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"code\":\"00\"}",
        "{\"transId\":null,\"code\":\"00\"}",
        "{\"transId\":\"\",\"code\":\"00\"}",
        "{\"transId\":{\"a\":1},\"code\":\"00\"}",
        "{\"transId\":[1],\"code\":\"00\"}"
      })
  void ac164_badTransactionIdIsStoredWithItsErrorCodeAndJoinsNoTransaction(String body) {
    createService("THU_HO");

    assertEquals(201, callback("THU_HO", body).statusCode());

    assertEquals(1, count("callbacks"));
    assertEquals(0, count("transactions"));
    assertNull(jdbc.queryForObject("SELECT transaction_id FROM callbacks", String.class));
    assertNotNull(
        jdbc.queryForObject(
            "SELECT mapping_field_results->'transactionId'->>'errorCode' FROM callbacks",
            String.class));
    assertEquals(
        "00",
        jdbc.queryForObject(
            "SELECT mapping_field_results->'status'->>'value' FROM callbacks", String.class));
  }

  @ParameterizedTest
  @ValueSource(strings = {"[1,2]", "5", "\"text\"", "null", "true"})
  void ac165_nonObjectPayloadsAreAcceptedAndAllThreeFieldsFail(String body) {
    createService("THU_HO");

    HttpResponse<String> response = callback("THU_HO", body);

    assertEquals(201, response.statusCode(), response.body());
    assertEquals(
        "PAYLOAD_NOT_OBJECT",
        jdbc.queryForObject(
            "SELECT mapping_field_results->'transactionId'->>'errorCode' FROM callbacks",
            String.class));
    assertEquals(
        "PAYLOAD_NOT_OBJECT",
        jdbc.queryForObject(
            "SELECT mapping_field_results->'status'->>'errorCode' FROM callbacks", String.class));
    assertEquals(
        "PAYLOAD_NOT_OBJECT",
        jdbc.queryForObject(
            "SELECT mapping_field_results->'timestamp'->>'errorCode' FROM callbacks",
            String.class));
    assertEquals(0, count("transactions"));
  }

  @Test
  void ac163_numbersAndBooleansAreStoredVerbatimAsStatus() {
    createService("THU_HO");

    callback("THU_HO", "{\"transId\":\"A\",\"code\":0,\"time\":\"2026-10-19T10:00:00Z\"}");
    callback("THU_HO", "{\"transId\":\"B\",\"code\":true,\"time\":\"2026-10-19T10:00:00Z\"}");
    callback("THU_HO", "{\"transId\":\"C\",\"code\":1.50,\"time\":\"2026-10-19T10:00:00Z\"}");
    callback("THU_HO", "{\"transId\":\"D\",\"code\":1e3,\"time\":\"2026-10-19T10:00:00Z\"}");
    callback(
        "THU_HO",
        "{\"transId\":\"E\",\"code\":123456789012345678901234567890,\"time\":\"2026-10-19T10:00:00Z\"}");

    List<String> statuses =
        jdbc.queryForList(
            "SELECT latest_status FROM transactions ORDER BY transaction_id", String.class);
    assertEquals(List.of("0", "true", "1.50", "1e3", "123456789012345678901234567890"), statuses);
  }

  @Test
  void aServiceWithADifferentJsonShapeNeedsNoCodeChange() {
    createService("THU_HO");
    createServiceFromJson(
        serviceJson("NESTED", "$.data.transId", "$.data.state.code", null, 200, "{}", null));

    callback("THU_HO", payload("T1", "00"));
    callback("NESTED", "{\"data\":{\"transId\":\"N1\",\"state\":{\"code\":\"OK\"}}}");

    assertEquals(
        "N1",
        jdbc.queryForObject(
            "SELECT transaction_id FROM transactions WHERE service_code = 'NESTED'", String.class));
    assertEquals(
        "OK",
        jdbc.queryForObject(
            "SELECT latest_status FROM transactions WHERE service_code = 'NESTED'", String.class));
    assertEquals(2, count("transactions"));
  }

  @Test
  void newestCallbackWinsByReceivedAtEvenWhenItsBusinessTimestampIsOlder() {
    createService("THU_HO");
    clock.set(Instant.parse("2026-10-19T03:00:00Z"));
    callback("THU_HO", "{\"transId\":\"T1\",\"code\":\"A\",\"time\":\"2026-10-19T12:00:00Z\"}");
    clock.set(Instant.parse("2026-10-19T03:00:05Z"));
    callback("THU_HO", "{\"transId\":\"T1\",\"code\":\"B\",\"time\":\"2026-10-19T01:00:00Z\"}");

    assertEquals("B", jdbc.queryForObject("SELECT latest_status FROM transactions", String.class));
    assertEquals(2, jdbc.queryForObject("SELECT callback_count FROM transactions", Integer.class));
  }

  // ---------- Lỗi ghi ----------

  @Test
  void writeFailureReturns500DoesNotStoreAnythingAndLogsOnlyExceptionClassAndSqlState(
      CapturedOutput output) {
    createService("THU_HO");
    jdbc.execute(
        "CREATE OR REPLACE FUNCTION tpp_test_fail() RETURNS trigger AS $$ BEGIN"
            + " RAISE EXCEPTION 'boom-raw-message'; END; $$ LANGUAGE plpgsql");
    jdbc.execute(
        "CREATE TRIGGER tpp_test_fail_trg BEFORE INSERT ON transactions"
            + " FOR EACH ROW EXECUTE FUNCTION tpp_test_fail()");
    HttpResponse<String> response;
    try {
      response =
          callback(
              "THU_HO",
              "{\"transId\":\"T\\n2026-10-19T00:00:00.000Z INFO FAKE2\",\"code\":\"00\",\"marker\":\"PAYLOADMARKER\"}",
              "Authorization",
              "Bearer SECRET-TOKEN");
    } finally {
      jdbc.execute("DROP TRIGGER IF EXISTS tpp_test_fail_trg ON transactions");
    }

    assertEquals(500, response.statusCode());
    assertEquals("INTERNAL_ERROR", errorHeader(response));
    assertEquals("INTERNAL_ERROR", jsonBody(response).get("error").asText());
    assertFalse(
        response.body().contains("{\"ok\":true}"),
        "không được trả response đã cấu hình khi ghi lỗi");
    assertEquals(0, count("callbacks"));
    assertEquals(0, count("transactions"));

    String log = output.getAll();
    assertTrue(log.contains("sqlState=P0001"), log);
    assertTrue(log.contains("exception="), log);
    assertFalse(log.contains("boom-raw-message"), "không log thông điệp thô của ngoại lệ");
    assertFalse(log.contains("PAYLOADMARKER"), "không log payload");
    assertFalse(log.contains("SECRET-TOKEN"), "không log header");
    assertFalse(log.lines().anyMatch(l -> l.startsWith("2026-10-19T00:00:00.000Z INFO FAKE2")));
    assertFalse(log.lines().anyMatch(l -> l.contains("FAKE2")));
  }

  @Test
  void jsonThatJacksonAcceptsButJsonbRejectsIs500WithASafeLog(CapturedOutput output) {
    createService("THU_HO");

    HttpResponse<String> response =
        callback("THU_HO", "{\"transId\":\"T1\",\"code\":\"00\",\"bad\":\"a\\u0000b-NULMARKER\"}");

    assertEquals(500, response.statusCode());
    assertEquals("INTERNAL_ERROR", errorHeader(response));
    assertEquals(0, count("callbacks"));
    assertEquals(0, count("transactions"));
    assertFalse(output.getAll().contains("NULMARKER"));
    assertTrue(output.getAll().contains("sqlState="));
  }

  // ---------- Log ----------

  @Test
  void acceptedCallbackLogsOneInfoLineWithServiceRecordOutcomeAndStatusAndNoSensitiveData(
      CapturedOutput output) {
    createService("THU_HO");

    callback(
        "THU_HO",
        "{\"transId\":\"T1\",\"code\":\"00\",\"secretField\":\"PAYLOADMARKER\"}",
        "Authorization",
        "Bearer TOKENVALUE",
        "Cookie",
        "sid=COOKIEVALUE");

    String log = output.getAll();
    List<String> accepted = log.lines().filter(l -> l.contains("Callback request:")).toList();
    assertEquals(1, accepted.size(), log);
    assertTrue(accepted.get(0).contains("service=THU_HO"), accepted.get(0));
    assertTrue(accepted.get(0).contains("recordId=1"), accepted.get(0));
    assertTrue(accepted.get(0).contains("outcome=SUCCESS"), accepted.get(0));
    assertTrue(accepted.get(0).contains("httpStatus=201"), accepted.get(0));
    assertTrue(LOG_PREFIX.matcher(accepted.get(0)).matches(), accepted.get(0));
    assertFalse(log.contains("TOKENVALUE"));
    assertFalse(log.contains("COOKIEVALUE"));
    assertFalse(log.contains("PAYLOADMARKER"));
  }

  @Test
  void rejectionLogsOneInfoLineWithTheErrorCode(CapturedOutput output) {
    createService("THU_HO");

    callback("THU_HO", "not json");

    List<String> rejected =
        output.getAll().lines().filter(l -> l.contains("Callback request:")).toList();
    assertEquals(1, rejected.size(), output.getAll());
    assertTrue(rejected.get(0).contains("rejectionReason=INVALID_JSON"), rejected.get(0));
    assertTrue(rejected.get(0).contains("recordId=1"), rejected.get(0));
    assertTrue(rejected.get(0).contains("httpStatus=400"), rejected.get(0));
    assertTrue(rejected.get(0).contains("service=THU_HO"), rejected.get(0));
  }

  @Test
  void aServiceCodeWithLineBreaksCannotForgeLogLines(CapturedOutput output) {
    String path = "THU_HO%0D%0A2026-10-19T00:00:00.000Z%20INFO%20FAKE%20LINE%20injected";

    HttpResponse<String> response = postJson("/callbacks/" + path, SAMPLE);

    assertTrue(response.statusCode() == 404 || response.statusCode() == 400, response.body());
    List<String> lines = output.getAll().lines().toList();
    assertFalse(
        lines.stream().anyMatch(l -> l.startsWith("2026-10-19T00:00:00.000Z")), output.getAll());
    for (String line : lines) {
      if (line.contains("FAKE")) {
        assertTrue(
            LOG_PREFIX.matcher(line).matches(),
            "Dòng chứa giá trị bên gọi phải bắt đầu bằng tiền tố log thật: " + line);
        assertTrue(line.length() < 400, line);
      }
    }
  }

  @Test
  void veryLongServiceCodeIsTruncatedInTheLog(CapturedOutput output) {
    String longCode = "L".repeat(300);

    callback(longCode, SAMPLE);

    assertFalse(output.getAll().contains("L".repeat(150)));
    assertTrue(output.getAll().contains("service=" + "L".repeat(100)));
  }

  @Test
  void responseBodyOfTheRejectionNeverEchoesTheRawServiceCode() {
    HttpResponse<String> response = callback("EVIL%3Cscript%3E", SAMPLE);

    assertEquals(404, response.statusCode());
    assertFalse(response.body().contains("script"), response.body());
    JsonNode body = jsonBody(response);
    assertFalse(body.has("fieldErrors"));
  }
}
