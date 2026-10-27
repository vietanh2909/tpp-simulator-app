package com.tpp.simulator.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Chạy N tác vụ cùng lúc, thả bằng một chốt xuất phát chung, rồi gom kết quả theo thứ tự. */
public final class ParallelRunner {

  private ParallelRunner() {}

  public static <T> List<T> runAll(List<Callable<T>> tasks) {
    ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
    try {
      CountDownLatch ready = new CountDownLatch(tasks.size());
      CountDownLatch go = new CountDownLatch(1);
      List<Future<T>> futures = new ArrayList<>();
      for (Callable<T> task : tasks) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  return task.call();
                }));
      }
      if (!ready.await(30, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Các luồng không sẵn sàng");
      }
      go.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) {
        results.add(future.get(120, TimeUnit.SECONDS));
      }
      return results;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    } finally {
      pool.shutdownNow();
    }
  }
}
