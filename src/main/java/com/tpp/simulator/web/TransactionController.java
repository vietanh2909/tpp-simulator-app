package com.tpp.simulator.web;

import com.tpp.simulator.config.ApiException;
import com.tpp.simulator.config.ErrorCode;
import com.tpp.simulator.config.FieldError;
import com.tpp.simulator.config.LogSanitizer;
import com.tpp.simulator.ledger.CallbackLedger;
import com.tpp.simulator.ledger.PageResult;
import com.tpp.simulator.ledger.TransactionDetail;
import com.tpp.simulator.ledger.TransactionSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** C4: danh sách giao dịch có bộ lọc (W21) và chi tiết giao dịch bằng tham số truy vấn (W6). */
@RestController
public class TransactionController {

  private final CallbackLedger ledger;

  public TransactionController(CallbackLedger ledger) {
    this.ledger = ledger;
  }

  @GetMapping("/api/transactions")
  public PageResult<TransactionSummary> list(@RequestParam MultiValueMap<String, String> params) {
    ListParams.Transactions request = ListParams.transactions(params);
    return ledger.listTransactions(request.filter(), request.page(), request.size());
  }

  @GetMapping("/api/transactions/detail")
  public TransactionDetail detail(
      @RequestParam(required = false) String serviceCode,
      @RequestParam(required = false) String transactionId) {
    List<FieldError> errors = new ArrayList<>();
    if (serviceCode == null || serviceCode.isEmpty()) {
      errors.add(new FieldError("serviceCode", "Trường bắt buộc."));
    }
    if (transactionId == null || transactionId.isEmpty()) {
      errors.add(new FieldError("transactionId", "Trường bắt buộc."));
    }
    if (!errors.isEmpty()) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "Thiếu tham số bắt buộc.", errors, null);
    }
    // Giá trị chứa NUL không bao giờ khớp giao dịch nào và PostgreSQL không nhận nó làm tham số.
    boolean hasNul = serviceCode.indexOf('\u0000') >= 0 || transactionId.indexOf('\u0000') >= 0;
    return (hasNul
            ? Optional.<TransactionDetail>empty()
            : ledger.findTransaction(serviceCode, transactionId))
        .orElseThrow(
            () ->
                new ApiException(
                    ErrorCode.TRANSACTION_NOT_FOUND,
                    "Không tìm thấy giao dịch.",
                    List.of(),
                    LogSanitizer.clean(serviceCode)));
  }
}
