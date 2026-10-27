# tpp-simulator-app

Backend của TPP Callback Simulator (Java 21, Spring Boot 3.5.16, Maven, PostgreSQL 16, Flyway).

Công cụ giả lập nhận callback từ hệ thống ngân hàng ở môi trường dev/UAT: bạn tạo một dịch vụ (đường dẫn ánh xạ
`transactionId`, `status`, `timestamp`, kiểu xác thực và một response cố định), ngân hàng gọi
`POST /callbacks/{serviceCode}`, backend lưu từng lần nhận (kể cả lần bị từ chối), gom theo giao dịch và cho tra cứu qua
API. Bản này gồm **U1 (skeleton)** và **U2 (lõi backend)**: quản lý dịch vụ đầy đủ (sửa, bật/tắt, xóa mềm, phiên bản cấu hình),
khóa API, bốn định dạng thời gian, thử mapping, danh sách giao dịch và callback có bộ lọc, công cụ nạp 100.000 callback,
script đo và dịch vụ mẫu. Giao diện web (U3, U4) nằm ở `tpp-simulator-portal`.

> **Cảnh báo bảo mật.** API quản lý và tra cứu **không có đăng nhập** và dùng chung cổng với `/callbacks`. Vì vậy
> backend mặc định chỉ lắng nghe `127.0.0.1`. Nếu bạn đổi sang địa chỉ khác (biến môi trường `SERVER_ADDRESS`, ví dụ
> `0.0.0.0`), **mọi người cùng mạng đọc được toàn bộ lịch sử và đổi được cấu hình**; hãy tự bảo vệ bằng tường lửa.
> Khóa API của dịch vụ được lưu nguyên văn trong cột `api_key_secret` của bảng `services` (nơi duy nhất); không phản hồi API,
> nhật ký hay header đã lưu nào chứa khóa.
>
> **Không gửi dữ liệu cá nhân thật** của khách hàng vào công cụ này. Payload callback được lưu nguyên văn trong
> PostgreSQL (jsonb) và giữ vô thời hạn; đây là dữ liệu kiểm thử.

## Điều kiện cần

- JDK 21 (ví dụ Temurin 21) và Maven 3.9+
- PostgreSQL 16: dùng bản cài sẵn trên máy hoặc Docker Compose (tùy chọn). **Mọi bước kiểm chứng (kiểm thử, công cụ nạp,
  script đo, dọn dữ liệu) chạy được mà không cần Docker.**
- Windows PowerShell 5.1 và `curl.exe` cho các script và ví dụ cURL (trên Linux/macOS dùng `curl` và bản bash của script)
- Quyền `CREATEDB` của người dùng PostgreSQL **chỉ** cần để lệnh nạp thật tự tạo database `_load`; **không** cần cho
  `scripts\verify.ps1` (kiểm thử chạy trên database kiểm thử có sẵn)

## Khởi động PostgreSQL

Cách 1, dùng PostgreSQL cài sẵn: tạo database `tpp_simulator` và đặt ba biến môi trường kết nối trong chính phiên shell chạy
ứng dụng (không ghi vào tệp nào trong kho; giá trị dưới đây chỉ để minh họa):

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://localhost:5432/tpp_simulator'
$env:SPRING_DATASOURCE_USERNAME = 'tpp'
$env:SPRING_DATASOURCE_PASSWORD = 'tpp'
```

Cách 2, Docker Compose (tùy chọn): `docker compose up -d` chạy PostgreSQL ở `localhost:5432`, database `tpp_simulator`, user
`tpp`, mật khẩu `tpp` (chỉ dành cho phát triển local), dữ liệu trong named volume `tpp_simulator_pgdata`. Cổng chỉ gắn vào
loopback (`127.0.0.1:5432`).

## Chạy ứng dụng

```powershell
mvn spring-boot:run
```

Flyway tự tạo lược đồ (`V1__init_schema`, `V2__list_filter_indexes`) khi khởi động; không cần bước thủ công nào khác. Nếu không
kết nối được cơ sở dữ liệu, ứng dụng báo lỗi nêu nguyên nhân kết nối rồi thoát với mã lỗi khác 0.

| Biến môi trường | Mặc định | Ý nghĩa |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/tpp_simulator` | Địa chỉ cơ sở dữ liệu |
| `SPRING_DATASOURCE_USERNAME` | `tpp` | Người dùng |
| `SPRING_DATASOURCE_PASSWORD` | `tpp` | Mật khẩu (đổi khi không dùng Docker Compose cục bộ) |
| `SERVER_ADDRESS` | `127.0.0.1` | Địa chỉ lắng nghe (xem cảnh báo ở trên) |
| `SERVER_PORT` | `8080` | Cổng |

Kiểm tra sức khỏe: `curl.exe http://127.0.0.1:8080/actuator/health` trả `{"status":"UP"}`. Chỉ endpoint `health` được
mở; mọi endpoint Actuator khác trả 404.

## 1. Dịch vụ mẫu và cURL

Ba dịch vụ mẫu `THU_HO` (không xác thực, ISO 8601), `CHI_HO` (khóa API, thời gian theo mẫu) và `BIEN_DONG_SO_DU` (Unix mili giây)
nằm ở `docs/samples/`, kèm hai payload mỗi dịch vụ (hợp lệ và lỗi) và README riêng có đủ lệnh cURL cho PowerShell và bash:
[`docs/samples/README.md`](docs/samples/README.md). Ứng dụng **không** tự tạo dịch vụ lúc khởi động. Tạo cả ba (chạy lại được):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\create-sample-services.ps1
```

```bash
scripts/create-sample-services.sh
```

Thử nhanh (PowerShell dùng `curl.exe`, chạy từ thư mục `tpp-simulator-app`):

```powershell
curl.exe -i -X POST http://127.0.0.1:8080/callbacks/CHI_HO -H "Content-Type: application/json" -H "X-Api-Key: sample-key-chi-ho" --data-binary "@docs/samples/CHI_HO.payload-valid.json"
curl.exe -i -X POST http://127.0.0.1:8080/callbacks/CHI_HO -H "Content-Type: application/json" -H "X-Api-Key: sai" --data-binary "@docs/samples/CHI_HO.payload-valid.json"
curl.exe -G http://127.0.0.1:8080/api/callbacks --data-urlencode "outcome=REJECTED" --data-urlencode "rejectionReason=INVALID_API_KEY"
```

Khóa `sample-key-chi-ho` là giá trị giả công khai, chỉ để thử.

### API

| Việc | Lệnh gọi |
|---|---|
| Nhận callback | `POST /callbacks/{serviceCode}` (response đã cấu hình; không bao giờ có header `X-Simulator-Error`) |
| Tạo, đọc, liệt kê dịch vụ | `POST /api/services`, `GET /api/services/{serviceCode}`, `GET /api/services?includeDeleted=true` |
| Sửa, bật/tắt, xóa mềm | `PUT /api/services/{serviceCode}`, `PUT /api/services/{serviceCode}/enabled`, `DELETE /api/services/{serviceCode}` |
| Thử mapping | `POST /api/mapping-tests` (không ghi gì vào cơ sở dữ liệu) |
| Danh sách giao dịch / callback | `GET /api/transactions`, `GET /api/callbacks` (bộ lọc `serviceCode`, `transactionId`, `status` hoặc `outcome`, `rejectionReason`, `receivedFrom`, `receivedTo`, `page`, `size`) |
| Chi tiết | `GET /api/transactions/detail?serviceCode=...&transactionId=...`, `GET /api/callbacks/{recordId}` |

Quy tắc đáng nhớ:

- Thứ tự kiểm tra một callback: body quá 1.048.576 byte (413), mã dịch vụ chưa từng có (404), đã xóa (404), đang tắt (503),
  khóa API sai hoặc thiếu (401), Content-Type không phải JSON (400), JSON hỏng (400), rồi ánh xạ và ghi. Mọi lần từ chối của
  một dịch vụ có thật (trừ 413) được ghi thành một lần nhận `REJECTED` với lý do `SERVICE_DELETED`, `SERVICE_DISABLED`,
  `MISSING_API_KEY`, `INVALID_API_KEY`, `UNSUPPORTED_CONTENT_TYPE` hoặc `INVALID_JSON`; chúng không vào giao dịch nào.
- Nếu lần nhận bị từ chối **không ghi được**, phản hồi vẫn mang mã từ chối gốc kèm header `X-Simulator-Record-Failed: true`.
  Callback được chấp nhận mà không ghi được trả `500 INTERNAL_ERROR`.
- Bộ lọc thời gian theo giây: giá trị không có phần thập phân là cả giây đó (`receivedTo=2026-10-19T11:00:00Z` gồm lần nhận lúc
  11:00:00.400). Dấu `+` của offset phải mã hóa thành `%2B`. Giá trị rỗng của một tham số coi như vắng.
- Sửa dịch vụ chỉ sinh phiên bản cấu hình mới khi đổi đường dẫn mapping, định dạng hoặc mẫu thời gian, múi giờ hay response;
  callback cũ giữ nguyên kết quả và phiên bản đã dùng. Để trống ô khóa khi sửa dịch vụ `API_KEY` là giữ khóa cũ.
- `transactionId` và `status` trong bộ lọc khớp theo **chứa chuỗi**, không phân biệt hoa thường; `%`, `_`, `\` là ký tự thường.

### Mã lỗi

Mọi lỗi do ứng dụng sinh ra là JSON `{"error","message","fieldErrors?"}` kèm header `X-Simulator-Error` có giá trị bằng
`error`. Response đã cấu hình của dịch vụ **không bao giờ** có header này, kể cả khi bạn cấu hình trả 401 hay 500.

| `error` | HTTP | Khi nào |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Dữ liệu dịch vụ hoặc thử mapping sai (kèm `fieldErrors`), thân JSON hỏng/sai kiểu, tham số truy vấn sai |
| `UNSUPPORTED_CONTENT_TYPE`, `INVALID_JSON` | 400 | Callback không phải `application/json` / body rỗng hoặc hỏng |
| `UNAUTHORIZED` | 401 | Callback tới dịch vụ `API_KEY` thiếu hoặc sai khóa |
| `SERVICE_NOT_FOUND`, `TRANSACTION_NOT_FOUND`, `CALLBACK_NOT_FOUND` | 404 | Không có dịch vụ (hoặc đã xóa) / giao dịch / lần nhận |
| `NOT_FOUND`, `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE` | 404, 405, 415 | Đường dẫn lạ, phương thức sai, Content-Type sai ở API quản lý |
| `SERVICE_CODE_DUPLICATE` | 409 | Mã dịch vụ đã có (không phân biệt hoa thường, kể cả đã xóa) |
| `PAYLOAD_TOO_LARGE` | 413 | Body callback lớn hơn 1.048.576 byte |
| `INTERNAL_ERROR` | 500 | Lỗi ghi hoặc đọc cơ sở dữ liệu, hết thời gian chờ khóa, lỗi bất ngờ |
| `SERVICE_DISABLED` | 503 | Dịch vụ đang tắt |

Hợp đồng đầy đủ nằm ở [`docs/api/openapi.yaml`](docs/api/openapi.yaml).

## 2. Công cụ nạp 100.000 callback

Công cụ nạp là một lệnh riêng trong cùng JAR (không bao giờ chạy lúc khởi động ứng dụng). Mỗi lần nhận đi qua đúng logic nhận
callback của ứng dụng (ánh xạ, che header, gom giao dịch, ghi lần từ chối) nhưng không qua HTTP. **Nó chỉ ghi vào database có tên
kết thúc bằng `_load`** (mặc định `tpp_simulator_load`); tên khác bị từ chối trước mọi kết nối.

```powershell
mvn -B -DskipTests package
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://localhost:5432/tpp_simulator'   # chỉ để tìm máy chủ; database đích lấy từ --database
$env:SPRING_DATASOURCE_USERNAME = 'tpp'
$env:SPRING_DATASOURCE_PASSWORD = '<mật khẩu>'
java -Dloader.main=com.tpp.simulator.loadtool.LoadToolMain -cp target\tpp-simulator-app-0.0.1-SNAPSHOT.jar `
  org.springframework.boot.loader.launch.PropertiesLauncher --callbacks=100000 --database=tpp_simulator_load
```

| Tham số | Mặc định | Ý nghĩa |
|---|---|---|
| `--callbacks=N` | 100000 | Số lần nhận cần nạp |
| `--seed=N` | cố định | Cùng seed cho cùng cấu trúc và giá trị dữ liệu |
| `--days=N` | 30 | Rải thời điểm nhận đều trong N ngày gần nhất |
| `--services=N` | 3 | Số dịch vụ `LOAD_01` trở đi (cấu hình xoay vòng: `NONE`+ISO, `API_KEY`+mẫu, `NONE`+Unix mili giây) |
| `--append` | tắt | Dùng lại dịch vụ `LOAD_` đã có và nạp thêm; không có thì dừng khi `LOAD_01..LOAD_N` đã tồn tại |
| `--database=tên` | `tpp_simulator_load` | Database đích, phải kết thúc bằng `_load` |

- Nếu database đích chưa có, công cụ tạo nó (cần quyền `CREATEDB`). Không tạo được thì dừng và in lệnh tạo thủ công:
  `CREATE DATABASE "tpp_simulator_load";`. Flyway áp lược đồ cùng phiên bản với ứng dụng.
- Dữ liệu: khoảng 4 lần nhận mỗi giao dịch, 85% `SUCCESS`, 10% `MAPPING_ERROR`, 5% `REJECTED` với đủ sáu lý do, payload khoảng 1 KB.
- Xong in `Load finished: callbacks=... transactions=... writtenThisRun=... elapsedSeconds=...` và thoát 0. Lỗi ghi giữa chừng dừng
  ngay, giữ dữ liệu đã ghi, in `Load failed: ...` kèm số `callbacks` đọc từ cơ sở dữ liệu và thoát 1; từ chối trước khi ghi thoát 2.
- Kỳ vọng khoảng 15 phút cho 100.000 callback (chỉ là mục tiêu, không có ngưỡng đạt/hỏng). Sau khi nạp công cụ chạy `ANALYZE`.
- Chạy backend trên database đã nạp để đo: đặt `SPRING_DATASOURCE_URL` trỏ vào `.../tpp_simulator_load` rồi `mvn spring-boot:run`.

## 3. Đo độ trễ danh sách

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\measure-list-latency.ps1 -BaseUrl http://127.0.0.1:8080
```

Script đo **một** kịch bản: `GET /api/transactions?page=1&size=20`, không bộ lọc, 20 lần khởi động nóng rồi 200 lần tuần tự, in
p50, p95, p99 và cấu hình máy; thoát 0 khi mọi phản hồi 200 và p95 dưới 1000 ms. **Không được đo** (khoảng trống đã chấp nhận): lọc
`transactionId` hoặc `status` theo chuỗi chứa, `serviceCode` kèm khoảng thời gian, lọc `outcome` hoặc `rejectionReason`, trang
sâu, `size=50` hoặc `100`, toàn bộ `GET /api/callbacks` và `GET /api/callbacks/{recordId}`. Chỉ mục đã thêm ở `V2` giảm rủi ro
cấu trúc nhưng không phải bằng chứng về thời gian.

## 4. Kiểm tra 50 callback đồng thời

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\check-concurrency.ps1 -BaseUrl http://127.0.0.1:8080
```

Một tiến trình PowerShell, một `HttpClient` chung và chốt xuất phát chung gửi 50 callback cùng `transactionId` rồi 50 `transactionId`
khác nhau tới một dịch vụ tạm, đối chiếu qua API công khai và đo `maxInFlight` (số request chồng nhau tối đa, đo ở phía máy khách).
**PASS** cần mọi tiêu chí chức năng đạt **và** `maxInFlight >= 25`. **FAIL** khi có tiêu chí chức năng không đạt. **INCONCLUSIVE**
khi chức năng đạt nhưng `maxInFlight < 25`, tức không có bằng chứng các request chồng nhau: chạy lại, không hạ ngưỡng. Mã thoát: 0 cả
hai kịch bản PASS; 1 có FAIL; 2 có INCONCLUSIVE. Dịch vụ tạm chỉ được xóa mềm, các Callback đã ghi ở lại, nên chạy trên database
phát triển hoặc database `_load`.

## 5. Dung lượng đĩa và cách dọn

Không có giới hạn tốc độ, trần số bản ghi hay mã `429`, và lần nhận bị từ chối luôn được ghi (mỗi lần có thể mang body tới
1 MiB). Một vòng lặp gửi sai khóa vì thế có thể làm đầy đĩa. Dữ liệu không tự dọn. **Cách dọn chính là xóa database** (không cần
Docker):

```sql
DROP DATABASE tpp_simulator;     -- hoặc database "_load" của công cụ nạp
CREATE DATABASE tpp_simulator;   -- backend tự áp lược đồ khi khởi động lại
```

Chạy các lệnh trên bằng `psql` ở database khác (ví dụ `postgres`) sau khi dừng backend. Với database nạp, chỉ cần `DROP DATABASE
tpp_simulator_load;` rồi chạy lại công cụ. Cách phụ cho ai dùng Docker Compose: `docker compose down -v` (xóa volume
`tpp_simulator_pgdata`). Không nên chạy công cụ nạp lúc đang đo độ trễ vì cả hai cùng dùng máy chủ PostgreSQL và đĩa.

## 6. Phần mở rộng `pg_trgm`

Mặc định **không cần** phần mở rộng nào và không có bản chuyển nào tạo chúng. Nếu một phép đo cho thấy lọc chuỗi chứa
(`transactionId`, `status`) không đạt p95 dưới 1000 ms, có thể thêm `pg_trgm` và chỉ mục ở bản chuyển `V3` trở đi; khi đó môi trường
cần bản cài PostgreSQL có gói `contrib` và người dùng đủ quyền `CREATE EXTENSION`, và README này sẽ được cập nhật.

## 7. Điều kiện môi trường, nhật ký và giới hạn payload

- Windows PowerShell 5.1 (không dùng cú pháp PowerShell 7), `curl.exe`; các script PowerShell lưu UTF-8 có BOM, script bash dùng
  kết thúc dòng LF.
- **Nhật ký của endpoint callback:** đúng một dòng cho mỗi request, logger `com.tpp.simulator.web.CallbackAccessLog`, dạng
  `Callback request: service=THU_HO recordId=7 outcome=SUCCESS httpStatus=201` (trường vắng thì bỏ cả khóa). Lần từ chối ghi
  `rejectionReason=...` thay cho `outcome=...`. Lỗi ghi dùng mức ERROR và thêm `exception=<lớp> sqlState=<mã>`. Không bao giờ ghi
  body, giá trị header, khóa hay thông điệp thô của cơ sở dữ liệu. Muốn chẩn đoán lỗi ghi, tìm dòng ERROR của logger này và tra
  `sqlState` (ví dụ `55P03` là hết thời gian chờ khóa dòng khi sửa dịch vụ, `08006` là mất kết nối).
- **Payload không lưu được ở dạng `jsonb`:** PostgreSQL từ chối chuỗi chứa ký tự thoát `\u0000`, nửa cặp thay thế lẻ hoặc số
  ngoài miền `numeric`, dù chúng là JSON hợp lệ. Callback được chấp nhận như vậy trả `500 INTERNAL_ERROR` và không lưu gì. Lần nhận
  bị từ chối thì lưu nguyên văn trong `payloadRawText` (byte hỏng và ký tự NUL thay bằng U+FFFD) thay vì `payloadJson`.
- Body UTF-8 hỏng là `INVALID_JSON`. `payloadJson` là dạng đã chuẩn hóa của jsonb (số như `1e3` có thể hiện thành `1000`, thứ tự
  khóa và khóa trùng không được giữ).
- Chi tiết giao dịch không có trần kích thước: một giao dịch có rất nhiều lần nhận lớn có thể cho phản hồi hàng chục MiB.
- Spring Boot 3.5.16 đã hết hỗ trợ mã nguồn mở; chấp nhận vì công cụ chỉ chạy local trên `127.0.0.1`.

## Sao lưu và khôi phục dữ liệu

Callback không bao giờ bị sửa hay xóa và dữ liệu giữ vô thời hạn. Sao lưu bằng `pg_dump` của PostgreSQL cài sẵn:

```powershell
pg_dump -h localhost -U tpp -d tpp_simulator > backup.sql
# Khôi phục vào cơ sở dữ liệu trống (chạy ứng dụng một lần để Flyway tạo lược đồ, hoặc tạo lại cơ sở dữ liệu trước)
Get-Content backup.sql | psql -h localhost -U tpp -d tpp_simulator
```

Với Docker Compose: `docker exec tpp-simulator-postgres pg_dump -U tpp -d tpp_simulator > backup.sql`.

## Kiểm thử, định dạng và bao phủ

```powershell
mvn -B verify          # Spotless + kiểm thử + báo cáo và luật bao phủ JaCoCo
mvn spotless:apply     # tự động format theo google-java-format
```

- Build thất bại nếu code chưa format (`spotless:check` chạy ở phase `validate`) hoặc line coverage dưới 80% (luật JaCoCo ở phase
  `verify`; báo cáo ở `target/site/jacoco/`). Mã công cụ nạp được tính vào ngưỡng. Không hạ ngưỡng để qua kiểm tra.
- Kiểm thử tích hợp luôn chạy trên PostgreSQL 16 thật. **Không cần Docker:** đặt ba biến môi trường trỏ vào một database có tên
  chứa chữ `test` (kiểm thử xóa sạch dữ liệu của nó; tên khác bị từ chối), trong cùng phiên shell chạy `mvn`:

  ```powershell
  $env:TPP_TEST_DB_URL = 'jdbc:postgresql://localhost:5432/tpp_simulator_test'
  $env:TPP_TEST_DB_USER = '<người dùng>'
  $env:TPP_TEST_DB_PASSWORD = '<mật khẩu>'
  mvn -B verify
  ```

  Không ghi mật khẩu vào tệp. Chỉ chạy **một** tiến trình `mvn` tại một thời điểm trên cùng database kiểm thử (hai lần chạy đồng
  thời gây deadlock giả). Khi thiếu các biến này, bộ kiểm thử dùng Testcontainers (cần Docker; đây là cách CI GitHub Actions chạy).
- Từ thư mục gốc workspace chạy lệnh kiểm chứng đã duyệt: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify.ps1`
  (biến `TPP_TEST_DB_*` đặt trong cùng phiên shell được truyền xuống Maven). Bốn script ở trên **không** nằm trong `verify.ps1` hay CI.

## Cấu trúc

```text
pom.xml
compose.yaml                          PostgreSQL 16 cho môi trường local (tùy chọn)
scripts/                              measure-list-latency.ps1, check-concurrency.ps1, create-sample-services.ps1/.sh
src/main/java/com/tpp/simulator/
  catalog/                            tạo, sửa, bật/tắt, xóa mềm, liệt kê dịch vụ, phiên bản cấu hình (ServiceCatalog)
  mapping/                            trích transactionId, status, timestamp (bốn định dạng), thử mapping (MappingEngine)
  ingestion/                          quy trình nhận callback, khóa API, ghi lần từ chối (CallbackIngestion)
  ledger/                             ghi Callback, gom Transaction, bộ lọc, danh sách và chi tiết (CallbackLedger)
  web/                                bộ điều khiển REST, bộ lọc kích thước, nhật ký callback, bộ xử lý lỗi tập trung
  config/                             đồng hồ, làm sạch log, header đã che, mã lỗi dùng chung
  loadtool/                           công cụ nạp dữ liệu thử (chỉ chạy bằng lệnh riêng, không phụ thuộc ngược)
src/main/resources/application.yml
src/main/resources/db/migration/      migration Flyway (không bao giờ sửa bản đã áp dụng)
src/test/java/                        kiểm thử
docs/api/openapi.yaml                 hợp đồng API
docs/samples/                         dịch vụ mẫu, payload mẫu và lệnh cURL
.github/workflows/ci.yml              CI: Java 21 (Temurin), mvn -B verify
```

Phụ thuộc gói một chiều: `web` -> `ingestion`/`catalog`/`ledger`/`mapping`; `ingestion` -> `catalog`, `mapping`, `ledger`; `catalog` ->
`mapping`; `loadtool` -> các gói trên và không gói nào phụ thuộc `loadtool`; `mapping` không phụ thuộc gói ứng dụng nào; `config`
được mọi gói dùng nhưng không phụ thuộc gói nào (có kiểm thử).
