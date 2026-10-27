package com.tpp.simulator.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tpp.simulator.ingestion.RejectedPayloadClassifier.Classified;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** BR15.4, RD-09: quyết định payloadJson hay payloadRawText một lần, trước khi ghi. */
class RejectedPayloadClassifierTest {

  private static Classified classify(String text) {
    return RejectedPayloadClassifier.classify(text.getBytes(StandardCharsets.UTF_8));
  }

  private static void assertJson(String text, String expectedJson) {
    Classified c = classify(text);
    assertEquals(expectedJson, c.payloadJson(), text);
    assertNull(c.payloadRawText(), text);
  }

  private static void assertRaw(Classified c, String expectedRaw) {
    assertNull(c.payloadJson());
    assertEquals(expectedRaw, c.payloadRawText());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"a\":1}",
        "[1,2,3]",
        "\"text\"",
        "123",
        "true",
        "false",
        "null",
        " null ",
        "{}",
        "[]",
        "{\"a\":{\"b\":[1.5,-2,1e3]}}",
        "{\"é\":\"ế\"}",
        "\"\\uD83D\\uDE00\""
      })
  void validStorableJsonIsKeptAsJson(String text) {
    assertJson(text, text);
  }

  @Test
  void aByteOrderMarkIsDroppedFromTheJsonText() {
    Classified c = classify("\uFEFF{\"a\":1}");

    assertEquals("{\"a\":1}", c.payloadJson());
    assertNull(c.payloadRawText());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not json",
        "{\"a\":",
        "{\"a\":1} {\"b\":2}",
        "{'a':1}",
        "[1,]",
        "1 2",
        "}{",
        "nul"
      })
  void textThatIsNotExactlyOneJsonValueIsKeptAsRawText(String text) {
    assertRaw(classify(text), text);
  }

  @Test
  void anEmptyBodyIsAnEmptyRawString() {
    assertRaw(RejectedPayloadClassifier.classify(new byte[0]), "");
    assertRaw(RejectedPayloadClassifier.classify(null), "");
  }

  @Test
  void invalidUtf8BytesBecomeReplacementCharactersInRawText() {
    byte[] body = {'{', '"', 'a', '"', ':', (byte) 0xC3, (byte) 0x28, '}'};

    Classified c = RejectedPayloadClassifier.classify(body);

    assertNull(c.payloadJson());
    assertTrue(c.payloadRawText().contains("\uFFFD"), c.payloadRawText());
    assertTrue(c.payloadRawText().startsWith("{\"a\":"));
  }

  @Test
  void aNulCharacterInsideRawTextIsReplaced() {
    Classified c = classify("abc\u0000def");

    assertRaw(c, "abc\uFFFDdef");
  }

  @Test
  void jsonThatPostgresJsonbCannotStoreFaithfullyFallsBackToRawText() {
    String escapedNul = "{\"a\":\"x\\u0000y\"}";
    String escapedNulKey = "{\"x\\u0000y\":1}";
    String loneHigh = "{\"a\":\"\\uD83D\"}";
    String loneLow = "{\"a\":\"\\uDE00\"}";
    String loneKey = "{\"\\uD83D\":1}";

    for (String text : new String[] {escapedNul, escapedNulKey, loneHigh, loneLow, loneKey}) {
      assertRaw(classify(text), text);
    }
  }

  @Test
  void aProperSurrogatePairIsStorable() {
    assertJson("{\"a\":\"\\uD83D\\uDE00\"}", "{\"a\":\"\\uD83D\\uDE00\"}");
    assertJson("{\"a\":\"😀\"}", "{\"a\":\"😀\"}");
  }

  @Test
  void numbersBeyondTheNumericDomainFallBackToRawText() {
    String hugeExponent = "{\"n\":1e200000}";
    String tooManyDigits = "[" + "9".repeat(900) + "]";

    assertJson("{\"n\":1e100000}", "{\"n\":1e100000}");
    assertRaw(classify(hugeExponent), hugeExponent);
    assertJson(tooManyDigits, tooManyDigits);
    assertRaw(classify("{\"n\":1e-200000}"), "{\"n\":1e-200000}");
    assertRaw(classify("{\"n\":1e-17000}"), "{\"n\":1e-17000}");
    assertJson("{\"n\":1e-16000}", "{\"n\":1e-16000}");
  }

  @Test
  void numericDomainIsComputedWithoutExpandingTheNumber() {
    assertTrue(RejectedPayloadClassifier.storableNumber("0"));
    assertTrue(RejectedPayloadClassifier.storableNumber("0e100000"));
    assertFalse(RejectedPayloadClassifier.storableNumber("0e999999999"));
    assertTrue(RejectedPayloadClassifier.storableNumber("-12.5e3"));
    assertTrue(RejectedPayloadClassifier.storableNumber("1e100000"));
    assertFalse(RejectedPayloadClassifier.storableNumber("1e100001"));
    assertFalse(RejectedPayloadClassifier.storableNumber("1e131072"));
    assertFalse(RejectedPayloadClassifier.storableNumber("1e999999999"));
    assertFalse(RejectedPayloadClassifier.storableNumber("1e-999999999"));
    assertFalse(RejectedPayloadClassifier.storableNumber("not-a-number"));
    assertTrue(RejectedPayloadClassifier.storableNumber("1." + "0".repeat(16383)));
  }

  @Test
  void deepNestingBeyondTheParserLimitIsRawText() {
    String deep = "[".repeat(2000) + "]".repeat(2000);

    assertRaw(classify(deep), deep);
  }
}
