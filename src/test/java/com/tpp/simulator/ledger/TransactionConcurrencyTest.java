package com.tpp.simulator.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tpp.simulator.config.MaskedHeaders;
import com.tpp.simulator.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * NFR2.2, NFR2.3, BR4.6: nhiều luồng ghi song song cùng một giao dịch trên PostgreSQL thật, mỗi
 * luồng một giao dịch cơ sở dữ liệu riêng. Tổng số bằng số lần ghi, hai callback đầu tiên cùng cặp
 * không lỗi, và dòng "gần nhất" đúng bất kể thứ tự hoàn tất.
 */
class TransactionConcurrencyTest extends AbstractIntegrationTest {

  private static final Instant T0 = Instant.parse("2026-10-19T03:00:00Z");

  @Autowired CallbackLedger ledger;
  @Autowired PlatformTransactionManager txManager;

  long versionId;

  @BeforeEach
  void service() {
    versionId = insertServiceRow("THU_HO");
  }

  private NewCallback callback(String transactionId, String status, Instant receivedAt) {
    return new NewCallback(
        "THU_HO",
        versionId,
        receivedAt,
        "SUCCESS",
        new MaskedHeaders(Map.of()),
        "{}",
        transactionId,
        status,
        null,
        "{}",
        201,
        "{}");
  }

  /** Chạy mọi tác vụ cùng lúc (chốt bằng latch) và trả về lỗi đầu tiên nếu có. */
  private void runConcurrently(List<Runnable> tasks) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
    CountDownLatch ready = new CountDownLatch(tasks.size());
    CountDownLatch go = new CountDownLatch(1);
    List<Future<?>> futures = new ArrayList<>();
    for (Runnable task : tasks) {
      futures.add(
          pool.submit(
              () -> {
                ready.countDown();
                try {
                  go.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                task.run();
              }));
    }
    assertTrue(ready.await(10, TimeUnit.SECONDS));
    go.countDown();
    for (Future<?> future : futures) {
      future.get(60, TimeUnit.SECONDS);
    }
    pool.shutdownNow();
  }

  @RepeatedTest(5)
  void nParallelWritesToTheSameTransactionGiveCountNAndTheCorrectLatestRow() throws Exception {
    int n = 20;
    String transactionId = "T-" + System.nanoTime();
    Random random = new Random();
    List<Runnable> tasks = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      // receivedAt tăng theo i nhưng thứ tự hoàn tất của các luồng là ngẫu nhiên.
      NewCallback callback = callback(transactionId, "S" + i, T0.plusSeconds(i));
      int jitter = random.nextInt(20);
      tasks.add(
          () ->
              new TransactionTemplate(txManager)
                  .executeWithoutResult(
                      s -> {
                        ledger.append(callback);
                        try {
                          Thread.sleep(jitter);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                      }));
    }

    runConcurrently(tasks);

    Map<String, Object> t =
        jdbc.queryForMap(
            "SELECT * FROM transactions WHERE service_code = 'THU_HO' AND transaction_id = ?",
            transactionId);
    assertEquals(n, t.get("callback_count"));
    assertEquals(
        n,
        jdbc.queryForObject(
            "SELECT count(*) FROM callbacks WHERE transaction_id = ?",
            Integer.class,
            transactionId));
    // Dòng gần nhất là lần nhận có (received_at, record_id) lớn nhất, và MỌI cột latest_* đến từ
    // đúng lần đó.
    Map<String, Object> newest =
        jdbc.queryForMap(
            "SELECT record_id, status, received_at FROM callbacks WHERE transaction_id = ?"
                + " ORDER BY received_at DESC, record_id DESC LIMIT 1",
            transactionId);
    assertEquals(newest.get("record_id"), t.get("latest_callback_id"));
    assertEquals(newest.get("status"), t.get("latest_status"));
    assertEquals(newest.get("received_at"), t.get("latest_received_at"));
    assertEquals("S" + (n - 1), t.get("latest_status"));
  }

  @RepeatedTest(15)
  void twoFirstCallbacksOfTheSamePairAtTheSameTimeNeitherFails() throws Exception {
    String transactionId = "FIRST-" + System.nanoTime();
    List<Runnable> tasks =
        List.of(
            () ->
                new TransactionTemplate(txManager)
                    .executeWithoutResult(s -> ledger.append(callback(transactionId, "A", T0))),
            () ->
                new TransactionTemplate(txManager)
                    .executeWithoutResult(
                        s -> ledger.append(callback(transactionId, "B", T0.plusSeconds(1)))));

    runConcurrently(tasks);

    Map<String, Object> t =
        jdbc.queryForMap("SELECT * FROM transactions WHERE transaction_id = ?", transactionId);
    assertEquals(2, t.get("callback_count"));
    assertEquals("B", t.get("latest_status"));
  }

  @Test
  void callbackReceivedEarlierButCommittedLastDoesNotReplaceTheNewerState() throws Exception {
    String transactionId = "REVERSE";
    // Luồng "mới" (receivedAt lớn) ghi và giữ giao dịch mở; luồng "cũ" ghi sau rồi hoàn tất sau.
    CountDownLatch newerWritten = new CountDownLatch(1);
    CountDownLatch olderMayFinish = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    Future<?> newer =
        pool.submit(
            () ->
                new TransactionTemplate(txManager)
                    .executeWithoutResult(
                        s -> {
                          ledger.append(callback(transactionId, "NEWER", T0.plusSeconds(10)));
                          newerWritten.countDown();
                          try {
                            olderMayFinish.await(10, TimeUnit.SECONDS);
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                          }
                        }));
    assertTrue(newerWritten.await(10, TimeUnit.SECONDS));
    Future<?> older =
        pool.submit(
            () ->
                new TransactionTemplate(txManager)
                    .executeWithoutResult(
                        s -> ledger.append(callback(transactionId, "OLDER", T0))));
    Thread.sleep(300);
    olderMayFinish.countDown();
    newer.get(30, TimeUnit.SECONDS);
    older.get(30, TimeUnit.SECONDS);
    pool.shutdownNow();

    Map<String, Object> t =
        jdbc.queryForMap("SELECT * FROM transactions WHERE transaction_id = ?", transactionId);
    assertEquals(2, t.get("callback_count"));
    assertEquals("NEWER", t.get("latest_status"));
  }
}
