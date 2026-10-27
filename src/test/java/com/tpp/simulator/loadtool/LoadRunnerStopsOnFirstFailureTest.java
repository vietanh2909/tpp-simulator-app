package com.tpp.simulator.loadtool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tpp.simulator.catalog.ServiceRuntimeSnapshot;
import com.tpp.simulator.config.ErrorCode;
import com.tpp.simulator.ingestion.ConfiguredResponse;
import com.tpp.simulator.ingestion.IngestionResult;
import com.tpp.simulator.ingestion.IngestionResult.Kind;
import com.tpp.simulator.ingestion.WriteStatus;
import com.tpp.simulator.loadtool.LoadRunner.LoopResult;
import com.tpp.simulator.mapping.MappingOutcome;
import com.tpp.simulator.mapping.TimestampFormat;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** RD-07: mọi WriteStatus khác WRITTEN là lỗi đầu tiên; vòng nạp dừng ngay, không nạp tiếp. */
class LoadRunnerStopsOnFirstFailureTest {

  private static final Instant NOW = Instant.parse("2026-10-19T03:00:00Z");

  private static Map<Integer, ServiceRuntimeSnapshot> snapshots(int n) {
    Map<Integer, ServiceRuntimeSnapshot> map = new HashMap<>();
    for (int i = 1; i <= n; i++) {
      map.put(
          i,
          new ServiceRuntimeSnapshot(
              LoadServiceDefinitions.serviceCode(i),
              true,
              false,
              "NONE",
              null,
              null,
              i,
              1,
              "$.transId",
              "$.code",
              "$.time",
              TimestampFormat.ISO_8601,
              null,
              ZoneId.of("UTC"),
              200,
              "{}"));
    }
    return map;
  }

  private static IngestionResult written() {
    return new IngestionResult(
        Kind.ACCEPTED,
        WriteStatus.WRITTEN,
        1L,
        MappingOutcome.SUCCESS,
        null,
        new ConfiguredResponse(200, "{}"),
        null,
        null,
        null,
        null);
  }

  private static IngestionResult failure(WriteStatus status, Kind kind) {
    return new IngestionResult(
        kind, status, null, null, null, null, ErrorCode.INTERNAL_ERROR, "m", "x.Y", "08006");
  }

  private static LoadDataGenerator generator(int n) {
    return new LoadDataGenerator(new LoadParameters(n, 1, 1, 3, false, "x_load"), NOW);
  }

  @ParameterizedTest
  @EnumSource(
      value = WriteStatus.class,
      names = {"ACCEPTED_WRITE_FAILED", "REJECTED_RECORD_FAILED", "SNAPSHOT_READ_FAILED"})
  void everyNonWrittenStatusStopsTheLoopAtOnceAndNothingMoreIsLoaded(WriteStatus status) {
    AtomicInteger calls = new AtomicInteger();

    LoopResult result =
        LoadRunner.ingestAll(
            generator(100),
            snapshots(3),
            (snapshot, item) ->
                calls.incrementAndGet() == 5 ? failure(status, Kind.ACCEPTED) : written(),
            null);

    assertTrue(result.failed());
    assertEquals(4, result.written());
    assertEquals(5, calls.get());
    assertEquals(status, result.failedResult().writeStatus());
    assertEquals(4, result.failedItem().index());
  }

  @Test
  void aRejectedReceiptThatCouldNotBeStoredIsNotTreatedAsANormalRejection() {
    // Kết quả vẫn mang mã từ chối gốc (401) nhưng WriteStatus báo ghi lỗi.
    IngestionResult notStored =
        new IngestionResult(
            Kind.REJECTED,
            WriteStatus.REJECTED_RECORD_FAILED,
            null,
            null,
            null,
            null,
            ErrorCode.UNAUTHORIZED,
            "m",
            "x.Y",
            "08006");

    LoopResult result =
        LoadRunner.ingestAll(generator(10), snapshots(3), (s, i) -> notStored, null);

    assertTrue(result.failed());
    assertEquals(0, result.written());
  }

  @Test
  void aCleanRunWritesEverythingAndNeverFails() {
    AtomicInteger calls = new AtomicInteger();

    LoopResult result =
        LoadRunner.ingestAll(
            generator(250),
            snapshots(3),
            (s, i) -> {
              calls.incrementAndGet();
              return written();
            },
            null);

    assertFalse(result.failed());
    assertEquals(250, result.written());
    assertEquals(250, calls.get());
    assertNull(result.failedItem());
  }

  @Test
  void variantsAreAppliedToTheSnapshotPassedToTheIngestion() {
    boolean[] seen = new boolean[3];

    LoadRunner.ingestAll(
        generator(2000),
        snapshots(3),
        (snapshot, item) -> {
          switch (item.variant()) {
            case DISABLED -> {
              seen[0] = !snapshot.enabled();
            }
            case DELETED -> {
              seen[1] = snapshot.deleted();
            }
            case API_KEY -> {
              seen[2] = true;
            }
            default -> {
              assertTrue(snapshot.enabled() && !snapshot.deleted());
            }
          }
          return written();
        },
        null);

    assertTrue(seen[0] && seen[1]);
  }

  @Test
  void progressIsPrintedEveryTenPercent() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    LoadRunner.ingestAll(
        generator(100),
        snapshots(3),
        (s, i) -> written(),
        new PrintStream(bytes, true, StandardCharsets.UTF_8));

    String text = bytes.toString(StandardCharsets.UTF_8);
    assertEquals(10, text.lines().count(), text);
    assertTrue(text.contains("Progress: 100/100"), text);
  }
}
