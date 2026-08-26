param([string]$ChainKey = (Get-Date).ToUniversalTime().ToString('yyyyMMdd'))
# 导出 dbg_audit_event 指定分片为 JSON 文件（供 audit-verify.mjs 独立重算）
# occurred_at 格式化为 Java Instant.toString 形态（微秒截断后 0/3/6 位小数）
# SQL 经 base64 传参避免 pwsh→bash→ssh→docker 四层引号转义问题
$PSNativeCommandUseErrorActionPreference = $false
$out = Join-Path $PSScriptRoot "audit-$ChainKey.json"
$sql = @"
select coalesce(json_agg(t order by t.id), '[]'::json) from (
  select id::text as id, event_id, category, action, actor_id,
         actor_snapshot::text as actor_snapshot,
         target_type, target_id,
         target_snapshot::text as target_snapshot,
         result, source_ip, trace_id,
         details::text as details,
         case
           when occurred_at = date_trunc('second', occurred_at)
             then to_char(occurred_at at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS"Z"')
           when occurred_at = date_trunc('millisecond', occurred_at)
             then to_char(occurred_at at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS.FF3"Z"')
           else to_char(occurred_at at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS.FF6"Z"')
         end as occurred_iso,
         previous_hash, event_hash
  from dbg_audit_event where chain_key = '$ChainKey' order by id
) t
"@
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($sql))
$result = & "D:\Git\bin\bash.exe" "/c/Users/cxy784853792/AppData/Local/Temp/ssh-mrlu.sh" "echo $b64 | base64 -d | docker exec -i postgres psql -U postgres -d datagate -t -A -f -" 2>$null
[System.IO.File]::WriteAllText($out, ($result -join "`n"), [Text.UTF8Encoding]::new($false))
Write-Output "EXPORTED=$out"
