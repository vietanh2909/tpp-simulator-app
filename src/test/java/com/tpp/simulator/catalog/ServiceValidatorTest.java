package com.tpp.simulator.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpp.simulator.catalog.ServiceValidator.MappingSettings;
import com.tpp.simulator.catalog.ServiceValidator.NewService;
import com.tpp.simulator.config.ApiException;
import com.tpp.simulator.config.ErrorCode;
import com.tpp.simulator.config.FieldError;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** BR11.1 đến BR11.8 và BR12.3: kiểm tra đầy đủ khi tạo, sửa và thử mapping. */
class ServiceValidatorTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final ServiceValidator validator = new ServiceValidator();

  /** Bản sao có thể chỉnh từng trường của yêu cầu tạo. */
  private static final class Req {
    String code = "THU_HO";
    String name = "Thu hộ";
    String description = "Dịch vụ thu hộ";
    Boolean enabled;
    String authType = "NONE";
    String header;
    String secret;
    String tx = "$.transId";
    String status = "$.code";
    String ts = "$.time";
    String format = "ISO_8601";
    String pattern;
    String zone;
    Integer http = 201;
    JsonNode body = body("{\"ok\":true}");

    ServiceCreateRequest create() {
      return new ServiceCreateRequest(
          code,
          name,
          description,
          enabled,
          authType,
          header,
          secret,
          tx,
          status,
          ts,
          format,
          pattern,
          zone,
          http,
          body);
    }

    ServiceUpdateRequest update() {
      return new ServiceUpdateRequest(
          name,
          description,
          authType,
          header,
          secret,
          tx,
          status,
          ts,
          format,
          pattern,
          zone,
          http,
          body);
    }
  }

  private static Req req(java.util.function.Consumer<Req> change) {
    Req r = new Req();
    change.accept(r);
    return r;
  }

  private static JsonNode body(String json) {
    try {
      return JSON.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static Set<String> fields(ApiException e) {
    assertEquals(ErrorCode.VALIDATION_FAILED, e.code());
    return e.fieldErrors().stream().map(FieldError::field).collect(Collectors.toSet());
  }

  private Set<String> createFails(java.util.function.Consumer<Req> change) {
    return fields(
        assertThrows(ApiException.class, () -> validator.validateCreate(req(change).create())));
  }

  private String createMessage(java.util.function.Consumer<Req> change, String field) {
    ApiException e =
        assertThrows(ApiException.class, () -> validator.validateCreate(req(change).create()));
    return e.fieldErrors().stream()
        .filter(f -> f.field().equals(field))
        .findFirst()
        .orElseThrow()
        .message();
  }

  private NewService created(java.util.function.Consumer<Req> change) {
    return validator.validateCreate(req(change).create());
  }

  static Stream<String> invalidCodes() {
    return Stream.of("", "a b", "a.b", "a/b", "tiếng", "x".repeat(51));
  }

  static Stream<String> validCodes() {
    return Stream.of("a", "A-b_9", "x".repeat(50));
  }

  // ---------- nền của U1 ----------

  @Test
  void validRequestGetsDefaults() {
    NewService service = created(r -> {});

    assertTrue(service.enabled());
    assertEquals("Asia/Ho_Chi_Minh", service.defaultTimeZone());
    assertEquals("{\"ok\":true}", service.responseBody());
    assertEquals("THU_HO", service.serviceCode());
    assertNull(service.apiKeyHeaderName());
    assertNull(service.apiKeySecret());
  }

  @Test
  void collectsEveryFieldErrorAtOnce() {
    Req bad = new Req();
    bad.code = "bad code!";
    bad.name = " ";
    bad.description = "d".repeat(501);
    bad.authType = "SOMETHING";
    bad.tx = "transId";
    bad.status = "";
    bad.ts = "time";
    bad.format = "WHATEVER";
    bad.zone = "Mars/Phobos";
    bad.http = 99;
    bad.body = null;

    ApiException e = assertThrows(ApiException.class, () -> validator.validateCreate(bad.create()));

    assertEquals(
        Set.of(
            "serviceCode",
            "name",
            "description",
            "authType",
            "transactionIdPath",
            "statusPath",
            "timestampPath",
            "timestampFormat",
            "defaultTimeZone",
            "responseHttpStatus",
            "responseBody"),
        fields(e));
  }

  @Test
  void emptyRequestReportsAllMandatoryFields() {
    ServiceCreateRequest empty =
        new ServiceCreateRequest(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null);

    ApiException e = assertThrows(ApiException.class, () -> validator.validateCreate(empty));

    assertEquals(
        Set.of(
            "serviceCode",
            "name",
            "authType",
            "transactionIdPath",
            "statusPath",
            "timestampFormat",
            "responseHttpStatus",
            "responseBody"),
        fields(e));
  }

  @ParameterizedTest
  @MethodSource("invalidCodes")
  void serviceCodeMustBeOneToFiftyAlphanumericDashUnderscore(String code) {
    assertTrue(createFails(r -> r.code = code).contains("serviceCode"));
  }

  @ParameterizedTest
  @MethodSource("validCodes")
  void validServiceCodesAreAccepted(String code) {
    assertEquals(code, created(r -> r.code = code).serviceCode());
  }

  @ParameterizedTest
  @ValueSource(ints = {204, 205, 304, 199, 600, 0})
  void forbiddenOrOutOfRangeHttpStatusIsRejected(int status) {
    assertEquals(Set.of("responseHttpStatus"), createFails(r -> r.http = status));
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 201, 400, 401, 500, 599})
  void allowedHttpStatusesAreAccepted(int status) {
    assertEquals(status, created(r -> r.http = status).responseHttpStatus());
  }

  @Test
  void timestampPathIsOptionalAndEmptyMeansAbsent() {
    assertNull(created(r -> r.ts = "").timestampPath());
    assertNull(created(r -> r.ts = null).timestampPath());
  }

  @Test
  void responseBodyMustBeNonNullAndNoLargerThan64KbAndStorable() {
    String big = "\"" + "x".repeat(70_000) + "\"";
    String exactlyAtLimit = "\"" + "x".repeat(65_534) + "\"";

    assertEquals(Set.of("responseBody"), createFails(r -> r.body = body("null")));
    assertEquals(Set.of("responseBody"), createFails(r -> r.body = body(big)));
    assertEquals(Set.of("responseBody"), createFails(r -> r.body = body("\"a\\u0000b\"")));
    assertEquals(65_536, created(r -> r.body = body(exactlyAtLimit)).responseBody().length());
  }

  @Test
  void validJsonScalarsAndArraysAreAcceptedAsResponseBody() {
    for (String json : List.of("[1,2]", "\"ok\"", "5", "true", "{}")) {
      assertEquals(json, created(r -> r.body = body(json)).responseBody());
    }
  }

  @Test
  void explicitDisabledAndCustomTimeZoneAreKept() {
    NewService service =
        created(
            r -> {
              r.enabled = false;
              r.zone = "UTC";
            });

    assertFalse(service.enabled());
    assertEquals("UTC", service.defaultTimeZone());
  }

  @Test
  void errorMessagesNeverEchoTheRawInput() {
    Req r = new Req();
    r.code = "evil<script>";
    r.tx = "TOPSECRETPATH";
    r.zone = "Mars/Secret";

    ApiException e = assertThrows(ApiException.class, () -> validator.validateCreate(r.create()));
    String all = e.getMessage() + e.fieldErrors();

    assertFalse(all.contains("evil<script>"));
    assertFalse(all.contains("TOPSECRETPATH"));
    assertFalse(all.contains("Mars/Secret"));
  }

  // ---------- BR11.1: authType và timestampFormat được nhận ----------

  @Test
  void bothAuthTypesAndAllFourTimestampFormatsAreAccepted() {
    for (String format : List.of("ISO_8601", "UNIX_SECONDS", "UNIX_MILLIS")) {
      assertEquals(format, created(r -> r.format = format).timestampFormat());
    }
    NewService pattern =
        created(
            r -> {
              r.format = "PATTERN";
              r.pattern = "yyyy-MM-dd HH:mm:ss";
            });
    assertEquals("yyyy-MM-dd HH:mm:ss", pattern.timestampPattern());
    NewService apiKey =
        created(
            r -> {
              r.authType = "API_KEY";
              r.header = "X-Api-Key";
              r.secret = "k1";
            });
    assertEquals("API_KEY", apiKey.authType());
  }

  // ---------- BR11.2: đường dẫn mapping ----------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "transId",
        "$",
        "$.",
        "$..a",
        "$.a.",
        "$.a..b",
        "$.a b",
        "$.a[0]",
        "$.a['b']",
        "$.*",
        "$.tiếng",
        "$.a,b",
        "$.a\tb"
      })
  void invalidMappingPathsAreRejected(String path) {
    assertEquals(Set.of("transactionIdPath"), createFails(r -> r.tx = path));
    assertEquals(Set.of("statusPath"), createFails(r -> r.status = path));
    assertEquals(Set.of("timestampPath"), createFails(r -> r.ts = path));
  }

  @ParameterizedTest
  @ValueSource(strings = {"$.a", "$.a.b.c", "$.a-b.c_d.E9", "$.1.2.3.4.5.6.7.8.9.10"})
  void validMappingPathsAreAccepted(String path) {
    assertEquals(path, created(r -> r.tx = path).transactionIdPath());
  }

  @Test
  void anElevenSegmentPathAndAnOverlongPathAreRejected() {
    assertEquals(Set.of("transactionIdPath"), createFails(r -> r.tx = "$.a.b.c.d.e.f.g.h.i.j.k"));
    assertEquals(Set.of("transactionIdPath"), createFails(r -> r.tx = "$." + "a".repeat(199)));
  }

  @Test
  void aPathErrorNamesTheFirstOffendingCharacterWithItsCodePoint() {
    assertTrue(createMessage(r -> r.tx = "$.a b", "transactionIdPath").contains("U+0020"));
    assertTrue(createMessage(r -> r.tx = "$.a[0]", "transactionIdPath").contains("'['"));
    assertTrue(createMessage(r -> r.tx = "$.a[0]", "transactionIdPath").contains("U+005B"));
    assertTrue(createMessage(r -> r.tx = "$.é", "transactionIdPath").contains("U+00E9"));
  }

  // ---------- BR11.3, BR11.4: mẫu ngày giờ ----------

  @Test
  void patternFormatRequiresAValidCompletePattern() {
    assertEquals(
        Set.of("timestampPattern"),
        createFails(
            r -> {
              r.format = "PATTERN";
              r.pattern = null;
            }));
    for (String bad : List.of("yyyy-MM-dd", "HH:mm:ss", "yyyy-MM-dd hh:mm", "yyyy-MM-dd {", "")) {
      assertEquals(
          Set.of("timestampPattern"),
          createFails(
              r -> {
                r.format = "PATTERN";
                r.pattern = bad;
              }),
          bad);
    }
    assertEquals(
        Set.of("timestampPattern"),
        createFails(
            r -> {
              r.format = "PATTERN";
              r.pattern = "y".repeat(101);
            }));
  }

  @Test
  void patternIsDroppedAndNotCheckedForOtherFormats() {
    assertNull(created(r -> r.pattern = "not a ( pattern").timestampPattern());
    assertNull(
        created(
                r -> {
                  r.format = "UNIX_MILLIS";
                  r.pattern = "yyyy";
                })
            .timestampPattern());
  }

  // ---------- BR11.5, BR11.6, BR11.7: header và khóa API ----------

  private static void apiKey(Req r, String header, String secret) {
    r.authType = "API_KEY";
    r.header = header;
    r.secret = secret;
  }

  @Test
  void apiKeyServiceRequiresBothHeaderNameAndSecret() {
    assertEquals(
        Set.of("apiKeyHeaderName", "apiKeySecret"), createFails(r -> apiKey(r, null, null)));
    assertEquals(Set.of("apiKeySecret"), createFails(r -> apiKey(r, "X-Api-Key", "")));
    assertEquals(Set.of("apiKeyHeaderName"), createFails(r -> apiKey(r, "", "k")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "X Api",
        "X:Api",
        "X-Ápi",
        "a\tb",
        "Host",
        "content-type",
        "Content-Length",
        "TRANSFER-ENCODING",
        "Connection"
      })
  void invalidOrReservedHeaderNamesAreRejected(String header) {
    assertEquals(Set.of("apiKeyHeaderName"), createFails(r -> apiKey(r, header, "k")));
  }

  @Test
  void headerNameLengthIsLimitedToOneHundred() {
    assertEquals(Set.of("apiKeyHeaderName"), createFails(r -> apiKey(r, "a".repeat(101), "k")));
    assertEquals("a".repeat(100), created(r -> apiKey(r, "a".repeat(100), "k")).apiKeyHeaderName());
  }

  @ParameterizedTest
  @ValueSource(strings = {"X-Api-Key", "Authorization", "Cookie", "x_y.z~!#$%&'*+^`|"})
  void validHeaderNamesAreKeptAsWritten(String header) {
    assertEquals(header, created(r -> apiKey(r, header, "k")).apiKeyHeaderName());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        " ZZSECRET",
        "ZZSECRET ",
        "ZZSECRET\n",
        "ZZSECRET\u00e9",
        "ZZSECRET\u007f",
        "ZZSECRET\u0000"
      })
  void invalidApiKeysAreRejectedWithoutEchoingThem(String secret) {
    String message = createMessage(r -> apiKey(r, "X-Api-Key", secret), "apiKeySecret");

    assertFalse(message.contains("ZZSECRET"));
  }

  @Test
  void apiKeyLengthBoundsAreOneToTwoHundredFiftySix() {
    assertEquals("k", created(r -> apiKey(r, "X-Api-Key", "k")).apiKeySecret());
    assertEquals(
        "k".repeat(256), created(r -> apiKey(r, "X-Api-Key", "k".repeat(256))).apiKeySecret());
    assertEquals(Set.of("apiKeySecret"), createFails(r -> apiKey(r, "X-Api-Key", "k".repeat(257))));
  }

  @Test
  void anApiKeyMayContainInnerSpacesAndIsStoredExactly() {
    assertEquals(
        "Bearer abc", created(r -> apiKey(r, "Authorization", "Bearer abc")).apiKeySecret());
  }

  @Test
  void noneServiceIgnoresAnyHeaderNameAndSecret() {
    NewService service =
        created(
            r -> {
              r.header = "X-Api-Key";
              r.secret = "should-be-ignored";
            });

    assertNull(service.apiKeyHeaderName());
    assertNull(service.apiKeySecret());
  }

  @Test
  void theSecretNeverAppearsInErrorsOrToString() {
    Req r = new Req();
    r.code = "bad code";
    apiKey(r, "X-Api-Key", "SUPER-SECRET-VALUE");
    ApiException e = assertThrows(ApiException.class, () -> validator.validateCreate(r.create()));
    assertFalse((e.getMessage() + e.fieldErrors()).contains("SUPER-SECRET"));

    NewService ok = created(x -> apiKey(x, "X-Api-Key", "SUPER-SECRET-VALUE"));
    assertFalse(ok.toString().contains("SUPER-SECRET"));
  }

  // ---------- BR12.3: sửa ----------

  private NewService updated(UnaryOperator<Req> change, String currentAuth, boolean keyConfigured) {
    Req r = change.apply(new Req());
    return validator.validateUpdate(r.update(), currentAuth, keyConfigured);
  }

  private Set<String> updateFails(UnaryOperator<Req> change, String currentAuth, boolean key) {
    Req r = change.apply(new Req());
    return fields(
        assertThrows(
            ApiException.class, () -> validator.validateUpdate(r.update(), currentAuth, key)));
  }

  @Test
  void updateKeepsTheOldKeyWhenTheSecretIsBlankAndTheServiceIsAlreadyApiKey() {
    NewService service =
        updated(
            r -> {
              apiKey(r, "X-New-Header", null);
              return r;
            },
            "API_KEY",
            true);

    assertTrue(service.keepExistingKey());
    assertNull(service.apiKeySecret());
    assertEquals("X-New-Header", service.apiKeyHeaderName());
  }

  @Test
  void updateReplacesTheKeyWhenANewSecretIsGiven() {
    NewService service =
        updated(
            r -> {
              apiKey(r, "X-Api-Key", "new-key");
              return r;
            },
            "API_KEY",
            true);

    assertFalse(service.keepExistingKey());
    assertEquals("new-key", service.apiKeySecret());
  }

  @Test
  void switchingFromNoneToApiKeyWithoutASecretIsRejected() {
    assertEquals(
        Set.of("apiKeySecret"),
        updateFails(
            r -> {
              apiKey(r, "X-Api-Key", "");
              return r;
            },
            "NONE",
            false));
  }

  @Test
  void switchingToNoneDropsHeaderAndSecret() {
    NewService service =
        updated(
            r -> {
              r.header = "X-Api-Key";
              r.secret = "k";
              return r;
            },
            "API_KEY",
            true);

    assertEquals("NONE", service.authType());
    assertNull(service.apiKeyHeaderName());
    assertNull(service.apiKeySecret());
    assertFalse(service.keepExistingKey());
  }

  @Test
  void updateAlwaysRequiresTheHeaderNameForApiKey() {
    assertEquals(
        Set.of("apiKeyHeaderName"),
        updateFails(
            r -> {
              apiKey(r, null, null);
              return r;
            },
            "API_KEY",
            true));
  }

  @Test
  void updateWithMissingOptionalFieldsUsesDefaults() {
    NewService service =
        updated(
            r -> {
              r.description = null;
              r.zone = null;
              return r;
            },
            "NONE",
            false);

    assertNull(service.description());
    assertEquals("Asia/Ho_Chi_Minh", service.defaultTimeZone());
    assertNull(service.serviceCode());
  }

  @Test
  void updateCollectsEveryFieldErrorAtOnce() {
    ServiceUpdateRequest empty =
        new ServiceUpdateRequest(
            null, null, null, null, null, null, null, null, null, null, null, null, null);

    ApiException e =
        assertThrows(ApiException.class, () -> validator.validateUpdate(empty, "NONE", false));

    assertEquals(
        Set.of(
            "name",
            "authType",
            "transactionIdPath",
            "statusPath",
            "timestampFormat",
            "responseHttpStatus",
            "responseBody"),
        fields(e));
  }

  // ---------- BR18.2: cấu hình của thử mapping ----------

  @Test
  void mappingSettingsUseTheSameRulesAsSaving() {
    List<FieldError> errors = new ArrayList<>();

    MappingSettings ok =
        validator.validateMappingSettings(
            "$.a", "$.b", null, "PATTERN", "yyyy-MM-dd HH:mm", null, errors);

    assertTrue(errors.isEmpty());
    assertEquals("Asia/Ho_Chi_Minh", ok.defaultTimeZone());
    assertEquals("PATTERN", ok.timestampFormat());

    List<FieldError> bad = new ArrayList<>();
    validator.validateMappingSettings("a", "", "x", "NOPE", null, "Mars/X", bad);
    assertEquals(
        Set.of(
            "transactionIdPath",
            "statusPath",
            "timestampPath",
            "timestampFormat",
            "defaultTimeZone"),
        bad.stream().map(FieldError::field).collect(Collectors.toSet()));
  }
}
