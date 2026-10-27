#!/usr/bin/env bash
# Tạo ba dịch vụ mẫu (THU_HO, CHI_HO, BIEN_DONG_SO_DU) bằng API; chạy lại được (BR21.4).
#
# Với mỗi tệp docs/samples/*.service.json gọi POST /api/services bằng đúng byte của tệp.
#   201 -> "đã tạo"
#   409 -> "đã tồn tại (hoặc đã bị xóa)" rồi tiếp tục
#   mã khác hoặc không kết nối được -> ghi nhận lỗi, vẫn thử các dịch vụ còn lại
# Mã thoát 0 khi mọi dịch vụ đã tạo hoặc đã tồn tại; 1 khi có lỗi khác.
# Script không sửa, không xóa dịch vụ nào và không cần jq.
#
# Dùng:  scripts/create-sample-services.sh [địa-chỉ-backend] [thư-mục-mẫu]
# Mặc định: http://127.0.0.1:8080 và docs/samples cạnh thư mục chứa script.

set -u

BASE_URL="${1:-http://127.0.0.1:8080}"
BASE_URL="${BASE_URL%/}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SAMPLES_DIR="${2:-$SCRIPT_DIR/../docs/samples}"

shopt -s nullglob
files=("$SAMPLES_DIR"/*.service.json)
if [ "${#files[@]}" -eq 0 ]; then
  echo "Không thấy tệp *.service.json nào trong $SAMPLES_DIR"
  exit 1
fi

failed=0
for file in "${files[@]}"; do
  name="$(basename "$file")"
  code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/services" \
    -H 'Content-Type: application/json; charset=utf-8' --data-binary "@$file")"
  case "$code" in
    201) echo "[đã tạo]    $name" ;;
    409) echo "[đã tồn tại (hoặc đã bị xóa)] $name" ;;
    *)
      failed=$((failed + 1))
      echo "[LỖI]       $name: HTTP $code (000 nghĩa là không kết nối được)"
      ;;
  esac
done

if [ "$failed" -gt 0 ]; then
  echo "$failed dịch vụ lỗi."
  exit 1
fi
echo "Xong: cả ba dịch vụ mẫu đã sẵn sàng."
exit 0
