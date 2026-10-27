package com.tpp.simulator.mapping;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.Locale;

/**
 * Bộ biên dịch mẫu ngày giờ dùng chung cho lúc lưu, thử mapping và nhận callback (RD-08). Thuần,
 * không trạng thái.
 *
 * <p>Mẫu được biên dịch với kỷ nguyên mặc định (để {@code yyyy} chạy được dưới {@code STRICT}),
 * {@code ResolverStyle.STRICT} (ngày giờ không tồn tại bị từ chối, không tự điều chỉnh) và {@code
 * Locale.ENGLISH} (tên tháng, thứ bằng tiếng Anh chuẩn).
 */
public final class TimestampPatterns {

  /** Độ dài tối đa của mẫu (BR11.3). */
  public static final int MAX_PATTERN_LENGTH = 100;

  private static final ZoneId PROBE_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
  // Giờ chiều để phủ chỉ báo sáng/chiều của mẫu 12 giờ.
  private static final ZonedDateTime PROBE = ZonedDateTime.of(2001, 2, 3, 16, 5, 6, 0, PROBE_ZONE);

  private TimestampPatterns() {}

  /**
   * Biên dịch mẫu.
   *
   * @throws IllegalArgumentException nếu mẫu sai cú pháp hoặc quá dài
   */
  public static DateTimeFormatter compile(String pattern) {
    if (pattern == null || pattern.isEmpty() || pattern.length() > MAX_PATTERN_LENGTH) {
      throw new IllegalArgumentException("Invalid pattern length");
    }
    return new DateTimeFormatterBuilder()
        .appendPattern(pattern)
        .parseDefaulting(ChronoField.ERA, 1)
        .toFormatter(Locale.ENGLISH)
        .withResolverStyle(ResolverStyle.STRICT);
  }

  /**
   * Phép thử định dạng khi lưu hoặc thử mapping (BR11.3): mẫu phải đúng cú pháp, định dạng rồi phân
   * tích lại được, và in đủ năm, tháng, ngày, giờ, phút.
   *
   * @return null nếu hợp lệ, ngược lại là lý do (thông báo cố định, không lặp lại mẫu)
   */
  public static String validate(String pattern) {
    if (pattern == null || pattern.isBlank()) {
      return "Trường bắt buộc khi định dạng là PATTERN.";
    }
    if (pattern.length() > MAX_PATTERN_LENGTH) {
      return "Tối đa " + MAX_PATTERN_LENGTH + " ký tự.";
    }
    DateTimeFormatter formatter;
    try {
      formatter = compile(pattern);
    } catch (IllegalArgumentException e) {
      return "Mẫu ngày giờ sai cú pháp.";
    }
    try {
      String base = formatter.format(PROBE);
      formatter.parseBest(base, ZonedDateTime::from, LocalDateTime::from);
      boolean complete =
          !base.equals(formatter.format(PROBE.plusYears(1)))
              && !base.equals(formatter.format(PROBE.plusMonths(1)))
              && !base.equals(formatter.format(PROBE.plusDays(1)))
              && !base.equals(formatter.format(PROBE.plusHours(1)))
              && !base.equals(formatter.format(PROBE.plusMinutes(1)));
      if (!complete) {
        return "Mẫu phải có đủ năm, tháng, ngày, giờ và phút.";
      }
    } catch (DateTimeException e) {
      return "Mẫu không đủ thông tin để xác định ngày giờ.";
    }
    return null;
  }

  /**
   * Phân tích chuỗi theo mẫu, khớp toàn bộ và chặt. Múi giờ hoặc offset trong mẫu được ưu tiên,
   * ngược lại dùng {@code defaultZone} (giờ trùng lấy offset sớm hơn, giờ không tồn tại được dời
   * tới).
   *
   * @throws DateTimeException nếu không khớp hoặc ngày giờ không tồn tại
   */
  public static Instant parse(String text, DateTimeFormatter formatter, ZoneId defaultZone) {
    TemporalAccessor parsed = formatter.parseBest(text, ZonedDateTime::from, LocalDateTime::from);
    if (parsed instanceof ZonedDateTime zoned) {
      return zoned.toInstant();
    }
    return ((LocalDateTime) parsed).atZone(defaultZone).toInstant();
  }
}
