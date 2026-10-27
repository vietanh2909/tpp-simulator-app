package com.tpp.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tpp.simulator.support.AbstractIntegrationTest;
import com.tpp.simulator.support.FailureTrigger;
import com.tpp.simulator.support.LogCapture;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * NFR3.21, NFR3.22, SD-10, BR16.2: một bài quét dùng chung cho đường thường và đường lỗi ghi. Hai
 * nhóm giá trị mẫu duy nhất: NHÓM BÍ MẬT (khóa API đúng và sai, giá trị Authorization, Cookie)
 * không được xuất hiện ở đâu ngoài cột khóa của Service; NHÓM PAYLOAD (nội dung body, được lưu và
 * trả lại theo thiết kế) chỉ bị quét ở nhật ký và phản hồi của endpoint callback.
 */
class SecretLeakScanTest extends AbstractIntegrationTest {

  private final String goodKey = "GOODKEY-" + UUID.randomUUID();
  private final String wrongKey = "WRONGKEY-" + UUID.randomUUID();
  private final String bearer = "Bearer BEARER-" + UUID.randomUUID();
  private final String cookie = "sid=COOKIE-" + UUID.randomUUID();
  private final String marker = "PAYLOADMARKER-" + UUID.randomUUID();

  private final List<HttpResponse<String>> callbackResponses = new ArrayList<>();
  private final List<HttpResponse<String>> apiResponses = new ArrayList<>();

  private List<String> secrets() {
    return List.of(goodKey, wrongKey, bearer, cookie, bearer.substring(7), cookie.substring(4));
  }

  private String body(String id) {
    return "{\"transId\":\""
        + id
        + "\",\"code\":\"00\",\"time\":\"2026-10-19T10:00:00+07:00\","
        + "\"note\":\""
        + marker
        + "\"}";
  }

  private String keyedService() {
    return serviceJson("KEYED")
        .replace("\"authType\":\"NONE\"", "\"authType\":\"API_KEY\"")
        .replace(
            "{\"serviceCode\"",
            "{\"apiKeyHeaderName\":\"X-Api-Key\",\"apiKeySecret\":\""
                + goodKey
                + "\",\"serviceCode\"");
  }

  private void sendCallback(String contentType, String payload, String... headers) {
    callbackResponses.add(send("POST", "/callbacks/KEYED", contentType, payload, headers));
  }

  private void exerciseEveryKindOfReceipt() {
    apiResponses.add(postJson("/api/services", keyedService()));
    String[] standard = {"Authorization", bearer, "Cookie", cookie};
    String[] withGood = {"Authorization", bearer, "Cookie", cookie, "X-Api-Key", goodKey};
    String[] withWrong = {"Authorization", bearer, "Cookie", cookie, "X-Api-Key", wrongKey};
    sendCallback(JSON, body("T1"), withGood); // SUCCESS
    sendCallback(JSON, "{\"x\":1,\"note\":\"" + marker + "\"}", withGood); // MAPPING_ERROR
    sendCallback(JSON, body("T2"), withWrong); // INVALID_API_KEY
    sendCallback(JSON, body("T3"), standard); // MISSING_API_KEY
    sendCallback("text/plain", body("T4"), withGood); // UNSUPPORTED_CONTENT_TYPE
    sendCallback(JSON, "{bad " + marker, withGood); // INVALID_JSON
    jdbc.update("UPDATE services SET enabled = false");
    sendCallback(JSON, body("T5"), withWrong); // SERVICE_DISABLED
    jdbc.update("UPDATE services SET enabled = true, deleted = true");
    sendCallback(JSON, body("T6"), withWrong); // SERVICE_DELETED
    jdbc.update("UPDATE services SET deleted = false");
    apiResponses.add(get("/api/services"));
    apiResponses.add(get("/api/services/KEYED"));
    apiResponses.add(get("/api/services?includeDeleted=true"));
    apiResponses.add(get("/api/callbacks?size=100"));
    apiResponses.add(get("/api/transactions"));
    apiResponses.add(get("/api/transactions/detail?serviceCode=KEYED&transactionId=T1"));
    for (long id : jdbc.queryForList("SELECT record_id FROM callbacks", Long.class)) {
      apiResponses.add(get("/api/callbacks/" + id));
    }
    String update =
        "{\"name\":\"n\",\"authType\":\"API_KEY\",\"apiKeyHeaderName\":\"X-Api-Key\","
            + "\"apiKeySecret\":\""
            + goodKey
            + "\",\"transactionIdPath\":\"$.transId\","
            + "\"statusPath\":\"$.code\",\"timestampFormat\":\"ISO_8601\",\"responseHttpStatus\":201,"
            + "\"responseBody\":{\"ok\":true}}";
    apiResponses.add(send("PUT", "/api/services/KEYED", JSON, update));
    apiResponses.add(send("PUT", "/api/services/KEYED/enabled", JSON, "{\"enabled\":true}"));
  }

  private static String flatten(List<HttpResponse<String>> responses) {
    StringBuilder text = new StringBuilder();
    for (HttpResponse<String> response : responses) {
      text.append(response.headers().map()).append('\n').append(response.body()).append('\n');
    }
    return text.toString();
  }

  /** Mọi bảng, trừ cột khóa của Service. */
  private String databaseText() {
    StringBuilder text = new StringBuilder();
    text.append(
        jdbc.queryForList(
            "SELECT service_code, name, description, enabled, deleted, auth_type, api_key_header_name,"
                + " current_version_number FROM services"));
    text.append(jdbc.queryForList("SELECT * FROM service_config_versions"));
    text.append(jdbc.queryForList("SELECT * FROM transactions"));
    text.append(
        jdbc.queryForList(
            "SELECT record_id, service_code, outcome, rejection_reason, request_headers_masked::text,"
                + " transaction_id, status, mapping_field_results::text,"
                + " response_body_returned::text FROM callbacks"));
    return text.toString();
  }

  @Test
  void secretsAppearNowhereButTheServiceKeyColumn() {
    try (LogCapture logs = LogCapture.root()) {
      exerciseEveryKindOfReceipt();

      String everything =
          flatten(callbackResponses) + flatten(apiResponses) + databaseText() + logs.allText();
      for (String secret : secrets()) {
        assertFalse(everything.contains(secret), "Lộ giá trị bí mật: " + secret);
      }
    }
    assertEquals(goodKey, jdbc.queryForObject("SELECT api_key_secret FROM services", String.class));
  }

  @Test
  void theMaskedHeadersAreStoredForEveryOutcome() {
    exerciseEveryKindOfReceipt();

    List<String> stored =
        jdbc.queryForList("SELECT request_headers_masked::text FROM callbacks", String.class);
    assertEquals(8, stored.size());
    for (String headers : stored) {
      assertTrue(headers.contains("\"***\""), headers);
    }
  }

  @Test
  void payloadContentNeverReachesTheLogsOrTheCallbackEndpointResponses() {
    try (LogCapture logs = LogCapture.root()) {
      exerciseEveryKindOfReceipt();

      assertFalse(logs.allText().contains(marker), logs.allText());
      assertFalse(flatten(callbackResponses).contains(marker));
    }
  }

  @Test
  void theSameScanHoldsWhenWritesFail() {
    apiResponses.add(postJson("/api/services", keyedService()));
    String[] withWrong = {"Authorization", bearer, "Cookie", cookie, "X-Api-Key", wrongKey};
    String[] withGood = {"Authorization", bearer, "Cookie", cookie, "X-Api-Key", goodKey};

    try (FailureTrigger trigger = FailureTrigger.on("callbacks", "INSERT", "KEYED");
        LogCapture logs = LogCapture.root()) {
      sendCallback(JSON, body("T1"), withGood); // 500
      sendCallback(JSON, body("T2"), withWrong); // 401 + X-Simulator-Record-Failed
      String update =
          "{\"name\":\"n\",\"authType\":\"API_KEY\",\"apiKeyHeaderName\":\"X-Api-Key\","
              + "\"apiKeySecret\":\""
              + wrongKey
              + "\",\"transactionIdPath\":\"$.transId\","
              + "\"statusPath\":\"$.code\",\"timestampFormat\":\"ISO_8601\",\"responseHttpStatus\":201,"
              + "\"responseBody\":{\"ok\":true}}";
      try (FailureTrigger onServices = FailureTrigger.on("services", "UPDATE", "KEYED")) {
        apiResponses.add(send("PUT", "/api/services/KEYED", JSON, update));
      }

      String everything = flatten(callbackResponses) + flatten(apiResponses) + logs.allText();
      assertFalse(everything.contains(wrongKey), "wrongKey lộ ra");
      assertFalse(everything.contains(goodKey), "goodKey lộ ra");
      assertFalse(everything.contains(bearer));
      assertFalse(everything.contains(cookie));
      assertFalse(everything.contains(marker));
      assertFalse(everything.contains("test failure"));
      assertEquals(500, callbackResponses.get(0).statusCode());
      assertEquals(401, callbackResponses.get(1).statusCode());
      assertEquals(
          "true",
          callbackResponses.get(1).headers().firstValue("X-Simulator-Record-Failed").orElse(null));
    }
  }
}
