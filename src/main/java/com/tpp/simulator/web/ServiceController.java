package com.tpp.simulator.web;

import com.tpp.simulator.catalog.ServiceCatalog;
import com.tpp.simulator.catalog.ServiceCreateRequest;
import com.tpp.simulator.catalog.ServiceEnabledRequest;
import com.tpp.simulator.catalog.ServiceUpdateRequest;
import com.tpp.simulator.catalog.ServiceView;
import com.tpp.simulator.config.ApiException;
import com.tpp.simulator.config.ErrorCode;
import com.tpp.simulator.config.FieldError;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C2: tạo, liệt kê, đọc, sửa, bật/tắt và xóa mềm dịch vụ. Với {@code PUT}, Content-Type sai (415)
 * và thân hỏng hoặc sai kiểu (400) được xử lý khi Spring đọc thân, tức TRƯỚC khi vào catalog; sau
 * đó catalog kiểm tra 404 rồi lỗi trường bên trong khóa dòng (BR13.2).
 */
@RestController
public class ServiceController {

  private final ServiceCatalog catalog;

  public ServiceController(ServiceCatalog catalog) {
    this.catalog = catalog;
  }

  @PostMapping("/api/services")
  public ResponseEntity<ServiceView> create(@RequestBody ServiceCreateRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(catalog.create(request));
  }

  @GetMapping("/api/services")
  public List<ServiceView> list(@RequestParam(required = false) String includeDeleted) {
    boolean all = false;
    if (includeDeleted != null && !includeDeleted.isEmpty()) {
      if ("true".equals(includeDeleted)) {
        all = true;
      } else if (!"false".equals(includeDeleted)) {
        throw new ApiException(
            ErrorCode.VALIDATION_FAILED,
            "Tham số không hợp lệ.",
            List.of(new FieldError("includeDeleted", "includeDeleted phải là true hoặc false.")),
            null);
      }
    }
    return catalog.list(all);
  }

  @GetMapping("/api/services/{serviceCode}")
  public ServiceView get(@PathVariable String serviceCode) {
    return catalog.get(serviceCode);
  }

  @PutMapping("/api/services/{serviceCode}")
  public ServiceView update(
      @PathVariable String serviceCode, @RequestBody ServiceUpdateRequest request) {
    return catalog.update(serviceCode, request);
  }

  @PutMapping("/api/services/{serviceCode}/enabled")
  public ServiceView setEnabled(
      @PathVariable String serviceCode, @RequestBody ServiceEnabledRequest request) {
    return catalog.setEnabled(serviceCode, request);
  }

  @DeleteMapping("/api/services/{serviceCode}")
  public ResponseEntity<Void> delete(@PathVariable String serviceCode) {
    catalog.delete(serviceCode);
    return ResponseEntity.noContent().build();
  }
}
