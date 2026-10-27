package com.tpp.simulator.loadtool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tpp.simulator.loadtool.LoadDataGenerator.Item;
import com.tpp.simulator.loadtool.LoadDataGenerator.Kind;
import com.tpp.simulator.loadtool.LoadDataGenerator.Variant;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** BR22.4, PD-08: dữ liệu nạp tất định, đúng tỷ lệ, đủ sáu lý do từ chối, thời điểm rải đều. */
class LoadDataGeneratorTest {

  private static final Instant NOW = Instant.parse("2026-10-19T03:00:00Z");

  private static List<Item> generate(int callbacks, long seed, int services, int days) {
    LoadDataGenerator generator =
        new LoadDataGenerator(
            new LoadParameters(callbacks, seed, days, services, false, "x_load"), NOW);
    List<Item> items = new ArrayList<>();
    while (generator.hasNext()) {
      items.add(generator.next());
    }
    return items;
  }

  private static String text(Item item) {
    return new String(item.body(), StandardCharsets.UTF_8);
  }

  @Test
  void theSameSeedGivesTheSameData() {
    List<Item> a = generate(2000, 11, 3, 30);
    List<Item> b = generate(2000, 11, 3, 30);

    assertEquals(a.size(), b.size());
    for (int i = 0; i < a.size(); i++) {
      assertEquals(a.get(i).serviceNumber(), b.get(i).serviceNumber());
      assertEquals(a.get(i).kind(), b.get(i).kind());
      assertEquals(a.get(i).receivedAt(), b.get(i).receivedAt());
      assertEquals(text(a.get(i)), text(b.get(i)));
    }
  }

  @Test
  void aDifferentSeedGivesDifferentData() {
    String first = text(generate(50, 1, 3, 30).get(10));
    String second = text(generate(50, 2, 3, 30).get(10));

    assertFalse(first.equals(second));
  }

  @Test
  void theRatiosAreAboutEightyFiveTenAndFivePercent() {
    List<Item> items = generate(20_000, 5, 3, 30);
    Map<Kind, Integer> counts = new HashMap<>();
    items.forEach(i -> counts.merge(i.kind(), 1, Integer::sum));

    assertEquals(85.0, counts.get(Kind.SUCCESS) * 100.0 / items.size(), 2.0);
    assertEquals(10.0, counts.get(Kind.MAPPING_ERROR) * 100.0 / items.size(), 2.0);
    assertEquals(5.0, counts.get(Kind.REJECTED) * 100.0 / items.size(), 1.5);
  }

  @Test
  void allSixRejectionReasonsAppearInTheFixedRotation() {
    List<String> reasons =
        generate(2000, 3, 3, 30).stream()
            .filter(i -> i.kind() == Kind.REJECTED)
            .map(Item::rejectionReason)
            .toList();

    assertTrue(reasons.size() >= 60, "chỉ có " + reasons.size());
    assertEquals(LoadDataGenerator.REASONS, reasons.subList(0, 6));
    assertEquals(LoadDataGenerator.REASONS, reasons.subList(6, 12));
    assertEquals(new LinkedHashSet<>(LoadDataGenerator.REASONS), new LinkedHashSet<>(reasons));
  }

  @Test
  void rejectionReasonsShapeTheRequestAsTheyShould() {
    for (Item item :
        generate(3000, 9, 3, 30).stream().filter(i -> i.kind() == Kind.REJECTED).toList()) {
      switch (item.rejectionReason()) {
        case "SERVICE_DISABLED" -> assertEquals(Variant.DISABLED, item.variant());
        case "SERVICE_DELETED" -> assertEquals(Variant.DELETED, item.variant());
        case "UNSUPPORTED_CONTENT_TYPE" -> assertEquals("text/plain", item.contentType());
        case "INVALID_JSON" -> assertTrue(text(item).startsWith("{not json"));
        case "MISSING_API_KEY" -> {
          assertTrue(LoadServiceDefinitions.requiresApiKey(item.serviceNumber()));
          assertFalse(item.headers().containsKey(LoadServiceDefinitions.API_KEY_HEADER));
        }
        case "INVALID_API_KEY" -> {
          assertTrue(LoadServiceDefinitions.requiresApiKey(item.serviceNumber()));
          assertEquals(
              List.of("wrong-key"), item.headers().get(LoadServiceDefinitions.API_KEY_HEADER));
        }
        default -> throw new AssertionError(item.rejectionReason());
      }
    }
  }

  @Test
  void withASingleServiceTheApiKeyVariantIsUsedForTheKeyReasons() {
    LoadDataGenerator generator =
        new LoadDataGenerator(new LoadParameters(1000, 4, 30, 1, false, "x_load"), NOW);
    assertTrue(generator.needsApiKeyVariant());
    Set<String> reasonsWithVariant = new LinkedHashSet<>();
    Set<String> reasons = new LinkedHashSet<>();
    while (generator.hasNext()) {
      Item item = generator.next();
      assertEquals(1, item.serviceNumber());
      if (item.kind() == Kind.REJECTED) {
        reasons.add(item.rejectionReason());
        if (item.variant() == Variant.API_KEY) {
          reasonsWithVariant.add(item.rejectionReason());
        }
      }
    }

    assertEquals(new LinkedHashSet<>(LoadDataGenerator.REASONS), reasons);
    assertEquals(Set.of("MISSING_API_KEY", "INVALID_API_KEY"), reasonsWithVariant);
  }

  @Test
  void withSeveralServicesNoVariantIsNeeded() {
    LoadDataGenerator generator =
        new LoadDataGenerator(new LoadParameters(10, 4, 30, 2, false, "x_load"), NOW);

    assertFalse(generator.needsApiKeyVariant());
  }

  @Test
  void receivedAtIsAscendingAndSpreadOverTheRequestedDays() {
    List<Item> items = generate(5000, 8, 3, 10);

    Instant previous = Instant.MIN;
    for (Item item : items) {
      assertFalse(item.receivedAt().isBefore(previous));
      previous = item.receivedAt();
    }
    Instant first = items.get(0).receivedAt();
    Instant last = items.get(items.size() - 1).receivedAt();
    assertFalse(first.isBefore(NOW.minus(Duration.ofDays(10))));
    assertFalse(last.isAfter(NOW));
    assertTrue(Duration.between(first, last).toDays() >= 9);
  }

  @Test
  void acceptedPayloadsAreAboutOneKilobyteAndTransactionsAreAboutFourCallbacksEach() {
    List<Item> items = generate(20_000, 6, 3, 30);
    Map<String, Integer> perTransaction = new HashMap<>();
    long totalBytes = 0;
    int accepted = 0;
    for (Item item : items) {
      if (item.kind() == Kind.REJECTED) {
        continue;
      }
      accepted++;
      totalBytes += item.body().length;
      String body = text(item);
      int at = body.indexOf("\"transId\":\"");
      if (at >= 0) {
        String id = body.substring(at + 11, body.indexOf('"', at + 11));
        if (!id.isEmpty()) {
          perTransaction.merge(item.serviceNumber() + ":" + id, 1, Integer::sum);
        }
      }
    }

    assertEquals(1000.0, totalBytes / (double) accepted, 150.0);
    double average =
        perTransaction.values().stream().mapToInt(Integer::intValue).average().orElse(0);
    assertTrue(average > 2.5 && average < 6.0, "trung bình " + average);
    assertTrue(perTransaction.values().stream().allMatch(count -> count <= 20));
  }

  @Test
  void mappingErrorPayloadsAreMissingOrBrokenInTheExpectedWays() {
    List<Item> errors =
        generate(3000, 2, 3, 30).stream().filter(i -> i.kind() == Kind.MAPPING_ERROR).toList();

    assertTrue(errors.stream().anyMatch(i -> !text(i).contains("\"code\"")));
    assertTrue(errors.stream().anyMatch(i -> text(i).contains("not-a-time")));
    assertTrue(errors.stream().anyMatch(i -> !text(i).contains("\"transId\"")));
  }

  @Test
  void apiKeyServicesSendTheirKeyOnAcceptedCallbacks() {
    for (Item item : generate(500, 1, 3, 30)) {
      if (item.kind() != Kind.REJECTED
          && LoadServiceDefinitions.requiresApiKey(item.serviceNumber())) {
        assertEquals(
            List.of(LoadServiceDefinitions.API_KEY_VALUE),
            item.headers().get(LoadServiceDefinitions.API_KEY_HEADER));
      }
    }
  }
}
