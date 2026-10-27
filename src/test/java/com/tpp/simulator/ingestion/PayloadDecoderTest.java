package com.tpp.simulator.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PayloadDecoderTest {

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"a\":1}",
        "[1,2]",
        "5",
        "\"text\"",
        "null",
        "true",
        "  {\"a\" : [1, {\"b\":null}]}  "
      })
  void acceptsAnySingleJsonValue(String json) {
    assertEquals(json, PayloadDecoder.decode(bytes(json)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "   ",
        "{",
        "{\"a\":}",
        "{\"a\":1,}",
        "{'a':1}",
        "[1,2",
        "abc",
        "{\"a\":1} {\"b\":2}",
        "5 6",
        "{\"a\":1}x",
        "01",
        "NaN",
        "// c\n{}"
      })
  void rejectsEmptyMalformedAndMultiValueBodies(String text) {
    assertThrows(
        PayloadDecoder.InvalidPayloadException.class, () -> PayloadDecoder.decode(bytes(text)));
  }

  @Test
  void rejectsNullOrEmptyBytes() {
    assertThrows(PayloadDecoder.InvalidPayloadException.class, () -> PayloadDecoder.decode(null));
    assertThrows(
        PayloadDecoder.InvalidPayloadException.class, () -> PayloadDecoder.decode(new byte[0]));
  }

  @Test
  void rejectsInvalidUtf8() {
    byte[] invalid = {'{', '"', 'a', '"', ':', '"', (byte) 0xC3, (byte) 0x28, '"', '}'};

    assertThrows(
        PayloadDecoder.InvalidPayloadException.class, () -> PayloadDecoder.decode(invalid));
  }

  @Test
  void stripsUtf8ByteOrderMarkAndKeepsUnicodeText() {
    byte[] withBom = bytes("\uFEFF{\"name\":\"Thành công\"}");

    assertEquals("{\"name\":\"Thành công\"}", PayloadDecoder.decode(withBom));
  }

  @Test
  void rejectsExcessivelyDeepNesting() {
    String deep = "[".repeat(2000) + "]".repeat(2000);

    assertThrows(
        PayloadDecoder.InvalidPayloadException.class, () -> PayloadDecoder.decode(bytes(deep)));
  }
}
