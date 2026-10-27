package com.tpp.simulator.ledger;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * Ngữ nghĩa bộ lọc thời gian ở độ chính xác giây (BR19.1, PD-05), dựng ở MỘT chỗ.
 *
 * <p>Đầu vào là ngày giờ ISO 8601 có múi giờ. Phần thập phân của giây được nhận (1 đến 9 chữ số,
 * phần quá micro giây bị cắt, không làm tròn). Giá trị KHÔNG có phần thập phân nghĩa là cả giây đó:
 * cận dưới tính từ {@code .000000}, cận trên đến {@code .999999}. "Có phần thập phân hay không" xác
 * định bằng văn bản gốc, nên {@code .000} vẫn là có.
 *
 * <p>Không bao giờ ném ngoài {@link DateTimeException}: tính bằng số học {@code long} trên epoch
 * (không đổi sang UTC nên không tràn với năm gần 999999999), kẹp vào khoảng lưu được của PostgreSQL
 * ({@code 0001-01-01T00:00:00Z} đến {@code 9999-12-31T23:59:59.999999Z}), còn việc kiểm tra "cận
 * dưới lớn hơn cận trên" so các giá trị CHƯA kẹp.
 */
public final class TimeBounds {

  private static final long MIN_EPOCH = -62_135_596_800L; // 0001-01-01T00:00:00Z
  private static final long MAX_EPOCH = 253_402_300_799L; // 9999-12-31T23:59:59Z
  private static final int MAX_MICRO_NANOS = 999_999_000;
  private static final Pattern HAS_FRACTION = Pattern.compile("(?i)T\\d{2}:\\d{2}:\\d{2}\\.\\d");

  private TimeBounds() {}

  /** Một cận đã phân tích: giá trị chưa kẹp để so thứ tự, và giá trị đã kẹp để truy vấn. */
  public record Bound(long epochSecond, int nanos, OffsetDateTime clamped) {

    /** true nếu cận này (chưa kẹp) lớn hơn cận kia (chưa kẹp). */
    public boolean isAfter(Bound other) {
      return epochSecond > other.epochSecond
          || (epochSecond == other.epochSecond && nanos > other.nanos);
    }
  }

  /**
   * @param text ngày giờ ISO 8601 có múi giờ
   * @param upper true cho {@code receivedTo} (không có phần thập phân thì đến hết giây)
   * @throws DateTimeException nếu không phải ngày giờ ISO 8601 có múi giờ hợp lệ
   */
  public static Bound parse(String text, boolean upper) {
    OffsetDateTime parsed = OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    long epoch = parsed.toEpochSecond();
    boolean fraction = HAS_FRACTION.matcher(text).find();
    int nanos = fraction ? parsed.getNano() / 1000 * 1000 : upper ? MAX_MICRO_NANOS : 0;

    long clampedEpoch = epoch;
    int clampedNanos = nanos;
    if (epoch < MIN_EPOCH) {
      clampedEpoch = MIN_EPOCH;
      clampedNanos = 0;
    } else if (epoch > MAX_EPOCH) {
      clampedEpoch = MAX_EPOCH;
      clampedNanos = MAX_MICRO_NANOS;
    }
    OffsetDateTime clamped =
        OffsetDateTime.ofInstant(Instant.ofEpochSecond(clampedEpoch, clampedNanos), ZoneOffset.UTC);
    return new Bound(epoch, nanos, clamped);
  }
}
