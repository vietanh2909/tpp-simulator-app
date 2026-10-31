# Hướng dẫn tích hợp API cho Frontend — TPP Callback Simulator

Tài liệu này dành cho người làm frontend (FE). Nguồn sự thật là `tpp-simulator-app/docs/api/openapi.yaml`; tài liệu này nhóm các API theo nhu cầu màn hình, giải thích quy ước chung và đưa ví dụ. Khi hai nơi khác nhau, `openapi.yaml` đúng.

> Trạng thái kiểm chứng: nội dung được dựng từ `openapi.yaml` và mã portal hiện có. Các ví dụ cURL chưa được chạy với backend thật trong lúc viết tài liệu.

## 1. Tổng quan

| Mục | Giá trị |
| --- | --- |
| Địa chỉ mặc định | `http://localhost:8080` (đổi bằng biến môi trường `SERVER_ADDRESS`; mặc định backend chỉ nghe loopback `127.0.0.1`) |
| Định dạng | `application/json; charset=utf-8`, tên trường camelCase, enum viết hoa |
| Xác thực | Không có cho API quản lý và tra cứu. Đường nhận callback có thể yêu cầu khóa API theo cấu hình từng dịch vụ |
| Thời gian | ISO 8601 UTC dạng `2026-10-19T03:00:00Z`, có thể có phần giây thập phân tới micro giây. Giao diện hiển thị theo `Asia/Ho_Chi_Minh` (GMT+7) |
| CORS | **Không bật.** FE phải gọi cùng nguồn qua proxy (xem mục 3) |
| Phiên bản | Không có phiên bản trong đường dẫn. Thay đổi chỉ là thêm trường; FE bỏ qua trường lạ |

### 1.1 Bốn nhóm API

| Nhóm | Mục đích | Màn hình FE | Endpoint |
| --- | --- | --- | --- |
| A. Quản lý dịch vụ | Tạo, xem, sửa, bật/tắt, xóa mềm dịch vụ callback | S1 Danh sách dịch vụ, S2 Cấu hình dịch vụ | 6 endpoint dưới `/api/services` |
| B. Thử mapping | Thử cấu hình đường dẫn JSON trên JSON mẫu, không ghi dữ liệu | S2 (khối thử mapping) | `POST /api/mapping-tests` |
| C. Nhận callback | Điểm nhận callback của hệ thống ngân hàng; FE dùng để "gửi thử" | S3 Gửi callback thử | `POST /callbacks/{serviceCode}` |
| D. Tra cứu | Xem giao dịch và từng lần nhận callback | S4 Theo dõi, S5 Chi tiết | 4 endpoint `GET` dưới `/api/transactions` và `/api/callbacks` |
| E. Vận hành | Kiểm tra sống | (tùy chọn) | `GET /actuator/health` |

### 1.2 Bảng tóm tắt endpoint

| Nhóm | Phương thức | Đường dẫn | Thành công | Ghi chú |
| --- | --- | --- | --- | --- |
| A | `GET` | `/api/services` | 200 mảng `Service` | Tham số `includeDeleted` |
| A | `POST` | `/api/services` | 201 `Service` | Tạo mới |
| A | `GET` | `/api/services/{serviceCode}` | 200 `Service` | Trả cả dịch vụ đã xóa (`deleted: true`) |
| A | `PUT` | `/api/services/{serviceCode}` | 200 `Service` | Sửa cấu hình |
| A | `PUT` | `/api/services/{serviceCode}/enabled` | 200 `Service` | Bật/tắt |
| A | `DELETE` | `/api/services/{serviceCode}` | 204 | Xóa mềm |
| B | `POST` | `/api/mapping-tests` | 200 | Không ghi dữ liệu |
| C | `POST` | `/callbacks/{serviceCode}` | Do dịch vụ cấu hình | Response không có `X-Simulator-Error` |
| D | `GET` | `/api/transactions` | 200 `Page` | Lọc, phân trang |
| D | `GET` | `/api/transactions/detail` | 200 | Dùng query, không dùng path |
| D | `GET` | `/api/callbacks` | 200 `Page` | Kể cả lần bị từ chối |
| D | `GET` | `/api/callbacks/{recordId}` | 200 `CallbackDetail` | `recordId` là số nguyên |
| E | `GET` | `/actuator/health` | 200 `{ "status": "UP" }` | 503 `DOWN` nếu mất DB |

## 2. Quy ước chung

### 2.1 Định dạng lỗi

Mọi lỗi do ứng dụng sinh ra có dạng:

```json
{
  "error": "VALIDATION_FAILED",
  "message": "Dữ liệu không hợp lệ.",
  "fieldErrors": [
    { "field": "transactionIdPath", "message": "Đường dẫn không hợp lệ." }
  ]
}
```

- `error` là mã ổn định, viết hoa. Dùng `error` để rẽ nhánh, không dùng `message` (chữ có thể đổi).
- `fieldErrors` là **mảng** `{ field, message }`, chỉ có khi `error` là `VALIDATION_FAILED`. Tên `field` trùng tên trường của backend, **không** trùng tên ô của form; FE phải ánh xạ (xem 4.1).
- Mọi response lỗi do ứng dụng sinh ra có header `X-Simulator-Error` bằng đúng trường `error`. **Response đã cấu hình của dịch vụ (nhóm C) không bao giờ có header này**, kể cả khi mã HTTP trùng một mã lỗi. FE dùng header này để phân biệt "simulator từ chối" và "response do người dùng cấu hình".
- `message` không chứa khóa API, đầu vào thô hay dữ liệu nhạy cảm.

| `error` | HTTP | Gặp ở | Gợi ý xử lý FE |
| --- | --- | --- | --- |
| `VALIDATION_FAILED` | 400 | A, B, D | Gắn `fieldErrors` vào từng ô; tham số truy vấn sai (ví dụ `size=10`) cũng ra mã này |
| `UNSUPPORTED_CONTENT_TYPE` | 400 | C | Header `Content-Type` không phải JSON |
| `INVALID_JSON` | 400 | C | Body rỗng hoặc không phải JSON hợp lệ |
| `UNAUTHORIZED` | 401 | C | Thiếu hoặc sai khóa API; thông báo giống nhau cho cả hai |
| `SERVICE_NOT_FOUND` | 404 | A, C | Không có dịch vụ, hoặc đã xóa (với PUT/DELETE) |
| `TRANSACTION_NOT_FOUND` | 404 | D | Hiện "Không tìm thấy giao dịch" |
| `CALLBACK_NOT_FOUND` | 404 | D | Hiện "Không tìm thấy callback" |
| `NOT_FOUND` | 404 | Mọi nơi | Đường dẫn không tồn tại |
| `METHOD_NOT_ALLOWED` | 405 | Mọi nơi | Sai phương thức |
| `SERVICE_CODE_DUPLICATE` | 409 | A | Gắn vào ô mã dịch vụ ("đã tồn tại, kể cả dịch vụ đã xóa") |
| `PAYLOAD_TOO_LARGE` | 413 | C | Body lớn hơn 1.048.576 byte |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | A, B | Yêu cầu quản lý hoặc thử mapping không gửi JSON |
| `INTERNAL_ERROR` | 500 | Mọi nơi | Thông báo chung, cho phép "Thử lại" |
| `SERVICE_DISABLED` | 503 | C | Dịch vụ đang tắt |

Header phụ `X-Simulator-Record-Failed: true` chỉ xuất hiện trên response 400, 401, 404 (dịch vụ đã xóa) và 503 của nhóm C khi lần từ chối cũng không ghi được vào cơ sở dữ liệu.

### 2.2 Phân trang

Áp dụng cho `GET /api/transactions` và `GET /api/callbacks`.

| Tham số | Giá trị | Mặc định |
| --- | --- | --- |
| `page` | số nguyên ≥ 1 | 1 |
| `size` | **chỉ 20, 50 hoặc 100** (giá trị khác trả 400) | 20 |

Phản hồi:

```json
{ "items": [], "page": 1, "size": 20, "totalItems": 134, "totalPages": 7 }
```

Thứ tự ổn định, mới nhất trước: giao dịch sắp theo `latestReceivedAt` rồi `latestCallbackId` giảm dần; callback sắp theo `receivedAt` rồi `recordId` giảm dần.

### 2.3 Lọc và khớp chuỗi

- `serviceCode` trong đường dẫn và tham số khớp **không phân biệt hoa thường**; phản hồi luôn trả đúng chữ gốc lúc tạo.
- `transactionId` (ở danh sách) và `status` khớp theo **chuỗi chứa, không phân biệt hoa thường**. Riêng `transactionId` ở `/api/transactions/detail` khớp **chính xác, phân biệt hoa thường**.
- Giao dịch có `latestStatus` rỗng **không** khớp bộ lọc `status`; không có cách lọc "thiếu status".
- Giá trị tham số rỗng (`receivedFrom=`) được coi như không có; FE nên bỏ hẳn tham số rỗng.

### 2.4 Lọc theo thời gian

`receivedFrom` và `receivedTo` là ISO 8601 **có múi giờ**, tính **cả hai đầu**. Gửi không có phần thập phân giây: đầu "từ" là đầu giây, đầu "đến" là hết giây đó.

- Ví dụ lọc 10:00:00 đến 11:00:00 giờ Việt Nam: `receivedFrom=2026-10-19T03:00:00Z&receivedTo=2026-10-19T04:00:00Z`.
- Dùng đuôi `Z` thì khỏi lo dấu `+` của múi giờ. Nếu dùng `+07:00`, phải mã hóa `+` thành `%2B` (`URLSearchParams` làm đúng việc này).
- Giá trị trả về có thể có tới micro giây (`2026-10-19T03:00:00.123456Z`). Nên cắt về mili giây khi chuyển sang `Date` để không phụ thuộc trình duyệt.

### 2.5 Mã hóa tham số

`transactionId` do ngân hàng đặt có thể chứa `/`, `?`, `#`, `%` hoặc khoảng trắng. Luôn mã hóa phần trăm (`encodeURIComponent`, hoặc `URLSearchParams`) khi đưa vào query hay path của trình duyệt.

## 3. Chạy FE với backend

Backend cấm CORS nên trình duyệt phải gọi cùng nguồn. Khi chạy dev, Vite proxy chuyển tiếp tới backend:

- `/api`: mọi phương thức.
- `/callbacks`: **chỉ `POST`**. Các phương thức khác (`GET` của trình duyệt khi tải lại trang SPA `/callbacks/12`) trả `index.html`, nhờ đó route SPA `callbacks/:callbackId` còn dùng được.
- Đích mặc định `http://127.0.0.1:8080`, không dùng `localhost` vì một số bản Node phân giải sang `::1` trong khi backend chỉ nghe IPv4.

URL callback hiển thị cho người dùng (để cấu hình phía ngân hàng) lấy gốc từ `window.location.origin` cộng `callbackPath` của dịch vụ (ví dụ `/callbacks/THU_HO`).

## 4. Nhóm A — Quản lý dịch vụ

Một "dịch vụ" là một điểm nhận callback có cấu hình riêng: mã, xác thực, đường dẫn mapping, định dạng thời gian và response trả về. Thêm dịch vụ mới chỉ cần cấu hình, không cần sửa mã.

### 4.1 Mô hình dữ liệu

Trường cấu hình (`ServiceConfig`, dùng cho tạo và sửa):

| Trường | Kiểu | Bắt buộc | Quy tắc |
| --- | --- | --- | --- |
| `name` | string | có | 1 đến 100 ký tự |
| `description` | string, null | không | Tối đa 500 ký tự |
| `authType` | `NONE` \| `API_KEY` | có | |
| `apiKeyHeaderName` | string, null | khi `API_KEY` | Tên header HTTP hợp lệ, 1 đến 100 ký tự, không trùng header dành riêng của giao thức. Với `NONE` bị bỏ qua |
| `transactionIdPath` | string | có | Dạng `$.a.b.c`: `$.` rồi 1 đến 10 đoạn nối bằng dấu chấm, mỗi đoạn gồm chữ cái ASCII, chữ số, `_`, `-`; tối đa 200 ký tự. Chỉ đi qua object lồng nhau; **không** wildcard, chỉ số mảng, filter |
| `statusPath` | string | có | Cùng quy tắc |
| `timestampPath` | string, null | không | Cùng quy tắc; vắng hoặc rỗng nghĩa là không đọc timestamp |
| `timestampFormat` | `ISO_8601` \| `UNIX_SECONDS` \| `UNIX_MILLIS` \| `PATTERN` | có | `PATTERN` ở backend tương ứng "Tùy chỉnh" trên giao diện |
| `timestampPattern` | string, null | khi `PATTERN` | Mẫu ngày giờ Java (`DateTimeFormatter`), tối đa 100 ký tự, có đủ năm, tháng, ngày, giờ, phút. Với định dạng khác bị bỏ và không lưu |
| `defaultTimeZone` | string | không | Mã múi giờ Java; mặc định `Asia/Ho_Chi_Minh`. Dùng cho ngày giờ không có múi giờ |
| `responseHttpStatus` | integer | có | 200 đến 599, **không** nhận 204, 205, 304 |
| `responseBody` | **giá trị JSON bất kỳ** | có | Gửi dạng giá trị JSON (object, mảng, chuỗi…), không phải chuỗi chứa JSON; tối đa 64 KB |

Trường chỉ có khi tạo (`ServiceCreate`): `serviceCode` (bắt buộc, 1 đến 50 ký tự theo `^[A-Za-z0-9_-]+$`), `enabled` (mặc định `true`), `apiKeySecret`.

`apiKeySecret` là **chỉ ghi**: 1 đến 256 ký tự ASCII in được, không có khoảng trắng đầu hoặc cuối; bắt buộc khi tạo với `API_KEY`. **Backend không bao giờ trả khóa về.** Khi sửa (`ServiceUpdate`): vắng hoặc rỗng nghĩa là giữ khóa cũ; đổi `authType` sang `API_KEY` mà không gửi khóa thì 400.

Phản hồi `Service` thêm các trường chỉ đọc:

| Trường | Ý nghĩa |
| --- | --- |
| `serviceCode` | Mã, đúng chữ lúc tạo |
| `enabled` | Đang bật |
| `deleted` | Đã xóa mềm |
| `apiKeyConfigured` | Đã có khóa. FE dùng cờ này để hiện "Đã cấu hình khóa / Để trống để giữ khóa hiện tại" |
| `callbackPath` | Ví dụ `/callbacks/THU_HO` |
| `currentVersionNumber` | Số phiên bản cấu hình, chỉ tăng khi cấu hình thật sự đổi (tên, mô tả, bật/tắt không tăng, nhưng gửi `apiKeySecret` mới thì có tăng) |
| `createdAt`, `updatedAt` | ISO 8601 UTC |

Ánh xạ tên trường backend sang tên thường dùng ở form:

| Backend | Form FE |
| --- | --- |
| `serviceCode` | `code` |
| `apiKeyHeaderName` | `apiKeyHeader` |
| `apiKeySecret` | `apiKeyValue` (chỉ ghi) |
| `transactionIdPath`, `statusPath`, `timestampPath` | `mapping.transactionId`, `mapping.status`, `mapping.timestamp` |
| `timestampFormat: PATTERN` | `CUSTOM` |
| `defaultTimeZone` | `timezone` |
| `responseHttpStatus` | `responseStatus` |

Dùng bảng này để đưa `fieldErrors[].field` về đúng ô của form.

```ts
type AuthType = 'NONE' | 'API_KEY'
type TimestampFormat = 'ISO_8601' | 'UNIX_SECONDS' | 'UNIX_MILLIS' | 'PATTERN'

interface Service {
  serviceCode: string
  name: string
  description: string | null
  enabled: boolean
  deleted: boolean
  authType: AuthType
  apiKeyHeaderName: string | null
  apiKeyConfigured: boolean
  transactionIdPath: string
  statusPath: string
  timestampPath: string | null
  timestampFormat: TimestampFormat
  timestampPattern: string | null
  defaultTimeZone: string
  responseHttpStatus: number
  responseBody: unknown
  callbackPath: string
  currentVersionNumber: number
  createdAt: string
  updatedAt: string
}
```

### 4.2 `GET /api/services` — danh sách dịch vụ

- Tham số: `includeDeleted` (`true` hoặc `false`, mặc định `false`; giá trị khác hai giá trị này thì 400). Dùng `true` cho bộ lọc dịch vụ ở màn hình theo dõi, để hiện nhãn "(đã xóa)".
- Phản hồi 200: **mảng** `Service` (không phân trang), sắp theo `serviceCode` tăng dần không phân biệt hoa thường.
- Backend **không có** tham số tìm kiếm hay lọc bật/tắt: FE tải một lần rồi tìm theo mã hoặc tên và lọc phía client.

```bash
curl 'http://localhost:8080/api/services?includeDeleted=true'
```

### 4.3 `POST /api/services` — tạo dịch vụ

- Body: `ServiceCreate`. Cần `Content-Type: application/json`.
- 201: `Service` mới. 400: `VALIDATION_FAILED` với **mọi** lỗi trường cùng lúc. 409: `SERVICE_CODE_DUPLICATE` (không phân biệt hoa thường, kể cả dịch vụ đã xóa). 415: sai Content-Type.

```bash
curl -X POST 'http://localhost:8080/api/services' -H 'Content-Type: application/json' -d '{
  "serviceCode": "THU_HO",
  "name": "Thu hộ",
  "description": "Không xác thực, thời gian ISO 8601",
  "enabled": true,
  "authType": "NONE",
  "transactionIdPath": "$.transId",
  "statusPath": "$.code",
  "timestampPath": "$.time",
  "timestampFormat": "ISO_8601",
  "defaultTimeZone": "Asia/Ho_Chi_Minh",
  "responseHttpStatus": 200,
  "responseBody": { "code": "00" }
}'
```

Ví dụ có khóa API:

```json
{
  "serviceCode": "CHI_HO", "name": "Chi hộ", "enabled": true,
  "authType": "API_KEY", "apiKeyHeaderName": "X-Api-Key", "apiKeySecret": "sample-key-chi-ho",
  "transactionIdPath": "$.processId", "statusPath": "$.status", "timestampPath": "$.date",
  "timestampFormat": "PATTERN", "timestampPattern": "yyyy-MM-dd HH:mm:ss",
  "defaultTimeZone": "Asia/Ho_Chi_Minh", "responseHttpStatus": 200, "responseBody": { "status": "OK" }
}
```

### 4.4 `GET /api/services/{serviceCode}` — đọc một dịch vụ

- 200: `Service`, **kể cả dịch vụ đã xóa** (xem `deleted`). 404: `SERVICE_NOT_FOUND`.
- FE form sửa nên coi dịch vụ `deleted: true` như không tìm thấy.

### 4.5 `PUT /api/services/{serviceCode}` — sửa cấu hình

- Body: `ServiceUpdate` (cùng trường `ServiceConfig`, **không** có `serviceCode`; mã không đổi được). `apiKeySecret` tùy chọn như nói ở 4.1.
- 200: `Service` đã sửa. 400, 404 (`SERVICE_NOT_FOUND`, gồm cả dịch vụ đã xóa), 415, 500.
- Gửi lại đúng cấu hình cũ thì không tạo phiên bản mới. Các yêu cầu sửa, bật/tắt, xóa đồng thời cho cùng dịch vụ được xếp hàng tuần tự.
- Mỗi lần nhận callback giữ lại số phiên bản cấu hình đã dùng, nên sửa cấu hình không làm đổi kết quả của callback cũ.

### 4.6 `PUT /api/services/{serviceCode}/enabled` — bật/tắt

- Body: `{ "enabled": true }` hoặc `{ "enabled": false }`. 200: `Service`.
- Chỉ cập nhật trạng thái trên giao diện **sau khi** API thành công; khi lỗi giữ nguyên trạng thái cũ và báo lỗi.

### 4.7 `DELETE /api/services/{serviceCode}` — xóa mềm

- 204 không có body. Giữ nguyên lịch sử callback. **Mã dịch vụ không dùng lại được**: tạo lại cùng mã (dù khác chữ hoa thường) sẽ 409.
- Nên có hộp thoại xác nhận trước khi gọi.

## 5. Nhóm B — Thử mapping

### `POST /api/mapping-tests`

Thử cấu hình đang nhập (có thể chưa lưu) trên JSON mẫu. **Không ghi dữ liệu**, nên không tạo callback hay giao dịch mới.

Body:

| Trường | Kiểu | Bắt buộc | Ghi chú |
| --- | --- | --- | --- |
| `transactionIdPath` | string | có | |
| `statusPath` | string | có | |
| `timestampPath` | string, null | không | |
| `timestampFormat` | enum như 4.1 | có | |
| `timestampPattern` | string, null | khi `PATTERN` | |
| `defaultTimeZone` | string | không | Mặc định `Asia/Ho_Chi_Minh` |
| `sampleJson` | **string** | có | JSON mẫu **dưới dạng chuỗi** (không phải object), tối đa 1 MiB |

Phản hồi 200:

```json
{
  "sampleJsonValid": true,
  "sampleJsonError": null,
  "transactionId": { "value": "THUHO-0001", "errorCode": null, "error": null, "warning": null },
  "status":        { "value": "00",         "errorCode": null, "error": null, "warning": null },
  "timestamp":     { "value": "2026-10-19T03:00:00Z", "errorCode": null, "error": null, "warning": null }
}
```

- Cấu hình sai cú pháp (ví dụ đường dẫn không hợp lệ) thì 400 `VALIDATION_FAILED`.
- **JSON mẫu hỏng thì vẫn 200** với `sampleJsonValid: false` và `sampleJsonError`; FE hiện "JSON mẫu không hợp lệ" ở khối này, không coi là lỗi mạng.
- Mỗi trường là `FieldResult`: `value` (chuỗi; timestamp là ISO 8601 UTC), `errorCode` (`NOT_FOUND`, `EMPTY`, `WRONG_TYPE`, `TOO_LONG`, `PAYLOAD_NOT_OBJECT`, `UNPARSEABLE` hoặc `null`), `error` (câu mô tả cho người đọc), `warning` (chỉ cho timestamp đã cấu hình đường dẫn mà không có giá trị). Một trường lỗi không làm hai trường còn lại mất kết quả.
- Nên hiện chữ "kết quả đã cũ" khi người dùng đổi cấu hình hoặc JSON mẫu sau lần thử.

```bash
curl -X POST 'http://localhost:8080/api/mapping-tests' -H 'Content-Type: application/json' -d '{
  "transactionIdPath": "$.transId", "statusPath": "$.code", "timestampPath": "$.time",
  "timestampFormat": "ISO_8601", "defaultTimeZone": "Asia/Ho_Chi_Minh",
  "sampleJson": "{\"transId\":\"THUHO-0001\",\"code\":\"00\",\"time\":\"2026-10-19T10:00:00+07:00\"}"
}'
```

## 6. Nhóm C — Nhận callback (dùng cho "gửi thử")

### `POST /callbacks/{serviceCode}`

Đây là endpoint mà hệ thống ngân hàng gọi tới. Màn hình "Gửi callback thử" gọi chính endpoint này (không có endpoint riêng).

- Body: bất kỳ giá trị JSON hợp lệ, tối đa 1.048.576 byte. Cần `Content-Type: application/json` (tham số như `charset=UTF-8` được chấp nhận).
- Header khóa API: chỉ khi dịch vụ dùng `API_KEY`, tên header theo `apiKeyHeaderName` (so tên không phân biệt hoa thường, so giá trị **phân biệt** hoa thường; header lặp lại tính là sai).
- Response thành công: **mã HTTP và body do dịch vụ cấu hình** (`responseHttpStatus`, `responseBody`), trả cả khi mapping lỗi. Không có header `X-Simulator-Error`.

Thứ tự kiểm tra cố định, dừng ở lỗi đầu tiên:

| Thứ tự | Điều kiện | Kết quả | Có ghi lịch sử không |
| --- | --- | --- | --- |
| 1 | Body > 1.048.576 byte | 413 `PAYLOAD_TOO_LARGE` | Không |
| 2 | Mã dịch vụ chưa từng có | 404 `SERVICE_NOT_FOUND` | Không |
| 3 | Dịch vụ đã xóa | 404 `SERVICE_NOT_FOUND` | Có (`SERVICE_DELETED`) |
| 4 | Dịch vụ đang tắt | 503 `SERVICE_DISABLED` | Có |
| 5 | Dịch vụ `API_KEY` thiếu hoặc sai khóa | 401 `UNAUTHORIZED` | Có (`MISSING_API_KEY` hoặc `INVALID_API_KEY`) |
| 6 | Content-Type không phải JSON | 400 `UNSUPPORTED_CONTENT_TYPE` | Có |
| 7 | JSON rỗng hoặc hỏng | 400 `INVALID_JSON` | Có |
| 8 | Còn lại | Response đã cấu hình | Có, kèm giao dịch |

"Lần nhận bị từ chối" (các dòng 3 đến 7) được lưu thành callback `REJECTED` và hiện ở tab "Tất cả callback" với lý do, nhưng không thuộc giao dịch nào.

Lưu ý cho màn hình gửi thử:

- Response **không có** header `X-Callback-Id` và không trả mã bản ghi. Sau khi gửi, chỉ liên kết sang màn hình theo dõi đã lọc (`/transactions?tab=callbacks&service=<mã>`).
- Đo thời gian (ms) ở trình duyệt, từ lúc gửi tới lúc nhận đủ body.
- Ô khóa API của trang gửi thử để trống, người dùng tự nhập; xóa giá trị ngay sau khi gửi; không lưu vào state dài hạn.
- Đọc header `X-Simulator-Error`: có thì "do simulator từ chối", không có thì "response đã cấu hình của dịch vụ".

```bash
curl -i -X POST 'http://localhost:8080/callbacks/THU_HO' -H 'Content-Type: application/json' \
  -d '{"transId":"THUHO-0001","code":"00","time":"2026-10-19T10:00:00+07:00"}'

curl -i -X POST 'http://localhost:8080/callbacks/CHI_HO' -H 'Content-Type: application/json' -H 'X-Api-Key: sample-key-chi-ho' \
  -d '{"processId":"CHIHO-0001","status":"SUCCESS","date":"2026-10-19 10:00:00"}'
```

## 7. Nhóm D — Tra cứu giao dịch và callback

**Giao dịch** là một cặp (`serviceCode`, `transactionId`), gom các callback cùng cặp, trạng thái lấy từ **lần nhận gần nhất** (không dựa vào timestamp nghiệp vụ). Callback thiếu hoặc lỗi `transactionId` có trong danh sách callback nhưng không thuộc giao dịch nào.

### 7.1 `GET /api/transactions` — danh sách giao dịch

| Tham số | Kiểu | Ghi chú |
| --- | --- | --- |
| `serviceCode` | string | Khớp không phân biệt hoa thường |
| `transactionId` | string | Chuỗi chứa, không phân biệt hoa thường |
| `status` | string | Chuỗi chứa, không phân biệt hoa thường |
| `receivedFrom`, `receivedTo` | ISO 8601 có múi giờ | Lọc theo thời điểm nhận **gần nhất** của giao dịch, tính cả hai đầu |
| `page`, `size` | | Xem 2.2 |

Phần tử `TransactionSummary`:

```json
{
  "serviceCode": "THU_HO",
  "serviceDeleted": false,
  "transactionId": "THUHO-0001",
  "latestStatus": "00",
  "latestBusinessTimestamp": "2026-10-19T03:00:00Z",
  "latestReceivedAt": "2026-10-26T06:21:09.123456Z",
  "latestCallbackId": 12,
  "latestHasMappingError": false,
  "callbackCount": 3
}
```

`latestStatus` là `null` khi callback gần nhất thiếu hoặc lỗi status: hiện nhãn chữ "Thiếu status", không dùng giá trị của callback trước. Hiển thị status nghiệp vụ bằng màu **trung tính** (không diễn giải `00` hay `FAILED`).

```bash
curl 'http://localhost:8080/api/transactions?serviceCode=THU_HO&status=0&page=1&size=20'
```

### 7.2 `GET /api/transactions/detail` — chi tiết giao dịch

- Query bắt buộc: `serviceCode`, `transactionId` (cả hai mã hóa phần trăm).
- 200: `{ "transaction": TransactionSummary, "callbacks": CallbackDetail[] }`, `callbacks` theo **thứ tự nhận, cũ nhất trước**. Không đặt trần số lần nhận; mỗi lần có thể chứa tới 1 MiB payload.
- 404: `TRANSACTION_NOT_FOUND`.

```bash
curl -G 'http://localhost:8080/api/transactions/detail' --data-urlencode 'serviceCode=THU_HO' --data-urlencode 'transactionId=THUHO-0001'
```

### 7.3 `GET /api/callbacks` — danh sách mọi lần nhận

| Tham số | Kiểu | Ghi chú |
| --- | --- | --- |
| `serviceCode` | string | |
| `transactionId` | string | Chuỗi chứa, không phân biệt hoa thường |
| `outcome` | `SUCCESS` \| `MAPPING_ERROR` \| `REJECTED` | Kết quả xử lý |
| `rejectionReason` | xem bảng dưới | Giá trị ngoài danh sách trả 400 |
| `receivedFrom`, `receivedTo` | ISO 8601 có múi giờ | Theo `receivedAt` |
| `page`, `size` | | Xem 2.2 |

`rejectionReason`: `INVALID_API_KEY` (sai khóa), `MISSING_API_KEY` (thiếu khóa), `INVALID_JSON` (JSON không hợp lệ), `UNSUPPORTED_CONTENT_TYPE` (Content-Type không hỗ trợ), `SERVICE_DISABLED` (dịch vụ tắt), `SERVICE_DELETED` (dịch vụ đã xóa); `null` khi không bị từ chối. Trên giao diện chỉ cho chọn "Lý do" khi "Kết quả xử lý" là "Bị từ chối".

Phần tử `CallbackSummary`:

```json
{
  "recordId": 12,
  "serviceCode": "CHI_HO",
  "serviceDeleted": false,
  "receivedAt": "2026-10-26T06:21:09.123456Z",
  "outcome": "REJECTED",
  "rejectionReason": "INVALID_API_KEY",
  "httpStatusReturned": 401,
  "transactionId": null,
  "status": null,
  "businessTimestamp": null
}
```

### 7.4 `GET /api/callbacks/{recordId}` — chi tiết một lần nhận

- `recordId` là **số nguyên** (không phải chuỗi dạng `CB-001`). Tham số không phải số thì FE nên hiện "Không tìm thấy" mà không gọi API. 404: `CALLBACK_NOT_FOUND`.
- 200: `CallbackDetail` = `CallbackSummary` cộng:

| Trường | Ý nghĩa |
| --- | --- |
| `configVersionNumber` | Số phiên bản cấu hình đã dùng (so với `currentVersionNumber` của dịch vụ). **Không** có bản chụp cấu hình đầy đủ |
| `requestHeaders` | **Object** `{ tên: giá trị }`. Header nhạy cảm (`Authorization`, `Proxy-Authorization`, `Cookie`, header khóa API của dịch vụ) có giá trị `***` |
| `payloadJson` | Payload JSON đã chuẩn hóa (số `1e3` có thể thành `1000`, thứ tự khóa không giữ) |
| `payloadRawText` | Body văn bản gốc khi **không** phải JSON hợp lệ |
| `mappingFieldResults` | `{ transactionId, status, timestamp }`, mỗi trường là `FieldResult`; **rỗng `{}`** với lần bị từ chối vì chưa chạy mapping |
| `responseStatusReturned` | Mã HTTP đã trả (có thể `null`) |
| `responseBodyReturned` | Body đã trả (vắng khi lỗi ghi) |

Mỗi lần nhận có đúng **một** trong `payloadJson` hoặc `payloadRawText`. `payloadJson` vắng `payloadRawText` nghĩa là payload ở `payloadJson`, kể cả JSON `null`. Body UTF-8 hỏng được lưu với ký tự thay thế U+FFFD.

Backend **không trả** giá trị gốc của timestamp, thời gian xử lý hay nguồn gửi (portal/ngân hàng). Muốn hiện giá trị gốc của timestamp, đọc từ payload.

Không dùng `dangerouslySetInnerHTML` cho payload và header: dữ liệu do hệ thống bên ngoài gửi tới, phải render dạng văn bản.

```bash
curl 'http://localhost:8080/api/callbacks/12'
```

```ts
type CallbackOutcome = 'SUCCESS' | 'MAPPING_ERROR' | 'REJECTED'
type RejectionReason =
  | 'INVALID_API_KEY' | 'MISSING_API_KEY' | 'INVALID_JSON'
  | 'UNSUPPORTED_CONTENT_TYPE' | 'SERVICE_DISABLED' | 'SERVICE_DELETED'

interface Page<T> { items: T[]; page: number; size: number; totalItems: number; totalPages: number }

interface FieldResult {
  value: string | null
  errorCode: 'NOT_FOUND' | 'EMPTY' | 'WRONG_TYPE' | 'TOO_LONG' | 'PAYLOAD_NOT_OBJECT' | 'UNPARSEABLE' | null
  error: string | null
  warning: string | null
}

interface TransactionSummary {
  serviceCode: string; serviceDeleted?: boolean; transactionId: string
  latestStatus: string | null; latestBusinessTimestamp: string | null
  latestReceivedAt: string; latestCallbackId: number
  latestHasMappingError: boolean; callbackCount: number
}

interface CallbackSummary {
  recordId: number; serviceCode: string; serviceDeleted?: boolean; receivedAt: string
  outcome: CallbackOutcome; rejectionReason: RejectionReason | null
  httpStatusReturned: number
  transactionId: string | null; status: string | null; businessTimestamp: string | null
}

interface CallbackDetail extends CallbackSummary {
  configVersionNumber: number | null
  requestHeaders: Record<string, string>
  payloadJson?: unknown
  payloadRawText?: string
  mappingFieldResults: Partial<Record<'transactionId' | 'status' | 'timestamp', FieldResult>>
  responseStatusReturned: number | null
  responseBodyReturned?: unknown
}
```

## 8. Nhóm E — Vận hành

`GET /actuator/health`: 200 `{ "status": "UP" }` khi ứng dụng chạy và kết nối được cơ sở dữ liệu; 503 `{ "status": "DOWN" }` khi mất kết nối. Đây là endpoint Actuator duy nhất được mở; các endpoint Actuator khác trả 404 `NOT_FOUND`.

## 9. Gợi ý tích hợp theo màn hình

| Màn hình | Gọi | Việc FE cần làm |
| --- | --- | --- |
| S1 Danh sách dịch vụ | `GET /api/services`, `PUT .../enabled`, `DELETE ...` | Tải một lần, tìm và lọc phía client; ghép URL callback từ gốc trang và `callbackPath`; bật/tắt chỉ đổi giao diện sau khi API thành công; xóa có xác nhận; phân biệt trạng thái rỗng và lỗi tải |
| S2 Cấu hình dịch vụ | `GET /api/services/{code}`, `POST /api/services`, `PUT /api/services/{code}`, `POST /api/mapping-tests` | Ô mã chỉ đọc khi sửa; ô khóa: tạo mới bắt buộc, sửa để trống là giữ khóa cũ; ánh xạ `fieldErrors` về từng ô (bảng 4.1); 409 gắn vào ô mã; `responseBody` gửi dạng giá trị JSON |
| S3 Gửi callback thử | `GET /api/services/{code}`, `POST /callbacks/{code}` | Ô khóa để trống; hiện mã HTTP, body, thời gian ms, `X-Simulator-Error`; giải thích 413; liên kết sang S4 đã lọc |
| S4 Theo dõi | `GET /api/transactions`, `GET /api/callbacks`, `GET /api/services?includeDeleted=true` | Bộ lọc, trang và cỡ trang nằm trong URL; mỗi tab nhớ bộ lọc riêng; tự cập nhật; xem 9.1 |
| S5 Chi tiết | `GET /api/transactions/detail`, `GET /api/callbacks/{recordId}` | Lịch sử theo thứ tự nhận; payload JSON hoặc văn bản gốc; header đã che; mapping từng trường; xem 9.2 |

### 9.1 Tự cập nhật (polling)

- Nhịp mặc định 5 giây, có công tắc bật/tắt và nút "Tải lại".
- Dùng `AbortController`: thao tác của người dùng (Áp dụng, đổi trang, đổi tab, Tải lại) hủy yêu cầu đang chạy và chỉ nhận kết quả mới nhất.
- **Bỏ qua nhịp** khi một lần tải đang chạy, không để hai yêu cầu chồng nhau.
- Khi tải lỗi: giữ dữ liệu cũ, hiện banner kèm giờ cập nhật thành công gần nhất; nhịp sau thành công thì xóa banner.
- Cập nhật tại chỗ, không đổi trang, bộ lọc, vị trí cuộn.
- Chỉ poll tab đang xem.

### 9.2 Chi tiết

- Chi tiết giao dịch nên tự cập nhật cùng nhịp; lần nhận đang chọn không tự nhảy; khi có lần nhận mới hiện "Có N callback mới" kèm nút chọn lần mới nhất.
- Lần bị từ chối: hiện lý do bằng chữ, `httpStatusReturned`, body gốc (nếu `payloadRawText`) và ghi chú "Chưa chạy mapping vì request bị từ chối" (khi `mappingFieldResults` rỗng).
- Dịch vụ của lần nhận có thể đã xóa (`serviceDeleted: true`): hiện nhãn "(đã xóa)".

### 9.3 Bảo mật phía giao diện

- Không bao giờ hiển thị, lưu hay đưa khóa API vào URL, log, thông báo lỗi. Form sửa chỉ biết `apiKeyConfigured`.
- Header đã che do backend làm; FE chỉ đánh dấu giá trị `***` là đã che.
- Thông báo lỗi lấy từ `message` của backend, không ghép nội dung yêu cầu.

## 10. Chạy thử toàn bộ luồng bằng cURL

Chạy theo thứ tự (backend chạy ở `localhost:8080`):

1. Tạo dịch vụ `THU_HO` (4.3).
2. Thử mapping (mục 5).
3. Gửi callback hợp lệ và callback lỗi timestamp tới `/callbacks/THU_HO` (mục 6).
4. `GET /api/transactions?serviceCode=THU_HO` và `GET /api/transactions/detail?serviceCode=THU_HO&transactionId=THUHO-0001` (7.1, 7.2).
5. Tạo `CHI_HO`, gửi thiếu khóa và sai khóa, rồi `GET /api/callbacks?outcome=REJECTED` để thấy lần từ chối (7.3).
6. Tắt `THU_HO` (4.6), gửi callback để thấy 503, rồi `GET /api/callbacks?rejectionReason=SERVICE_DISABLED`.

Các payload và cấu hình mẫu đầy đủ cho ba dịch vụ nằm trong `tpp-simulator-app/docs/samples/` (`*.service.json`, `*.payload-valid.json`, `*.payload-faulty.json`).

## 11. Điểm cần xác nhận khi chạy với backend thật

- Tên trường trong `fieldErrors[].field` cho từng ô của form (bảng ánh xạ 4.1 dựng từ hợp đồng, chưa đối chiếu từng lỗi thực tế).
- Thông báo `message` cụ thể của từng mã lỗi.
- Hành vi `GET /api/services/{code}` với dịch vụ đã xóa khi `PUT` (docs ghi 404 `SERVICE_NOT_FOUND`).
- Thời gian phản hồi của `GET /api/transactions` và `GET /api/callbacks` khi có 100.000 callback (mục tiêu NFR1 dưới 1 giây, đo ở Build and Test).
