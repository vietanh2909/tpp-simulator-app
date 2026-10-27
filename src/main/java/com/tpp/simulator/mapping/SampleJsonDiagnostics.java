package com.tpp.simulator.mapping;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import java.io.IOException;

/**
 * Mô tả vì sao JSON mẫu của thử mapping không hợp lệ (SD-09, BR18.3). Câu mô tả dựng từ TỪ VỰNG CỐ
 * ĐỊNH theo loại lỗi của bộ đọc luồng cộng dòng và cột; KHÔNG BAO GIỜ dùng thông điệp của ngoại lệ
 * Jackson vì chúng trích đoạn nội dung mẫu. Thuần, không trạng thái.
 */
public final class SampleJsonDiagnostics {

  private static final JsonFactory JSON = new JsonFactory();

  private SampleJsonDiagnostics() {}

  /**
   * @return null nếu {@code text} là đúng một giá trị JSON hợp lệ, ngược lại là câu mô tả lỗi
   */
  public static String diagnose(String text) {
    if (text == null || text.isBlank()) {
      return "JSON mẫu rỗng.";
    }
    try (JsonParser parser = JSON.createParser(text)) {
      JsonToken first = parser.nextToken();
      if (first == null) {
        return "JSON mẫu rỗng.";
      }
      if (first.isStructStart()) {
        parser.skipChildren();
      }
      if (parser.nextToken() != null) {
        return "JSON mẫu có nội dung thừa sau giá trị JSON đầu tiên"
            + where(parser.currentLocation());
      }
      return null;
    } catch (StreamConstraintsException e) {
      return "JSON mẫu vượt giới hạn cấu trúc của bộ đọc" + where(e.getLocation());
    } catch (JsonParseException e) {
      boolean ended = e instanceof com.fasterxml.jackson.core.io.JsonEOFException;
      return (ended ? "JSON mẫu kết thúc đột ngột" : "JSON mẫu có ký tự hoặc giá trị không hợp lệ")
          + where(e.getLocation());
    } catch (IOException e) {
      return "JSON mẫu không đọc được.";
    }
  }

  private static String where(JsonLocation location) {
    if (location == null) {
      return ".";
    }
    return " (dòng " + location.getLineNr() + ", cột " + location.getColumnNr() + ").";
  }
}
