package com.tpp.simulator.web;

import com.tpp.simulator.catalog.ServiceValidator;
import com.tpp.simulator.catalog.ServiceValidator.MappingSettings;
import com.tpp.simulator.config.ApiException;
import com.tpp.simulator.config.ErrorCode;
import com.tpp.simulator.config.FieldError;
import com.tpp.simulator.mapping.MappingConfig;
import com.tpp.simulator.mapping.MappingTester;
import com.tpp.simulator.mapping.TimestampFormat;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * C3: {@code POST /api/mapping-tests} (W20). Không mở giao dịch và KHÔNG tiêm bean dữ liệu nào
 * (PD-07): chỉ bộ kiểm tra cấu hình thuần và {@link MappingTester}, nên thử mapping vẫn chạy khi
 * PostgreSQL không kết nối được.
 */
@RestController
public class MappingTestController {

  /** Trần JSON mẫu: 1 MiB theo byte UTF-8 (BR18.3). */
  static final int MAX_SAMPLE_BYTES = 1_048_576;

  /** Thân yêu cầu thử mapping. */
  public record MappingTestRequest(
      String transactionIdPath,
      String statusPath,
      String timestampPath,
      String timestampFormat,
      String timestampPattern,
      String defaultTimeZone,
      String sampleJson) {}

  private final ServiceValidator validator;
  private final MappingTester tester;

  public MappingTestController(ServiceValidator validator, MappingTester tester) {
    this.validator = validator;
    this.tester = tester;
  }

  @PostMapping("/api/mapping-tests")
  public MappingTester.Result test(@RequestBody MappingTestRequest request) {
    List<FieldError> errors = new ArrayList<>();
    MappingSettings settings =
        validator.validateMappingSettings(
            request.transactionIdPath(),
            request.statusPath(),
            request.timestampPath(),
            request.timestampFormat(),
            request.timestampPattern(),
            request.defaultTimeZone(),
            errors);
    if (request.sampleJson() == null) {
      errors.add(new FieldError("sampleJson", "Trường bắt buộc."));
    } else if (request.sampleJson().getBytes(StandardCharsets.UTF_8).length > MAX_SAMPLE_BYTES) {
      errors.add(new FieldError("sampleJson", "JSON mẫu tối đa " + MAX_SAMPLE_BYTES + " byte."));
    }
    if (!errors.isEmpty()) {
      throw new ApiException(
          ErrorCode.VALIDATION_FAILED, "Dữ liệu thử mapping không hợp lệ.", errors, null);
    }
    MappingConfig config =
        new MappingConfig(
            settings.transactionIdPath(),
            settings.statusPath(),
            settings.timestampPath(),
            TimestampFormat.valueOf(settings.timestampFormat()),
            settings.timestampPattern(),
            ZoneId.of(settings.defaultTimeZone()));
    return tester.test(config, request.sampleJson());
  }
}
