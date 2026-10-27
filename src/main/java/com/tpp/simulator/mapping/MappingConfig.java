package com.tpp.simulator.mapping;

import java.time.ZoneId;

/**
 * Phần cấu hình ánh xạ của một phiên bản cấu hình dịch vụ.
 *
 * @param transactionIdPath đường dẫn dạng {@code $.a.b.c}
 * @param statusPath đường dẫn dạng {@code $.a.b.c}
 * @param timestampPath đường dẫn tùy chọn; null hoặc rỗng nghĩa là không đọc timestamp
 * @param timestampFormat định dạng timestamp; null nghĩa là {@code ISO_8601}
 * @param timestampPattern mẫu ngày giờ, chỉ dùng khi định dạng là {@code PATTERN}
 * @param defaultTimeZone múi giờ dùng cho ngày giờ không có múi giờ
 */
public record MappingConfig(
    String transactionIdPath,
    String statusPath,
    String timestampPath,
    TimestampFormat timestampFormat,
    String timestampPattern,
    ZoneId defaultTimeZone) {

  /** Cấu hình ISO 8601 (dạng của U1). */
  public MappingConfig(
      String transactionIdPath, String statusPath, String timestampPath, ZoneId defaultTimeZone) {
    this(
        transactionIdPath,
        statusPath,
        timestampPath,
        TimestampFormat.ISO_8601,
        null,
        defaultTimeZone);
  }
}
