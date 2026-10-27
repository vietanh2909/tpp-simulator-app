package com.tpp.simulator.config;

import java.sql.SQLException;
import org.slf4j.Logger;

/**
 * Ghi log lỗi cơ sở dữ liệu an toàn (observability-design.md): chỉ LỚP ngoại lệ và SQLSTATE, không
 * bao giờ thông điệp thô (có thể chứa dữ liệu dòng hoặc mảnh JSON), payload, header hay khóa.
 */
public final class DbErrors {

  private DbErrors() {}

  /** SQLSTATE của ngoại lệ gốc đầu tiên là {@link SQLException}, hoặc "-" nếu không có. */
  public static String sqlState(Throwable error) {
    Throwable current = error;
    int guard = 0;
    while (current != null && guard++ < 20) {
      if (current instanceof SQLException sql && sql.getSQLState() != null) {
        return sql.getSQLState();
      }
      current = current.getCause();
    }
    return "-";
  }

  public static void logWriteFailure(Logger log, String subject, Throwable error) {
    log.error(
        "Database failure: service={} exception={} sqlState={}",
        LogSanitizer.clean(subject),
        error.getClass().getName(),
        sqlState(error));
  }
}
