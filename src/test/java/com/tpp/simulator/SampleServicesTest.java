package com.tpp.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.tpp.simulator.support.AbstractIntegrationTest;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * NFR7.21, BR21.1 đến BR21.3: ba dịch vụ mẫu đọc từ CÁC TỆP THẬT trong {@code docs/samples}, nên
 * thiếu tệp ở bản checkout sạch làm kiểm thử đỏ.
 */
class SampleServicesTest extends AbstractIntegrationTest {

  private static final Path SAMPLES = Path.of("docs", "samples");
  private static final String CHI_HO_KEY = "sample-key-chi-ho";

  private byte[] file(String name) {
    try {
      return Files.readAllBytes(SAMPLES.resolve(name));
    } catch (IOException e) {
      throw new IllegalStateException("Thiếu tệp mẫu " + name, e);
    }
  }

  private HttpResponse<String> createFromFile(String code) {
    return sendBytes(
        "POST", "/api/services", "application/json; charset=utf-8", file(code + ".service.json"));
  }

  private HttpResponse<String> sendSample(String code, String payloadFile, String... headers) {
    return sendBytes("POST", "/callbacks/" + code, JSON, file(payloadFile), headers);
  }

  private JsonNode lastCallback() {
    long id = jdbc.queryForObject("SELECT max(record_id) FROM callbacks", Long.class);
    return jsonBody(get("/api/callbacks/" + id));
  }

  @BeforeEach
  void createTheThreeServices() {
    for (String code : new String[] {"THU_HO", "CHI_HO", "BIEN_DONG_SO_DU"}) {
      HttpResponse<String> response = createFromFile(code);
      assertEquals(201, response.statusCode(), code + " " + response.body());
    }
  }

  @Test
  void theThreeServicesAreCreatedWithTheDocumentedConfiguration() {
    JsonNode thuHo = jsonBody(get("/api/services/THU_HO"));
    JsonNode chiHo = jsonBody(get("/api/services/CHI_HO"));
    JsonNode balance = jsonBody(get("/api/services/BIEN_DONG_SO_DU"));

    assertEquals("NONE", thuHo.get("authType").asText());
    assertEquals("ISO_8601", thuHo.get("timestampFormat").asText());
    assertEquals("API_KEY", chiHo.get("authType").asText());
    assertEquals("X-Api-Key", chiHo.get("apiKeyHeaderName").asText());
    assertEquals("PATTERN", chiHo.get("timestampFormat").asText());
    assertEquals("yyyy-MM-dd HH:mm:ss", chiHo.get("timestampPattern").asText());
    assertEquals("Asia/Ho_Chi_Minh", chiHo.get("defaultTimeZone").asText());
    assertEquals("UNIX_MILLIS", balance.get("timestampFormat").asText());
    assertEquals(202, balance.get("responseHttpStatus").asInt());
    for (JsonNode service : new JsonNode[] {thuHo, chiHo, balance}) {
      assertTrue(service.get("enabled").asBoolean());
    }
    assertEquals(3, jsonBody(get("/api/services")).size());
    assertFalse(get("/api/services").body().contains(CHI_HO_KEY));
  }

  @Test
  void thuHoValidPayloadIsASuccessWithThreeMappedValues() {
    HttpResponse<String> response = sendSample("THU_HO", "THU_HO.payload-valid.json");

    assertEquals(200, response.statusCode());
    assertEquals("{\"code\":\"00\"}", response.body());
    JsonNode detail = lastCallback();
    assertEquals("SUCCESS", detail.get("outcome").asText());
    assertEquals("THUHO-0001", detail.get("transactionId").asText());
    assertEquals("00", detail.get("status").asText());
    assertEquals("2026-10-19T03:00:00Z", detail.get("businessTimestamp").asText());
  }

  @Test
  void thuHoFaultyPayloadIsAMappingErrorButStillJoinsItsTransaction() {
    HttpResponse<String> response = sendSample("THU_HO", "THU_HO.payload-faulty.json");

    assertEquals(200, response.statusCode());
    assertEquals("{\"code\":\"00\"}", response.body());
    JsonNode detail = lastCallback();
    assertEquals("MAPPING_ERROR", detail.get("outcome").asText());
    assertEquals(
        "NOT_FOUND", detail.get("mappingFieldResults").get("status").get("errorCode").asText());
    assertEquals(
        "UNPARSEABLE",
        detail.get("mappingFieldResults").get("timestamp").get("errorCode").asText());
    assertEquals(1, count("transactions"));
    assertEquals(
        "THUHO-0002", jdbc.queryForObject("SELECT transaction_id FROM transactions", String.class));
  }

  @Test
  void chiHoNeedsItsPublicSampleKey() {
    HttpResponse<String> withKey =
        sendSample("CHI_HO", "CHI_HO.payload-valid.json", "X-Api-Key", CHI_HO_KEY);
    JsonNode detail = lastCallback();
    HttpResponse<String> wrongKey =
        sendSample("CHI_HO", "CHI_HO.payload-valid.json", "X-Api-Key", "sai");
    HttpResponse<String> noKey = sendSample("CHI_HO", "CHI_HO.payload-valid.json");

    assertEquals(200, withKey.statusCode());
    assertEquals("{\"status\":\"OK\"}", withKey.body());
    assertEquals("SUCCESS", detail.get("outcome").asText());
    assertEquals("2026-10-19T03:00:00Z", detail.get("businessTimestamp").asText());
    assertEquals(401, wrongKey.statusCode());
    assertEquals(401, noKey.statusCode());
    assertEquals("UNAUTHORIZED", noKey.headers().firstValue("X-Simulator-Error").orElse(null));
  }

  @Test
  void chiHoFaultyPayloadReportsAWrongTypeStatusAndAnUnparseableDate() {
    sendSample("CHI_HO", "CHI_HO.payload-faulty.json", "X-Api-Key", CHI_HO_KEY);

    JsonNode detail = lastCallback();
    assertEquals("MAPPING_ERROR", detail.get("outcome").asText());
    assertEquals(
        "WRONG_TYPE", detail.get("mappingFieldResults").get("status").get("errorCode").asText());
    assertEquals(
        "UNPARSEABLE",
        detail.get("mappingFieldResults").get("timestamp").get("errorCode").asText());
  }

  @Test
  void balanceChangeValidPayloadUsesUnixMillisAndReturns202() {
    HttpResponse<String> response =
        sendSample("BIEN_DONG_SO_DU", "BIEN_DONG_SO_DU.payload-valid.json");

    assertEquals(202, response.statusCode());
    assertEquals("{\"received\":true}", response.body());
    JsonNode detail = lastCallback();
    assertEquals("SUCCESS", detail.get("outcome").asText());
    assertEquals("BDSD-0001", detail.get("transactionId").asText());
    assertEquals("POSTED", detail.get("status").asText());
    assertEquals("2025-10-19T05:00:00Z", detail.get("businessTimestamp").asText());
  }

  @Test
  void balanceChangeFaultyPayloadHasNoTransaction() {
    HttpResponse<String> response =
        sendSample("BIEN_DONG_SO_DU", "BIEN_DONG_SO_DU.payload-faulty.json");

    assertEquals(202, response.statusCode());
    JsonNode detail = lastCallback();
    assertEquals("MAPPING_ERROR", detail.get("outcome").asText());
    assertEquals(
        "NOT_FOUND",
        detail.get("mappingFieldResults").get("transactionId").get("errorCode").asText());
    assertEquals(
        "UNPARSEABLE",
        detail.get("mappingFieldResults").get("timestamp").get("errorCode").asText());
    assertNull(detail.get("transactionId").isNull() ? null : detail.get("transactionId").asText());
    assertEquals(0, count("transactions"));
  }

  @Test
  void creatingTheSamplesAgainIs409ForEveryOneAndTheCountStaysThree() {
    for (String code : new String[] {"THU_HO", "CHI_HO", "BIEN_DONG_SO_DU"}) {
      HttpResponse<String> again = createFromFile(code);

      assertEquals(409, again.statusCode(), code);
      assertEquals(
          "SERVICE_CODE_DUPLICATE", again.headers().firstValue("X-Simulator-Error").orElse(null));
    }
    assertEquals(3, count("services"));
  }

  @Test
  void everySampleFileIsPresentAndValidJson() throws IOException {
    for (String code : new String[] {"THU_HO", "CHI_HO", "BIEN_DONG_SO_DU"}) {
      for (String suffix :
          new String[] {".service.json", ".payload-valid.json", ".payload-faulty.json"}) {
        json.readTree(file(code + suffix));
      }
    }
  }
}
