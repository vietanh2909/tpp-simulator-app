package com.tpp.simulator.loadtool;

import java.io.PrintStream;
import java.time.Duration;

/**
 * Báo cáo kết thúc của công cụ nạp (OD-05, BR22.5). Không bao giờ chứa payload, khóa, mật khẩu hay
 * thông điệp thô của ngoại lệ: chỉ số đếm, trạng thái ghi, tên lớp lỗi và SQLSTATE.
 *
 * @param exitCode 0 thành công; 1 lỗi lúc chạy; 2 từ chối trước khi ghi
 * @param callbacksInDatabase số Callback đọc bằng {@code count(*)}; âm nếu không đếm được
 * @param transactionsInDatabase số giao dịch đọc bằng {@code count(*)}; âm nếu không đếm được
 * @param writtenThisRun số lần nhận ghi được trong lần chạy này (chỉ tính {@code WRITTEN})
 * @param failure mô tả lỗi (đã làm sạch); null khi thành công
 */
public record LoadReport(
    int exitCode,
    long callbacksInDatabase,
    long transactionsInDatabase,
    long writtenThisRun,
    Duration elapsed,
    String failure) {

  public static LoadReport success(
      long callbacks, long transactions, long written, Duration elapsed) {
    return new LoadReport(0, callbacks, transactions, written, elapsed, null);
  }

  public static LoadReport failed(
      long callbacks, long transactions, long written, Duration elapsed, String failure) {
    return new LoadReport(1, callbacks, transactions, written, elapsed, failure);
  }

  public static LoadReport refused(String reason) {
    return new LoadReport(2, -1, -1, 0, Duration.ZERO, reason);
  }

  /** In kết quả: thành công ra {@code out}; lỗi và từ chối ra {@code err}. */
  public void print(PrintStream out, PrintStream err) {
    if (exitCode == 0) {
      out.println(
          "Load finished: callbacks="
              + callbacksInDatabase
              + " transactions="
              + transactionsInDatabase
              + " writtenThisRun="
              + writtenThisRun
              + " elapsedSeconds="
              + elapsed.toSeconds());
    } else if (exitCode == 2) {
      err.println("Load refused: " + failure);
    } else {
      err.println(
          "Load failed: "
              + failure
              + " callbacksInDatabase="
              + (callbacksInDatabase < 0
                  ? "unknown (count failed)"
                  : String.valueOf(callbacksInDatabase))
              + " writtenThisRun="
              + writtenThisRun);
    }
  }
}
