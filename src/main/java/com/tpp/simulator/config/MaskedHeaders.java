package com.tpp.simulator.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Header của request đã che (BR2.10, BR16.1, ADR-007). Hàm tạo nhận header thô và tự che {@code
 * Authorization}, {@code Proxy-Authorization}, {@code Cookie} cùng header khóa API hiện hành của
 * dịch vụ (tên không phân biệt hoa thường) thành {@code ***}; không có đường tạo kiểu từ giá trị đã
 * che sẵn. CallbackLedger chỉ nhận kiểu này.
 *
 * <p>Tên header giữ chữ gốc; nhiều giá trị cùng tên được nối bằng {@code ", "}, riêng header bị che
 * chỉ còn một {@code ***}.
 */
public final class MaskedHeaders {

  public static final String MASK = "***";

  private static final Set<String> SENSITIVE =
      Set.of("authorization", "proxy-authorization", "cookie");

  private final Map<String, String> values;

  public MaskedHeaders(Map<String, List<String>> rawHeaders) {
    this(rawHeaders, null);
  }

  /**
   * @param apiKeyHeaderName tên header khóa API của dịch vụ lúc nhận; null với dịch vụ {@code NONE}
   */
  public MaskedHeaders(Map<String, List<String>> rawHeaders, String apiKeyHeaderName) {
    String apiKeyHeader =
        apiKeyHeaderName == null ? null : apiKeyHeaderName.toLowerCase(Locale.ROOT);
    Map<String, String> masked = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> entry : rawHeaders.entrySet()) {
      String name = entry.getKey();
      if (name == null) {
        continue;
      }
      String lower = name.toLowerCase(Locale.ROOT);
      String value =
          SENSITIVE.contains(lower) || lower.equals(apiKeyHeader)
              ? MASK
              : String.join(", ", entry.getValue() == null ? List.of() : entry.getValue());
      masked.merge(name, value, (a, b) -> MASK.equals(a) && MASK.equals(b) ? MASK : a + ", " + b);
    }
    this.values = Collections.unmodifiableMap(masked);
  }

  /** Bản đồ chỉ đọc tên header đến giá trị đã che. */
  public Map<String, String> asMap() {
    return values;
  }
}
