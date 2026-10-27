package com.tpp.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import com.tpp.simulator.ingestion.CallbackIngestionService;
import com.tpp.simulator.ingestion.IngestionResult;
import com.tpp.simulator.ingestion.WriteStatus;
import com.tpp.simulator.support.LogCapture;
import com.tpp.simulator.support.ParallelRunner;
import com.tpp.simulator.support.TcpProxy;
import com.tpp.simulator.support.TestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
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
 * NFR5.22, NFR2.26 (B), BR14.9, RD-02, OD-01: khi kho dữ liệu hỏng hoàn toàn, 20 request đồng thời
 * tới endpoint callback cho ĐÚNG 20 dòng ERROR của logger endpoint (không dòng thứ hai từ bộ xử lý
 * lỗi), phản hồi 500 không có header X-Simulator-Record-Failed, không bao giờ 401 hay 404; thử
 * mapping vẫn 200; tự phục hồi khi kho trở lại.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatabaseDownLoggingTest {

  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final String ACCESS_LOG = "com.tpp.simulator.web.CallbackAccessLog";
  private static final String SERVICE =
      "{\"serviceCode\":\"DOWN\",\"name\":\"Down\",\"authType\":\"API_KEY\","
          + "\"apiKeyHeaderName\":\"X-Api-Key\",\"apiKeySecret\":\"down-secret-key\","
          + "\"transactionIdPath\":\"$.transId\",\"statusPath\":\"$.code\","
          + "\"timestampFormat\":\"ISO_8601\",\"responseHttpStatus\":200,\"responseBody\":{\"ok\":true}}";
  private static final String PAYLOAD = "{\"transId\":\"T\",\"code\":\"00\"}";

  private static TcpProxy proxy;
  private static ConfigurableApplicationContext context;
  private static int appPort;

  @BeforeAll
  static void startApplicationBehindProxy() throws Exception {
    String[] hostPort = TestDatabase.hostAndPort().split(":");
    proxy = new TcpProxy(hostPort[0], Integer.parseInt(hostPort[1]));
    proxy.start();
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

  private static HttpResponse<String> request(
      String method, String path, String body, String... headers) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort + path))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    for (int i = 0; i + 1 < headers.length; i += 2) {
      builder.header(headers[i], headers[i + 1]);
    }
    return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  @Test
  @Order(1)
  void setUp() throws Exception {
    assertEquals(201, request("POST", "/api/services", SERVICE).statusCode());
    try (LogCapture log = LogCapture.of(ACCESS_LOG)) {
      assertEquals(
          200,
          request("POST", "/callbacks/DOWN", PAYLOAD, "X-Api-Key", "down-secret-key").statusCode());
      assertEquals(Level.INFO, log.events().get(0).getLevel());
    }
  }

  @Test
  @Order(2)
  void twentyConcurrentCallbacksWhileTheStoreIsDownLogExactlyTwentyErrorLines() throws Exception {
    proxy.stop();
    List<Callable<HttpResponse<String>>> tasks = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      tasks.add(() -> request("POST", "/callbacks/DOWN", PAYLOAD, "X-Api-Key", "wrong-key-value"));
    }

    try (LogCapture access = LogCapture.of(ACCESS_LOG);
        LogCapture all = LogCapture.root()) {
      long start = System.nanoTime();
      List<HttpResponse<String>> responses = ParallelRunner.runAll(tasks);
      long seconds = Duration.ofNanos(System.nanoTime() - start).toSeconds();

      assertTrue(seconds < 40, "mất " + seconds + " giây");
      for (HttpResponse<String> response : responses) {
        assertEquals(500, response.statusCode(), response.body());
        assertEquals(
            "INTERNAL_ERROR", response.headers().firstValue("X-Simulator-Error").orElse(null));
        assertNull(response.headers().firstValue("X-Simulator-Record-Failed").orElse(null));
      }
      assertEquals(20, access.events(Level.ERROR).size(), access.allText());
      assertEquals(20, access.events().size());
      for (var event : access.events()) {
        String line = event.getFormattedMessage();
        assertTrue(
            line.startsWith("Callback request: service=DOWN httpStatus=500 exception="), line);
        assertTrue(line.contains("sqlState="), line);
        assertFalse(line.contains("recordId"), line);
        assertFalse(line.contains("rejectionReason"), line);
      }
      // Không dòng thứ hai nào của ứng dụng cho các request callback (bộ xử lý lỗi không ghi).
      long applicationErrors =
          all.events(Level.ERROR).stream()
              .filter(e -> e.getLoggerName().startsWith("com.tpp.simulator"))
              .count();
      assertEquals(20, applicationErrors, all.allText());
      assertFalse(all.allText().contains("wrong-key-value"));
      assertFalse(all.allText().contains("down-secret-key"));
    }
  }

  @Test
  @Order(3)
  void theIngestionServiceReportsSnapshotReadFailedInsteadOfThrowing() {
    CallbackIngestionService ingestion = context.getBean(CallbackIngestionService.class);

    IngestionResult result =
        ingestion.receive(
            "DOWN",
            "application/json",
            Map.of("X-Api-Key", List.of("x")),
            PAYLOAD.getBytes(StandardCharsets.UTF_8),
            Instant.now());

    assertEquals(WriteStatus.SNAPSHOT_READ_FAILED, result.writeStatus());
    assertEquals(IngestionResult.Kind.SNAPSHOT_FAILED, result.kind());
    assertNull(result.recordId());
    assertTrue(result.failureClass() != null && !result.failureClass().isEmpty());
  }

  @Test
  @Order(4)
  void mappingTestsStillWorkWithoutTheStore() throws Exception {
    String body =
        "{\"transactionIdPath\":\"$.transId\",\"statusPath\":\"$.code\",\"timestampFormat\":\"ISO_8601\","
            + "\"sampleJson\":\"{\\\"transId\\\":\\\"T\\\",\\\"code\\\":\\\"00\\\"}\"}";

    HttpResponse<String> response = request("POST", "/api/mapping-tests", body);

    assertEquals(200, response.statusCode(), response.body());
    assertTrue(response.body().contains("\"sampleJsonValid\":true"), response.body());
  }

  @Test
  @Order(5)
  void afterTheStoreReturnsCallbacksAreServedAgainWithoutARestart() throws Exception {
    proxy.start();
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    HttpResponse<String> response = null;
    try (LogCapture access = LogCapture.of(ACCESS_LOG)) {
      while (System.nanoTime() < deadline) {
        response = request("POST", "/callbacks/DOWN", PAYLOAD, "X-Api-Key", "down-secret-key");
        if (response.statusCode() == 200) {
          break;
        }
        Thread.sleep(500);
      }
      assertEquals(200, response.statusCode(), response.body());
      assertEquals(Level.INFO, access.events().get(access.events().size() - 1).getLevel());
    }
  }
}
