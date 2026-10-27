package com.tpp.simulator.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpp.simulator.catalog.ServiceCatalog;
import com.tpp.simulator.catalog.ServiceRuntimeSnapshot;
import com.tpp.simulator.config.MaskedHeaders;
import com.tpp.simulator.ledger.CallbackLedger;
import com.tpp.simulator.ledger.NewCallback;
import com.tpp.simulator.ledger.RejectedCallback;
import com.tpp.simulator.mapping.MappingConfig;
import com.tpp.simulator.mapping.MappingEngine;
import com.tpp.simulator.mapping.MappingResult;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Quy trình nhận callback (W16, W18) sau bước kiểm tra kích thước của bộ lọc: đọc ảnh dịch vụ MỘT
 * lần, kiểm tra theo thứ tự cố định (đã xóa, tắt, khóa API, Content-Type, JSON), ánh xạ NGOÀI giao
 * dịch, che header, rồi ghi (BR14.1, BR14.2).
 *
 * <p>Hai lối vào dùng chung {@link #process}: {@link #receive} cho HTTP và {@link #receiveForLoad}
 * chỉ cho công cụ nạp (nhận sẵn ảnh dịch vụ và {@code receivedAt}). Đây là NƠI DUY NHẤT bắt lỗi kho
 * dữ liệu (RD-02): ba khối thử/bắt (đọc ảnh, ghi callback được chấp nhận, ghi lần từ chối) không
 * ném ngoại lệ ra ngoài mà trả {@link IngestionResult} mang {@link WriteStatus} tường minh. Một lần
 * thử, không bù về sau.
 */
@Service
public class CallbackIngestionService {

  private final ServiceCatalog catalog;
  private final MappingEngine mappingEngine;
  private final CallbackLedger ledger;
  private final ApiKeyVerifier apiKeyVerifier;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate transactionTemplate;

  public CallbackIngestionService(
      ServiceCatalog catalog,
      MappingEngine mappingEngine,
      CallbackLedger ledger,
      ApiKeyVerifier apiKeyVerifier,
      ObjectMapper objectMapper,
      PlatformTransactionManager transactionManager) {
    this.catalog = catalog;
    this.mappingEngine = mappingEngine;
    this.ledger = ledger;
    this.apiKeyVerifier = apiKeyVerifier;
    this.objectMapper = objectMapper;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /**
   * Lối vào HTTP.
   *
   * @param serviceCode mã dịch vụ lấy từ đường dẫn (khớp không phân biệt hoa thường)
   * @param contentType giá trị header Content-Type, có thể null
   * @param rawHeaders mọi header của request (chưa che)
   * @param body byte của body (đã qua giới hạn kích thước)
   * @param receivedAt thời điểm server bắt đầu nhận request (UTC, micro giây)
   */
  public IngestionResult receive(
      String serviceCode,
      String contentType,
      Map<String, List<String>> rawHeaders,
      byte[] body,
      Instant receivedAt) {
    ServiceRuntimeSnapshot snapshot;
    try {
      Optional<ServiceRuntimeSnapshot> found = catalog.findSnapshot(serviceCode);
      if (found.isEmpty()) {
        return IngestionResult.unknownService();
      }
      snapshot = found.get();
    } catch (RuntimeException e) {
      return IngestionResult.snapshotReadFailed(e);
    }
    return process(snapshot, contentType, rawHeaders, body, receivedAt);
  }

  /**
   * Lối vào nội bộ CHỈ cho công cụ nạp (LC-03): ảnh dịch vụ và {@code receivedAt} do công cụ cung
   * cấp. Không có đường HTTP nào đặt {@code receivedAt}.
   */
  public IngestionResult receiveForLoad(
      ServiceRuntimeSnapshot snapshot,
      String contentType,
      Map<String, List<String>> rawHeaders,
      byte[] body,
      Instant receivedAt) {
    return process(snapshot, contentType, rawHeaders, body, receivedAt);
  }

  private IngestionResult process(
      ServiceRuntimeSnapshot snapshot,
      String contentType,
      Map<String, List<String>> rawHeaders,
      byte[] body,
      Instant receivedAt) {
    RejectionReason reason = precheck(snapshot, contentType, rawHeaders);
    String payload = null;
    if (reason == null) {
      try {
        payload = PayloadDecoder.decode(body);
      } catch (PayloadDecoder.InvalidPayloadException e) {
        reason = RejectionReason.INVALID_JSON;
      }
    }
    MaskedHeaders headers =
        new MaskedHeaders(
            rawHeaders, snapshot.requiresApiKey() ? snapshot.apiKeyHeaderName() : null);
    if (reason != null) {
      return reject(snapshot, reason, headers, body, receivedAt);
    }
    return accept(snapshot, headers, payload, receivedAt);
  }

  // Bốn kiểm tra đầu của BR14.2 (đã xóa, tắt, khóa API, Content-Type); null nếu qua hết.
  private RejectionReason precheck(
      ServiceRuntimeSnapshot snapshot, String contentType, Map<String, List<String>> rawHeaders) {
    if (snapshot.deleted()) {
      return RejectionReason.SERVICE_DELETED;
    }
    if (!snapshot.enabled()) {
      return RejectionReason.SERVICE_DISABLED;
    }
    if (snapshot.requiresApiKey()) {
      switch (apiKeyVerifier.verify(snapshot, rawHeaders)) {
        case MISSING -> {
          return RejectionReason.MISSING_API_KEY;
        }
        case INVALID -> {
          return RejectionReason.INVALID_API_KEY;
        }
        default -> {
          // khóa đúng: đi tiếp
        }
      }
    }
    return isJson(contentType) ? null : RejectionReason.UNSUPPORTED_CONTENT_TYPE;
  }

  // W18: quyết định hình thức lưu payload một lần, ghi trong giao dịch RIÊNG.
  private IngestionResult reject(
      ServiceRuntimeSnapshot snapshot,
      RejectionReason reason,
      MaskedHeaders headers,
      byte[] body,
      Instant receivedAt) {
    RejectedPayloadClassifier.Classified payload = RejectedPayloadClassifier.classify(body);
    RejectedCallback rejected =
        new RejectedCallback(
            snapshot.serviceCode(),
            snapshot.versionId(),
            receivedAt,
            reason.name(),
            reason.errorCode().status().value(),
            headers,
            payload.payloadJson(),
            payload.payloadRawText(),
            errorBodyJson(reason));
    try {
      Long recordId = transactionTemplate.execute(status -> ledger.appendRejected(rejected));
      return IngestionResult.rejected(reason, recordId);
    } catch (RuntimeException e) {
      return IngestionResult.rejectedRecordFailed(reason, e);
    }
  }

  // Ánh xạ ngoài giao dịch rồi ghi nguyên tử Callback và Transaction (BR14.7, BR14.8).
  private IngestionResult accept(
      ServiceRuntimeSnapshot snapshot, MaskedHeaders headers, String payload, Instant receivedAt) {
    MappingResult mapping =
        mappingEngine.map(
            payload,
            new MappingConfig(
                snapshot.transactionIdPath(),
                snapshot.statusPath(),
                snapshot.timestampPath(),
                snapshot.timestampFormat(),
                snapshot.timestampPattern(),
                snapshot.defaultTimeZone()));
    NewCallback callback =
        new NewCallback(
            snapshot.serviceCode(),
            snapshot.versionId(),
            receivedAt,
            mapping.outcome().name(),
            headers,
            payload,
            mapping.transactionId().hasError() ? null : mapping.transactionId().value(),
            mapping.status().hasError() ? null : mapping.status().value(),
            mapping.businessTimestampUtc(),
            fieldResultsJson(mapping),
            snapshot.responseHttpStatus(),
            snapshot.responseBody());
    try {
      Long recordId = transactionTemplate.execute(status -> ledger.append(callback));
      return IngestionResult.written(
          recordId,
          mapping.outcome(),
          new ConfiguredResponse(snapshot.responseHttpStatus(), snapshot.responseBody()));
    } catch (RuntimeException e) {
      return IngestionResult.acceptedWriteFailed(mapping.outcome(), e);
    }
  }

  private static boolean isJson(String contentType) {
    if (contentType == null || contentType.isBlank()) {
      return false;
    }
    try {
      MediaType type = MediaType.parseMediaType(contentType);
      return MediaType.APPLICATION_JSON.equalsTypeAndSubtype(type);
    } catch (InvalidMediaTypeException e) {
      return false;
    }
  }

  private String errorBodyJson(RejectionReason reason) {
    Map<String, String> body = new LinkedHashMap<>();
    body.put("error", reason.errorCode().name());
    body.put("message", reason.message());
    try {
      return objectMapper.writeValueAsString(body);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialize error body", e);
    }
  }

  private String fieldResultsJson(MappingResult mapping) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("transactionId", mapping.transactionId());
    fields.put("status", mapping.status());
    fields.put("timestamp", mapping.timestamp());
    try {
      return objectMapper.writeValueAsString(fields);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialize mapping results", e);
    }
  }
}
