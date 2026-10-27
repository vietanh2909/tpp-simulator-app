package com.tpp.simulator.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DateTimeException;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * BR19.1, PD-05: ngữ nghĩa thời gian theo giây, kẹp cận, không bao giờ ném ngoài DateTimeException.
 */
class TimeBoundsTest {

  private static OffsetDateTime at(String iso) {
    return OffsetDateTime.parse(iso);
  }

  @Test
  void aValueWithoutFractionMeansTheWholeSecond() {
    assertEquals(
        at("2026-10-19T11:00:00.000000Z"),
        TimeBounds.parse("2026-10-19T11:00:00Z", false).clamped());
    assertEquals(
        at("2026-10-19T11:00:00.999999Z"),
        TimeBounds.parse("2026-10-19T11:00:00Z", true).clamped());
  }

  @Test
  void aValueWithAFractionIsComparedAsSent() {
    assertEquals(
        at("2026-10-19T11:00:00.400Z"),
        TimeBounds.parse("2026-10-19T11:00:00.400Z", true).clamped());
    assertEquals(
        at("2026-10-19T11:00:00.400Z"),
        TimeBounds.parse("2026-10-19T11:00:00.4Z", false).clamped());
  }

  @Test
  void aZeroFractionIsStillAFractionAccordingToTheOriginalText() {
    assertEquals(
        at("2026-10-19T11:00:00Z"), TimeBounds.parse("2026-10-19T11:00:00.000Z", true).clamped());
    assertEquals(
        at("2026-10-19T11:00:00Z"),
        TimeBounds.parse("2026-10-19T11:00:00.000000Z", true).clamped());
  }

  @Test
  void digitsBeyondMicrosecondsAreTruncatedNotRounded() {
    assertEquals(
        at("2026-10-19T11:00:00.123456Z"),
        TimeBounds.parse("2026-10-19T11:00:00.123456999Z", true).clamped());
    assertEquals(
        at("2026-10-19T11:00:00.999999Z"),
        TimeBounds.parse("2026-10-19T11:00:00.9999999Z", false).clamped());
  }

  @Test
  void offsetsAreConvertedToUtcAndSecondsMayBeAbsent() {
    assertEquals(
        at("2026-10-19T04:00:00Z"), TimeBounds.parse("2026-10-19T11:00:00+07:00", false).clamped());
    assertEquals(
        at("2026-10-19T04:00:00Z"), TimeBounds.parse("2026-10-19T11:00+07:00", false).clamped());
  }

  @Test
  void upperBoundOfTheSameSecondIsNotBeforeTheLowerBound() {
    TimeBounds.Bound from = TimeBounds.parse("2026-10-19T11:00:00Z", false);
    TimeBounds.Bound to = TimeBounds.parse("2026-10-19T11:00:00Z", true);

    assertFalse(from.isAfter(to));
    assertTrue(to.isAfter(from));
    assertTrue(TimeBounds.parse("2026-10-19T11:00:01Z", false).isAfter(to));
  }

  @Test
  void hugeYearsAreClampedWithoutThrowing() {
    TimeBounds.Bound top = TimeBounds.parse("+999999999-12-31T23:59:59-18:00", true);
    TimeBounds.Bound bottom = TimeBounds.parse("-999999999-01-01T00:00:00+18:00", false);

    assertEquals(at("9999-12-31T23:59:59.999999Z"), top.clamped());
    assertEquals(at("0001-01-01T00:00:00Z"), bottom.clamped());
    assertTrue(top.isAfter(bottom));
    assertTrue(top.isAfter(TimeBounds.parse("2026-10-19T00:00:00Z", true)));
  }

  @Test
  void theRawOrderIsComparedBeforeClamping() {
    TimeBounds.Bound hugeFrom = TimeBounds.parse("+999999999-12-31T23:59:59-18:00", false);
    TimeBounds.Bound hugeTo = TimeBounds.parse("+999999998-12-31T23:59:59Z", true);

    assertTrue(hugeFrom.isAfter(hugeTo));
    assertEquals(hugeFrom.clamped(), hugeTo.clamped());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        " ",
        "abc",
        "2026-10-19",
        "2026-10-19T11:00:00",
        "2026-02-30T00:00:00Z",
        "2026-10-19 11:00:00Z",
        "2026-13-01T00:00:00Z",
        "2026-10-19T24:00:01Z",
        "20261019T110000Z",
        "2026-10-19T11:00:00+25:00",
        "12345678901-01-01T00:00:00Z"
      })
  void anythingThatIsNotAnIsoInstantWithAnOffsetFailsWithADateTimeException(String text) {
    assertThrows(DateTimeException.class, () -> TimeBounds.parse(text, false));
  }
}
