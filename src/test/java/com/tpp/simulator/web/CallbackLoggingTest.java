package com.tpp.simulator.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.tpp.simulator.ingestion.RejectionReason;
import com.tpp.simulator.mapping.MappingOutcome;
import com.tpp.simulator.support.AbstractIntegrationTest;
import com.tpp.simulator.support.LogCapture;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * OD-01, OD-02, NFR5.21, NFR3.24: đúng một dòng nhật ký không nhạy cảm cho mỗi request callback.
 */
class CallbackLoggingTest extends AbstractIntegrationTest {

  private static final String LOGGER = "com.tpp.simulator.web.CallbackAccessLog";

  @BeforeEach
  void services() {
    createService("OPEN");
    createServiceFromJson(
        serviceJson("KEYED")
            .replace("\"authType\":\"NONE\"", "\"authType\":\"API_KEY\"")
            .replace(
                "{\"serviceCode\"",
                "{\"apiKeyHeaderName\":\"X-Api-Key\",\"apiKeySecret\":\"k1\",\"serviceCode\""));
  }

  private static String line(LogCapture log) {
    List<ILoggingEvent> events = log.events();
    assertEquals(1, events.size(), log.allText());
    assertEquals(Level.INFO, events.get(0).getLevel());
    return events.get(0).getFormattedMessage();
  }

  // ---------- mẫu thông điệp (đơn vị) ----------

  @Test
  void theMessageOmitsAbsentFieldsEntirelyAndKeepsAFixedOrder() {
    assertEquals(
        "Callback request: service=THU_HO httpStatus=413",
        new CallbackLogEntry().service("THU_HO").httpStatus(413).message());
    assertEquals(
        "Callback request: service=THU_HO recordId=7 outcome=SUCCESS httpStatus=201",
        new CallbackLogEntry()
            .service("THU_HO")
            .recordId(7L)
            .outcome(MappingOutcome.SUCCESS)
            .httpStatus(201)
            .message());
    assertEquals(
        "Callback request: service=X recordId=8 rejectionReason=INVALID_API_KEY httpStatus=401",
        new CallbackLogEntry()
            .service("X")
            .recordId(8L)
            .rejectionReason(RejectionReason.INVALID_API_KEY)
            .httpStatus(401)
            .message());
    assertEquals(
        "Callback request: service=X httpStatus=500 exception=a.B sqlState=08006",
        new CallbackLogEntry().service("X").httpStatus(500).failure("a.B", "08006").message());
  }

  @Test
  void theServiceCodeIsSanitizedAndTruncated() {
    String message =
        new CallbackLogEntry()
            .service("A\r\nFAKE\tLINE" + "x".repeat(200))
            .httpStatus(404)
            .message();

    assertFalse(message.contains("\n"));
    assertFalse(message.contains("\r"));
    assertFalse(message.contains("\t"));
    assertTrue(message.length() < 200, message);
  }

  @Test
  void emittingTwiceWritesOnlyOneLine() {
    try (LogCapture log = LogCapture.of(LOGGER)) {
      CallbackLogEntry entry = new CallbackLogEntry().service("X").httpStatus(200);

      CallbackAccessLog.emit(entry);
      CallbackAccessLog.emit(entry);

      assertEquals(1, log.events().size());
    }
  }

  @Test
  void aFailureEntryIsLoggedAtErrorLevel() {
    try (LogCapture log = LogCapture.of(LOGGER)) {
      CallbackAccessLog.emit(
          new CallbackLogEntry().service("X").httpStatus(500).failure("a.B", "-"));

      assertEquals(Level.ERROR, log.events().get(0).getLevel());
    }
  }

  // ---------- qua HTTP ----------

  @Test
  void anAcceptedCallbackLogsRecordIdOutcomeAndStatus() {
    try (LogCapture log = LogCapture.of(LOGGER)) {
      callback("OPEN", payload("T1", "00"));

      assertEquals(
          "Callback request: service=OPEN recordId=1 outcome=SUCCESS httpStatus=201", line(log));
    }
  }

  @Test
  void aMappingErrorLogsItsOutcome() {
    try (LogCapture log = LogCapture.of(LOGGER)) {
      callback("OPEN", "{\"x\":1}");

      assertTrue(line(log).contains("outcome=MAPPING_ERROR"));
    }
  }

  @Test
  void everyRejectionReasonLogsExactlyOneInfoLineWithTheReason() {
    jdbc.update("UPDATE services SET enabled = false WHERE service_code = 'OPEN'");
    String[][] cases = {
      {"OPEN", "SERVICE_DISABLED", "503"},
      {"KEYED", "MISSING_API_KEY", "401"},
    };
    for (String[] c : cases) {
      try (LogCapture log = LogCapture.of(LOGGER)) {
        callback(c[0], payload("T", "00"));

        String text = line(log);
        assertTrue(text.contains("rejectionReason=" + c[1]), text);
        assertTrue(text.contains("httpStatus=" + c[2]), text);
        assertTrue(text.contains("recordId="), text);
        assertFalse(text.contains("outcome="), text);
      }
    }
    try (LogCapture log = LogCapture.of(LOGGER)) {
      callback("KEYED", payload("T", "00"), "X-Api-Key", "wrong");
      assertTrue(line(log).contains("rejectionReason=INVALID_API_KEY"));
    }
    try (LogCapture log = LogCapture.of(LOGGER)) {
      send("POST", "/callbacks/KEYED", "text/plain", "x", "X-Api-Key", "k1");
      assertTrue(line(log).contains("rejectionReason=UNSUPPORTED_CONTENT_TYPE"));
    }
    try (LogCapture log = LogCapture.of(LOGGER)) {
      callback("KEYED", "{bad", "X-Api-Key", "k1");
      assertTrue(line(log).contains("rejectionReason=INVALID_JSON"));
    }
    jdbc.update("UPDATE services SET deleted = true WHERE service_code = 'KEYED'");
    try (LogCapture log = LogCapture.of(LOGGER)) {
      callback("KEYED", payload("T", "00"));
      assertTrue(line(log).contains("rejectionReason=SERVICE_DELETED"));
    }
  }

  @Test
  void anUnknownServiceAndAnOversizedBodyLogOneLineWithoutRecordId() {
    try (LogCapture log = LogCapture.of(LOGGER)) {
      callback("NOPE", "{}");

      assertEquals("Callback request: service=NOPE httpStatus=404", line(log));
    }
    try (LogCapture log = LogCapture.of(LOGGER)) {
      callback("OPEN", "{\"pad\":\"" + "x".repeat(1_048_600) + "\"}");

      assertEquals("Callback request: service=OPEN httpStatus=413", line(log));
    }
  }

  @Test
  void theCentralErrorAdviceAddsNoSecondLineForTheCallbackEndpoint() {
    try (LogCapture log = LogCapture.root()) {
      callback("KEYED", payload("T", "00"));

      long lines =
          log.events().stream()
              .filter(e -> e.getLoggerName().startsWith("com.tpp.simulator"))
              .count();
      assertEquals(1, lines, log.allText());
    }
  }

  @Test
  void aWeirdServiceCodeInThePathCannotForgeLogLines() {
    try (LogCapture log = LogCapture.of(LOGGER)) {
      HttpResponse<String> response =
          postJson("/callbacks/OPEN%0D%0A2026-10-19T00:00:00.000Z%20INFO%20FAKE", "{}");

      // Tomcat có thể từ chối đường dẫn chứa CR LF mã hóa (400) trước khi tới ứng dụng.
      if (response.statusCode() == 404) {
        String text = line(log);
        assertFalse(text.contains("\n"));
        assertTrue(text.startsWith("Callback request: service=OPEN"), text);
      } else {
        assertEquals(400, response.statusCode());
        assertTrue(log.events().isEmpty());
      }
    }
  }

  @Test
  void nothingSensitiveIsEverLogged() {
    try (LogCapture log = LogCapture.root()) {
      callback(
          "KEYED",
          "{\"transId\":\"T\",\"code\":\"00\",\"secret\":\"PAYLOADMARKER\"}",
          "X-Api-Key",
          "wrong-key-value",
          "Authorization",
          "Bearer TOKENVALUE",
          "Cookie",
          "sid=COOKIEVALUE");

      String all = log.allText();
      for (String secret :
          List.of("wrong-key-value", "TOKENVALUE", "COOKIEVALUE", "PAYLOADMARKER", "k1")) {
        assertFalse(all.contains(secret), secret + " trong " + all);
      }
    }
  }
}
