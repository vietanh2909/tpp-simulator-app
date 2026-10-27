package com.tpp.simulator.ingestion;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Giải mã body callback thành văn bản UTF-8 (nghiêm ngặt) và kiểm tra là đúng MỘT giá trị JSON hợp
 * lệ (BR2.4). Mọi giá trị JSON hợp lệ (object, mảng, số, chuỗi, null) đều được chấp nhận ở bước
 * này.
 */
final class PayloadDecoder {

  private static final JsonFactory JSON = new JsonFactory();

  private PayloadDecoder() {}

  /**
   * @return văn bản JSON hợp lệ (đã bỏ BOM nếu có)
   * @throws InvalidPayloadException nếu body rỗng, không phải UTF-8 hợp lệ, hoặc không phải đúng
   *     một giá trị JSON
   */
  static String decode(byte[] body) {
    if (body == null || body.length == 0) {
      throw new InvalidPayloadException();
    }
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(body))
              .toString();
    } catch (CharacterCodingException e) {
      throw new InvalidPayloadException();
    }
    if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
      text = text.substring(1);
    }
    try (JsonParser parser = JSON.createParser(text)) {
      JsonToken first = parser.nextToken();
      if (first == null) {
        throw new InvalidPayloadException();
      }
      if (first.isStructStart()) {
        parser.skipChildren();
      }
      if (parser.nextToken() != null) {
        throw new InvalidPayloadException();
      }
    } catch (IOException e) {
      throw new InvalidPayloadException();
    }
    return text;
  }

  /** Body không phải JSON hợp lệ. */
  static final class InvalidPayloadException extends RuntimeException {
    InvalidPayloadException() {
      super("Invalid JSON payload", null, false, false);
    }
  }
}
