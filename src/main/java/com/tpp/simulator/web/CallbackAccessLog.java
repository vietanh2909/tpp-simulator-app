package com.tpp.simulator.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nơi duy nhất ghi dòng nhật ký của endpoint callback (OD-01): đúng một dòng mỗi request, mức INFO
 * hoặc mức ERROR khi có lỗi ghi. Tên logger là cái mà kiểm thử đếm.
 */
public final class CallbackAccessLog {

  private static final Logger LOG = LoggerFactory.getLogger(CallbackAccessLog.class);

  private CallbackAccessLog() {}

  /** Ghi dòng của request; gọi lần hai với cùng mục thì không ghi thêm. */
  public static void emit(CallbackLogEntry entry) {
    if (!entry.markEmitted()) {
      return;
    }
    if (entry.hasFailure()) {
      LOG.error(entry.message());
    } else {
      LOG.info(entry.message());
    }
  }
}
