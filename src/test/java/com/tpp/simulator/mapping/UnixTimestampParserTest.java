package com.tpp.simulator.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** BR17.1, BR17.2, RD-08: Unix giây và mili giây, chính xác, khoảng 1970 đến 2100. */
class UnixTimestampParserTest {

  private static Optional<Instant> seconds(String text, boolean jsonNumber) {
    return UnixTimestampParser.parse(text, jsonNumber, false);
  }

  private static Optional<Instant> millis(String text, boolean jsonNumber) {
    return UnixTimestampParser.parse(text, jsonNumber, true);
  }

  @Test
  void secondsAndMillisecondsGiveTheSameInstant() {
    Instant expected = Instant.parse("2025-10-19T05:00:00Z");

    assertEquals(Optional.of(expected), seconds("1760850000", true));
    assertEquals(Optional.of(expected), seconds("1760850000", false));
    assertEquals(Optional.of(expected), millis("1760850000000", true));
  }

  @Test
  void fractionIsKeptToMicrosecondsAndTruncatedBeyond() {
    assertEquals(
        Optional.of(Instant.parse("2025-10-19T05:00:00.123Z")), seconds("1760850000.123", true));
    assertEquals(
        Optional.of(Instant.parse("2025-10-19T05:00:00.500Z")), seconds("1760850000.5", false));
    assertEquals(
        Optional.of(Instant.parse("2025-10-19T05:00:00.123456Z")),
        seconds("1760850000.1234569999", false));
    assertEquals(
        Optional.of(Instant.parse("2025-10-19T05:00:00.123Z")), millis("1760850000123", true));
    assertEquals(
        Optional.of(Instant.parse("2025-10-19T05:00:00.123456Z")),
        millis("1760850000123.456789", true));
  }

  @ParameterizedTest
  @ValueSource(strings = {"1760850000.", ".5", "abc", "", " 1", "1 ", "+1", "-5", "1,000", "1e3"})
  void malformedStringsAreUnparseable(String text) {
    assertEquals(Optional.empty(), seconds(text, false));
  }

  @ParameterizedTest
  @ValueSource(strings = {"-5", "-0", "1.", ".5", "0x10", "1e", "e5", "1e+", "--1"})
  void malformedOrNegativeJsonNumbersAreUnparseable(String text) {
    assertEquals(Optional.empty(), seconds(text, true));
  }

  @Test
  void rangeIsInclusiveAtTheEpochAndAtYear2100() {
    assertEquals(Optional.of(Instant.EPOCH), seconds("0", false));
    assertEquals(Optional.of(Instant.EPOCH), seconds("0000", false));
    assertEquals(Optional.of(Instant.parse("2100-01-01T00:00:00Z")), seconds("4102444800", false));
    assertEquals(Optional.empty(), seconds("4102444800.000001", false));
    assertEquals(Optional.empty(), seconds("4102444801", false));
    assertEquals(
        Optional.of(Instant.parse("2100-01-01T00:00:00Z")), millis("4102444800000", false));
    assertEquals(Optional.empty(), millis("4102444800001", false));
  }

  @Test
  void millisecondsSentAsSecondsAreOutOfRange() {
    assertEquals(Optional.empty(), seconds("1760850000000", true));
  }

  @Test
  void anHugePositiveExponentIsRejectedWithoutExpandingIt() {
    long start = System.nanoTime();

    assertEquals(Optional.empty(), seconds("1e999999999", true));
    assertEquals(Optional.empty(), seconds("1E+99999999999999999999", true));
    assertEquals(Optional.empty(), millis("9.9e13", true));

    assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1000);
  }

  @Test
  void aHugeNegativeExponentTruncatesToTheEpoch() {
    assertEquals(Optional.of(Instant.EPOCH), seconds("1e-999999999", true));
    assertEquals(Optional.of(Instant.EPOCH), seconds("5e-99999999999999999999", true));
    assertEquals(Optional.of(Instant.EPOCH), seconds("1e-10", true));
  }

  @Test
  void zeroWithAHugeExponentIsStillTheEpoch() {
    assertEquals(Optional.of(Instant.EPOCH), seconds("0e999999999", true));
  }

  @ParameterizedTest
  @CsvSource({
    "1.76085e9, 2025-10-19T05:00:00Z",
    "1.76085E+9, 2025-10-19T05:00:00Z",
    "1760850000e0, 2025-10-19T05:00:00Z",
    "17608500e2, 2025-10-19T05:00:00Z",
    "176085000000e-2, 2025-10-19T05:00:00Z",
    "1760850000.0, 2025-10-19T05:00:00Z",
    "0.000001, 1970-01-01T00:00:00.000001Z",
    "0.0000019, 1970-01-01T00:00:00.000001Z"
  })
  void jsonNumbersWithExponentsAreEvaluatedExactly(String text, String expected) {
    assertEquals(Optional.of(Instant.parse(expected)), seconds(text, true));
  }

  @Test
  void aVeryLongDigitStringIsRejectedQuickly() {
    String longDigits = "1" + "0".repeat(5000);

    assertEquals(Optional.empty(), seconds(longDigits, false));
  }

  @Test
  void aLongFractionIsHandledExactly() {
    String text = "1760850000." + "9".repeat(2000);

    assertEquals(Optional.of(Instant.parse("2025-10-19T05:00:00.999999Z")), seconds(text, false));
  }
}
