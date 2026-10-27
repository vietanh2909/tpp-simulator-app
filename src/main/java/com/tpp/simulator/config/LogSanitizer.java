package com.tpp.simulator.config;

/**
 * Làm sạch giá trị do bên gọi cung cấp trước khi ghi log (NFR3.2): thay mọi ký tự điều khiển (kể cả
 * xuống dòng) bằng khoảng trắng và cắt ở {@value #MAX_LENGTH} ký tự để không chèn được dòng log
 * giả.
 */
public final class LogSanitizer {

  public static final int MAX_LENGTH = 100;

  private LogSanitizer() {}

  public static String clean(String value) {
    if (value == null) {
      return "-";
    }
    StringBuilder out = new StringBuilder(Math.min(value.length(), MAX_LENGTH));
    int count = 0;
    int i = 0;
    while (i < value.length() && count < MAX_LENGTH) {
      int cp = value.codePointAt(i);
      i += Character.charCount(cp);
      if (Character.isISOControl(cp)
          || cp == 0x2028
          || cp == 0x2029
          || Character.getType(cp) == Character.FORMAT) {
        out.append(' ');
      } else {
        out.appendCodePoint(cp);
      }
      count++;
    }
    return out.toString();
  }
}
