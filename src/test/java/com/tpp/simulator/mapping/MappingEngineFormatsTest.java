package com.tpp.simulator.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** AC1.7.x: MappingEngine với bốn định dạng timestamp (BR17.x). */
class MappingEngineFormatsTest {

  private static final ZoneId HCM = ZoneId.of("Asia/Ho_Chi_Minh");

  private final MappingEngine engine = new MappingEngine();

  private MappingResult map(TimestampFormat format, String pattern, String json) {
    return engine.map(json, new MappingConfig("$.id", "$.st", "$.ts", format, pattern, HCM));
  }

  private static String payload(String timestampJson) {
    return "{\"id\":\"T1\",\"st\":\"OK\",\"ts\":" + timestampJson + "}";
  }

  private void assertTimestamp(MappingResult result, String expectedUtc) {
    assertEquals(null, result.timestamp().errorCode(), result.timestamp().error());
    assertEquals(expectedUtc, result.timestamp().value());
    assertEquals(Instant.parse(expectedUtc), result.businessTimestampUtc());
    assertEquals(MappingOutcome.SUCCESS, result.outcome());
  }

  private void assertUnparseable(MappingResult result) {
    assertEquals(MappingErrorCode.UNPARSEABLE, result.timestamp().errorCode());
    assertNull(result.businessTimestampUtc());
    assertEquals(MappingOutcome.MAPPING_ERROR, result.outcome());
    // Lỗi timestamp không ảnh hưởng hai trường còn lại (BR17.5).
    assertEquals("T1", result.transactionId().value());
    assertEquals("OK", result.status().value());
  }

  // ---------- UNIX_SECONDS ----------

  @Test
  void unixSecondsAcceptsJsonNumbersAndDigitStrings() {
    assertTimestamp(
        map(TimestampFormat.UNIX_SECONDS, null, payload("1760850000")), "2025-10-19T05:00:00Z");
    assertTimestamp(
        map(TimestampFormat.UNIX_SECONDS, null, payload("\"1760850000\"")), "2025-10-19T05:00:00Z");
    assertTimestamp(
        map(TimestampFormat.UNIX_SECONDS, null, payload("1760850000.123")),
        "2025-10-19T05:00:00.123Z");
    assertTimestamp(
        map(TimestampFormat.UNIX_SECONDS, null, payload("\"1760850000.5\"")),
        "2025-10-19T05:00:00.500Z");
  }

  @Test
  void unixSecondsRejectsBadShapesWithoutFailingOtherFields() {
    for (String bad :
        new String[] {
          "\"abc\"",
          "-5",
          "true",
          "false",
          "\"1760850000.\"",
          "\".5\"",
          "1760850000000",
          "1e999999999",
          "\"\\u0031\\u0032x\""
        }) {
      assertUnparseable(map(TimestampFormat.UNIX_SECONDS, null, payload(bad)));
    }
  }

  @Test
  void unixSecondsHugeNegativeExponentIsTheEpoch() {
    assertTimestamp(
        map(TimestampFormat.UNIX_SECONDS, null, payload("1e-999999999")), "1970-01-01T00:00:00Z");
  }

  @Test
  void unixSecondsObjectAndArrayAreWrongType() {
    for (String bad : new String[] {"{\"a\":1}", "[1]"}) {
      MappingResult result = map(TimestampFormat.UNIX_SECONDS, null, payload(bad));
      assertEquals(MappingErrorCode.WRONG_TYPE, result.timestamp().errorCode());
      assertEquals(MappingOutcome.MAPPING_ERROR, result.outcome());
    }
  }

  // ---------- UNIX_MILLIS ----------

  @Test
  void unixMillisAcceptsNumbersAndStringsAndRejectsSeconds() {
    assertTimestamp(
        map(TimestampFormat.UNIX_MILLIS, null, payload("1760850000000")), "2025-10-19T05:00:00Z");
    assertTimestamp(
        map(TimestampFormat.UNIX_MILLIS, null, payload("\"1760850000123\"")),
        "2025-10-19T05:00:00.123Z");
    assertUnparseable(map(TimestampFormat.UNIX_MILLIS, null, payload("4102444800001")));
  }

  // ---------- PATTERN ----------

  @Test
  void patternParsesInTheDefaultZone() {
    assertTimestamp(
        map(TimestampFormat.PATTERN, "yyyy-MM-dd HH:mm:ss", payload("\"2026-10-19 10:00:00\"")),
        "2026-10-19T03:00:00Z");
  }

  @Test
  void patternWithAnOffsetIgnoresTheDefaultZone() {
    assertTimestamp(
        map(TimestampFormat.PATTERN, "dd/MMM/yyyy HH:mm Z", payload("\"19/Oct/2026 10:00 +0700\"")),
        "2026-10-19T03:00:00Z");
  }

  @Test
  void patternAcceptsAJsonNumberByItsOriginalText() {
    assertTimestamp(
        map(TimestampFormat.PATTERN, "yyyyMMddHHmmss", payload("20261019100000")),
        "2026-10-19T03:00:00Z");
  }

  @Test
  void patternRejectsNonexistentDatesExtraCharactersAndBooleans() {
    for (String bad :
        new String[] {
          "\"2026-02-30 10:00:00\"", "\"2026-13-01 10:00:00\"", "\"2026-10-19 24:00:00\"",
          "\"2026-10-19 10:00:00 extra\"", "\"abc\"", "true"
        }) {
      assertUnparseable(map(TimestampFormat.PATTERN, "yyyy-MM-dd HH:mm:ss", payload(bad)));
    }
  }

  @Test
  void aBrokenStoredPatternNeverFailsTheCallback() {
    assertUnparseable(map(TimestampFormat.PATTERN, "yyyy-MM-dd {", payload("\"2026-10-19\"")));
    assertUnparseable(map(TimestampFormat.PATTERN, null, payload("\"2026-10-19\"")));
  }

  // ---------- ISO_8601 và quy tắc chung ----------

  @Test
  void isoRejectsNumbersAndKeepsU1Behaviour() {
    assertUnparseable(map(TimestampFormat.ISO_8601, null, payload("1760850000")));
    assertTimestamp(
        map(TimestampFormat.ISO_8601, null, payload("\"2026-10-19T10:00:00\"")),
        "2026-10-19T03:00:00Z");
  }

  @Test
  void aNullFormatMeansIso8601() {
    assertTimestamp(
        engine.map(
            payload("\"2026-10-19T10:00:00+07:00\""),
            new MappingConfig("$.id", "$.st", "$.ts", null, null, HCM)),
        "2026-10-19T03:00:00Z");
  }

  @Test
  void missingNullOrBlankTimestampIsOnlyAWarningForEveryFormat() {
    for (TimestampFormat format : TimestampFormat.values()) {
      for (String missing : new String[] {"null", "\"\"", "\"   \""}) {
        MappingResult result = map(format, "yyyy-MM-dd HH:mm:ss", payload(missing));
        assertNull(result.timestamp().errorCode(), format + " " + missing);
        assertEquals(MappingOutcome.SUCCESS, result.outcome());
      }
      MappingResult absent = map(format, "yyyy-MM-dd HH:mm:ss", "{\"id\":\"T1\",\"st\":\"OK\"}");
      assertNull(absent.timestamp().errorCode());
      assertEquals(MappingOutcome.SUCCESS, absent.outcome());
    }
  }

  @Test
  void yearsOutsideOneToNineNineNineNineAreUnparseableForEveryFormat() {
    assertUnparseable(map(TimestampFormat.ISO_8601, null, payload("\"+10000-01-01T00:00:00Z\"")));
    assertUnparseable(
        map(TimestampFormat.PATTERN, "yyyy-MM-dd HH:mm", payload("\"0000-01-01 00:00\"")));
  }
}
