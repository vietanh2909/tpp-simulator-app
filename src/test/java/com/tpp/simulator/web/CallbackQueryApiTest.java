package com.tpp.simulator.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.tpp.simulator.support.AbstractIntegrationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** W23, BR19.5, BR19.6: chi tiết một lần nhận cho cả ba kết quả. */
class CallbackQueryApiTest extends AbstractIntegrationTest {

  private long lastId() {
    return jdbc.queryForObject("SELECT max(record_id) FROM callbacks", Long.class);
  }

  @Test
  void aSuccessfulReceiptShowsEveryField() {
    createService("THU_HO");
    callback(
        "THU_HO", payload("T1", "00"), "X-Request-Id", "req-7", "Authorization", "Bearer SECRET");

    HttpResponse<String> response = get("/api/callbacks/" + lastId());

    assertEquals(200, response.statusCode(), response.body());
    JsonNode body = jsonBody(response);
    assertEquals("THU_HO", body.get("serviceCode").asText());
    assertFalse(body.get("serviceDeleted").asBoolean());
    assertEquals("SUCCESS", body.get("outcome").asText());
    assertTrue(body.get("rejectionReason").isNull());
    assertEquals(201, body.get("httpStatusReturned").asInt());
    assertEquals("T1", body.get("transactionId").asText());
    assertEquals("00", body.get("status").asText());
    assertEquals("2026-10-19T03:00:00Z", body.get("businessTimestamp").asText());
    assertEquals(1, body.get("configVersionNumber").asInt());
    assertEquals("***", headerIgnoreCase(body.get("requestHeaders"), "Authorization"));
    assertEquals("req-7", headerIgnoreCase(body.get("requestHeaders"), "X-Request-Id"));
    assertEquals("T1", body.get("payloadJson").get("transId").asText());
    assertFalse(body.has("payloadRawText"));
    assertEquals("T1", body.get("mappingFieldResults").get("transactionId").get("value").asText());
    assertEquals(201, body.get("responseStatusReturned").asInt());
    assertTrue(body.get("responseBodyReturned").get("ok").asBoolean());
    assertFalse(response.body().contains("SECRET"));
  }

  @Test
  void aMappingErrorReceiptShowsTheFieldLevelErrors() {
    createService("THU_HO");
    callback("THU_HO", "{\"code\":\"00\"}");

    JsonNode body = jsonBody(get("/api/callbacks/" + lastId()));

    assertEquals("MAPPING_ERROR", body.get("outcome").asText());
    assertEquals(
        "NOT_FOUND",
        body.get("mappingFieldResults").get("transactionId").get("errorCode").asText());
    assertTrue(body.get("transactionId").isNull());
    assertEquals("00", body.get("status").asText());
  }

  @Test
  void theDetailAgreesWithTheListRow() {
    createService("THU_HO");
    callback("THU_HO", payload("T1", "00"));

    JsonNode row = jsonBody(get("/api/callbacks")).get("items").get(0);
    JsonNode detail = jsonBody(get("/api/callbacks/" + row.get("recordId").asLong()));

    for (String field :
        new String[] {
          "recordId",
          "serviceCode",
          "receivedAt",
          "outcome",
          "httpStatusReturned",
          "transactionId",
          "status"
        }) {
      assertEquals(row.get(field), detail.get(field), field);
    }
  }

  @Test
  void anOldReceiptKeepsTheVersionNumberItWasProcessedWith() {
    createService("THU_HO");
    callback("THU_HO", payload("T1", "00"));
    long first = lastId();
    String update =
        "{\"name\":\"n\",\"authType\":\"NONE\",\"transactionIdPath\":\"$.transId\",\"statusPath\":\"$.other\","
            + "\"timestampFormat\":\"ISO_8601\",\"responseHttpStatus\":201,\"responseBody\":{\"ok\":true}}";
    assertEquals(200, send("PUT", "/api/services/THU_HO", JSON, update).statusCode());
    callback("THU_HO", payload("T2", "00"));

    assertEquals(1, jsonBody(get("/api/callbacks/" + first)).get("configVersionNumber").asInt());
    assertEquals(2, jsonBody(get("/api/callbacks/" + lastId())).get("configVersionNumber").asInt());
  }

  @Test
  void aReceiptOfADeletedServiceIsFlagged() {
    createService("THU_HO");
    callback("THU_HO", payload("T1", "00"));
    send("DELETE", "/api/services/THU_HO", null, null);

    JsonNode body = jsonBody(get("/api/callbacks/" + 1));

    assertTrue(body.get("serviceDeleted").asBoolean());
  }

  @Test
  void unknownIdsAre404WithTheCallbackNotFoundCode() {
    for (String id : new String[] {"999", "0", "-1", "9223372036854775807"}) {
      HttpResponse<String> response = get("/api/callbacks/" + id);

      assertEquals(404, response.statusCode(), id);
      assertEquals(
          "CALLBACK_NOT_FOUND",
          response.headers().firstValue("X-Simulator-Error").orElse(null),
          id);
    }
  }

  @Test
  void anIdThatIsNotA64BitIntegerIs400() {
    for (String id : new String[] {"abc", "1.5", "99999999999999999999", "0x10", "1e3", "%20"}) {
      HttpResponse<String> response = get("/api/callbacks/" + id);

      assertEquals(400, response.statusCode(), id);
      assertEquals(
          "VALIDATION_FAILED", response.headers().firstValue("X-Simulator-Error").orElse(null), id);
      assertTrue(response.body().contains("\"field\":\"recordId\""), response.body());
    }
  }

  @Test
  void aTransactionDetailShowsRejectedReceiptsNowhere() {
    createService("THU_HO");
    callback("THU_HO", payload("T1", "00"));
    jdbc.update("UPDATE services SET enabled = false");
    callback("THU_HO", payload("T1", "00"));

    JsonNode detail = jsonBody(get("/api/transactions/detail?serviceCode=THU_HO&transactionId=T1"));

    assertEquals(1, detail.get("callbacks").size());
    assertEquals(1, detail.get("transaction").get("callbackCount").asInt());
  }
}
