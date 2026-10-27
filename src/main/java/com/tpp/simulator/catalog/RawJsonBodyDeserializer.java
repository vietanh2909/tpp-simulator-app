package com.tpp.simulator.catalog;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ValueNode;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Objects;

/**
 * Đọc {@code responseBody} thành văn bản JSON gọn (bỏ khoảng trắng thừa) nhưng GIỮ NGUYÊN văn bản
 * của từng số ({@code 100000.00}, {@code 1e3}, số thập phân dài), vì dựng cây rồi {@code
 * toString()} sẽ làm mất chúng. Duyệt theo token, không đệ quy.
 */
public class RawJsonBodyDeserializer extends StdDeserializer<JsonNode> {

  private static final long serialVersionUID = 1L;

  public RawJsonBodyDeserializer() {
    super(JsonNode.class);
  }

  @Override
  public JsonNode deserialize(JsonParser parser, DeserializationContext context)
      throws IOException {
    StringWriter out = new StringWriter();
    try (JsonGenerator generator = parser.getCodec().getFactory().createGenerator(out)) {
      int depth = 0;
      JsonToken token = parser.currentToken();
      while (true) {
        switch (token) {
          case START_OBJECT -> {
            generator.writeStartObject();
            depth++;
          }
          case END_OBJECT -> {
            generator.writeEndObject();
            depth--;
          }
          case START_ARRAY -> {
            generator.writeStartArray();
            depth++;
          }
          case END_ARRAY -> {
            generator.writeEndArray();
            depth--;
          }
          case FIELD_NAME -> generator.writeFieldName(parser.currentName());
          case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> generator.writeNumber(parser.getText());
          case VALUE_STRING -> generator.writeString(parser.getText());
          case VALUE_TRUE -> generator.writeBoolean(true);
          case VALUE_FALSE -> generator.writeBoolean(false);
          case VALUE_NULL -> generator.writeNull();
          default -> {
            return (JsonNode) context.handleUnexpectedToken(JsonNode.class, parser);
          }
        }
        if (depth == 0) {
          break;
        }
        token = parser.nextToken();
      }
    }
    return new RawJsonNode(out.toString());
  }

  /** Nút giữ văn bản JSON đã kiểm tra hợp lệ; {@code toString()} trả đúng văn bản đó. */
  static final class RawJsonNode extends ValueNode {

    private static final long serialVersionUID = 1L;

    private final String text;

    RawJsonNode(String text) {
      this.text = text;
    }

    @Override
    public JsonToken asToken() {
      return JsonToken.VALUE_EMBEDDED_OBJECT;
    }

    @Override
    public JsonNodeType getNodeType() {
      return JsonNodeType.POJO;
    }

    @Override
    public String asText() {
      return text;
    }

    @Override
    public void serialize(JsonGenerator generator, SerializerProvider provider) throws IOException {
      generator.writeRawValue(text);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof RawJsonNode node && text.equals(node.text);
    }

    @Override
    public int hashCode() {
      return Objects.hash(text);
    }

    @Override
    public String toString() {
      return text;
    }
  }
}
