package com.tpp.simulator.ingestion;

import com.tpp.simulator.catalog.ServiceRuntimeSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * So khớp khóa API của dịch vụ {@code API_KEY} (W17, BR14.4, SD-02). Tên header so không phân biệt
 * hoa thường; giá trị so chính xác từng ký tự, phân biệt hoa thường, bằng {@link
 * MessageDigest#isEqual} (thời gian không phụ thuộc vị trí khác biệt đầu tiên). Không bao giờ ghi
 * hay trả về khóa.
 */
@Component
public class ApiKeyVerifier {

  /** Kết quả so khớp. */
  public enum Result {
    VALID,
    MISSING,
    INVALID
  }

  public Result verify(ServiceRuntimeSnapshot snapshot, Map<String, List<String>> rawHeaders) {
    List<String> values = new ArrayList<>();
    for (Map.Entry<String, List<String>> header : rawHeaders.entrySet()) {
      if (header.getKey() != null
          && header.getKey().equalsIgnoreCase(snapshot.apiKeyHeaderName())
          && header.getValue() != null) {
        values.addAll(header.getValue());
      }
    }
    if (values.isEmpty() || (values.size() == 1 && values.get(0).isBlank())) {
      return Result.MISSING;
    }
    if (values.size() > 1) {
      return Result.INVALID;
    }
    byte[] expected = snapshot.apiKeySecret().getBytes(StandardCharsets.UTF_8);
    byte[] received = values.get(0).getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(expected, received) ? Result.VALID : Result.INVALID;
  }
}
