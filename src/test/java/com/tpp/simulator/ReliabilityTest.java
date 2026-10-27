package com.tpp.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tpp.simulator.support.TcpProxy;
import com.tpp.simulator.support.TestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * NFR2.4: khi PostgreSQL tạm mất, API trả 500 INTERNAL_ERROR kèm X-Simulator-Error (không bao giờ
 * báo thành công khi chưa ghi được), health là DOWN, và tự phục hồi khi cơ sở dữ liệu trở lại,
 * không cần khởi động lại. Mô phỏng bằng một proxy TCP đứng giữa ứng dụng và PostgreSQL thật.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReliabilityTest {

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private static TcpProxy proxy;
  private static ConfigurableApplicationContext context;
  private static int appPort;

  @BeforeAll
  static void startApplicationBehindProxy() throws Exception {
    String[] hostPort = TestDatabase.hostAndPort().split(":");
    proxy = new TcpProxy(hostPort[0], Integer.parseInt(hostPort[1]));
    proxy.start();
    // Tham số dòng lệnh có ưu tiên cao hơn application.yml (properties() của builder thì không).
    context =
        new SpringApplicationBuilder(TppSimulatorApplication.class)
            .run(
                "--spring.datasource.url=jdbc:postgresql://127.0.0.1:"
                    + proxy.port()
                    + TestDatabase.databasePath(),
                "--spring.datasource.username=" + TestDatabase.USERNAME,
                "--spring.datasource.password=" + TestDatabase.PASSWORD,
                "--server.port=0",
                "--spring.main.banner-mode=off");
    appPort = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
    new JdbcTemplate(context.getBean(javax.sql.DataSource.class))
        .execute(
            "TRUNCATE transactions, callbacks, service_config_versions, services RESTART IDENTITY CASCADE");
  }

  @AfterAll
  static void stop() {
    if (context != null) {
      context.close();
    }
    if (proxy != null) {
      proxy.close();
    }
  }

  private static HttpResponse<String> request(String method, String path, String body)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort + path))
            .timeout(Duration.ofSeconds(40))
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static final String SERVICE =
      "{\"serviceCode\":\"REL\",\"name\":\"Reliability\",\"authType\":\"NONE\","
          + "\"transactionIdPath\":\"$.transId\",\"statusPath\":\"$.code\","
          + "\"timestampFormat\":\"ISO_8601\",\"responseHttpStatus\":200,\"responseBody\":{\"ok\":true}}";

  @Test
  @Order(1)
  void worksNormallyBeforeTheOutage() throws Exception {
    assertEquals(201, request("POST", "/api/services", SERVICE).statusCode());
    assertEquals(
        200,
        request("POST", "/callbacks/REL", "{\"transId\":\"T1\",\"code\":\"00\"}").statusCode());
    HttpResponse<String> health = request("GET", "/actuator/health", null);
    assertEquals(200, health.statusCode());
    assertTrue(health.body().contains("UP"), health.body());
  }

  @Test
  @Order(2)
  void duringTheOutageEveryWriteIs500WithTheErrorHeaderAndNothingIsReportedAsSuccess()
      throws Exception {
    proxy.stop();
    long started = System.nanoTime();

    HttpResponse<String> callback =
        request("POST", "/callbacks/REL", "{\"transId\":\"T2\",\"code\":\"00\"}");
    HttpResponse<String> create = request("POST", "/api/services", SERVICE.replace("REL", "REL2"));
    HttpResponse<String> list = request("GET", "/api/transactions", null);
    HttpResponse<String> health = request("GET", "/actuator/health", null);

    Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
    for (HttpResponse<String> response : java.util.List.of(callback, create, list)) {
      assertEquals(500, response.statusCode(), response.body());
      assertEquals(
          "INTERNAL_ERROR", response.headers().firstValue("X-Simulator-Error").orElse(null));
      assertFalse(
          response.body().contains("ok"),
          "không được trả response đã cấu hình: " + response.body());
      assertFalse(response.body().toLowerCase().contains("connection"), response.body());
    }
    assertEquals(503, health.statusCode());
    assertTrue(health.body().contains("DOWN"), health.body());
    // Mỗi yêu cầu không lấy được kết nối chờ tối đa 5 giây rồi trả lỗi (không treo).
    assertTrue(
        elapsed.toSeconds() < 40,
        "các yêu cầu phải trả lỗi sau tối đa ~5 giây mỗi cái: " + elapsed);
  }

  @Test
  @Order(3)
  void afterTheDatabaseReturnsTheApplicationServesAgainWithoutARestart() throws Exception {
    proxy.start();
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    HttpResponse<String> response = null;
    while (System.nanoTime() < deadline) {
      response = request("POST", "/callbacks/REL", "{\"transId\":\"T3\",\"code\":\"00\"}");
      if (response.statusCode() == 200) {
        break;
      }
      Thread.sleep(500);
    }

    assertNotEquals(null, response);
    assertEquals(
        200, response.statusCode(), "phải phục vụ lại trong vòng 30 giây: " + response.body());
    assertEquals(200, request("GET", "/api/transactions", null).statusCode());
    assertEquals(200, request("GET", "/actuator/health", null).statusCode());
    JdbcTemplate jdbc = new JdbcTemplate(context.getBean(javax.sql.DataSource.class));
    // Callback T2 gửi lúc mất kết nối không được lưu; T1 và T3 thì có.
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM callbacks WHERE service_code = 'REL'", Integer.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM callbacks WHERE transaction_id = 'T2'", Integer.class));
  }
}
