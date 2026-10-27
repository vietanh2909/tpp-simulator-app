package com.tpp.simulator.loadtool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** OD-05, BR22.5: báo cáo kết thúc có đúng các số cần thiết và không lộ gì khác. */
class LoadReportTest {

  private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
  private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

  @Test
  void successPrintsTheFourFiguresToStdout() {
    LoadReport.success(100_000, 25_011, 100_000, Duration.ofSeconds(371)).print(out, err);

    assertEquals(
        "Load finished: callbacks=100000 transactions=25011 writtenThisRun=100000 elapsedSeconds=371"
            + System.lineSeparator(),
        outBytes.toString(StandardCharsets.UTF_8));
    assertEquals("", errBytes.toString(StandardCharsets.UTF_8));
  }

  @Test
  void failurePrintsTheLoadFailedLineToStderrWithTheDatabaseCount() {
    LoadReport.failed(
            7, 2, 6, Duration.ofSeconds(1), "writeStatus=ACCEPTED_WRITE_FAILED sqlState=P0001")
        .print(out, err);

    String text = errBytes.toString(StandardCharsets.UTF_8);
    assertTrue(
        text.startsWith("Load failed: writeStatus=ACCEPTED_WRITE_FAILED sqlState=P0001"), text);
    assertTrue(text.contains("callbacksInDatabase=7"), text);
    assertTrue(text.contains("writtenThisRun=6"), text);
    assertEquals("", outBytes.toString(StandardCharsets.UTF_8));
  }

  @Test
  void aCountThatFailedIsReportedAsUnknown() {
    LoadReport.failed(-1, -1, 3, Duration.ZERO, "exception=x").print(out, err);

    assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains("unknown"));
  }

  @Test
  void refusalIsExitTwo() {
    LoadReport report = LoadReport.refused("vì lý do");
    report.print(out, err);

    assertEquals(2, report.exitCode());
    assertEquals(
        "Load refused: vì lý do" + System.lineSeparator(),
        errBytes.toString(StandardCharsets.UTF_8));
    assertFalse(outBytes.toString(StandardCharsets.UTF_8).contains("Load"));
  }

  @Test
  void exitCodesAreZeroOneTwo() {
    assertEquals(0, LoadReport.success(1, 1, 1, Duration.ZERO).exitCode());
    assertEquals(1, LoadReport.failed(1, 1, 1, Duration.ZERO, "x").exitCode());
    assertEquals(2, LoadReport.refused("x").exitCode());
  }
}
