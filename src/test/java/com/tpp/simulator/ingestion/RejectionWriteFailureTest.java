package com.tpp.simulator.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import com.tpp.simulator.support.AbstractIntegrationTest;
import com.tpp.simulator.support.FailureTrigger;
import com.tpp.simulator.support.LogCapture;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * NFR2.26, RD-02, BR14.8, BR15.6: lỗi ghi qua HTTP (A: lần từ chối, B: callback được chấp nhận).
 */
class RejectionWriteFailureTest extends AbstractIntegrationTest {

  private static final String ACCESS_LOG = "com.tpp.simulator.web.CallbackAccessLog";
  private static final String KEY = "good-key-" + java.util.UUID.randomUUID();

  @BeforeEach
  void service() {
    createServiceFromJson(
        serviceJson("KEYED")
            .replace("\"authType\":\"NONE\"", "\"authType\":\"API_KEY\"")
            .replace(
                "{\"serviceCode\"",
                "{\"apiKeyHeaderName\":\"X-Api-Key\",\"apiKeySecret\":\""
                    + KEY
                    + "\",\"serviceCode\""));
  }

  @Test
  void aRejectedReceiptThatCannotBeStoredKeepsItsOriginalStatusAndAddsTheRecordFailedHeader() {
    try (FailureTrigger trigger = FailureTrigger.on("callbacks", "INSERT", "KEYED");
        LogCapture log = LogCapture.of(ACCESS_LOG)) {
      long start = System.nanoTime();

      HttpResponse<String> response = callback("KEYED", payload("T1", "00"), "X-Api-Key", "wrong");

      assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() <= 7);
      assertEquals(401, response.statusCode(), response.body());
      assertEquals("UNAUTHORIZED", response.headers().firstValue("X-Simulator-Error").orElse(null));
      assertEquals("true", response.headers().firstValue("X-Simulator-Record-Failed").orElse(null));
      assertEquals("UNAUTHORIZED", jsonBody(response).get("error").asText());
      assertEquals(1, log.events().size(), log.allText());
      assertEquals(Level.ERROR, log.events().get(0).getLevel());
      String line = log.events().get(0).getFormattedMessage();
      assertTrue(line.contains("service=KEYED"), line);
      assertTrue(line.contains("rejectionReason=INVALID_API_KEY"), line);
      assertTrue(line.contains("httpStatus=401"), line);
      assertTrue(line.contains("sqlState=P0001"), line);
      assertFalse(line.contains("wrong"), line);
      assertFalse(line.contains("recordId"), line);
    }

    assertEquals(0, count("callbacks"));
  }

  @Test
  void aWrongKeyIsRecordedNormallyAgainOnceTheFailureIsGone() {
    try (FailureTrigger trigger = FailureTrigger.on("callbacks", "INSERT", "KEYED")) {
      callback("KEYED", payload("T1", "00"), "X-Api-Key", "wrong");
    }

    HttpResponse<String> response = callback("KEYED", payload("T2", "00"), "X-Api-Key", "wrong");

    assertEquals(401, response.statusCode());
    assertNull(response.headers().firstValue("X-Simulator-Record-Failed").orElse(null));
    assertEquals(1, count("callbacks"));
  }

  @Test
  void anAcceptedCallbackThatCannotBeStoredIs500WithoutTheRecordFailedHeader() {
    try (FailureTrigger trigger = FailureTrigger.on("callbacks", "INSERT", "KEYED");
        LogCapture log = LogCapture.of(ACCESS_LOG)) {
      HttpResponse<String> response = callback("KEYED", payload("T1", "00"), "X-Api-Key", KEY);

      assertEquals(500, response.statusCode(), response.body());
      assertEquals(
          "INTERNAL_ERROR", response.headers().firstValue("X-Simulator-Error").orElse(null));
      assertNull(response.headers().firstValue("X-Simulator-Record-Failed").orElse(null));
      assertFalse(response.body().contains("ok"), response.body());
      assertEquals(1, log.events().size(), log.allText());
      String line = log.events().get(0).getFormattedMessage();
      assertEquals(Level.ERROR, log.events().get(0).getLevel());
      assertTrue(line.contains("outcome=SUCCESS"), line);
      assertTrue(line.contains("httpStatus=500"), line);
      assertTrue(line.contains("sqlState=P0001"), line);
      assertFalse(line.contains(KEY), line);
    }

    assertEquals(0, count("callbacks"));
    assertEquals(0, count("transactions"));
  }

  @Test
  void aFailedAcceptedCallbackLeavesAnEarlierTransactionUntouched() {
    assertEquals(201, callback("KEYED", payload("T1", "00"), "X-Api-Key", KEY).statusCode());

    try (FailureTrigger trigger = FailureTrigger.on("callbacks", "INSERT", "KEYED")) {
      assertEquals(500, callback("KEYED", payload("T1", "11"), "X-Api-Key", KEY).statusCode());
    }

    assertEquals(1, jdbc.queryForObject("SELECT callback_count FROM transactions", Integer.class));
    assertEquals("00", jdbc.queryForObject("SELECT latest_status FROM transactions", String.class));
  }

  @Test
  void noneOfTheErrorOutputEverContainsTheRawDatabaseMessage() {
    try (FailureTrigger trigger = FailureTrigger.on("callbacks", "INSERT", "KEYED");
        LogCapture log = LogCapture.root()) {
      HttpResponse<String> response = callback("KEYED", payload("T1", "00"), "X-Api-Key", KEY);

      assertFalse(response.body().contains("test failure"));
      assertFalse(response.headers().map().toString().contains("test failure"));
      assertFalse(log.allText().contains("test failure"), log.allText());
      assertFalse(log.allText().contains(KEY), log.allText());
    }
  }
}
