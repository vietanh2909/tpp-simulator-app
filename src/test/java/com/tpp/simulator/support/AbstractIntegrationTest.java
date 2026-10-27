package com.tpp.simulator.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Lớp cơ sở của kiểm thử tích hợp: ứng dụng thật trên cổng ngẫu nhiên, PostgreSQL thật dùng chung
 * ({@link TestDatabase}), đồng hồ thay thế được ({@link TestClock}) và dọn dữ liệu trước mỗi kiểm
 * thử. Mọi lớp con dùng chung một ngữ cảnh Spring nên khởi động một lần.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestClockConfig.class)
public abstract class AbstractIntegrationTest {

  protected static final String JSON = "application/json";

  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  @LocalServerPort protected int port;

  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected ObjectMapper json;
  @Autowired protected TestClock clock;

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> TestDatabase.URL);
    registry.add("spring.datasource.username", () -> TestDatabase.USERNAME);
    registry.add("spring.datasource.password", () -> TestDatabase.PASSWORD);
  }

  /** Quét trigger ép lỗi còn sót trước và sau mỗi lớp kiểm thử (RD-04). */
  @BeforeAll
  static void sweepFailureTriggersBefore() {
    FailureTrigger.sweep();
  }

  @AfterAll
  static void sweepFailureTriggersAfter() {
    FailureTrigger.sweep();
  }

  @BeforeEach
  void cleanDatabaseAndClock() {
    FailureTrigger.sweep();
    jdbc.execute(
        "TRUNCATE transactions, callbacks, service_config_versions, services RESTART IDENTITY CASCADE");
    clock.reset();
  }

  // ---------- HTTP helpers ----------

  protected String url(String pathAndQuery) {
    return "http://127.0.0.1:" + port + pathAndQuery;
  }

  /** Gửi yêu cầu; {@code headers} là các cặp tên, giá trị liên tiếp. */
  protected HttpResponse<String> send(
      String method, String pathAndQuery, String contentType, String body, String... headers) {
    return sendBytes(
        method,
        pathAndQuery,
        contentType,
        body == null ? null : body.getBytes(StandardCharsets.UTF_8),
        headers);
  }

  protected HttpResponse<String> sendBytes(
      String method, String pathAndQuery, String contentType, byte[] body, String... headers) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(url(pathAndQuery))).timeout(Duration.ofSeconds(30));
    if (contentType != null) {
      builder.header("Content-Type", contentType);
    }
    for (int i = 0; i + 1 < headers.length; i += 2) {
      builder.header(headers[i], headers[i + 1]);
    }
    builder.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(body));
    try {
      return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  protected HttpResponse<String> get(String pathAndQuery, String... headers) {
    return send("GET", pathAndQuery, null, null, headers);
  }

  protected HttpResponse<String> postJson(String pathAndQuery, String body, String... headers) {
    return send("POST", pathAndQuery, JSON, body, headers);
  }

  protected JsonNode jsonBody(HttpResponse<String> response) {
    try {
      return json.readTree(response.body());
    } catch (IOException e) {
      throw new IllegalStateException("Response body is not JSON: " + response.body(), e);
    }
  }

  /**
   * Giá trị của header theo tên không phân biệt hoa thường (JDK HttpClient gửi tên header chữ
   * thường).
   */
  protected static String headerIgnoreCase(JsonNode headers, String name) {
    for (java.util.Iterator<String> it = headers.fieldNames(); it.hasNext(); ) {
      String key = it.next();
      if (key.equalsIgnoreCase(name)) {
        return headers.get(key).asText();
      }
    }
    return null;
  }

  protected static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  // ---------- Dữ liệu mẫu ----------

  /**
   * Chèn thẳng một dịch vụ cùng phiên bản cấu hình số 1 ($.transId, $.code, $.time); trả versionId.
   */
  protected long insertServiceRow(String code) {
    jdbc.update(
        "INSERT INTO services (service_code, name, enabled, deleted, auth_type, current_version_number,"
            + " created_at, updated_at) VALUES (?, 'n', true, false, 'NONE', 1, now(), now())",
        code);
    return jdbc.queryForObject(
        "INSERT INTO service_config_versions (service_code, version_number, transaction_id_path,"
            + " status_path, timestamp_path, timestamp_format, default_time_zone, response_http_status,"
            + " response_body, created_at) VALUES (?, 1, '$.transId', '$.code', '$.time', 'ISO_8601',"
            + " 'Asia/Ho_Chi_Minh', 201, '{\"ok\":true}', now()) RETURNING version_id",
        Long.class,
        code);
  }

  protected int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  /** Thân tạo dịch vụ mẫu {@code THU_HO}: $.transId, $.code, $.time; response 201 {"ok":true}. */
  protected static String serviceJson(String code) {
    return serviceJson(code, "$.transId", "$.code", "$.time", 201, "{\"ok\":true}", null);
  }

  protected static String serviceJson(
      String code,
      String transactionIdPath,
      String statusPath,
      String timestampPath,
      int httpStatus,
      String responseBodyJson,
      Boolean enabled) {
    StringBuilder sb = new StringBuilder("{");
    sb.append("\"serviceCode\":\"").append(code).append("\",");
    sb.append("\"name\":\"Dich vu ").append(code).append("\",");
    sb.append("\"authType\":\"NONE\",");
    sb.append("\"transactionIdPath\":\"").append(transactionIdPath).append("\",");
    sb.append("\"statusPath\":\"").append(statusPath).append("\",");
    if (timestampPath != null) {
      sb.append("\"timestampPath\":\"").append(timestampPath).append("\",");
    }
    sb.append("\"timestampFormat\":\"ISO_8601\",");
    if (enabled != null) {
      sb.append("\"enabled\":").append(enabled).append(",");
    }
    sb.append("\"responseHttpStatus\":").append(httpStatus).append(",");
    sb.append("\"responseBody\":").append(responseBodyJson);
    return sb.append("}").toString();
  }

  protected void createService(String code) {
    HttpResponse<String> response = postJson("/api/services", serviceJson(code));
    assertEquals(201, response.statusCode(), response.body());
  }

  protected void createServiceFromJson(String serviceJson) {
    HttpResponse<String> response = postJson("/api/services", serviceJson);
    assertEquals(201, response.statusCode(), response.body());
  }

  protected HttpResponse<String> callback(String serviceCode, String body, String... headers) {
    return postJson("/callbacks/" + serviceCode, body, headers);
  }

  protected static String payload(String transactionId, String status) {
    return "{\"transId\":\""
        + transactionId
        + "\",\"code\":\""
        + status
        + "\",\"time\":\"2026-10-19T10:00:00+07:00\"}";
  }
}
