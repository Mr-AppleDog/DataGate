param([Parameter(Mandatory=$true)][string]$Sql)
# 在 VM 的 postgres 容器内执行 SQL（base64 传参避免多层引号转义），输出原样返回
$PSNativeCommandUseErrorActionPreference = $false
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Sql))
& "D:\Git\bin\bash.exe" "/c/Users/cxy784853792/AppData/Local/Temp/ssh-mrlu.sh" "echo $b64 | base64 -d | docker exec -i postgres psql -U postgres -d datagate -v ON_ERROR_STOP=1 -f -" 2>&1
