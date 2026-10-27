package com.tpp.simulator.web;

import com.tpp.simulator.config.ApiException;
import com.tpp.simulator.config.ErrorCode;
import com.tpp.simulator.config.FieldError;
import com.tpp.simulator.ledger.CallbackDetail;
import com.tpp.simulator.ledger.CallbackLedger;
import com.tpp.simulator.ledger.CallbackListItem;
import com.tpp.simulator.ledger.PageResult;
import java.util.List;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** C4 (phần U2): danh sách callback có bộ lọc (W22) và chi tiết một lần nhận (W23). */
@RestController
public class CallbackQueryController {

  private final CallbackLedger ledger;

  public CallbackQueryController(CallbackLedger ledger) {
    this.ledger = ledger;
  }

  @GetMapping("/api/callbacks")
  public PageResult<CallbackListItem> list(@RequestParam MultiValueMap<String, String> params) {
    ListParams.Callbacks request = ListParams.callbacks(params);
    return ledger.listCallbacks(request.filter(), request.page(), request.size());
  }

  @GetMapping("/api/callbacks/{recordId}")
  public CallbackDetail detail(@PathVariable String recordId) {
    long id;
    try {
      id = Long.parseLong(recordId);
    } catch (NumberFormatException e) {
      throw new ApiException(
          ErrorCode.VALIDATION_FAILED,
          "Tham số không hợp lệ.",
          List.of(new FieldError("recordId", "recordId phải là số nguyên 64 bit.")),
          null);
    }
    return ledger
        .findCallback(id)
        .orElseThrow(
            () ->
                new ApiException(
                    ErrorCode.CALLBACK_NOT_FOUND, "Không tìm thấy lần nhận.", List.of(), null));
  }
}
