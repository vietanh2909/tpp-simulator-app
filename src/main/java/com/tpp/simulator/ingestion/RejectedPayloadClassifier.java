package com.tpp.simulator.ingestion;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quyết định hình thức lưu payload của lần nhận bị từ chối MỘT lần, TRƯỚC khi ghi (BR15.4, RD-09).
 * Thuần, chỉ dựa vào nội dung body:
 *
 * <ul>
 *   <li>JSON hợp lệ (đúng một giá trị) và lưu trung thực được ở định dạng {@code jsonb} → {@code
 *       payloadJson}, kể cả body đúng chữ {@code null} (giá trị JSON null).
 *   <li>Ngược lại → {@code payloadRawText}: văn bản giải mã, byte hỏng và ký tự NUL thay bằng
 *       U+FFFD; body rỗng là chuỗi rỗng.
 * </ul>
 *
 * "Lưu trung thực được" nghĩa là không có chuỗi hay tên khóa chứa U+0000 hoặc nửa cặp thay thế lẻ,
 * và mọi số nằm trong miền số của {@code numeric} (tối đa 131.072 chữ số phần nguyên, 16.383 chữ số
 * phần thập phân, số mũ tuyệt đối tối đa 100.000).
 */
public final class RejectedPayloadClassifier {

  /** Kết quả: đúng một trong hai trường khác null. */
  public record Classified(String payloadJson, String payloadRawText) {}

  static final int MAX_INTEGER_DIGITS = 131_072;
  static final int MAX_FRACTION_DIGITS = 16_383;
  static final long MAX_EXPONENT = 100_000;

  private static final JsonFactory JSON = new JsonFactory();
  private static final Pattern NUMBER =
      Pattern.compile("^-?([0-9]+)(?:\\.([0-9]+))?(?:[eE]([+-]?)([0-9]+))?$");

  private RejectedPayloadClassifier() {}

  public static Classified classify(byte[] body) {
    if (body == null || body.length == 0) {
      return new Classified(null, "");
    }
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(body))
              .toString();
    } catch (CharacterCodingException e) {
      return new Classified(null, lenientText(body));
    }
    String jsonText = !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    if (storableJson(jsonText)) {
      return new Classified(jsonText, null);
    }
    return new Classified(null, text.replace('\u0000', '\uFFFD'));
  }

  private static String lenientText(byte[] body) {
    return new String(body, StandardCharsets.UTF_8).replace('\u0000', '\uFFFD');
  }

  private static boolean storableJson(String text) {
    try (JsonParser parser = JSON.createParser(text)) {
      JsonToken first = parser.nextToken();
      if (first == null) {
        return false;
      }
      int depth = 0;
      JsonToken token = first;
      do {
        switch (token) {
          case START_OBJECT, START_ARRAY -> depth++;
          case END_OBJECT, END_ARRAY -> depth--;
          case FIELD_NAME -> {
            if (!storableString(parser.currentName())) {
              return false;
            }
          }
          case VALUE_STRING -> {
            if (!storableString(parser.getText())) {
              return false;
            }
          }
          case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
            if (!storableNumber(parser.getText())) {
              return false;
            }
          }
          default -> {
            // true, false, null: luôn lưu được.
          }
        }
        if (depth == 0) {
          break;
        }
        token = parser.nextToken();
      } while (token != null);
      return parser.nextToken() == null;
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  /** Không có U+0000 và không có nửa cặp thay thế lẻ. */
  static boolean storableString(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\u0000') {
        return false;
      }
      if (Character.isHighSurrogate(c)) {
        if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
          return false;
        }
        i++;
      } else if (Character.isLowSurrogate(c)) {
        return false;
      }
    }
    return true;
  }

  /** Miền số của numeric, tính bằng số học long trên chuỗi chữ số (không tạo BigDecimal). */
  static boolean storableNumber(String text) {
    Matcher m = NUMBER.matcher(text);
    if (!m.matches()) {
      return false;
    }
    String integer = m.group(1);
    String fraction = m.group(2) == null ? "" : m.group(2);
    long exponent = 0;
    if (m.group(4) != null) {
      String digits = m.group(4).replaceFirst("^0+(?=.)", "");
      if (digits.length() > 9) {
        return false;
      }
      exponent = Long.parseLong(digits);
      if (exponent > MAX_EXPONENT) {
        return false;
      }
      if ("-".equals(m.group(3))) {
        exponent = -exponent;
      }
    }
    String all = (integer + fraction).replaceFirst("^0+", "");
    if (all.isEmpty()) {
      return true;
    }
    // Số chữ số phần nguyên và phần thập phân sau khi áp dụng số mũ.
    long scale = fraction.length() - exponent;
    long integerDigits = Math.max(0, all.length() - scale);
    long fractionDigits = Math.max(0, scale);
    return integerDigits <= MAX_INTEGER_DIGITS && fractionDigits <= MAX_FRACTION_DIGITS;
  }
}
