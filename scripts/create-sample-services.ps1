<#
.SYNOPSIS
  Tạo ba dịch vụ mẫu (THU_HO, CHI_HO, BIEN_DONG_SO_DU) bằng API; chạy lại được (BR21.4).

.DESCRIPTION
  Với mỗi tệp docs/samples/*.service.json gọi POST /api/services bằng đúng byte UTF-8 của tệp.
    201 -> "đã tạo"
    409 -> "đã tồn tại (hoặc đã bị xóa)" rồi tiếp tục (với POST này 409 chỉ có thể là trùng mã)
    mã khác hoặc không kết nối được -> ghi nhận lỗi, vẫn thử các dịch vụ còn lại
  Mã thoát 0 khi mọi dịch vụ đã tạo hoặc đã tồn tại; 1 khi có lỗi khác. Script không sửa và không
  xóa dịch vụ nào. Yêu cầu Windows PowerShell 5.1; backend phải đang chạy.

.PARAMETER BaseUrl
  Địa chỉ backend. Mặc định http://127.0.0.1:8080

.PARAMETER SamplesDir
  Thư mục chứa *.service.json. Mặc định docs/samples cạnh thư mục chứa script.
#>
[CmdletBinding()]
param(
  [string]$BaseUrl = 'http://127.0.0.1:8080',
  [string]$SamplesDir = ''
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
if (-not $SamplesDir) {
  $SamplesDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'docs\samples'
}

Add-Type -AssemblyName System.Net.Http
$handler = New-Object System.Net.Http.HttpClientHandler
$handler.UseProxy = $false
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(30)

$files = @(Get-ChildItem -Path $SamplesDir -Filter '*.service.json' | Sort-Object Name)
if ($files.Count -eq 0) {
  Write-Host ('Không thấy tệp *.service.json nào trong ' + $SamplesDir)
  exit 1
}

$failed = 0
foreach ($file in $files) {
  $name = $file.Name
  try {
    $bytes = [System.IO.File]::ReadAllBytes($file.FullName)
    $content = New-Object System.Net.Http.ByteArrayContent(,$bytes)
    $content.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('application/json; charset=utf-8')
    $response = $client.PostAsync($BaseUrl + '/api/services', $content).GetAwaiter().GetResult()
    $status = [int]$response.StatusCode
    if ($status -eq 201) {
      Write-Host ('[đã tạo]    ' + $name)
    } elseif ($status -eq 409) {
      Write-Host ('[đã tồn tại (hoặc đã bị xóa)] ' + $name)
    } else {
      $failed++
      Write-Host ('[LỖI]       {0}: HTTP {1}' -f $name, $status)
    }
  } catch {
    $failed++
    Write-Host ('[LỖI]       {0}: {1}' -f $name, $_.Exception.Message)
  }
}
$client.Dispose()
if ($failed -gt 0) {
  Write-Host ('{0} dịch vụ lỗi.' -f $failed)
  exit 1
}
Write-Host 'Xong: cả ba dịch vụ mẫu đã sẵn sàng.'
exit 0
