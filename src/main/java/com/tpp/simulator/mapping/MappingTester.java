package com.tpp.simulator.mapping;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.stereotype.Component;

/**
 * Thử mapping thuần (BR18.x, PD-07): dùng đúng {@link MappingEngine} như nhận callback thật với cấu
 * hình trong yêu cầu, tính ở server, không chạm cơ sở dữ liệu và không lưu mẫu.
 */
@Component
public class MappingTester {

  /**
   * @param sampleJsonError mô tả lỗi của JSON mẫu (không lặp nội dung mẫu); null nếu hợp lệ
   * @param timestamp vắng khi không cấu hình {@code timestampPath}
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Result(
      boolean sampleJsonValid,
      String sampleJsonError,
      FieldResult transactionId,
      FieldResult status,
      FieldResult timestamp) {}

  private final MappingEngine engine;

  public MappingTester(MappingEngine engine) {
    this.engine = engine;
  }

  public Result test(MappingConfig config, String sampleJson) {
    String text =
        sampleJson != null && !sampleJson.isEmpty() && sampleJson.charAt(0) == '\uFEFF'
            ? sampleJson.substring(1)
            : sampleJson;
    String problem = SampleJsonDiagnostics.diagnose(text);
    if (problem != null) {
      return new Result(false, problem, null, null, null);
    }
    MappingResult mapped = engine.map(text, config);
    boolean timestampConfigured =
        config.timestampPath() != null && !config.timestampPath().isBlank();
    return new Result(
        true,
        null,
        mapped.transactionId(),
        mapped.status(),
        timestampConfigured ? mapped.timestamp() : null);
  }
}
