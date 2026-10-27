package com.tpp.simulator.mapping;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Trích ba giá trị (transactionId, status, timestamp) từ payload JSON bằng MỘT lượt đọc dòng (token
 * của Jackson), không dựng cây (NFR1.4). Không trạng thái, không truy cập cơ sở dữ liệu, không phụ
 * thuộc gói ứng dụng nào khác.
 *
 * <ul>
 *   <li>Đường dẫn dạng {@code $.a.b.c}: chỉ đi qua object lồng nhau, khóa phân biệt hoa thường,
 *       không wildcard hay chỉ số mảng (BR3.1). Khóa trùng: giá trị xuất hiện sau cùng thắng.
 *   <li>Số lấy từ văn bản của token nên giữ nguyên ({@code 1.50}, {@code 1e3}, số nguyên rất lớn)
 *       (BR3.2).
 *   <li>Mỗi trường được đánh giá độc lập (BR3.3); gốc không phải object thì cả ba trường lỗi
 *       (BR3.4).
 * </ul>
 */
@Component
public class MappingEngine {

  /** Độ dài tối đa (ký tự Unicode) của transactionId và status. */
  public static final int MAX_VALUE_LENGTH = 255;

  /** Khoảng năm UTC chấp nhận cho timestamp nghiệp vụ (an toàn với timestamptz). */
  private static final int MIN_YEAR = 1;

  private static final int MAX_YEAR = 9999;

  private static final JsonFactory JSON = new JsonFactory();

  private enum Kind {
    STRING,
    NUMBER,
    BOOLEAN,
    NULL,
    OBJECT,
    ARRAY
  }

  private record Captured(Kind kind, String text) {}

  /**
   * Ánh xạ một payload JSON đã được kiểm tra hợp lệ.
   *
   * @throws IllegalArgumentException nếu payload không phải JSON hợp lệ (người gọi phải kiểm tra
   *     trước)
   */
  public MappingResult map(String payload, MappingConfig config) {
    List<String> txPath = parsePath(config.transactionIdPath());
    List<String> statusPath = parsePath(config.statusPath());
    boolean timestampConfigured =
        config.timestampPath() != null && !config.timestampPath().isBlank();
    List<String> tsPath = timestampConfigured ? parsePath(config.timestampPath()) : null;

    List<List<String>> targets = new ArrayList<>(3);
    for (List<String> p : Arrays.asList(txPath, statusPath, tsPath)) {
      if (p != null && !p.isEmpty() && !targets.contains(p)) {
        targets.add(p);
      }
    }

    Map<List<String>, Captured> captures = new HashMap<>();
    boolean rootIsObject;
    try (JsonParser parser = JSON.createParser(payload)) {
      JsonToken first = parser.nextToken();
      rootIsObject = first == JsonToken.START_OBJECT;
      if (rootIsObject) {
        walkObject(parser, new ArrayList<>(), targets, captures);
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("Payload is not valid JSON", e);
    }

    FieldResult txResult;
    FieldResult statusResult;
    FieldResult tsResult;
    Instant businessTimestamp = null;

    if (!rootIsObject) {
      txResult = notObject();
      statusResult = notObject();
      tsResult = timestampConfigured ? notObject() : FieldResult.skipped();
    } else {
      txResult = textField(txPath.isEmpty() ? null : captures.get(txPath));
      statusResult = textField(statusPath.isEmpty() ? null : captures.get(statusPath));
      if (!timestampConfigured) {
        tsResult = FieldResult.skipped();
      } else {
        Captured c = tsPath.isEmpty() ? null : captures.get(tsPath);
        tsResult = timestampField(c, config);
        if (!tsResult.hasError() && tsResult.value() != null) {
          businessTimestamp = Instant.parse(tsResult.value());
        }
      }
    }

    MappingOutcome outcome =
        txResult.hasError() || statusResult.hasError() || tsResult.hasError()
            ? MappingOutcome.MAPPING_ERROR
            : MappingOutcome.SUCCESS;
    return new MappingResult(txResult, statusResult, tsResult, businessTimestamp, outcome);
  }

  /**
   * Tách {@code $.a.b} thành [a, b]; trả danh sách rỗng nếu đường dẫn sai cú pháp (luôn NOT_FOUND).
   */
  static List<String> parsePath(String path) {
    if (path == null || !path.startsWith("$.") || path.length() == 2) {
      return List.of();
    }
    String[] parts = path.substring(2).split("\\.", -1);
    List<String> segments = new ArrayList<>(parts.length);
    for (String part : parts) {
      if (part.isEmpty()) {
        return List.of();
      }
      segments.add(part);
    }
    return List.copyOf(segments);
  }

  // Đọc các trường của object hiện tại (token START_OBJECT đã được đọc).
  private static void walkObject(
      JsonParser parser,
      List<String> path,
      List<List<String>> targets,
      Map<List<String>, Captured> captures)
      throws IOException {
    JsonToken token = parser.nextToken();
    while (token == JsonToken.FIELD_NAME) {
      path.add(parser.currentName());
      boolean onTrack = isOnTrack(path, targets);
      JsonToken value = parser.nextToken();
      if (!onTrack) {
        if (value.isStructStart()) {
          parser.skipChildren();
        }
      } else {
        List<String> key = List.copyOf(path);
        // Khóa trùng: lần cuối thắng, nên bỏ mọi giá trị đã lấy ở dưới khóa này.
        captures.keySet().removeIf(k -> startsWith(k, key));
        switch (value) {
          case START_OBJECT -> {
            if (targets.contains(key)) {
              captures.put(key, new Captured(Kind.OBJECT, null));
            }
            if (hasTargetBelow(key, targets)) {
              walkObject(parser, path, targets, captures);
            } else {
              parser.skipChildren();
            }
          }
          case START_ARRAY -> {
            if (targets.contains(key)) {
              captures.put(key, new Captured(Kind.ARRAY, null));
            }
            parser.skipChildren();
          }
          case VALUE_NULL -> putIfTarget(captures, targets, key, Kind.NULL, null);
          case VALUE_TRUE, VALUE_FALSE ->
              putIfTarget(captures, targets, key, Kind.BOOLEAN, parser.getText());
          case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT ->
              putIfTarget(captures, targets, key, Kind.NUMBER, parser.getText());
          default -> putIfTarget(captures, targets, key, Kind.STRING, parser.getText());
        }
      }
      path.remove(path.size() - 1);
      token = parser.nextToken();
    }
  }

  private static void putIfTarget(
      Map<List<String>, Captured> captures,
      List<List<String>> targets,
      List<String> key,
      Kind kind,
      String text) {
    if (targets.contains(key)) {
      captures.put(key, new Captured(kind, text));
    }
  }

  // Đường dẫn hiện tại là một mục tiêu hoặc tiền tố của một mục tiêu.
  private static boolean isOnTrack(List<String> path, List<List<String>> targets) {
    for (List<String> target : targets) {
      if (startsWith(target, path)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasTargetBelow(List<String> key, List<List<String>> targets) {
    for (List<String> target : targets) {
      if (target.size() > key.size() && startsWith(target, key)) {
        return true;
      }
    }
    return false;
  }

  private static boolean startsWith(List<String> list, List<String> prefix) {
    return list.size() >= prefix.size() && list.subList(0, prefix.size()).equals(prefix);
  }

  private static FieldResult notObject() {
    return FieldResult.error(
        MappingErrorCode.PAYLOAD_NOT_OBJECT, "Payload gốc không phải object JSON.");
  }

  private static FieldResult textField(Captured captured) {
    if (captured == null) {
      return FieldResult.error(
          MappingErrorCode.NOT_FOUND, "Không tìm thấy giá trị tại đường dẫn đã cấu hình.");
    }
    switch (captured.kind()) {
      case NULL:
        return empty();
      case OBJECT:
      case ARRAY:
        return FieldResult.error(
            MappingErrorCode.WRONG_TYPE,
            "Giá trị phải là chuỗi, số hoặc true/false, không phải object hoặc mảng.");
      case STRING:
        if (captured.text().isBlank()) {
          return empty();
        }
        return lengthChecked(captured.text());
      default:
        return lengthChecked(captured.text());
    }
  }

  private static FieldResult empty() {
    return FieldResult.error(MappingErrorCode.EMPTY, "Giá trị rỗng hoặc chỉ có khoảng trắng.");
  }

  private static FieldResult lengthChecked(String text) {
    if (text.codePointCount(0, text.length()) > MAX_VALUE_LENGTH) {
      return FieldResult.error(
          MappingErrorCode.TOO_LONG, "Giá trị dài hơn " + MAX_VALUE_LENGTH + " ký tự.");
    }
    return FieldResult.ok(text);
  }

  private static FieldResult timestampField(Captured captured, MappingConfig config) {
    if (captured == null
        || captured.kind() == Kind.NULL
        || (captured.kind() == Kind.STRING && captured.text().isBlank())) {
      return FieldResult.warn("Không có giá trị timestamp tại đường dẫn đã cấu hình.");
    }
    TimestampFormat format =
        config.timestampFormat() == null ? TimestampFormat.ISO_8601 : config.timestampFormat();
    if (captured.kind() == Kind.OBJECT || captured.kind() == Kind.ARRAY) {
      return FieldResult.error(
          MappingErrorCode.WRONG_TYPE, "Giá trị thời gian không được là object hoặc mảng.");
    }
    if (captured.kind() == Kind.BOOLEAN) {
      return unparseable(format);
    }
    boolean number = captured.kind() == Kind.NUMBER;
    try {
      Instant instant =
          switch (format) {
            case ISO_8601 -> number ? null : parseIso(captured.text(), config.defaultTimeZone());
            case UNIX_SECONDS ->
                UnixTimestampParser.parse(captured.text(), number, false).orElse(null);
            case UNIX_MILLIS ->
                UnixTimestampParser.parse(captured.text(), number, true).orElse(null);
            case PATTERN ->
                TimestampPatterns.parse(
                    captured.text(),
                    TimestampPatterns.compile(config.timestampPattern()),
                    config.defaultTimeZone());
          };
      if (instant == null) {
        return unparseable(format);
      }
      // timestamptz của PostgreSQL không lưu được năm ngoài 1..9999 (BR17.4).
      int utcYear = instant.atOffset(ZoneOffset.UTC).getYear();
      if (utcYear < MIN_YEAR || utcYear > MAX_YEAR) {
        return unparseable(format);
      }
      return FieldResult.ok(
          DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.MICROS)));
    } catch (DateTimeException | IllegalArgumentException e) {
      return unparseable(format);
    }
  }

  private static Instant parseIso(String text, ZoneId defaultZone) {
    TemporalAccessor parsed =
        DateTimeFormatter.ISO_DATE_TIME.parseBest(text, ZonedDateTime::from, LocalDateTime::from);
    return parsed instanceof ZonedDateTime zoned
        ? zoned.toInstant()
        : ((LocalDateTime) parsed).atZone(defaultZone).toInstant();
  }

  private static FieldResult unparseable(TimestampFormat format) {
    String message =
        switch (format) {
          case ISO_8601 -> "Giá trị thời gian không phải ISO 8601 hợp lệ.";
          case UNIX_SECONDS, UNIX_MILLIS ->
              "Giá trị thời gian không phải số Unix hợp lệ trong khoảng 1970 đến 2100.";
          case PATTERN -> "Giá trị thời gian không khớp mẫu đã cấu hình.";
        };
    return FieldResult.error(MappingErrorCode.UNPARSEABLE, message);
  }
}
