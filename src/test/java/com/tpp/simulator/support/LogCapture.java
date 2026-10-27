package com.tpp.simulator.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Bắt dòng nhật ký của một logger trong lúc kiểm thử (OD-02). Dùng với try-with-resources; khi đóng
 * sẽ gỡ bộ ghi khỏi logger.
 */
public final class LogCapture implements AutoCloseable {

  private final Logger logger;
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  private LogCapture(Logger logger) {
    this.logger = logger;
    appender.start();
    logger.addAppender(appender);
  }

  /** Bắt logger theo tên (mức mặc định của logger không đổi). */
  public static LogCapture of(String loggerName) {
    return new LogCapture((Logger) LoggerFactory.getLogger(loggerName));
  }

  /** Bắt logger gốc: mọi dòng của ứng dụng. */
  public static LogCapture root() {
    return new LogCapture((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME));
  }

  public List<ILoggingEvent> events() {
    return List.copyOf(appender.list);
  }

  public List<ILoggingEvent> events(Level level) {
    return events().stream().filter(e -> e.getLevel() == level).toList();
  }

  /** Mọi thông điệp đã định dạng, nối bằng xuống dòng (để quét chuỗi nhạy cảm). */
  public String allText() {
    StringBuilder text = new StringBuilder();
    for (ILoggingEvent event : events()) {
      text.append(event.getLevel()).append(' ').append(event.getFormattedMessage()).append('\n');
      if (event.getThrowableProxy() != null) {
        text.append(event.getThrowableProxy().getClassName());
        text.append(' ').append(event.getThrowableProxy().getMessage()).append('\n');
      }
    }
    return text.toString();
  }

  @Override
  public void close() {
    logger.detachAppender(appender);
    appender.stop();
  }
}
