package com.tpp.simulator.web;

import com.tpp.simulator.config.ApiException;
import com.tpp.simulator.config.ErrorCode;
import com.tpp.simulator.config.FieldError;
import com.tpp.simulator.ledger.CallbackFilter;
import com.tpp.simulator.ledger.TimeBounds;
import com.tpp.simulator.ledger.TransactionFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.util.MultiValueMap;

/**
 * Đọc và kiểm tra tham số của hai API danh sách (BR19.3, PD-04, PD-05). Gom MỌI lỗi cùng lúc; giá
 * trị rỗng của mọi tham số là vắng (không lỗi); giá trị chỉ gồm khoảng trắng KHÔNG phải rỗng; tham
 * số lặp lại là lỗi ở chính tham số đó; tham số lạ bị bỏ qua. Mọi {@code DateTimeException} thành
 * 400 ở đúng field, không bao giờ 500.
 */
final class ListParams {

  static final Set<Integer> ALLOWED_SIZES = Set.of(20, 50, 100);
  static final Set<String> OUTCOMES = Set.of("SUCCESS", "MAPPING_ERROR", "REJECTED");
  static final Set<String> REJECTION_REASONS =
      Set.of(
          "INVALID_API_KEY",
          "MISSING_API_KEY",
          "INVALID_JSON",
          "UNSUPPORTED_CONTENT_TYPE",
          "SERVICE_DISABLED",
          "SERVICE_DELETED");

  private static final Pattern DIGITS = Pattern.compile("^[0-9]+$");
  private static final int DEFAULT_PAGE = 1;
  private static final int DEFAULT_SIZE = 20;

  private final MultiValueMap<String, String> params;
  private final List<FieldError> errors = new ArrayList<>();

  private ListParams(MultiValueMap<String, String> params) {
    this.params = params;
  }

  /** Kết quả đã kiểm tra của danh sách giao dịch. */
  record Transactions(TransactionFilter filter, long page, int size) {}

  /** Kết quả đã kiểm tra của danh sách callback. */
  record Callbacks(CallbackFilter filter, long page, int size) {}

  static Transactions transactions(MultiValueMap<String, String> params) {
    ListParams p = new ListParams(params);
    String serviceCode = p.text("serviceCode");
    String transactionId = p.text("transactionId");
    String status = p.text("status");
    TimeBounds.Bound[] range = p.range();
    long page = p.page();
    int size = p.size();
    p.failIfAny();
    return new Transactions(
        new TransactionFilter(
            serviceCode,
            transactionId,
            status,
            range[0] == null ? null : range[0].clamped(),
            range[1] == null ? null : range[1].clamped()),
        page,
        size);
  }

  static Callbacks callbacks(MultiValueMap<String, String> params) {
    ListParams p = new ListParams(params);
    String serviceCode = p.text("serviceCode");
    String transactionId = p.text("transactionId");
    String outcome = p.oneOf("outcome", OUTCOMES);
    String rejectionReason = p.oneOf("rejectionReason", REJECTION_REASONS);
    TimeBounds.Bound[] range = p.range();
    long page = p.page();
    int size = p.size();
    p.failIfAny();
    return new Callbacks(
        new CallbackFilter(
            serviceCode,
            transactionId,
            outcome,
            rejectionReason,
            range[0] == null ? null : range[0].clamped(),
            range[1] == null ? null : range[1].clamped()),
        page,
        size);
  }

  /** Giá trị của tham số: null nếu vắng hoặc rỗng; lỗi nếu lặp lại. */
  private String value(String name) {
    List<String> values = params.get(name);
    if (values == null || values.isEmpty()) {
      return null;
    }
    if (values.size() > 1) {
      errors.add(new FieldError(name, "Tham số không được lặp lại."));
      return null;
    }
    String value = values.get(0);
    if (value != null && value.indexOf('\u0000') >= 0) {
      // PostgreSQL không nhận ký tự NUL trong tham số văn bản: chặn ở đây, không chạm database.
      errors.add(new FieldError(name, "Giá trị không được chứa ký tự NUL."));
      return null;
    }
    return value == null || value.isEmpty() ? null : value;
  }

  private String text(String name) {
    return value(name);
  }

  private String oneOf(String name, Set<String> allowed) {
    String value = value(name);
    if (value != null && !allowed.contains(value)) {
      errors.add(new FieldError(name, "Giá trị không thuộc danh sách cho phép."));
      return null;
    }
    return value;
  }

  private TimeBounds.Bound bound(String name, boolean upper) {
    String value = value(name);
    if (value == null) {
      return null;
    }
    try {
      return TimeBounds.parse(value, upper);
    } catch (RuntimeException e) {
      errors.add(
          new FieldError(
              name,
              "Phải là thời điểm ISO 8601 có múi giờ (Z hoặc offset; mã hóa dấu + thành %2B)."));
      return null;
    }
  }

  private TimeBounds.Bound[] range() {
    TimeBounds.Bound from = bound("receivedFrom", false);
    TimeBounds.Bound to = bound("receivedTo", true);
    if (from != null && to != null && from.isAfter(to)) {
      errors.add(new FieldError("receivedTo", "receivedTo phải không nhỏ hơn receivedFrom."));
    }
    return new TimeBounds.Bound[] {from, to};
  }

  /** Số nguyên dương dạng long: bỏ số 0 đầu, kẹp chuỗi dài về Long.MAX_VALUE (PD-04). */
  private Long positive(String name) {
    String value = value(name);
    if (value == null) {
      return null;
    }
    if (!DIGITS.matcher(value).matches()) {
      errors.add(new FieldError(name, name + " phải là số nguyên từ 1 trở lên."));
      return -1L;
    }
    String trimmed = value.replaceFirst("^0+(?=.)", "");
    long number = trimmed.length() > 18 ? Long.MAX_VALUE : Long.parseLong(trimmed);
    if (number < 1) {
      errors.add(new FieldError(name, name + " phải là số nguyên từ 1 trở lên."));
    }
    return number;
  }

  private long page() {
    Long page = positive("page");
    return page == null ? DEFAULT_PAGE : page;
  }

  private int size() {
    Long size = positive("size");
    if (size == null) {
      return DEFAULT_SIZE;
    }
    if (size >= 1 && !ALLOWED_SIZES.contains(size > Integer.MAX_VALUE ? -1 : size.intValue())) {
      errors.add(new FieldError("size", "size chỉ nhận 20, 50 hoặc 100."));
    }
    return size > Integer.MAX_VALUE ? DEFAULT_SIZE : size.intValue();
  }

  private void failIfAny() {
    if (!errors.isEmpty()) {
      throw new ApiException(
          ErrorCode.VALIDATION_FAILED, "Tham số truy vấn không hợp lệ.", errors, null);
    }
  }
}
