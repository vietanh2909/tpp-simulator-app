package com.tpp.simulator.loadtool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tpp.simulator.catalog.ServiceCatalog;
import com.tpp.simulator.ingestion.CallbackIngestionService;
import com.tpp.simulator.loadtool.LoadDataGenerator.Item;
import com.tpp.simulator.loadtool.LoadDataGenerator.Kind;
import com.tpp.simulator.support.AbstractIntegrationTest;
import com.tpp.simulator.support.FailureTrigger;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * RD-07, NFR2.27: lỗi ghi giữa chừng đi qua {@code CallbackIngestionService} THẬT (không bộ ghi
 * giả): công cụ dừng ngay ở lỗi đầu tiên, giữ dữ liệu đã ghi, báo số đếm từ cơ sở dữ liệu, thoát mã
 * 1; chạy lại không append bị từ chối, có append thì tiếp tục.
 */
class LoadRunnerWriteFailureTest extends AbstractIntegrationTest {

  @Autowired private ServiceCatalog catalog;
  @Autowired private CallbackIngestionService ingestion;
  @Autowired private JdbcClient jdbcClient;
  @Autowired private Clock appClock;

  private static final LoadParameters PARAMETERS =
      new LoadParameters(500, 20_261_019L, 1, 2, false, "tpp_simulator_test");

  private LoadRunner runner() {
    return new LoadRunner(catalog, ingestion, jdbcClient, appClock, null);
  }

  /** Chỉ số của lần nhận đầu tiên thỏa điều kiện, tính bằng cùng bộ sinh và cùng seed. */
  private static int firstIndex(Predicate<Item> match) {
    LoadDataGenerator generator = new LoadDataGenerator(PARAMETERS, Instant.now());
    while (generator.hasNext()) {
      Item item = generator.next();
      if (match.test(item)) {
        return item.index();
      }
    }
    throw new IllegalStateException("không có lần nhận nào khớp");
  }

  private static String printed(LoadReport report) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    report.print(
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8));
    return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
  }

  @Test
  void anAcceptedReceiptThatCannotBeStoredStopsTheLoadAtThatReceipt() {
    // Lần nhận đầu tiên (chỉ số 0) thuộc LOAD_01, nên chọn LOAD_02 để có dữ liệu đã ghi trước lỗi.
    int expected = firstIndex(i -> i.serviceNumber() == 2 && i.kind() == Kind.SUCCESS);
    assertTrue(expected > 0);

    LoadReport report;
    try (FailureTrigger trigger = FailureTrigger.on("callbacks", "INSERT", "LOAD_02", "SUCCESS")) {
      report = runner().run(PARAMETERS);
    }

    assertEquals(1, report.exitCode());
    assertTrue(report.failure().contains("ACCEPTED_WRITE_FAILED"), report.failure());
    assertEquals(expected, report.writtenThisRun());
    assertEquals(expected, report.callbacksInDatabase());
    assertEquals(expected, count("callbacks"));
    String output = printed(report);
    assertTrue(output.startsWith("Load failed"), output);
    assertTrue(output.contains("callbacksInDatabase=" + expected), output);
    assertFalse(output.contains("test failure"), output);
    // Sau khi đóng trigger, không lần nhận nào được nạp tiếp.
    assertEquals(expected, count("callbacks"));
  }

  @Test
  void aRejectedReceiptThatCannotBeStoredAlsoStopsTheLoad() {
    int expected = firstIndex(i -> i.serviceNumber() == 2 && i.kind() == Kind.REJECTED);

    LoadReport report;
    try (FailureTrigger trigger = FailureTrigger.on("callbacks", "INSERT", "LOAD_02", "REJECTED")) {
      report = runner().run(PARAMETERS);
    }

    assertEquals(1, report.exitCode());
    assertTrue(report.failure().contains("REJECTED_RECORD_FAILED"), report.failure());
    assertEquals(expected, count("callbacks"));
    assertEquals(expected, report.callbacksInDatabase());
  }

  @Test
  void afterAFailureRerunningNeedsAppendAndThenContinues() {
    int expected = firstIndex(i -> i.serviceNumber() == 1 && i.kind() == Kind.SUCCESS);
    try (FailureTrigger trigger = FailureTrigger.on("callbacks", "INSERT", "LOAD_01", "SUCCESS")) {
      runner().run(PARAMETERS);
    }

    LoadReport refused = runner().run(PARAMETERS);
    // Lần chạy bị từ chối không ghi thêm gì: đọc thẳng từ cơ sở dữ liệu NGAY SAU lần chạy đó
    // (LoadReport.refused đặt callbacksInDatabase = -1 nên không dùng được làm bằng chứng).
    long afterRefusal = count("callbacks");
    LoadReport appended =
        runner().run(new LoadParameters(100, 20_261_019L, 1, 2, true, "tpp_simulator_test"));

    assertEquals(2, refused.exitCode());
    assertEquals(expected, afterRefusal);
    assertEquals(0, appended.exitCode(), String.valueOf(appended.failure()));
    assertEquals(expected + 100, count("callbacks"));
    assertEquals(2, count("services"));
  }
}
