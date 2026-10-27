package com.tpp.simulator.loadtool;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Sinh tất định các lần nhận để nạp (BR22.4, PD-08): cùng {@code seed} cho cùng cấu trúc và giá trị
 * (thời điểm nhận tính từ lúc chạy). Khoảng 4 lần nhận mỗi giao dịch (tối đa 20), 85% SUCCESS, 10%
 * MAPPING_ERROR, 5% REJECTED với sáu lý do xoay vòng, thời điểm nhận rải đều trong {@code days}
 * ngày, payload khoảng 1 KB. Sinh từng lần nhận một, không giữ danh sách trong bộ nhớ.
 */
public final class LoadDataGenerator {

  /** Trạng thái của ảnh dịch vụ cần dùng khi nạp lần nhận này. */
  public enum Variant {
    NORMAL,
    DISABLED,
    DELETED,
    API_KEY
  }

  /** Loại kết quả mong đợi của lần nhận. */
  public enum Kind {
    SUCCESS,
    MAPPING_ERROR,
    REJECTED
  }

  /** Một lần nhận cần nạp. */
  public record Item(
      int index,
      int serviceNumber,
      Variant variant,
      Kind kind,
      String rejectionReason,
      String contentType,
      Map<String, List<String>> headers,
      byte[] body,
      Instant receivedAt) {}

  /** Sáu lý do từ chối, xoay vòng theo đúng thứ tự này (PD-08). */
  static final List<String> REASONS =
      List.of(
          "SERVICE_DISABLED",
          "SERVICE_DELETED",
          "MISSING_API_KEY",
          "INVALID_API_KEY",
          "UNSUPPORTED_CONTENT_TYPE",
          "INVALID_JSON");

  private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
  private static final String[] STATUSES = {"00", "01", "02", "99"};
  private static final String ALPHABET =
      "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  private static final int PAD_LENGTH = 900;
  private static final int MAX_CALLBACKS_PER_TRANSACTION = 20;

  private final int total;
  private final int services;
  private final SplittableRandom random;
  private final Instant start;
  private final long stepMicros;
  private final List<Integer> apiKeyServices = new ArrayList<>();
  private final Map<Integer, List<long[]>> openTransactions = new HashMap<>();
  private final Map<Integer, Long> nextTransaction = new HashMap<>();
  private int index;
  private int rejectionCounter;

  /**
   * @param now thời điểm chạy; khoảng thời điểm nhận là {@code days} ngày trước đó đến {@code now}
   */
  public LoadDataGenerator(LoadParameters parameters, Instant now) {
    this.total = parameters.callbacks();
    this.services = parameters.services();
    this.random = new SplittableRandom(parameters.seed());
    long spanMicros = parameters.days() * 86_400L * 1_000_000L;
    this.stepMicros = Math.max(1, spanMicros / total);
    this.start = now.truncatedTo(ChronoUnit.MICROS).minus(spanMicros, ChronoUnit.MICROS);
    for (int n = 1; n <= services; n++) {
      if (LoadServiceDefinitions.requiresApiKey(n)) {
        apiKeyServices.add(n);
      }
    }
  }

  /** true nếu khoảng dịch vụ không có dịch vụ API_KEY thật, nên phải dùng biến thể ảnh. */
  public boolean needsApiKeyVariant() {
    return apiKeyServices.isEmpty();
  }

  public int total() {
    return total;
  }

  public boolean hasNext() {
    return index < total;
  }

  public Item next() {
    int i = index++;
    Instant receivedAt =
        start.plus(i * stepMicros + random.nextLong(stepMicros), ChronoUnit.MICROS);
    int roll = random.nextInt(100);
    if (roll < 85) {
      return accepted(i, receivedAt, Kind.SUCCESS);
    }
    if (roll < 95) {
      return accepted(i, receivedAt, Kind.MAPPING_ERROR);
    }
    return rejected(i, receivedAt, REASONS.get(rejectionCounter++ % REASONS.size()));
  }

  // ---------- lần nhận được chấp nhận ----------

  private Item accepted(int i, Instant receivedAt, Kind kind) {
    int service = 1 + random.nextInt(services);
    String transactionId =
        kind == Kind.SUCCESS || random.nextInt(2) == 0 ? transactionOf(service) : null;
    String body = body(service, transactionId, receivedAt, kind, i);
    return new Item(
        i,
        service,
        Variant.NORMAL,
        kind,
        null,
        "application/json",
        headers(i, service, true),
        body.getBytes(StandardCharsets.UTF_8),
        receivedAt);
  }

  private String transactionOf(int service) {
    List<long[]> open = openTransactions.computeIfAbsent(service, k -> new ArrayList<>());
    // Xác suất dùng lại 75% cho trung bình khoảng 4 lần nhận mỗi giao dịch.
    if (!open.isEmpty() && random.nextInt(4) != 0) {
      int pick = random.nextInt(open.size());
      long[] entry = open.get(pick);
      entry[1]++;
      String id = "TX" + service + "-" + entry[0];
      if (entry[1] >= MAX_CALLBACKS_PER_TRANSACTION) {
        open.remove(pick);
      }
      return id;
    }
    long sequence = nextTransaction.merge(service, 1L, Long::sum);
    if (open.size() >= 200) {
      open.remove(0);
    }
    open.add(new long[] {sequence, 1});
    return "TX" + service + "-" + sequence;
  }

  private String body(int service, String transactionId, Instant receivedAt, Kind kind, int i) {
    StringBuilder text = new StringBuilder(PAD_LENGTH + 200).append('{');
    int fault = kind == Kind.MAPPING_ERROR ? i % 3 : -1;
    if (fault != 2) {
      text.append("\"transId\":\"")
          .append(transactionId == null ? "" : transactionId)
          .append("\",");
    }
    if (fault != 0) {
      text.append("\"code\":\"").append(STATUSES[random.nextInt(STATUSES.length)]).append("\",");
    }
    text.append("\"time\":").append(timestamp(service, receivedAt, fault == 1)).append(',');
    text.append("\"note\":\"");
    for (int k = 0; k < PAD_LENGTH; k++) {
      text.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return text.append("\"}").toString();
  }

  private String timestamp(int service, Instant receivedAt, boolean broken) {
    if (broken) {
      return "\"not-a-time\"";
    }
    Instant business = receivedAt.minusSeconds(random.nextInt(30)).truncatedTo(ChronoUnit.SECONDS);
    return switch (LoadServiceDefinitions.kind(service)) {
      case ISO_NONE ->
          "\"" + DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(business.atZone(ZONE)) + "\"";
      case PATTERN_API_KEY ->
          "\""
              + DateTimeFormatter.ofPattern(LoadServiceDefinitions.PATTERN)
                  .format(business.atZone(ZONE))
              + "\"";
      case MILLIS_NONE -> String.valueOf(business.toEpochMilli());
    };
  }

  // ---------- lần nhận bị từ chối ----------

  private Item rejected(int i, Instant receivedAt, String reason) {
    boolean keyReason = reason.equals("MISSING_API_KEY") || reason.equals("INVALID_API_KEY");
    int service;
    Variant variant = Variant.NORMAL;
    if (keyReason) {
      if (apiKeyServices.isEmpty()) {
        service = 1;
        variant = Variant.API_KEY;
      } else {
        service = apiKeyServices.get(rejectionCounter % apiKeyServices.size());
      }
    } else {
      service = 1 + random.nextInt(services);
      if (reason.equals("SERVICE_DISABLED")) {
        variant = Variant.DISABLED;
      } else if (reason.equals("SERVICE_DELETED")) {
        variant = Variant.DELETED;
      }
    }
    String contentType =
        reason.equals("UNSUPPORTED_CONTENT_TYPE") ? "text/plain" : "application/json";
    String body =
        reason.equals("INVALID_JSON")
            ? "{not json " + i
            : body(service, transactionIdFor(service), receivedAt, Kind.SUCCESS, i);
    Map<String, List<String>> headers = headers(i, service, !keyReason);
    if (reason.equals("INVALID_API_KEY")) {
      headers.put(LoadServiceDefinitions.API_KEY_HEADER, List.of("wrong-key"));
    }
    return new Item(
        i,
        service,
        variant,
        Kind.REJECTED,
        reason,
        contentType,
        headers,
        body.getBytes(StandardCharsets.UTF_8),
        receivedAt);
  }

  // Lần từ chối không vào giao dịch; dùng một mã tùy ý không ảnh hưởng bộ gom giao dịch.
  private String transactionIdFor(int service) {
    return "REJ" + service + "-" + random.nextInt(1000);
  }

  // ---------- header ----------

  private Map<String, List<String>> headers(int i, int service, boolean withKey) {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    headers.put("X-Request-Id", List.of("load-" + i));
    if (withKey && LoadServiceDefinitions.requiresApiKey(service)) {
      headers.put(
          LoadServiceDefinitions.API_KEY_HEADER, List.of(LoadServiceDefinitions.API_KEY_VALUE));
    }
    return headers;
  }
}
