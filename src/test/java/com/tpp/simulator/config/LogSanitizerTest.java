package com.tpp.simulator.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LogSanitizerTest {

  @Test
  void replacesNewlinesAndControlCharactersWithSpaces() {
    String cleaned = LogSanitizer.clean("THU_HO\r\n2026-10-19 INFO fake line\u0000\u001b[31m");

    assertFalse(cleaned.contains("\n"));
    assertFalse(cleaned.contains("\r"));
    assertFalse(cleaned.contains("\u0000"));
    assertFalse(cleaned.contains("\u001b"));
    assertTrue(cleaned.startsWith("THU_HO  2026-10-19 INFO fake line"));
  }

  @Test
  void replacesUnicodeLineSeparators() {
    assertEquals("a b c", LogSanitizer.clean("a\u2028b\u2029c"));
  }

  @Test
  void truncatesToOneHundredCharacters() {
    String cleaned = LogSanitizer.clean("x".repeat(250));

    assertEquals(100, cleaned.length());
  }

  @Test
  void countsSurrogatePairsAsOneCharacterAndNeverSplitsThem() {
    String cleaned = LogSanitizer.clean("\uD83D\uDE00".repeat(150));

    assertEquals(100, cleaned.codePointCount(0, cleaned.length()));
    assertEquals(200, cleaned.length());
  }

  @Test
  void keepsOrdinaryTextAndHandlesNull() {
    assertEquals("THU_HO", LogSanitizer.clean("THU_HO"));
    assertEquals("-", LogSanitizer.clean(null));
    assertEquals("", LogSanitizer.clean(""));
  }
}
