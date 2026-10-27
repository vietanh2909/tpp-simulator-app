# Dịch vụ mẫu và lệnh cURL

Ba dịch vụ mẫu, mỗi dịch vụ một tệp cấu hình (`<mã>.service.json`, đúng thân của `POST /api/services`) và hai payload
thử (`<mã>.payload-valid.json` ánh xạ đúng ba trường, `<mã>.payload-faulty.json` thiếu hoặc sai một trường):

| Dịch vụ | Xác thực | Định dạng thời gian | Đường dẫn (transactionId / status / timestamp) | Response |
|---|---|---|---|---|
| `THU_HO` | `NONE` | `ISO_8601` | `$.transId` / `$.code` / `$.time` | 200 `{"code":"00"}` |
| `CHI_HO` | `API_KEY`, header `X-Api-Key`, khóa mẫu `sample-key-chi-ho` | `PATTERN` `yyyy-MM-dd HH:mm:ss`, `Asia/Ho_Chi_Minh` | `$.processId` / `$.status` / `$.date` | 200 `{"status":"OK"}` |
| `BIEN_DONG_SO_DU` | `NONE` | `UNIX_MILLIS` | `$.trnId` / `$.stage` / `$.dateTime` | 202 `{"received":true}` |

> **Khóa `sample-key-chi-ho` là giá trị giả công khai, chỉ dùng để thử.** Không bao giờ dùng khóa thật trong tệp mẫu.
> Ứng dụng **không** tự tạo dịch vụ nào lúc khởi động; dịch vụ chỉ có sau khi bạn chạy script hoặc lệnh dưới đây.

## Tạo ba dịch vụ

Chạy lại được: dịch vụ đã có thì script báo "đã tồn tại" và tiếp tục (mã thoát 0).

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\create-sample-services.ps1
```

```bash
scripts/create-sample-services.sh
```

Đổi địa chỉ backend bằng `-BaseUrl http://127.0.0.1:9090` (PowerShell) hoặc tham số đầu tiên (bash). Hoặc tạo từng dịch
vụ bằng tay:

```powershell
curl.exe -i -X POST http://127.0.0.1:8080/api/services -H "Content-Type: application/json" --data-binary "@docs/samples/THU_HO.service.json"
```

## Gửi payload mẫu (PowerShell, dùng `curl.exe`)

Chạy từ thư mục `tpp-simulator-app`.

```powershell
# THU_HO: payload hợp lệ -> 200 {"code":"00"}, kết quả SUCCESS, timestamp 2026-10-19T03:00:00Z
curl.exe -i -X POST http://127.0.0.1:8080/callbacks/THU_HO -H "Content-Type: application/json" --data-binary "@docs/samples/THU_HO.payload-valid.json"
# THU_HO: payload lỗi (thiếu code, ngày sai) -> vẫn 200 theo cấu hình, kết quả MAPPING_ERROR
curl.exe -i -X POST http://127.0.0.1:8080/callbacks/THU_HO -H "Content-Type: application/json" --data-binary "@docs/samples/THU_HO.payload-faulty.json"

# CHI_HO: khóa đúng -> 200 {"status":"OK"}
curl.exe -i -X POST http://127.0.0.1:8080/callbacks/CHI_HO -H "Content-Type: application/json" -H "X-Api-Key: sample-key-chi-ho" --data-binary "@docs/samples/CHI_HO.payload-valid.json"
# CHI_HO: khóa sai -> 401, header X-Simulator-Error: UNAUTHORIZED (lần nhận vẫn được ghi)
curl.exe -i -X POST http://127.0.0.1:8080/callbacks/CHI_HO -H "Content-Type: application/json" -H "X-Api-Key: sai" --data-binary "@docs/samples/CHI_HO.payload-valid.json"
# CHI_HO: payload lỗi (status là object, ngày 30/2) -> 200, MAPPING_ERROR
curl.exe -i -X POST http://127.0.0.1:8080/callbacks/CHI_HO -H "Content-Type: application/json" -H "X-Api-Key: sample-key-chi-ho" --data-binary "@docs/samples/CHI_HO.payload-faulty.json"

# BIEN_DONG_SO_DU: hợp lệ -> 202 {"received":true}, timestamp 2025-10-19T05:00:00Z
curl.exe -i -X POST http://127.0.0.1:8080/callbacks/BIEN_DONG_SO_DU -H "Content-Type: application/json" --data-binary "@docs/samples/BIEN_DONG_SO_DU.payload-valid.json"
# BIEN_DONG_SO_DU: lỗi (thiếu trnId, dateTime là "abc") -> 202, MAPPING_ERROR, không vào giao dịch nào
curl.exe -i -X POST http://127.0.0.1:8080/callbacks/BIEN_DONG_SO_DU -H "Content-Type: application/json" --data-binary "@docs/samples/BIEN_DONG_SO_DU.payload-faulty.json"
```

## Gửi payload mẫu (bash, dùng `curl`)

```bash
curl -i -X POST http://127.0.0.1:8080/callbacks/THU_HO -H "Content-Type: application/json" --data-binary @docs/samples/THU_HO.payload-valid.json
curl -i -X POST http://127.0.0.1:8080/callbacks/THU_HO -H "Content-Type: application/json" --data-binary @docs/samples/THU_HO.payload-faulty.json
curl -i -X POST http://127.0.0.1:8080/callbacks/CHI_HO -H "Content-Type: application/json" -H "X-Api-Key: sample-key-chi-ho" --data-binary @docs/samples/CHI_HO.payload-valid.json
curl -i -X POST http://127.0.0.1:8080/callbacks/CHI_HO -H "Content-Type: application/json" -H "X-Api-Key: sai" --data-binary @docs/samples/CHI_HO.payload-valid.json
curl -i -X POST http://127.0.0.1:8080/callbacks/CHI_HO -H "Content-Type: application/json" -H "X-Api-Key: sample-key-chi-ho" --data-binary @docs/samples/CHI_HO.payload-faulty.json
curl -i -X POST http://127.0.0.1:8080/callbacks/BIEN_DONG_SO_DU -H "Content-Type: application/json" --data-binary @docs/samples/BIEN_DONG_SO_DU.payload-valid.json
curl -i -X POST http://127.0.0.1:8080/callbacks/BIEN_DONG_SO_DU -H "Content-Type: application/json" --data-binary @docs/samples/BIEN_DONG_SO_DU.payload-faulty.json
```

## Xem kết quả

```powershell
# Mọi lần nhận, kể cả bị từ chối (mới nhất trước)
curl.exe "http://127.0.0.1:8080/api/callbacks"
# Chỉ các lần bị từ chối vì sai khóa
curl.exe -G http://127.0.0.1:8080/api/callbacks --data-urlencode "outcome=REJECTED" --data-urlencode "rejectionReason=INVALID_API_KEY"
# Chi tiết một lần nhận
curl.exe http://127.0.0.1:8080/api/callbacks/1
# Giao dịch
curl.exe "http://127.0.0.1:8080/api/transactions?serviceCode=THU_HO"
```

`UNIX_SECONDS` không có dịch vụ mẫu riêng; định dạng này được phủ bằng kiểm thử tự động.
