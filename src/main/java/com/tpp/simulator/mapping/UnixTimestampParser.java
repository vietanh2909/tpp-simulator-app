package com.tpp.simulator.mapping;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Đổi timestamp Unix (giây hoặc mili giây) sang thời điểm UTC chính xác đến micro giây (BR17.1,
 * BR17.2, RD-08). Thuần, không trạng thái.
 *
 * <p>Giá trị được hiểu theo số thập phân chính xác, không qua số thực dấu phẩy động. Khoảng hợp lệ
 * 1970-01-01 đến 2100-01-01 UTC (tính cả hai đầu) được kiểm tra theo bậc độ lớn bằng số học {@code
 * long} TRƯỚC khi tạo {@link BigDecimal}, nên {@code 1e999999999} cho kết quả rỗng ngay mà không
 * khai triển, còn {@code 1e-999999999} cho đúng thời điểm gốc.
 */
public final class UnixTimestampParser {

  private static final Pattern STRING_FORM = Pattern.compile("^([0-9]+)(?:\\.([0-9]+))?$");
  private static final Pattern NUMBER_FORM =
      Pattern.compile("^([0-9]+)(?:\\.([0-9]+))?(?:[eE]([+-]?)([0-9]+))?$");

  /** 2100-01-01T00:00:00Z tính bằng giây. */
  private static final long MAX_SECONDS = 4_102_444_800L;

  private static final long MAX_MICROS = MAX_SECONDS * 1_000_000L;

  // Số mũ có nhiều hơn chừng này chữ số thì chắc chắn ngoài mọi khoảng cần xét.
  private static final int MAX_EXPONENT_DIGITS = 9;
  private static final long HUGE_EXPONENT = 10_000_000_000L;

  private UnixTimestampParser() {}

  /**
   * @param text văn bản gốc của số JSON ({@code jsonNumber} true) hoặc của chuỗi JSON
   * @param millis true nếu đơn vị là mili giây
   * @return thời điểm UTC, hoặc rỗng nếu sai dạng, âm hoặc ngoài khoảng 1970 đến 2100
   */
  public static Optional<Instant> parse(String text, boolean jsonNumber, boolean millis) {
    Matcher matcher = (jsonNumber ? NUMBER_FORM : STRING_FORM).matcher(text);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    String integerPart = matcher.group(1);
    String fractionPart = matcher.group(2) == null ? "" : matcher.group(2);
    long exponent = jsonNumber ? exponent(matcher.group(3), matcher.group(4)) : 0;
    // (nhóm 3, 4 chỉ có ở dạng số JSON)

    String digits = integerPart + fractionPart;
    int leadingZeros = 0;
    while (leadingZeros < digits.length() && digits.charAt(leadingZeros) == '0') {
      leadingZeros++;
    }
    if (leadingZeros == digits.length()) {
      return Optional.of(Instant.EPOCH);
    }
    digits = digits.substring(leadingZeros);
    // Vị trí dấu thập phân tính từ đầu chuỗi chữ số đã bỏ số 0 đầu: số chữ số phần nguyên.
    long integerDigits = (long) integerPart.length() - leadingZeros + exponent;

    int maxIntegerDigits = millis ? 13 : 10;
    if (integerDigits > maxIntegerDigits) {
      return Optional.empty();
    }
    if (integerDigits < -9) {
      return Optional.of(Instant.EPOCH);
    }
    BigDecimal value =
        new BigDecimal(new BigInteger(digits), (int) (digits.length() - integerDigits));
    BigDecimal micros = value.multiply(BigDecimal.valueOf(millis ? 1_000L : 1_000_000L));
    long truncated = micros.setScale(0, RoundingMode.DOWN).longValueExact();
    if (truncated < 0 || truncated > MAX_MICROS) {
      return Optional.empty();
    }
    return Optional.of(Instant.EPOCH.plus(truncated, ChronoUnit.MICROS));
  }

  private static long exponent(String sign, String digits) {
    if (digits == null) {
      return 0;
    }
    String trimmed = digits.replaceFirst("^0+(?=.)", "");
    long magnitude =
        trimmed.length() > MAX_EXPONENT_DIGITS ? HUGE_EXPONENT : Long.parseLong(trimmed);
    return "-".equals(sign) ? -magnitude : magnitude;
  }
}
