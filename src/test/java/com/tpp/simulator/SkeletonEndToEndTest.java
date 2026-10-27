package com.tpp.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.tpp.simulator.support.AbstractIntegrationTest;
import java.net.http.HttpResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Bằng chứng "skeleton chạy được" (code-generation-plan.md, Step 9): máy chủ thật trên cổng ngẫu
 * nhiên, HTTP thật, PostgreSQL thật, đi đúng chuỗi lệnh cURL của README: tạo THU_HO, gọi
 * /callbacks/THU_HO và /callbacks/thu_ho, đọc danh sách rồi chi tiết giao dịch.
 */
class SkeletonEndToEndTest extends AbstractIntegrationTest {

  private static final String BODY =
      "{\"transId\":\"T1\",\"code\":\"00\",\"time\":\"2026-10-19T10:00:00+07:00\"}";

  @Test
  void createServiceThenReceiveCallbacksThenReadTransactionListAndDetail() {
    // 1. Tạo dịch vụ THU_HO.
    HttpResponse<String> created = postJson("/api/services", serviceJson("THU_HO"));
    assertEquals(201, created.statusCode(), created.body());
    assertEquals("/callbacks/THU_HO", jsonBody(created).get("callbackPath").asText());

    // 2. AC1.1.1, AC1.6.1: gọi /callbacks/THU_HO.
    clock.set(Instant.parse("2026-10-19T03:30:00.000001Z"));
    HttpResponse<String> first =
        callback("THU_HO", BODY, "Authorization", "Bearer abc.def", "X-Trace", "e2e-1");
    assertEquals(201, first.statusCode(), first.body());
    assertEquals("{\"ok\":true}", first.body());
    assertTrue(first.headers().firstValue("X-Simulator-Error").isEmpty());

    // 3. AC1.1.2, AC1.1.4: /callbacks/thu_ho với charset=UTF-8 vào cùng dịch vụ, bản ghi riêng.
    clock.set(Instant.parse("2026-10-19T03:30:05.000002Z"));
    HttpResponse<String> second =
        send("POST", "/callbacks/thu_ho", "application/json; charset=UTF-8", BODY);
    assertEquals(201, second.statusCode(), second.body());
    assertEquals("{\"ok\":true}", second.body());

    // 4. AC1.14.1, AC1.10.1: danh sách giao dịch.
    JsonNode list = jsonBody(get("/api/transactions"));
    assertEquals(1, list.get("totalItems").asInt());
    JsonNode row = list.get("items").get(0);
    assertEquals("THU_HO", row.get("serviceCode").asText());
    assertEquals("T1", row.get("transactionId").asText());
    assertEquals("00", row.get("latestStatus").asText());
    assertEquals("2026-10-19T03:00:00Z", row.get("latestBusinessTimestamp").asText());
    assertEquals("2026-10-19T03:30:05.000002Z", row.get("latestReceivedAt").asText());
    assertEquals(2, row.get("callbackCount").asInt());

    // 5. AC1.1.3: chi tiết giao dịch với tham số mã hóa phần trăm.
    HttpResponse<String> detail =
        get(
            "/api/transactions/detail?serviceCode="
                + enc("THU_HO")
                + "&transactionId="
                + enc("T1"));
    assertEquals(200, detail.statusCode(), detail.body());
    assertFalse(detail.body().contains("abc.def"), "Authorization phải được che");
    JsonNode callbacks = jsonBody(detail).get("callbacks");
    assertEquals(2, callbacks.size());
    assertEquals("2026-10-19T03:30:00.000001Z", callbacks.get(0).get("receivedAt").asText());
    assertEquals("2026-10-19T03:30:05.000002Z", callbacks.get(1).get("receivedAt").asText());
    assertEquals("***", headerIgnoreCase(callbacks.get(0).get("requestHeaders"), "Authorization"));
    assertEquals("e2e-1", headerIgnoreCase(callbacks.get(0).get("requestHeaders"), "X-Trace"));
    assertEquals(
        "T1",
        callbacks.get(0).get("mappingFieldResults").get("transactionId").get("value").asText());
    assertEquals(201, callbacks.get(0).get("responseStatusReturned").asInt());
    assertEquals(1, callbacks.get(0).get("configVersionNumber").asInt());
    assertTrue(callbacks.get(0).get("responseBodyReturned").get("ok").asBoolean());

    // 6. AC1.6.3, AC1.10.4: callback thiếu code làm trạng thái trống kèm lỗi, tổng 3.
    clock.set(Instant.parse("2026-10-19T03:30:10Z"));
    assertEquals(
        201,
        callback("THU_HO", "{\"transId\":\"T1\",\"time\":\"2026-10-19T10:00:00+07:00\"}")
            .statusCode());
    JsonNode afterMissing = jsonBody(get("/api/transactions")).get("items").get(0);
    assertTrue(afterMissing.get("latestStatus").isNull());
    assertTrue(afterMissing.get("latestHasMappingError").asBoolean());
    assertEquals(3, afterMissing.get("callbackCount").asInt());
  }
}
