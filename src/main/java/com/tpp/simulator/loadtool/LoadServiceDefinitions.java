package com.tpp.simulator.loadtool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpp.simulator.catalog.ServiceCreateRequest;
import java.util.Locale;

/**
 * Cấu hình dịch vụ {@code LOAD_xx} theo bảng gán CỐ ĐỊNH theo số thứ tự, chu kỳ 3 (PD-08): số 1
 * {@code NONE} + {@code ISO_8601}; số 2 {@code API_KEY} + {@code PATTERN}; số 0 {@code NONE} +
 * {@code UNIX_MILLIS}. Khóa là giá trị giả công khai của công cụ.
 */
public final class LoadServiceDefinitions {

  /** Header khóa của các dịch vụ {@code API_KEY} do công cụ tạo. */
  public static final String API_KEY_HEADER = "X-Api-Key";

  /** Khóa giả công khai của công cụ nạp (không phải bí mật). */
  public static final String API_KEY_VALUE = "load-tool-public-sample-key";

  public static final String PATTERN = "yyyy-MM-dd HH:mm:ss";

  /** Kiểu dịch vụ theo số thứ tự. */
  public enum Kind {
    ISO_NONE,
    PATTERN_API_KEY,
    MILLIS_NONE
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  private LoadServiceDefinitions() {}

  public static String serviceCode(int number) {
    return String.format(Locale.ROOT, "LOAD_%02d", number);
  }

  public static Kind kind(int number) {
    return switch (number % 3) {
      case 1 -> Kind.ISO_NONE;
      case 2 -> Kind.PATTERN_API_KEY;
      default -> Kind.MILLIS_NONE;
    };
  }

  public static boolean requiresApiKey(int number) {
    return kind(number) == Kind.PATTERN_API_KEY;
  }

  /** Yêu cầu tạo dịch vụ thứ {@code number}. */
  public static ServiceCreateRequest request(int number) {
    Kind kind = kind(number);
    try {
      return new ServiceCreateRequest(
          serviceCode(number),
          "Dịch vụ nạp " + number,
          "Tạo bởi công cụ nạp dữ liệu thử",
          true,
          kind == Kind.PATTERN_API_KEY ? "API_KEY" : "NONE",
          kind == Kind.PATTERN_API_KEY ? API_KEY_HEADER : null,
          kind == Kind.PATTERN_API_KEY ? API_KEY_VALUE : null,
          "$.transId",
          "$.code",
          "$.time",
          switch (kind) {
            case ISO_NONE -> "ISO_8601";
            case PATTERN_API_KEY -> "PATTERN";
            case MILLIS_NONE -> "UNIX_MILLIS";
          },
          kind == Kind.PATTERN_API_KEY ? PATTERN : null,
          "Asia/Ho_Chi_Minh",
          200,
          JSON.readTree("{\"code\":\"00\"}"));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Cannot build the response body", e);
    }
  }
}
