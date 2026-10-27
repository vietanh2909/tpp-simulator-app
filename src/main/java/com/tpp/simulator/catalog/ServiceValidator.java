package com.tpp.simulator.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tpp.simulator.config.ApiException;
import com.tpp.simulator.config.ErrorCode;
import com.tpp.simulator.config.FieldError;
import com.tpp.simulator.mapping.TimestampPatterns;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Kiểm tra yêu cầu tạo và sửa dịch vụ theo BR11.1 đến BR11.8 và BR12.3. Thu thập MỌI lỗi trường
 * cùng lúc; thông báo dựng từ mẫu cố định và tên trường, không nhắc lại giá trị đầu vào (ngoại lệ
 * duy nhất: ký tự vi phạm đầu tiên của đường dẫn mapping, BR11.2). Không bao giờ nhắc lại bất kỳ
 * phần nào của khóa API.
 */
@Component
public class ServiceValidator {

  static final String DEFAULT_TIME_ZONE = "Asia/Ho_Chi_Minh";
  static final int MAX_RESPONSE_BODY_BYTES = 64 * 1024;
  static final int MAX_PATH_LENGTH = 200;
  static final int MAX_PATH_SEGMENTS = 10;

  private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,50}$");
  private static final Pattern HEADER_NAME_PATTERN =
      Pattern.compile("^[A-Za-z0-9!#$%&'*+\\-.^_`|~]{1,100}$");
  private static final Set<String> RESERVED_HEADERS =
      Set.of("host", "content-type", "content-length", "transfer-encoding", "connection");
  private static final Set<String> AUTH_TYPES = Set.of("NONE", "API_KEY");
  private static final Set<String> TIMESTAMP_FORMATS =
      Set.of("ISO_8601", "UNIX_SECONDS", "UNIX_MILLIS", "PATTERN");
  private static final Set<Integer> NO_BODY_STATUSES = Set.of(204, 205, 304);

  /**
   * Dịch vụ đã kiểm tra, sẵn sàng ghi.
   *
   * @param serviceCode null khi kiểm tra yêu cầu sửa
   * @param apiKeyHeaderName chỉ có khi {@code authType} là API_KEY
   * @param apiKeySecret khóa mới; null khi {@code NONE} hoặc khi sửa mà giữ khóa cũ ({@code
   *     keepExistingKey})
   * @param timestampPattern chỉ có khi định dạng là PATTERN
   */
  public record NewService(
      String serviceCode,
      String name,
      String description,
      boolean enabled,
      String authType,
      String apiKeyHeaderName,
      String apiKeySecret,
      boolean keepExistingKey,
      String transactionIdPath,
      String statusPath,
      String timestampPath,
      String timestampFormat,
      String timestampPattern,
      String defaultTimeZone,
      int responseHttpStatus,
      String responseBody) {

    @Override
    public String toString() {
      return "NewService[serviceCode=" + serviceCode + ", authType=" + authType + "]";
    }
  }

  /** Cấu hình ánh xạ đã kiểm tra của yêu cầu thử mapping (BR18.2). */
  public record MappingSettings(
      String transactionIdPath,
      String statusPath,
      String timestampPath,
      String timestampFormat,
      String timestampPattern,
      String defaultTimeZone) {}

  /** Kiểm tra yêu cầu tạo (BR11.x). */
  public NewService validateCreate(ServiceCreateRequest request) {
    List<FieldError> errors = new ArrayList<>();
    String code = request.serviceCode();
    if (code == null) {
      errors.add(required("serviceCode"));
    } else if (!CODE_PATTERN.matcher(code).matches()) {
      errors.add(
          new FieldError(
              "serviceCode",
              "Mã dịch vụ phải có 1 đến 50 ký tự gồm chữ, số, dấu gạch ngang và gạch dưới."));
    }
    NewService service =
        validateFields(
            errors,
            code,
            request.enabled() == null || request.enabled(),
            request.name(),
            request.description(),
            request.authType(),
            request.apiKeyHeaderName(),
            request.apiKeySecret(),
            false,
            request.transactionIdPath(),
            request.statusPath(),
            request.timestampPath(),
            request.timestampFormat(),
            request.timestampPattern(),
            request.defaultTimeZone(),
            request.responseHttpStatus(),
            request.responseBody());
    failIfAny(errors);
    return service;
  }

  /**
   * Kiểm tra yêu cầu sửa trên trạng thái hiện hành của dịch vụ (BR12.3).
   *
   * @param currentAuthType kiểu xác thực hiện hành của dòng đã khóa
   * @param keyConfigured dịch vụ hiện có khóa
   */
  public NewService validateUpdate(
      ServiceUpdateRequest request, String currentAuthType, boolean keyConfigured) {
    List<FieldError> errors = new ArrayList<>();
    boolean keepKey =
        "API_KEY".equals(request.authType())
            && isEmpty(request.apiKeySecret())
            && "API_KEY".equals(currentAuthType)
            && keyConfigured;
    NewService service =
        validateFields(
            errors,
            null,
            true,
            request.name(),
            request.description(),
            request.authType(),
            request.apiKeyHeaderName(),
            request.apiKeySecret(),
            keepKey,
            request.transactionIdPath(),
            request.statusPath(),
            request.timestampPath(),
            request.timestampFormat(),
            request.timestampPattern(),
            request.defaultTimeZone(),
            request.responseHttpStatus(),
            request.responseBody());
    failIfAny(errors);
    return service;
  }

  /** Kiểm tra cấu hình ánh xạ của yêu cầu thử mapping (BR18.2): cùng quy tắc như khi lưu. */
  public MappingSettings validateMappingSettings(
      String transactionIdPath,
      String statusPath,
      String timestampPath,
      String timestampFormat,
      String timestampPattern,
      String defaultTimeZone,
      List<FieldError> errors) {
    String tx = checkPath("transactionIdPath", transactionIdPath, true, errors);
    String status = checkPath("statusPath", statusPath, true, errors);
    String ts = checkPath("timestampPath", timestampPath, false, errors);
    String format = checkFormat(timestampFormat, errors);
    String pattern = checkPattern(format, timestampPattern, errors);
    String zone = checkZone(defaultTimeZone, errors);
    return new MappingSettings(tx, status, ts, format, pattern, zone);
  }

  private NewService validateFields(
      List<FieldError> errors,
      String code,
      boolean enabled,
      String name,
      String description,
      String authType,
      String apiKeyHeaderName,
      String apiKeySecret,
      boolean keepKey,
      String transactionIdPath,
      String statusPath,
      String timestampPath,
      String timestampFormat,
      String timestampPattern,
      String defaultTimeZone,
      Integer responseHttpStatus,
      JsonNode responseBody) {
    if (name == null || name.isBlank()) {
      errors.add(required("name"));
    } else if (name.length() > 100) {
      errors.add(tooLong("name", 100));
    }
    if (description != null && description.length() > 500) {
      errors.add(tooLong("description", 500));
    }

    String headerName = null;
    String secret = null;
    if (authType == null) {
      errors.add(required("authType"));
    } else if (!AUTH_TYPES.contains(authType)) {
      errors.add(new FieldError("authType", "Giá trị authType phải là NONE hoặc API_KEY."));
    } else if (authType.equals("API_KEY")) {
      headerName = checkHeaderName(apiKeyHeaderName, errors);
      if (isEmpty(apiKeySecret)) {
        if (!keepKey) {
          errors.add(required("apiKeySecret"));
        }
      } else {
        secret = checkSecret(apiKeySecret, errors);
      }
    }

    String txPath = checkPath("transactionIdPath", transactionIdPath, true, errors);
    String statusP = checkPath("statusPath", statusPath, true, errors);
    String timestampP = checkPath("timestampPath", timestampPath, false, errors);
    String format = checkFormat(timestampFormat, errors);
    String pattern = checkPattern(format, timestampPattern, errors);
    String zone = checkZone(defaultTimeZone, errors);

    int status = 0;
    if (responseHttpStatus == null) {
      errors.add(required("responseHttpStatus"));
    } else if (responseHttpStatus < 200 || responseHttpStatus > 599) {
      errors.add(
          new FieldError("responseHttpStatus", "Mã HTTP phải nằm trong khoảng 200 đến 599."));
    } else if (NO_BODY_STATUSES.contains(responseHttpStatus)) {
      errors.add(
          new FieldError(
              "responseHttpStatus",
              "Mã 204, 205 và 304 không được dùng vì HTTP không cho phép body."));
    } else {
      status = responseHttpStatus;
    }

    String bodyText = checkResponseBody(responseBody, errors);

    return new NewService(
        code,
        name,
        description,
        enabled,
        authType,
        headerName,
        secret,
        keepKey,
        txPath,
        statusP,
        timestampP,
        format,
        pattern,
        zone,
        status,
        bodyText);
  }

  private static void failIfAny(List<FieldError> errors) {
    if (!errors.isEmpty()) {
      throw new ApiException(
          ErrorCode.VALIDATION_FAILED, "Dữ liệu dịch vụ không hợp lệ.", errors, null);
    }
  }

  private static boolean isEmpty(String value) {
    return value == null || value.isEmpty();
  }

  private static String checkFormat(String format, List<FieldError> errors) {
    if (format == null) {
      errors.add(required("timestampFormat"));
      return null;
    }
    if (!TIMESTAMP_FORMATS.contains(format)) {
      errors.add(
          new FieldError(
              "timestampFormat",
              "Giá trị timestampFormat phải là ISO_8601, UNIX_SECONDS, UNIX_MILLIS hoặc PATTERN."));
      return null;
    }
    return format;
  }

  // BR11.3, BR11.4: mẫu chỉ có nghĩa khi định dạng là PATTERN; ngược lại bị bỏ, không kiểm tra.
  private static String checkPattern(String format, String pattern, List<FieldError> errors) {
    if (!"PATTERN".equals(format)) {
      return null;
    }
    String problem = TimestampPatterns.validate(pattern);
    if (problem != null) {
      errors.add(new FieldError("timestampPattern", problem));
      return null;
    }
    return pattern;
  }

  private static String checkZone(String requested, List<FieldError> errors) {
    String zone = requested == null ? DEFAULT_TIME_ZONE : requested;
    if (zone.length() > 64 || !isValidZone(zone)) {
      errors.add(new FieldError("defaultTimeZone", "Múi giờ không hợp lệ."));
    }
    return zone;
  }

  // BR11.5
  private static String checkHeaderName(String name, List<FieldError> errors) {
    if (isEmpty(name)) {
      errors.add(required("apiKeyHeaderName"));
      return null;
    }
    if (!HEADER_NAME_PATTERN.matcher(name).matches()) {
      errors.add(
          new FieldError(
              "apiKeyHeaderName",
              "Tên header phải dài 1 đến 100 ký tự và chỉ gồm chữ, số hoặc các dấu hợp lệ của tên"
                  + " header HTTP."));
      return null;
    }
    if (RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
      errors.add(
          new FieldError("apiKeyHeaderName", "Tên header này dành riêng cho giao thức HTTP."));
      return null;
    }
    return name;
  }

  // BR11.6: thông báo chỉ nêu quy tắc, không lặp lại bất kỳ ký tự nào của khóa.
  private static String checkSecret(String secret, List<FieldError> errors) {
    if (secret.length() > 256) {
      errors.add(new FieldError("apiKeySecret", "Khóa API tối đa 256 ký tự."));
      return null;
    }
    boolean printable = secret.chars().allMatch(c -> c >= 32 && c <= 126);
    if (!printable) {
      errors.add(
          new FieldError("apiKeySecret", "Khóa API chỉ gồm ký tự ASCII in được (mã 32 đến 126)."));
      return null;
    }
    if (secret.charAt(0) == ' ' || secret.charAt(secret.length() - 1) == ' ') {
      errors.add(
          new FieldError("apiKeySecret", "Khóa API không được có khoảng trắng ở đầu hoặc cuối."));
      return null;
    }
    return secret;
  }

  // BR11.2
  private static String checkPath(
      String field, String value, boolean mandatory, List<FieldError> errors) {
    if (value == null || value.isEmpty()) {
      if (mandatory) {
        errors.add(required(field));
      }
      return null;
    }
    if (value.length() > MAX_PATH_LENGTH) {
      errors.add(tooLong(field, MAX_PATH_LENGTH));
      return value;
    }
    if (!value.startsWith("$.") || value.length() == 2) {
      errors.add(new FieldError(field, "Đường dẫn phải bắt đầu bằng $. và có ít nhất một khóa."));
      return value;
    }
    String[] segments = value.substring(2).split("\\.", -1);
    for (String segment : segments) {
      if (segment.isEmpty()) {
        errors.add(
            new FieldError(
                field, "Đường dẫn có đoạn rỗng (hai dấu chấm liền nhau hoặc dấu chấm ở cuối)."));
        return value;
      }
      for (int i = 0; i < segment.length(); ) {
        int cp = segment.codePointAt(i);
        i += Character.charCount(cp);
        if (!isPathChar(cp)) {
          errors.add(
              new FieldError(
                  field,
                  "Đường dẫn có ký tự không hỗ trợ "
                      + describe(cp)
                      + "; mỗi đoạn chỉ gồm chữ cái ASCII, chữ số, gạch dưới và gạch ngang."));
          return value;
        }
      }
    }
    if (segments.length > MAX_PATH_SEGMENTS) {
      errors.add(new FieldError(field, "Đường dẫn tối đa " + MAX_PATH_SEGMENTS + " đoạn."));
    }
    return value;
  }

  private static boolean isPathChar(int cp) {
    return (cp >= 'a' && cp <= 'z')
        || (cp >= 'A' && cp <= 'Z')
        || (cp >= '0' && cp <= '9')
        || cp == '_'
        || cp == '-';
  }

  // Ký tự vi phạm: ký tự in được kèm mã điểm; ký tự điều khiển chỉ nêu mã điểm.
  private static String describe(int cp) {
    String code = String.format("U+%04X", cp);
    if (Character.isISOControl(cp) || Character.isWhitespace(cp) || cp == 0x2028 || cp == 0x2029) {
      return code;
    }
    return "'" + new String(Character.toChars(cp)) + "' (" + code + ")";
  }

  private static String checkResponseBody(JsonNode body, List<FieldError> errors) {
    if (body == null || body.isNull() || body.isMissingNode()) {
      errors.add(required("responseBody"));
      return null;
    }
    String text = body.toString();
    if (text.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BODY_BYTES) {
      errors.add(new FieldError("responseBody", "Body phản hồi vượt quá 64 KB."));
      return null;
    }
    if (text.contains("\\u0000")) {
      errors.add(new FieldError("responseBody", "Body phản hồi chứa ký tự NUL không lưu được."));
      return null;
    }
    return text;
  }

  private static boolean isValidZone(String zone) {
    try {
      ZoneId.of(zone);
      return true;
    } catch (DateTimeException e) {
      return false;
    }
  }

  private static FieldError required(String field) {
    return new FieldError(field, "Trường bắt buộc.");
  }

  private static FieldError tooLong(String field, int max) {
    return new FieldError(field, "Tối đa " + max + " ký tự.");
  }
}
