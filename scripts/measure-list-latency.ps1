<#
.SYNOPSIS
  Đo độ trễ của MỘT kịch bản danh sách (NFR1.21): GET /api/transactions?page=1&size=20, không lọc.

.DESCRIPTION
  Chạy tay sau khi đã nạp 100.000 callback (công cụ nạp, xem README) và khởi động backend trên
  database đó. 20 lần khởi động nóng bị bỏ qua, rồi 200 lần tuần tự được tính. p50, p95, p99 theo
  hạng gần nhất trên danh sách sắp tăng dần (p95 là giá trị thứ 190). Mã thoát 0 khi mọi lần gọi
  trả 200 và p95 dưới 1000 ms; 1 khi p95 từ 1000 ms trở lên, có phản hồi không phải 200, hoặc
  không kết nối được.

  CHỈ MỘT KỊCH BẢN ĐƯỢC ĐO. KHÔNG ĐO (khoảng trống đã chấp nhận): lọc theo transactionId hoặc status
  (chứa chuỗi), lọc serviceCode kèm khoảng thời gian, lọc outcome hoặc rejectionReason, trang sâu,
  size=50 hoặc 100, toàn bộ GET /api/callbacks và GET /api/callbacks/{recordId}.

.PARAMETER BaseUrl
  Địa chỉ backend. Mặc định http://127.0.0.1:8080

.PARAMETER Samples
  Số lần đo được tính. Mặc định 200.

.PARAMETER Warmup
  Số lần khởi động nóng bị bỏ qua. Mặc định 20.

.PARAMETER PostgresVersion
  Chuỗi phiên bản PostgreSQL để in vào báo cáo. Không có thì thử "psql --version" (đó là phiên bản
  MÁY KHÁCH, không chắc là của máy chủ).
#>
[CmdletBinding()]
param(
  [string]$BaseUrl = 'http://127.0.0.1:8080',
  [int]$Samples = 200,
  [int]$Warmup = 20,
  [string]$PostgresVersion = ''
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$ThresholdMs = 1000

Add-Type -AssemblyName System.Net.Http
$handler = New-Object System.Net.Http.HttpClientHandler
$handler.UseProxy = $false
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(30)

function Get-Percentile {
  param([double[]]$Sorted, [double]$Fraction)
  $rank = [int][Math]::Ceiling($Fraction * $Sorted.Length)
  if ($rank -lt 1) { $rank = 1 }
  return $Sorted[$rank - 1]
}

try {
  Write-Host 'Kịch bản duy nhất được đo: GET /api/transactions?page=1&size=20 (không bộ lọc).'
  Write-Host 'KHÔNG đo: lọc transactionId/status, serviceCode kèm thời gian, outcome/rejectionReason, trang sâu, size 50/100, GET /api/callbacks.'

  $cpu = Get-WmiObject -Class Win32_Processor | Select-Object -First 1
  $computer = Get-WmiObject -Class Win32_ComputerSystem
  $os = Get-WmiObject -Class Win32_OperatingSystem
  Write-Host ('Máy: CPU={0}; nhân={1}; RAM={2:N1} GB; HĐH={3}' -f $cpu.Name.Trim(), $cpu.NumberOfCores, ($computer.TotalPhysicalMemory / 1GB), $os.Caption)
  if (-not $PostgresVersion) {
    try {
      $PostgresVersion = (& psql --version 2>&1 | Select-Object -First 1).ToString() + ' (phiên bản máy khách psql)'
    } catch {
      $PostgresVersion = 'không xác định (truyền -PostgresVersion)'
    }
  }
  Write-Host ('PostgreSQL: ' + $PostgresVersion)

  $count = $client.GetAsync($BaseUrl + '/api/callbacks?page=1&size=20').GetAwaiter().GetResult()
  if ([int]$count.StatusCode -ne 200) {
    Write-Host ('Không đọc được tổng số callback: HTTP {0}' -f [int]$count.StatusCode)
    exit 1
  }
  $total = ($count.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json).totalItems
  Write-Host ('callback trong database = {0}' -f $total)
  if ($total -ne 100000) {
    Write-Host 'CẢNH BÁO: database không phải bộ dữ liệu nạp chuẩn 100.000 callback (NFR1.27); vẫn đo.'
  }

  $url = $BaseUrl + '/api/transactions?page=1&size=20'
  $failures = 0
  for ($i = 0; $i -lt $Warmup; $i++) {
    $response = $client.GetAsync($url).GetAwaiter().GetResult()
    [void]$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
  }
  $millis = New-Object System.Collections.ArrayList
  for ($i = 0; $i -lt $Samples; $i++) {
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $response = $client.GetAsync($url).GetAwaiter().GetResult()
    [void]$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $watch.Stop()
    if ([int]$response.StatusCode -ne 200) { $failures++ }
    [void]$millis.Add($watch.Elapsed.TotalMilliseconds)
  }
  $sorted = [double[]]($millis.ToArray() | Sort-Object)
  $p50 = Get-Percentile -Sorted $sorted -Fraction 0.50
  $p95 = Get-Percentile -Sorted $sorted -Fraction 0.95
  $p99 = Get-Percentile -Sorted $sorted -Fraction 0.99
  $max = $sorted[$sorted.Length - 1]
  Write-Host ('{0} lần đo: p50={1:N1} ms  p95={2:N1} ms  p99={3:N1} ms  max={4:N1} ms; phản hồi không phải 200: {5}' -f $Samples, $p50, $p95, $p99, $max, $failures)
  if ($failures -gt 0 -or $p95 -ge $ThresholdMs) {
    Write-Host ('KHÔNG ĐẠT: cần mọi phản hồi 200 và p95 < {0} ms.' -f $ThresholdMs)
    exit 1
  }
  Write-Host ('ĐẠT: p95 < {0} ms.' -f $ThresholdMs)
  exit 0
} catch {
  Write-Host ('Lỗi: ' + $_.Exception.Message)
  exit 1
} finally {
  $client.Dispose()
}
