package com.tpp.simulator.loadtool;

import com.tpp.simulator.catalog.ServiceCatalog;
import com.tpp.simulator.catalog.ServiceRuntimeSnapshot;
import com.tpp.simulator.config.DbErrors;
import com.tpp.simulator.ingestion.CallbackIngestionService;
import com.tpp.simulator.ingestion.IngestionResult;
import com.tpp.simulator.ingestion.WriteStatus;
import com.tpp.simulator.loadtool.LoadDataGenerator.Item;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Lõi của công cụ nạp: kiểm tra đích, tạo hoặc dùng lại dịch vụ {@code LOAD_xx}, rồi nạp từng lần
 * nhận qua {@code CallbackIngestionService.receiveForLoad} (cùng quy tắc ánh xạ, che header, gom
 * giao dịch và ghi lần từ chối như đường HTTP, nhưng không qua HTTP).
 *
 * <p>{@link #run} KHÔNG kiểm tra tên database và không tự kết nối hay tạo database (việc đó thuộc
 * {@code LoadCommand}); kiểm thử nạp nhỏ gọi {@code run} trực tiếp trên database kiểm thử. Mọi
 * {@link WriteStatus} khác {@code WRITTEN} là lỗi đầu tiên: dừng ngay, không nạp tiếp, mã thoát 1
 * (RD-07). Lớp này không bắt ngoại lệ truy cập dữ liệu quanh lệnh nạp: nó đọc {@code WriteStatus}.
 */
public final class LoadRunner {

  private static final String EXISTING_LOAD_SERVICES =
      "SELECT service_code FROM services WHERE service_code ~ '^LOAD_[0-9]+$'";

  private final ServiceCatalog catalog;
  private final CallbackIngestionService ingestion;
  private final JdbcClient jdbc;
  private final Clock clock;
  private final PrintStream progress;

  public LoadRunner(
      ServiceCatalog catalog,
      CallbackIngestionService ingestion,
      JdbcClient jdbc,
      Clock clock,
      PrintStream progress) {
    this.catalog = catalog;
    this.ingestion = ingestion;
    this.jdbc = jdbc;
    this.clock = clock;
    this.progress = progress;
  }

  /** Kiểm tra đích trước mọi kết nối (tham số và tên database). */
  public static void validateTarget(LoadParameters parameters) {
    DatabaseNameGuard.validate(parameters.database());
  }

  /** Kết quả của vòng nạp. */
  record LoopResult(long written, Item failedItem, IngestionResult failedResult) {
    boolean failed() {
      return failedResult != null;
    }
  }

  /** Cách nạp một lần nhận (tách ra để kiểm thử dừng-ở-lỗi-đầu-tiên). */
  interface Ingest {
    IngestionResult apply(ServiceRuntimeSnapshot snapshot, Item item);
  }

  /**
   * Vòng nạp: mọi {@code WriteStatus} khác {@code WRITTEN} dừng ngay, không nạp thêm lần nhận nào.
   */
  static LoopResult ingestAll(
      LoadDataGenerator generator,
      Map<Integer, ServiceRuntimeSnapshot> snapshots,
      Ingest ingest,
      PrintStream progress) {
    long written = 0;
    int total = generator.total();
    int step = Math.max(1, total / 10);
    while (generator.hasNext()) {
      Item item = generator.next();
      ServiceRuntimeSnapshot snapshot = variant(snapshots, item);
      IngestionResult result = ingest.apply(snapshot, item);
      if (result.writeStatus() != WriteStatus.WRITTEN) {
        return new LoopResult(written, item, result);
      }
      written++;
      if (progress != null && written % step == 0) {
        progress.println("Progress: " + written + "/" + total);
      }
    }
    return new LoopResult(written, null, null);
  }

  private static ServiceRuntimeSnapshot variant(
      Map<Integer, ServiceRuntimeSnapshot> snapshots, Item item) {
    ServiceRuntimeSnapshot base = snapshots.get(item.serviceNumber());
    return switch (item.variant()) {
      case NORMAL -> base;
      case DISABLED -> base.withEnabled(false);
      case DELETED -> base.withDeleted(true);
      case API_KEY ->
          base.withApiKey(
              LoadServiceDefinitions.API_KEY_HEADER, LoadServiceDefinitions.API_KEY_VALUE);
    };
  }

  public LoadReport run(LoadParameters parameters) {
    long startNanos = System.nanoTime();
    long written = 0;
    try {
      Set<String> existing =
          new HashSet<>(jdbc.sql(EXISTING_LOAD_SERVICES).query(String.class).list());
      List<Integer> numbers =
          java.util.stream.IntStream.rangeClosed(1, parameters.services()).boxed().toList();
      if (!parameters.append()) {
        for (int n : numbers) {
          if (existing.contains(LoadServiceDefinitions.serviceCode(n))) {
            return LoadReport.refused(
                "Dịch vụ "
                    + LoadServiceDefinitions.serviceCode(n)
                    + " đã có trong database đích; dùng --append để nạp thêm hoặc xóa database nạp.");
          }
        }
      }
      for (int n : numbers) {
        if (!existing.contains(LoadServiceDefinitions.serviceCode(n))) {
          catalog.create(LoadServiceDefinitions.request(n));
        }
      }
      Map<Integer, ServiceRuntimeSnapshot> snapshots = new java.util.HashMap<>();
      for (int n : numbers) {
        snapshots.put(n, catalog.findSnapshot(LoadServiceDefinitions.serviceCode(n)).orElseThrow());
      }
      LoadDataGenerator generator = new LoadDataGenerator(parameters, clock.instant());
      LoopResult loop =
          ingestAll(
              generator,
              snapshots,
              (snapshot, item) ->
                  ingestion.receiveForLoad(
                      snapshot, item.contentType(), item.headers(), item.body(), item.receivedAt()),
              progress);
      written = loop.written();
      if (loop.failed()) {
        return LoadReport.failed(
            count("callbacks"),
            count("transactions"),
            written,
            elapsed(startNanos),
            "writeStatus="
                + loop.failedResult().writeStatus()
                + " exception="
                + loop.failedResult().failureClass()
                + " sqlState="
                + loop.failedResult().sqlState()
                + " itemIndex="
                + loop.failedItem().index());
      }
      jdbc.sql("ANALYZE callbacks, transactions, services, service_config_versions").update();
      return LoadReport.success(
          count("callbacks"), count("transactions"), written, elapsed(startNanos));
    } catch (RuntimeException e) {
      return LoadReport.failed(
          count("callbacks"),
          count("transactions"),
          written,
          elapsed(startNanos),
          "exception=" + e.getClass().getName() + " sqlState=" + DbErrors.sqlState(e));
    }
  }

  // Âm nếu chính phép đếm cũng lỗi.
  private long count(String table) {
    String sql = table.equals("callbacks") ? COUNT_CALLBACKS : COUNT_TRANSACTIONS;
    try {
      return jdbc.sql(sql).query(Long.class).single();
    } catch (RuntimeException e) {
      return -1;
    }
  }

  private static final String COUNT_CALLBACKS = "SELECT count(*) FROM callbacks";
  private static final String COUNT_TRANSACTIONS = "SELECT count(*) FROM transactions";

  private Duration elapsed(long startNanos) {
    return Duration.ofNanos(System.nanoTime() - startNanos);
  }
}
