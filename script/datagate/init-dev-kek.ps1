# DataGate local-development KEK bootstrap (CRED-003).
# Creates a repository-external AES-256 KEK. Existing files are validated and never overwritten.
[CmdletBinding()]
param(
    [string]$KekPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($KekPath)) {
    if (-not [string]::IsNullOrWhiteSpace($env:DATAGATE_KEK_FILE)) {
        $KekPath = $env:DATAGATE_KEK_FILE
    } else {
        $userProfilePath = [Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)
        if ([string]::IsNullOrWhiteSpace($userProfilePath)) {
            throw 'Cannot resolve the current user profile. Pass -KekPath explicitly.'
        }
        $KekPath = Join-Path $userProfilePath '.datagate\kek.txt'
    }
}

$resolvedKekPath = [IO.Path]::GetFullPath($KekPath)
$kekDirectory = [IO.Path]::GetDirectoryName($resolvedKekPath)
if ([string]::IsNullOrWhiteSpace($kekDirectory)) {
    throw "Invalid KEK path: $resolvedKekPath"
}

function Test-DataGateKekFile {
    param([Parameter(Mandatory = $true)][string]$Path)

    $firstKeyLine = [IO.File]::ReadAllLines($Path) |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and -not $_.StartsWith('#') } |
        Select-Object -First 1
    if (-not $firstKeyLine -or $firstKeyLine.IndexOf(':') -lt 1) {
        throw 'Existing KEK file has invalid format (expected version:BASE64); it was not overwritten.'
    }

    $encodedKey = $firstKeyLine.Substring($firstKeyLine.IndexOf(':') + 1).Trim()
    try {
        $decodedKey = [Convert]::FromBase64String($encodedKey)
    } catch {
        throw 'Existing KEK file contains invalid Base64; it was not overwritten.'
    }
    try {
        if ($decodedKey.Length -ne 32) {
            throw 'Existing KEK must decode to exactly 32 bytes; it was not overwritten.'
        }
    } finally {
        if ($null -ne $decodedKey) {
            [Array]::Clear($decodedKey, 0, $decodedKey.Length)
        }
    }
}

function Set-DataGateKekFilePermissions {
    param([Parameter(Mandatory = $true)][string]$Path)

    if ($env:OS -ne 'Windows_NT') {
        return
    }
    $currentAcl = Get-Acl -LiteralPath $Path
    if ($currentAcl.AreAccessRulesProtected) {
        return
    }
    $currentUserSid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $fileSecurity = [Security.AccessControl.FileSecurity]::new()
    $fileSecurity.SetOwner($currentUserSid)
    $fileSecurity.SetAccessRuleProtection($true, $false)
    $accessRule = [Security.AccessControl.FileSystemAccessRule]::new(
        $currentUserSid,
        [Security.AccessControl.FileSystemRights]::FullControl,
        [Security.AccessControl.AccessControlType]::Allow)
    $fileSecurity.AddAccessRule($accessRule)
    Set-Acl -LiteralPath $Path -AclObject $fileSecurity
}

if (Test-Path -LiteralPath $resolvedKekPath) {
    Test-DataGateKekFile -Path $resolvedKekPath
    Set-DataGateKekFilePermissions -Path $resolvedKekPath
    Write-Output "DataGate development KEK already exists and is valid: $resolvedKekPath"
    exit 0
}

[IO.Directory]::CreateDirectory($kekDirectory) | Out-Null
$keyBytes = New-Object byte[] 32
try {
    [Security.Cryptography.RandomNumberGenerator]::Fill($keyBytes)
    $line = 'v1:' + [Convert]::ToBase64String($keyBytes)
    $utf8WithoutBom = [Text.UTF8Encoding]::new($false)
    $stream = [IO.File]::Open($resolvedKekPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        $writer = [IO.StreamWriter]::new($stream, $utf8WithoutBom)
        try {
            $writer.WriteLine($line)
        } finally {
            $writer.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
} finally {
    if ($null -ne $keyBytes) {
        [Array]::Clear($keyBytes, 0, $keyBytes.Length)
    }
    $line = $null
}

Set-DataGateKekFilePermissions -Path $resolvedKekPath
Test-DataGateKekFile -Path $resolvedKekPath
Write-Output "Created DataGate development KEK outside the repository: $resolvedKekPath"
Write-Output 'Do not commit or share this file. Production must use a separately managed read-only Secret.'
