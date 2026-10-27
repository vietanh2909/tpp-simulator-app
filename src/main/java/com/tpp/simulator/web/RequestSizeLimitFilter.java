package com.tpp.simulator.web;

import com.tpp.simulator.config.ApiException;
import com.tpp.simulator.config.ErrorCode;
import com.tpp.simulator.config.FieldError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Đứng đầu chuỗi xử lý, theo bảng chính sách theo đường dẫn (PD-07):
 *
 * <ul>
 *   <li>{@code POST /callbacks/**}: ghi nhận {@code receivedAt} ngay lúc bắt đầu, đếm byte khi đọc
 *       body và dừng ở {@value #MAX_BODY_BYTES} byte (kể cả khi không có Content-Length hoặc dùng
 *       chunked). Vượt giới hạn thì trả 413 PAYLOAD_TOO_LARGE mà không đọc hết body (BR2.2,
 *       BR14.2).
 *   <li>{@code POST /api/mapping-tests}: trần {@value #MAPPING_TEST_MAX_BYTES} byte; vượt thì 400
 *       VALIDATION_FAILED ở trường {@code sampleJson}, không thêm mã trạng thái vào hợp đồng.
 * </ul>
 *
 * Dùng chung bộ dựng lỗi {@link ApiErrors} với bộ xử lý lỗi tập trung nên lỗi có cùng dạng và
 * header.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

  public static final int MAX_BODY_BYTES = 1_048_576;

  /** Trần thân yêu cầu thử mapping: 1 MiB chuỗi bị thoát ký tự ở mức xấu nhất cộng các trường. */
  public static final int MAPPING_TEST_MAX_BYTES = 8 * 1024 * 1024;

  /** Tên thuộc tính request chứa thời điểm bắt đầu nhận (kiểu {@link Instant}, UTC, micro giây). */
  public static final String RECEIVED_AT = RequestSizeLimitFilter.class.getName() + ".receivedAt";

  private static final String CALLBACK_PREFIX = "/callbacks/";
  private static final String MAPPING_TEST_PATH = "/api/mapping-tests";

  private final Clock clock;
  private final ApiErrors errors;

  public RequestSizeLimitFilter(Clock clock, ApiErrors errors) {
    this.clock = clock;
    this.errors = errors;
  }

  private static boolean isCallback(HttpServletRequest request) {
    return request.getRequestURI() != null && request.getRequestURI().startsWith(CALLBACK_PREFIX);
  }

  private static boolean isMappingTest(HttpServletRequest request) {
    return MAPPING_TEST_PATH.equals(request.getRequestURI());
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !"POST".equals(request.getMethod()) || !(isCallback(request) || isMappingTest(request));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    boolean callback = isCallback(request);
    int limit = callback ? MAX_BODY_BYTES : MAPPING_TEST_MAX_BYTES;
    if (callback) {
      request.setAttribute(RECEIVED_AT, clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    if (request.getContentLengthLong() > limit) {
      reject(request, response, callback, limit);
      return;
    }
    byte[] body = request.getInputStream().readNBytes(limit + 1);
    if (body.length > limit) {
      reject(request, response, callback, limit);
      return;
    }
    chain.doFilter(new CachedBodyRequest(request, body), response);
  }

  private void reject(
      HttpServletRequest request, HttpServletResponse response, boolean callback, int limit)
      throws IOException {
    if (!callback) {
      errors.write(
          response,
          new ApiException(
              ErrorCode.VALIDATION_FAILED,
              "Thân yêu cầu vượt quá giới hạn " + limit + " byte.",
              List.of(new FieldError("sampleJson", "Thân yêu cầu quá lớn.")),
              null));
      return;
    }
    String uri = request.getRequestURI();
    String segment = uri.substring(CALLBACK_PREFIX.length());
    int slash = segment.indexOf('/');
    CallbackAccessLog.emit(
        new CallbackLogEntry()
            .service(slash >= 0 ? segment.substring(0, slash) : segment)
            .httpStatus(ErrorCode.PAYLOAD_TOO_LARGE.status().value()));
    errors.write(
        response,
        new ApiException(
            ErrorCode.PAYLOAD_TOO_LARGE,
            "Body vượt quá giới hạn " + limit + " byte.",
            List.of(),
            null));
  }

  /** Request có body đã đọc sẵn vào bộ nhớ để bộ điều khiển đọc lại. */
  private static final class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    CachedBodyRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream source = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return source.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
          return source.read(buffer, offset, length);
        }

        @Override
        public boolean isFinished() {
          return source.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException("Async read is not supported");
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      String encoding = getCharacterEncoding();
      Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
      return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }
  }
}
