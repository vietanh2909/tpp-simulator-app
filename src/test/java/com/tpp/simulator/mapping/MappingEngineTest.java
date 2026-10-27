package com.tpp.simulator.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MappingEngineTest {

  private static final ZoneId HCM = ZoneId.of("Asia/Ho_Chi_Minh");

  private final MappingEngine engine = new MappingEngine();

  private static MappingConfig config(String tx, String status, String timestamp) {
    return new MappingConfig(tx, status, timestamp, HCM);
  }

  private MappingResult map(String json) {
    return engine.map(json, config("$.transId", "$.code", "$.time"));
  }

  // ---------- AC1.6.1, AC1.6.2 ----------

  @Test
  void ac161_extractsTransactionIdStatusAndNormalizesTimestampToUtc() {
    MappingResult result =
        map("{\"transId\":\"T1\",\"code\":\"00\",\"time\":\"2026-10-19T10:00:00+07:00\"}");

    assertEquals("T1", result.transactionId().value());
    assertEquals("00", result.status().value());
    assertEquals("2026-10-19T03:00:00Z", result.timestamp().value());
    assertEquals(Instant.parse("2026-10-19T03:00:00Z"), result.businessTimestampUtc());
    assertEquals(MappingOutcome.SUCCESS, result.outcome());
  }

  @Test
  void ac162_followsNestedObjectPaths() {
    MappingResult result =
        engine.map(
            "{\"data\":{\"transId\":\"T2\",\"state\":{\"code\":\"ok\"}}}",
            config("$.data.transId", "$.data.state.code", null));

    assertEquals("T2", result.transactionId().value());
    assertEquals("ok", result.status().value());
    assertEquals(MappingOutcome.SUCCESS, result.outcome());
  }

  // ---------- AC1.6.3 và nguyên văn số ----------

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "0|0",
        "1.50|1.50",
        "1e3|1e3",
        "-12|-12",
        "123456789012345678901234567890|123456789012345678901234567890",
        "0.1000000000000000055511151231257827|0.1000000000000000055511151231257827",
        "true|true",
        "false|false"
      })
  void ac163_numbersAndBooleansKeepTheirSourceText(String literal, String expected) {
    MappingResult result = map("{\"transId\":\"T1\",\"code\":" + literal + "}");

    assertEquals(expected, result.status().value());
    assertNull(result.status().errorCode());
  }

  @Test
  void statusIsNeverInterpretedAndStringKeepsWhitespaceAndUnicode() {
    MappingResult result = map("{\"transId\":\" T 1 \",\"code\":\"Thành công\"}");

    assertEquals(" T 1 ", result.transactionId().value());
    assertEquals("Thành công", result.status().value());
  }

  // ---------- AC1.6.4 ----------

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "{\"code\":\"00\"}|NOT_FOUND",
        "{\"transId\":null,\"code\":\"00\"}|EMPTY",
        "{\"transId\":\"\",\"code\":\"00\"}|EMPTY",
        "{\"transId\":\"   \",\"code\":\"00\"}|EMPTY",
        "{\"transId\":{\"a\":1},\"code\":\"00\"}|WRONG_TYPE",
        "{\"transId\":[\"a\"],\"code\":\"00\"}|WRONG_TYPE"
      })
  void ac164_transactionIdProblemsAreReportedPerFieldWhileOthersStillMap(
      String json, String expectedCode) {
    MappingResult result = map(json);

    assertEquals(MappingErrorCode.valueOf(expectedCode), result.transactionId().errorCode());
    assertNotNull(result.transactionId().error());
    assertNull(result.transactionId().value());
    assertEquals("00", result.status().value());
    assertEquals(MappingOutcome.MAPPING_ERROR, result.outcome());
  }

  @Test
  void ac164_statusProblemDoesNotBreakTransactionId() {
    MappingResult result = map("{\"transId\":\"T9\",\"code\":null}");

    assertEquals("T9", result.transactionId().value());
    assertEquals(MappingErrorCode.EMPTY, result.status().errorCode());
    assertEquals(MappingOutcome.MAPPING_ERROR, result.outcome());
  }

  @Test
  void valueOf255CharactersIsAcceptedAnd256IsTooLong() {
    MappingResult ok = map("{\"transId\":\"" + "a".repeat(255) + "\",\"code\":\"00\"}");
    MappingResult tooLong = map("{\"transId\":\"" + "a".repeat(256) + "\",\"code\":\"00\"}");

    assertNull(ok.transactionId().errorCode());
    assertEquals(MappingErrorCode.TOO_LONG, tooLong.transactionId().errorCode());
    assertEquals(MappingOutcome.MAPPING_ERROR, tooLong.outcome());
  }

  @Test
  void lengthIsCountedInCodePointsNotUtf16Units() {
    String emoji255 = "\uD83D\uDE00".repeat(255);
    MappingResult result = map("{\"transId\":\"" + emoji255 + "\",\"code\":\"00\"}");

    assertNull(result.transactionId().errorCode());
  }

  // ---------- AC1.6.5 và gốc không phải object ----------

  @ParameterizedTest
  @ValueSource(strings = {"[1,2]", "5", "\"text\"", "null", "true", "[]"})
  void ac165_nonObjectRootFailsAllThreeConfiguredFields(String json) {
    MappingResult result = map(json);

    assertEquals(MappingErrorCode.PAYLOAD_NOT_OBJECT, result.transactionId().errorCode());
    assertEquals(MappingErrorCode.PAYLOAD_NOT_OBJECT, result.status().errorCode());
    assertEquals(MappingErrorCode.PAYLOAD_NOT_OBJECT, result.timestamp().errorCode());
    assertEquals(MappingOutcome.MAPPING_ERROR, result.outcome());
  }

  @Test
  void nonObjectRootWithoutTimestampPathLeavesTimestampSkipped() {
    MappingResult result = engine.map("[1,2]", config("$.a", "$.b", null));

    assertEquals(MappingErrorCode.PAYLOAD_NOT_OBJECT, result.transactionId().errorCode());
    assertNull(result.timestamp().errorCode());
    assertNull(result.timestamp().warning());
  }

  // ---------- Đường dẫn và khóa trùng ----------

  @Test
  void duplicateKeysLastOneWins() {
    MappingResult result = map("{\"transId\":\"first\",\"code\":\"A\",\"transId\":\"last\"}");

    assertEquals("last", result.transactionId().value());
  }

  @Test
  void laterDuplicateReplacesEarlierObjectEntirely() {
    MappingResult replacedByScalar =
        engine.map("{\"a\":{\"b\":\"1\"},\"a\":5,\"c\":\"x\"}", config("$.a.b", "$.c", null));
    MappingResult replacedByOtherObject =
        engine.map(
            "{\"a\":{\"b\":\"1\"},\"a\":{\"z\":2},\"c\":\"x\"}", config("$.a.b", "$.c", null));
    MappingResult replacedByObjectWithKey =
        engine.map(
            "{\"a\":{\"b\":\"1\"},\"a\":{\"b\":\"2\"},\"c\":\"x\"}", config("$.a.b", "$.c", null));

    assertEquals(MappingErrorCode.NOT_FOUND, replacedByScalar.transactionId().errorCode());
    assertEquals(MappingErrorCode.NOT_FOUND, replacedByOtherObject.transactionId().errorCode());
    assertEquals("2", replacedByObjectWithKey.transactionId().value());
  }

  @Test
  void keysAreCaseSensitiveAndArraysAreNotTraversed() {
    MappingResult caseMismatch = map("{\"TransId\":\"T1\",\"code\":\"00\"}");
    MappingResult throughArray =
        engine.map("{\"a\":[{\"b\":\"1\"}],\"c\":\"x\"}", config("$.a.b", "$.c", null));

    assertEquals(MappingErrorCode.NOT_FOUND, caseMismatch.transactionId().errorCode());
    assertEquals(MappingErrorCode.NOT_FOUND, throughArray.transactionId().errorCode());
  }

  @Test
  void pathThroughAScalarIsNotFound() {
    MappingResult result = engine.map("{\"a\":\"text\",\"c\":\"x\"}", config("$.a.b", "$.c", null));

    assertEquals(MappingErrorCode.NOT_FOUND, result.transactionId().errorCode());
    assertEquals("x", result.status().value());
  }

  @Test
  void targetObjectIsWrongTypeEvenWhenAnotherPathGoesBelowIt() {
    MappingResult result = engine.map("{\"a\":{\"b\":\"deep\"}}", config("$.a", "$.a.b", null));

    assertEquals(MappingErrorCode.WRONG_TYPE, result.transactionId().errorCode());
    assertEquals("deep", result.status().value());
  }

  @ParameterizedTest
  @ValueSource(strings = {"transId", "$", "$.", "$..a", "$.a.", "$.a..b", ""})
  void malformedPathsAreAlwaysNotFound(String path) {
    MappingResult result =
        engine.map(
            "{\"transId\":\"T1\",\"\":\"empty-key\",\"a\":{\"\":1}}",
            config(path, "$.transId", null));

    assertEquals(MappingErrorCode.NOT_FOUND, result.transactionId().errorCode());
    assertEquals("T1", result.status().value());
  }

  @Test
  void largeUnrelatedStructuresAreSkipped() {
    StringBuilder json = new StringBuilder("{\"noise\":[");
    for (int i = 0; i < 2000; i++) {
      json.append(i == 0 ? "" : ",").append("{\"x\":[1,2,{\"y\":null}]}");
    }
    json.append("],\"other\":{\"deep\":{\"deeper\":1}},\"transId\":\"T1\",\"code\":\"00\"}");

    MappingResult result = map(json.toString());

    assertEquals("T1", result.transactionId().value());
    assertEquals("00", result.status().value());
  }

  @Test
  void invalidJsonIsRejectedAsIllegalArgument() {
    assertThrows(IllegalArgumentException.class, () -> map("{\"transId\":"));
  }

  // ---------- Timestamp ----------

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "2026-10-19T10:00:00Z|2026-10-19T10:00:00Z",
        "2026-10-19T10:00:00+07:00|2026-10-19T03:00:00Z",
        "2026-10-19T10:00:00-05:30|2026-10-19T15:30:00Z",
        "2026-10-19T10:00:00.123456+07:00|2026-10-19T03:00:00.123456Z",
        "2026-10-19T10:00:00.123456789Z|2026-10-19T10:00:00.123456Z",
        "2026-10-19T10:00:00|2026-10-19T03:00:00Z",
        "2026-10-19T10:00|2026-10-19T03:00:00Z",
        "2026-10-19T10:00:00+07:00[Asia/Ho_Chi_Minh]|2026-10-19T03:00:00Z"
      })
  void timestampIsNormalizedToUtc_localTimeUsesDefaultZone(String input, String expectedUtc) {
    MappingResult result = map("{\"transId\":\"T1\",\"code\":\"00\",\"time\":\"" + input + "\"}");

    assertNull(result.timestamp().errorCode(), result.timestamp().error());
    assertEquals(expectedUtc, result.timestamp().value());
    assertEquals(Instant.parse(expectedUtc), result.businessTimestampUtc());
    assertEquals(MappingOutcome.SUCCESS, result.outcome());
  }

  @Test
  void localTimeFollowsTheConfiguredDefaultZone() {
    MappingResult utc =
        engine.map(
            "{\"t\":\"1\",\"s\":\"2\",\"w\":\"2026-10-19T10:00:00\"}",
            new MappingConfig("$.t", "$.s", "$.w", ZoneId.of("UTC")));

    assertEquals("2026-10-19T10:00:00Z", utc.timestamp().value());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "+10000-01-01T00:00:00Z",
        "+999999999-12-31T23:59:59Z",
        "-0001-01-01T00:00:00Z",
        "-999999999-01-01T00:00:00Z",
        "0000-12-31T23:59:59Z",
        "9999-12-31T23:59:59-05:00",
        "0001-01-01T00:00:00"
      })
  void yearOutsideOneToNineNineNineNineIsUnparseableSoPostgresNeverSeesIt(String input) {
    MappingResult result = map("{\"transId\":\"T1\",\"code\":\"00\",\"time\":\"" + input + "\"}");

    assertEquals(MappingErrorCode.UNPARSEABLE, result.timestamp().errorCode());
    assertNull(result.businessTimestampUtc());
    assertEquals(MappingOutcome.MAPPING_ERROR, result.outcome());
    assertEquals("T1", result.transactionId().value());
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "0001-01-01T00:00:00Z|0001-01-01T00:00:00Z",
        "9999-12-31T23:59:59Z|9999-12-31T23:59:59Z",
        "9999-12-31T23:59:59.999999Z|9999-12-31T23:59:59.999999Z"
      })
  void boundaryYearsOneAndNineNineNineNineAreStillAccepted(String input, String expectedUtc) {
    MappingResult result = map("{\"transId\":\"T1\",\"code\":\"00\",\"time\":\"" + input + "\"}");

    assertNull(result.timestamp().errorCode(), result.timestamp().error());
    assertEquals(expectedUtc, result.timestamp().value());
    assertEquals(Instant.parse(expectedUtc), result.businessTimestampUtc());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2026-10-19",
        "19/10/2026 10:00:00",
        "yesterday",
        "1760842800",
        "2026-13-40T10:00:00Z"
      })
  void unparseableTimestampOnlyAffectsTheTimestampField(String input) {
    MappingResult result = map("{\"transId\":\"T1\",\"code\":\"00\",\"time\":\"" + input + "\"}");

    assertEquals(MappingErrorCode.UNPARSEABLE, result.timestamp().errorCode());
    assertNull(result.businessTimestampUtc());
    assertEquals("T1", result.transactionId().value());
    assertEquals("00", result.status().value());
    assertEquals(MappingOutcome.MAPPING_ERROR, result.outcome());
  }

  @Test
  void numericTimestampIsUnparseableAndStructuredTimestampIsWrongType() {
    MappingResult number = map("{\"transId\":\"T1\",\"code\":\"00\",\"time\":1760842800}");
    MappingResult object = map("{\"transId\":\"T1\",\"code\":\"00\",\"time\":{\"a\":1}}");
    MappingResult array = map("{\"transId\":\"T1\",\"code\":\"00\",\"time\":[1]}");

    assertEquals(MappingErrorCode.UNPARSEABLE, number.timestamp().errorCode());
    assertEquals(MappingErrorCode.WRONG_TYPE, object.timestamp().errorCode());
    assertEquals(MappingErrorCode.WRONG_TYPE, array.timestamp().errorCode());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"transId\":\"T1\",\"code\":\"00\"}",
        "{\"transId\":\"T1\",\"code\":\"00\",\"time\":null}",
        "{\"transId\":\"T1\",\"code\":\"00\",\"time\":\"\"}",
        "{\"transId\":\"T1\",\"code\":\"00\",\"time\":\"  \"}"
      })
  void missingTimestampValueWarnsButIsNotAnError(String json) {
    MappingResult result = map(json);

    assertNull(result.timestamp().errorCode());
    assertNotNull(result.timestamp().warning());
    assertNull(result.businessTimestampUtc());
    assertEquals(MappingOutcome.SUCCESS, result.outcome());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void withoutTimestampPathTheTimestampIsSilentlySkipped(String path) {
    MappingResult result =
        engine.map(
            "{\"transId\":\"T1\",\"code\":\"00\",\"time\":\"garbage\"}",
            config("$.transId", "$.code", path));

    assertNull(result.timestamp().errorCode());
    assertNull(result.timestamp().warning());
    assertNull(result.timestamp().value());
    assertEquals(MappingOutcome.SUCCESS, result.outcome());
  }

  @Test
  void nullTimestampPathIsSkippedToo() {
    MappingResult result =
        engine.map("{\"transId\":\"T1\",\"code\":\"00\"}", config("$.transId", "$.code", null));

    assertNull(result.timestamp().warning());
    assertEquals(MappingOutcome.SUCCESS, result.outcome());
  }
}
