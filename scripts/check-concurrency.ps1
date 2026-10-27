<#
.SYNOPSIS
  Kiểm chứng tay NFR2.21/NFR2.22: 50 callback đồng thời cùng và khác giao dịch (BR20.1, BR20.2).

.DESCRIPTION
  Một tiến trình PowerShell duy nhất. Dùng MỘT HttpClient chung, tạo sẵn N runspace, mỗi runspace
  chờ một chốt xuất phát chung rồi mới gửi, nên các request thật sự chồng nhau. Script đo
  "maxInFlight" (số request đang chạy cùng lúc tối đa, tính ở phía máy khách):
    PASS         mọi tiêu chí chức năng đạt VÀ maxInFlight >= 25
    FAIL         có tiêu chí chức năng không đạt
    INCONCLUSIVE chức năng đạt nhưng maxInFlight < 25 (không có bằng chứng các request chồng nhau)
  Mã thoát: 0 cả hai kịch bản PASS; 1 có FAIL (hoặc không kết nối được); 2 có INCONCLUSIVE.

  Script tạo một dịch vụ tạm CONC_<thời gian>_<hex> rồi xóa mềm ở cuối. Các Callback đã ghi không
  xóa được (chỉ xóa mềm dịch vụ), nên hãy chạy trên database phát triển hoặc database "_load".
  Yêu cầu Windows PowerShell 5.1; backend phải đang chạy.

.PARAMETER BaseUrl
  Địa chỉ backend. Mặc định http://127.0.0.1:8080

.PARAMETER Concurrency
  Số request đồng thời mỗi kịch bản. Mặc định 50.
#>
[CmdletBinding()]
param(
  [string]$BaseUrl = 'http://127.0.0.1:8080',
  [int]$Concurrency = 50
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$MinInFlight = 25

Add-Type -AssemblyName System.Net.Http
[System.Net.ServicePointManager]::DefaultConnectionLimit = 100
[System.Net.ServicePointManager]::Expect100Continue = $false
$handler = New-Object System.Net.Http.HttpClientHandler
$handler.UseProxy = $false
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(30)

function Invoke-Api {
  param([string]$Method, [string]$Path, [string]$Json)
  $request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::new($Method), ($BaseUrl + $Path))
  if ($Json) {
    $request.Content = New-Object System.Net.Http.StringContent($Json, [System.Text.Encoding]::UTF8, 'application/json')
  }
  $response = $client.SendAsync($request).GetAwaiter().GetResult()
  $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
  return New-Object PSObject -Property @{ Status = [int]$response.StatusCode; Body = $text }
}

function Invoke-Burst {
  param([string]$ServiceCode, [string[]]$Bodies)
  $count = $Bodies.Length
  $gate = New-Object System.Threading.ManualResetEvent($false)
  $ready = New-Object System.Threading.CountdownEvent($count)
  $pool = [System.Management.Automation.Runspaces.RunspaceFactory]::CreateRunspacePool(1, $count)
  $pool.Open()
  $worker = {
    param($HttpClient, $Url, $Json, $Gate, $Ready)
    $content = New-Object System.Net.Http.StringContent($Json, [System.Text.Encoding]::UTF8, 'application/json')
    [void]$Ready.Signal()
    [void]$Gate.WaitOne()
    $start = [System.Diagnostics.Stopwatch]::GetTimestamp()
    $status = -1
    $text = ''
    try {
      $response = $HttpClient.PostAsync($Url, $content).GetAwaiter().GetResult()
      $status = [int]$response.StatusCode
      $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    } catch {
      $text = $_.Exception.Message
    }
    $end = [System.Diagnostics.Stopwatch]::GetTimestamp()
    New-Object PSObject -Property @{ Status = $status; Body = $text; Start = $start; End = $end }
  }
  $jobs = New-Object System.Collections.ArrayList
  $url = $BaseUrl + '/callbacks/' + $ServiceCode
  for ($i = 0; $i -lt $count; $i++) {
    $shell = [System.Management.Automation.PowerShell]::Create()
    $shell.RunspacePool = $pool
    [void]$shell.AddScript($worker).AddArgument($client).AddArgument($url).AddArgument($Bodies[$i]).AddArgument($gate).AddArgument($ready)
    [void]$jobs.Add((New-Object PSObject -Property @{ Shell = $shell; Handle = $shell.BeginInvoke() }))
  }
  if (-not $ready.Wait(120000)) {
    throw 'Các runspace không sẵn sàng trong 120 giây.'
  }
  [void]$gate.Set()
  $results = New-Object System.Collections.ArrayList
  foreach ($job in $jobs) {
    $output = $job.Shell.EndInvoke($job.Handle)
    [void]$results.Add($output[0])
    $job.Shell.Dispose()
  }
  $pool.Close()
  $pool.Dispose()
  return ,$results
}

function Get-MaxInFlight {
  param($Results)
  $events = New-Object System.Collections.ArrayList
  foreach ($r in $Results) {
    [void]$events.Add((New-Object PSObject -Property @{ Tick = [long]$r.Start; Delta = 1 }))
    [void]$events.Add((New-Object PSObject -Property @{ Tick = [long]$r.End; Delta = -1 }))
  }
  # Hòa nhau thì điểm kết thúc (-1) đứng trước điểm bắt đầu (+1): khoảng chỉ chạm nhau không chồng.
  $sorted = $events | Sort-Object -Property @{ Expression = 'Tick'; Ascending = $true }, @{ Expression = 'Delta'; Ascending = $true }
  $current = 0
  $max = 0
  foreach ($e in $sorted) {
    $current += $e.Delta
    if ($current -gt $max) { $max = $current }
  }
  return $max
}

function Get-Timing {
  param($Results)
  $freq = [double][System.Diagnostics.Stopwatch]::Frequency
  $millis = @($Results | ForEach-Object { ([double]($_.End - $_.Start)) * 1000.0 / $freq } | Sort-Object)
  $first = ($Results | Measure-Object -Property Start -Minimum).Minimum
  $last = ($Results | Measure-Object -Property End -Maximum).Maximum
  return New-Object PSObject -Property @{
    TotalMs = ([double]($last - $first)) * 1000.0 / $freq
    MinMs = $millis[0]
    MedianMs = $millis[[int][Math]::Floor($millis.Count / 2)]
    MaxMs = $millis[$millis.Count - 1]
  }
}

function Test-Responses {
  param($Results, [int]$Expected)
  $good = 0
  foreach ($r in $Results) {
    if ($r.Status -eq 202 -and $r.Body -eq '{"accepted":true}') { $good++ }
  }
  return $good
}

function Write-Scenario {
  param([string]$Name, [string]$Verdict, [string[]]$Lines, $Results)
  $inFlight = Get-MaxInFlight -Results $Results
  $timing = Get-Timing -Results $Results
  Write-Host ('--- {0}: {1}' -f $Name, $Verdict)
  foreach ($line in $Lines) { Write-Host ('    ' + $line) }
  Write-Host ('    maxInFlight={0} (cần >= {1})' -f $inFlight, $MinInFlight)
  Write-Host ('    tổng thời gian={0:N0} ms; mỗi request: nhỏ nhất={1:N0} ms, trung vị={2:N0} ms, lớn nhất={3:N0} ms' -f $timing.TotalMs, $timing.MinMs, $timing.MedianMs, $timing.MaxMs)
  $verdictText = 'không đạt'
  if ($timing.MaxMs -lt 5000) { $verdictText = 'đạt' }
  Write-Host ('    NFR2.22(c): maxMs={0:N0} (<5000 {1})' -f $timing.MaxMs, $verdictText)
}

function Get-Verdict {
  param([bool]$FunctionalOk, $Results)
  if (-not $FunctionalOk) { return 'FAIL' }
  if ((Get-MaxInFlight -Results $Results) -lt $MinInFlight) { return 'INCONCLUSIVE' }
  return 'PASS'
}

$exitCode = 0
$serviceCode = ''
try {
  $health = Invoke-Api -Method 'GET' -Path '/actuator/health' -Json ''
  if ($health.Status -ne 200) {
    Write-Host ('Backend không sẵn sàng: /actuator/health trả {0}.' -f $health.Status)
    exit 1
  }

  $suffix = [Guid]::NewGuid().ToString('N').Substring(0, 4)
  $serviceCode = 'CONC_' + (Get-Date -Format 'yyyyMMddHHmmss') + '_' + $suffix
  $service = '{"serviceCode":"' + $serviceCode + '","name":"Kiểm tra đồng thời","authType":"NONE",' +
    '"transactionIdPath":"$.transId","statusPath":"$.code","timestampPath":"$.time",' +
    '"timestampFormat":"ISO_8601","responseHttpStatus":202,"responseBody":{"accepted":true}}'
  $created = Invoke-Api -Method 'POST' -Path '/api/services' -Json $service
  if ($created.Status -ne 201) {
    Write-Host ('Không tạo được dịch vụ tạm: HTTP {0}' -f $created.Status)
    exit 1
  }
  Write-Host ('Dịch vụ tạm: {0}; mỗi kịch bản gửi {1} request đồng thời.' -f $serviceCode, $Concurrency)

  # ---- Kịch bản BR20.1: cùng transactionId ----
  $bodies = @()
  for ($i = 0; $i -lt $Concurrency; $i++) {
    $bodies += ('{{"transId":"CONC-SAME","code":"S{0}","time":"2026-10-19T10:{1:00}:00+07:00"}}' -f $i, ($i % 60))
  }
  $same = Invoke-Burst -ServiceCode $serviceCode -Bodies $bodies
  $okResponses = Test-Responses -Results $same -Expected $Concurrency
  $callbacks = (Invoke-Api -Method 'GET' -Path ('/api/callbacks?serviceCode=' + $serviceCode + '&transactionId=CONC-SAME&size=100') -Json '').Body | ConvertFrom-Json
  $transactions = (Invoke-Api -Method 'GET' -Path ('/api/transactions?serviceCode=' + $serviceCode + '&transactionId=CONC-SAME&size=100') -Json '').Body | ConvertFrom-Json
  $lines = @()
  $lines += ('phản hồi đúng 202 và thân cấu hình: {0}/{1}' -f $okResponses, $Concurrency)
  $lines += ('Callback ghi được: {0} (cần {1})' -f $callbacks.totalItems, $Concurrency)
  $lines += ('số giao dịch: {0} (cần 1)' -f $transactions.totalItems)
  $functional = ($okResponses -eq $Concurrency) -and ($callbacks.totalItems -eq $Concurrency) -and ($transactions.totalItems -eq 1)
  if ($functional) {
    $tx = $transactions.items[0]
    $newest = $callbacks.items[0]
    $lines += ('callbackCount={0} (cần {1}); latestCallbackId={2} so với recordId đầu danh sách={3}; latestStatus={4} so với status={5}' -f $tx.callbackCount, $Concurrency, $tx.latestCallbackId, $newest.recordId, $tx.latestStatus, $newest.status)
    $functional = ($tx.callbackCount -eq $Concurrency) -and ($tx.latestCallbackId -eq $newest.recordId) -and ($tx.latestStatus -eq $newest.status)
  }
  $verdict1 = Get-Verdict -FunctionalOk $functional -Results $same
  Write-Scenario -Name 'BR20.1 (cùng transactionId)' -Verdict $verdict1 -Lines $lines -Results $same

  # ---- Kịch bản BR20.2: transactionId khác nhau ----
  $bodies = @()
  for ($i = 0; $i -lt $Concurrency; $i++) {
    $bodies += ('{{"transId":"CONC-B2-{0}","code":"OK","time":"2026-10-19T11:{1:00}:00+07:00"}}' -f $i, ($i % 60))
  }
  $distinct = Invoke-Burst -ServiceCode $serviceCode -Bodies $bodies
  $okResponses = Test-Responses -Results $distinct -Expected $Concurrency
  $callbacks = (Invoke-Api -Method 'GET' -Path ('/api/callbacks?serviceCode=' + $serviceCode + '&transactionId=CONC-B2&size=100') -Json '').Body | ConvertFrom-Json
  $transactions = (Invoke-Api -Method 'GET' -Path ('/api/transactions?serviceCode=' + $serviceCode + '&transactionId=CONC-B2&size=100') -Json '').Body | ConvertFrom-Json
  $lines = @()
  $lines += ('phản hồi đúng 202 và thân cấu hình: {0}/{1}' -f $okResponses, $Concurrency)
  $lines += ('Callback ghi được: {0} (cần {1})' -f $callbacks.totalItems, $Concurrency)
  $lines += ('số giao dịch: {0} (cần {1})' -f $transactions.totalItems, $Concurrency)
  $functional = ($okResponses -eq $Concurrency) -and ($callbacks.totalItems -eq $Concurrency) -and ($transactions.totalItems -eq $Concurrency)
  if ($functional) {
    $wrong = @($transactions.items | Where-Object { $_.callbackCount -ne 1 }).Count
    $lines += ('giao dịch có callbackCount khác 1: {0} (cần 0)' -f $wrong)
    $functional = ($wrong -eq 0)
  }
  $verdict2 = Get-Verdict -FunctionalOk $functional -Results $distinct
  Write-Scenario -Name 'BR20.2 (50 transactionId khác nhau)' -Verdict $verdict2 -Lines $lines -Results $distinct

  if ($verdict1 -eq 'FAIL' -or $verdict2 -eq 'FAIL') {
    $exitCode = 1
  } elseif ($verdict1 -eq 'INCONCLUSIVE' -or $verdict2 -eq 'INCONCLUSIVE') {
    $exitCode = 2
    Write-Host 'INCONCLUSIVE: chức năng đạt nhưng các request không chồng nhau đủ; chạy lại script (không hạ ngưỡng).'
  }
} catch {
  Write-Host ('Lỗi: ' + $_.Exception.Message)
  $exitCode = 1
} finally {
  if ($serviceCode) {
    try {
      $deleted = Invoke-Api -Method 'DELETE' -Path ('/api/services/' + $serviceCode) -Json ''
      if ($deleted.Status -ne 204) {
        Write-Host ('Không xóa mềm được dịch vụ tạm {0} (HTTP {1}); dọn bằng DELETE /api/services/{0} hoặc xóa database.' -f $serviceCode, $deleted.Status)
      }
    } catch {
      Write-Host ('Không xóa mềm được dịch vụ tạm {0}; dọn bằng DELETE /api/services/{0} hoặc xóa database.' -f $serviceCode)
    }
  }
  $client.Dispose()
}
exit $exitCode
