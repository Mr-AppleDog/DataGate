param(
  [Parameter(Mandatory=$true)][string]$Endpoint,   # 如 /auth/login
  [Parameter(Mandatory=$true)][string]$JsonBody,    # 明文 JSON
  [string]$Token,
  [string]$ClientId = "e5cd7e4891bf95d1d19206ce24a7b32e"
)
# 对 @ApiEncrypt 端点：用 api-crypto.mjs 加密后发 POST。响应不加密（response() 默认 false）。
$pubKey = "MFwwDQYJKoZIhvcNAQEBBQADSwAwSAJBAKoR8mX0rGKLqzcWmOzbfj64K8ZIgOdHnzkXSOVOZbFu/TJhZ7rFAN+eaGkl3C4buccQd/EjEsj9ir7ijT7h96MCAwEAAQ=="
$enc = node api-crypto.mjs encrypt $pubKey $JsonBody | ConvertFrom-Json
$headers = @{ "Content-Type"="application/json"; "encrypt-key"=$enc.header; "clientid"=$ClientId }
if ($Token) { $headers["Authorization"] = "Bearer $Token" }
try {
  $resp = Invoke-RestMethod -Uri "http://127.0.0.1:8080$Endpoint" -Method Post -Headers $headers -Body $enc.body -TimeoutSec 60
  $resp | ConvertTo-Json -Depth 8
} catch {
  $r = $_.Exception.Response
  if ($r) {
    $sr = New-Object System.IO.StreamReader($r.GetResponseStream())
    $body = $sr.ReadToEnd()
    "HTTP $([int]$r.StatusCode): $body"
  } else { "ERR: $($_.Exception.Message)" }
}
