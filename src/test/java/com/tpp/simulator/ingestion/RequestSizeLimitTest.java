package com.tpp.simulator.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tpp.simulator.support.AbstractIntegrationTest;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * BR2.2, NFR3.8: giới hạn 1.048.576 byte đếm khi đọc (kể cả chunked), 413 trước mọi kiểm tra khác.
 */
@ExtendWith(OutputCaptureExtension.class)
class RequestSizeLimitTest extends AbstractIntegrationTest {

  private static final int LIMIT = 1_048_576;

  /** JSON hợp lệ có kích thước byte đúng bằng {@code size}. */
  private static String jsonOfSize(int size) {
    String prefix = "{\"transId\":\"BIG\",\"code\":\"00\",\"pad\":\"";
    String suffix = "\"}";
    int padding = size - prefix.length() - suffix.length();
    return prefix + "x".repeat(padding) + suffix;
  }

  private static void assertTooLarge(HttpResponse<String> response) {
    assertEquals(413, response.statusCode(), response.body());
    assertEquals(
        "PAYLOAD_TOO_LARGE", response.headers().firstValue("X-Simulator-Error").orElse(null));
    assertTrue(
        response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
    assertTrue(response.body().contains("\"error\":\"PAYLOAD_TOO_LARGE\""), response.body());
  }

  private HttpResponse<String> chunked(byte[] body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url("/callbacks/THU_HO")))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", JSON)
            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void bodyOfExactlyTheLimitIsAccepted() {
    createService("THU_HO");
    String body = jsonOfSize(LIMIT);
    assertEquals(LIMIT, body.getBytes(StandardCharsets.UTF_8).length);

    HttpResponse<String> response = callback("THU_HO", body);

    assertEquals(201, response.statusCode(), response.body());
    assertEquals(1, count("callbacks"));
    assertEquals("BIG", jdbc.queryForObject("SELECT transaction_id FROM callbacks", String.class));
  }

  @Test
  void oneByteOverTheLimitIs413AndNothingIsStored() {
    createService("THU_HO");

    HttpResponse<String> response = callback("THU_HO", jsonOfSize(LIMIT + 1));

    assertTooLarge(response);
    assertEquals(0, count("callbacks"));
    assertEquals(0, count("transactions"));
  }

  @Test
  void largeBodyIsRejectedBeforeTheServiceIsLookedUpOrTheContentTypeIsChecked() {
    String body = jsonOfSize(LIMIT + 1000);

    assertTooLarge(callback("DOES_NOT_EXIST", body));
    assertTooLarge(send("POST", "/callbacks/DOES_NOT_EXIST", "text/plain", body));
  }

  @Test
  void chunkedBodyWithoutContentLengthIsCountedWhileReading() throws Exception {
    createService("THU_HO");

    HttpResponse<String> response =
        chunked(jsonOfSize(LIMIT + 5000).getBytes(StandardCharsets.UTF_8));

    assertTooLarge(response);
    assertEquals(0, count("callbacks"));
  }

  @Test
  void chunkedBodyUnderTheLimitIsAccepted() throws Exception {
    createService("THU_HO");

    HttpResponse<String> response = chunked(jsonOfSize(200_000).getBytes(StandardCharsets.UTF_8));

    assertEquals(201, response.statusCode(), response.body());
    assertEquals(1, count("callbacks"));
  }

  @Test
  void oversizedRejectionLogsOneInfoLineWithTheCode(CapturedOutput output) {
    createService("THU_HO");

    callback("THU_HO", jsonOfSize(LIMIT + 1));

    long lines =
        output
            .getAll()
            .lines()
            .filter(
                l -> l.contains("Callback request: service=THU_HO") && l.contains("httpStatus=413"))
            .count();
    assertEquals(1, lines, output.getAll());
    assertFalse(output.getAll().contains("xxxxxxxxxxxxxxxxxxxx"));
  }

  @Test
  void mappingTestBodyOverEightMebibytesIs400OnSampleJsonNotA413() {
    String huge = "{\"sampleJson\":\"" + "x".repeat(8 * 1024 * 1024) + "\"}";

    HttpResponse<String> response = postJson("/api/mapping-tests", huge);

    assertEquals(400, response.statusCode(), response.body().substring(0, 100));
    assertEquals(
        "VALIDATION_FAILED", response.headers().firstValue("X-Simulator-Error").orElse(null));
    assertTrue(response.body().contains("\"field\":\"sampleJson\""), response.body());
  }

  @Test
  void otherApiPathsAreNotSizeLimitedByTheFilter() {
    HttpResponse<String> response = get("/api/services");

    assertEquals(200, response.statusCode());
    assertNull(response.headers().firstValue("X-Simulator-Error").orElse(null));
  }

  @Test
  void receivedAtIsTakenFromTheClockWhenTheRequestStarts() {
    createService("THU_HO");
    Instant fixed = Instant.parse("2026-10-19T03:00:00.987654Z");
    clock.set(fixed);

    HttpResponse<String> response = callback("THU_HO", jsonOfSize(500_000));

    assertNull(response.headers().firstValue("X-Simulator-Error").orElse(null));
    assertEquals(
        fixed,
        jdbc.queryForObject("SELECT received_at FROM callbacks", java.sql.Timestamp.class)
            .toInstant());
  }
}
