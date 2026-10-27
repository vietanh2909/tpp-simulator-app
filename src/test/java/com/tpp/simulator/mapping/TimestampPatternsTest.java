package com.tpp.simulator.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** BR11.3, BR17.3, BR17.4, RD-08: biên dịch, kiểm tra khi lưu và phân tích chặt. */
class TimestampPatternsTest {

  private static final ZoneId HCM = ZoneId.of("Asia/Ho_Chi_Minh");

  private static Instant parse(String pattern, String text, ZoneId zone) {
    return TimestampPatterns.parse(text, TimestampPatterns.compile(pattern), zone);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "dd/MMM/yyyy HH:mm Z",
        "yyyyMMddHHmmss",
        "dd-MM-yy HH:mm",
        "yyyy-MM-dd hh:mm a",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "yyyy-MM-dd HH:mm:ss VV"
      })
  void acceptsCompletePatterns(String pattern) {
    assertNull(TimestampPatterns.validate(pattern), pattern);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "yyyy-MM-dd", // chỉ có ngày
        "HH:mm:ss", // chỉ có giờ
        "yyyy-MM-dd hh:mm", // 12 giờ không có chỉ báo sáng chiều
        "yyyy-MM HH:mm", // thiếu ngày
        "MM-dd HH:mm", // thiếu năm
        "yyyy-MM-dd HH", // thiếu phút
        "yyyy-MM-dd mm", // thiếu giờ
        "yyyy-MM-dd HH:mm:ss '", // nháy đơn không đóng
        "yyyy-MM-dd HH:mm:ss {", // ký tự dành riêng
        "yyyy-MM-dd HH:mm:ss R" // chữ cái mẫu không tồn tại
      })
  void rejectsIncompleteOrMalformedPatterns(String pattern) {
    assertNotNull(TimestampPatterns.validate(pattern), pattern);
  }

  @Test
  void rejectsAbsentBlankAndTooLongPatterns() {
    assertNotNull(TimestampPatterns.validate(null));
    assertNotNull(TimestampPatterns.validate(""));
    assertNotNull(TimestampPatterns.validate("   "));
    assertNotNull(TimestampPatterns.validate("y".repeat(101)));
    assertThrows(IllegalArgumentException.class, () -> TimestampPatterns.compile("y".repeat(101)));
    assertThrows(IllegalArgumentException.class, () -> TimestampPatterns.compile(null));
  }

  @Test
  void validationMessagesNeverEchoThePattern() {
    String message = TimestampPatterns.validate("SECRET-PATTERN (");

    assertNotNull(message);
    assertEquals(false, message.contains("SECRET"));
  }

  @Test
  void parsesTheDefaultStyleInTheDefaultZone() {
    assertEquals(
        Instant.parse("2026-10-19T03:00:00Z"),
        parse("yyyy-MM-dd HH:mm:ss", "2026-10-19 10:00:00", HCM));
  }

  @Test
  void aZoneOrOffsetInThePatternWinsOverTheDefaultZone() {
    assertEquals(
        Instant.parse("2026-10-19T03:00:00Z"),
        parse("dd/MMM/yyyy HH:mm Z", "19/Oct/2026 10:00 +0700", ZoneId.of("UTC")));
  }

  @Test
  void englishMonthAndWeekdayNamesAreUsed() {
    assertEquals(
        Instant.parse("2026-10-19T03:00:00Z"),
        parse("EEE, dd MMM yyyy HH:mm:ss", "Mon, 19 Oct 2026 10:00:00", HCM));
  }

  @Test
  void twoDigitYearsMapToTheTwoThousands() {
    assertEquals(
        Instant.parse("2026-10-19T03:00:00Z"), parse("dd-MM-yy HH:mm", "19-10-26 10:00", HCM));
  }

  @Test
  void twelveHourPatternsUseTheAmPmMarker() {
    assertEquals(
        Instant.parse("2026-10-19T08:30:00Z"),
        parse("yyyy-MM-dd hh:mm a", "2026-10-19 03:30 PM", HCM));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2026-02-30 10:00:00", // ngày không tồn tại
        "2026-13-01 10:00:00", // tháng 13
        "2026-10-19 24:00:00", // giờ 24
        "2026-10-19 10:60:00", // phút 60
        "2026-10-19 10:00:00 extra", // ký tự thừa ở cuối
        "x 2026-10-19 10:00:00", // ký tự thừa ở đầu
        "2026-10-19", // thiếu giờ
        "abc",
        ""
      })
  void strictParsingRejectsNonexistentOrPartialValues(String text) {
    assertThrows(DateTimeException.class, () -> parse("yyyy-MM-dd HH:mm:ss", text, HCM));
  }

  @Test
  void anOverlappingLocalTimeTakesTheEarlierOffset() {
    // 2026-11-01 01:30 xảy ra hai lần ở America/New_York; lấy offset sớm hơn (-04:00).
    assertEquals(
        Instant.parse("2026-11-01T05:30:00Z"),
        parse("yyyy-MM-dd HH:mm", "2026-11-01 01:30", ZoneId.of("America/New_York")));
  }

  @Test
  void aNonexistentLocalTimeIsShiftedForwardByTheGap() {
    // 2026-03-08 02:30 không tồn tại ở America/New_York; dời tới 03:30 (-04:00).
    assertEquals(
        Instant.parse("2026-03-08T07:30:00Z"),
        parse("yyyy-MM-dd HH:mm", "2026-03-08 02:30", ZoneId.of("America/New_York")));
  }

  @Test
  void compactNumericPatternsParse() {
    assertEquals(
        Instant.parse("2026-10-19T03:00:00Z"), parse("yyyyMMddHHmmss", "20261019100000", HCM));
  }
}
