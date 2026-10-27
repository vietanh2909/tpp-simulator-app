package com.tpp.simulator.catalog;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * So sánh cấu hình hiệu lực để quyết định có sinh phiên bản mới không (BR12.4). Chuẩn hóa từng
 * trường trước khi so: {@code timestampPath} rỗng bằng vắng, {@code timestampPattern} chỉ tính khi
 * định dạng là PATTERN, múi giờ so theo mã, {@code responseBody} so theo JSON tương đương (cùng cấu
 * trúc, giá trị chuỗi và văn bản số; chỉ khác khoảng trắng hoặc thứ tự khóa là không đổi; số 1 khác
 * 1.0; thân có khóa trùng trong cùng object so theo văn bản nguyên).
 */
public final class ServiceConfigComparator {

  private static final JsonFactory JSON = new JsonFactory();

  private ServiceConfigComparator() {}

  /** Các trường tạo phiên bản của một cấu hình. */
  public record VersionFields(
      String transactionIdPath,
      String statusPath,
      String timestampPath,
      String timestampFormat,
      String timestampPattern,
      String defaultTimeZone,
      int responseHttpStatus,
      String responseBody) {}

  /** true nếu có ít nhất một trường tạo phiên bản khác giá trị hiệu lực. */
  public static boolean differs(VersionFields current, VersionFields next) {
    return !Objects.equals(current.transactionIdPath(), next.transactionIdPath())
        || !Objects.equals(current.statusPath(), next.statusPath())
        || !Objects.equals(emptyToNull(current.timestampPath()), emptyToNull(next.timestampPath()))
        || !Objects.equals(current.timestampFormat(), next.timestampFormat())
        || !Objects.equals(effectivePattern(current), effectivePattern(next))
        || !sameZone(current.defaultTimeZone(), next.defaultTimeZone())
        || current.responseHttpStatus() != next.responseHttpStatus()
        || !equivalentJson(current.responseBody(), next.responseBody());
  }

  private static String emptyToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }

  private static String effectivePattern(VersionFields fields) {
    return "PATTERN".equals(fields.timestampFormat()) ? fields.timestampPattern() : null;
  }

  private static boolean sameZone(String a, String b) {
    try {
      return ZoneId.of(a).equals(ZoneId.of(b));
    } catch (RuntimeException e) {
      return Objects.equals(a, b);
    }
  }

  /** JSON tương đương theo BR12.4. */
  static boolean equivalentJson(String a, String b) {
    if (Objects.equals(a, b)) {
      return true;
    }
    try {
      return Objects.equals(canonical(a), canonical(b));
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  private static String canonical(String text) throws IOException {
    try (JsonParser parser = JSON.createParser(text)) {
      parser.nextToken();
      String result = value(parser);
      return result == null ? "RAW:" + text : result;
    }
  }

  // Trả null nếu gặp khóa trùng ở bất kỳ mức nào (khi đó so theo văn bản nguyên).
  private static String value(JsonParser parser) throws IOException {
    JsonToken token = parser.currentToken();
    switch (token) {
      case START_OBJECT -> {
        TreeMap<String, String> members = new TreeMap<>();
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
          String name = parser.currentName();
          parser.nextToken();
          String member = value(parser);
          if (member == null || members.put(name, member) != null) {
            return null;
          }
        }
        StringBuilder out = new StringBuilder("{");
        members.forEach((k, v) -> out.append(quote(k)).append(':').append(v).append(','));
        return out.append('}').toString();
      }
      case START_ARRAY -> {
        List<String> items = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
          String item = value(parser);
          if (item == null) {
            return null;
          }
          items.add(item);
        }
        return "[" + String.join(",", Collections.unmodifiableList(items)) + "]";
      }
      case VALUE_STRING -> {
        return quote(parser.getText());
      }
      case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
        return "n" + parser.getText();
      }
      case VALUE_TRUE -> {
        return "true";
      }
      case VALUE_FALSE -> {
        return "false";
      }
      default -> {
        return "null";
      }
    }
  }

  private static String quote(String text) {
    return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
  }
}
