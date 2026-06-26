param(
    [string]$RepoRoot = (Get-Location).Path
)

$ErrorActionPreference = "Stop"
$DefaultBackend = "https://buy.aisuper.top"
$DisplayText = "服务域名：https://buy.aisuper.top"

function Find-TargetFile([string]$Root) {
    $candidates = @(
        "V2rayNG\app\src\main\kotlin\com\v2ray\ang\ui\DingdangLoginActivity.kt",
        "V2rayNG\app\src\main\java\com\v2ray\ang\ui\DingdangLoginActivity.kt",
        "app\src\main\kotlin\com\v2ray\ang\ui\DingdangLoginActivity.kt",
        "app\src\main\java\com\v2ray\ang\ui\DingdangLoginActivity.kt"
    )
    foreach ($rel in $candidates) {
        $p = Join-Path $Root $rel
        if (Test-Path $p) { return (Resolve-Path $p).Path }
    }
    $hit = Get-ChildItem -Path $Root -Filter "DingdangLoginActivity.kt" -Recurse -File -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($hit) { return $hit.FullName }
    throw "Cannot find DingdangLoginActivity.kt under repository root: $Root"
}

function Find-ClassEnd([string]$Text) {
    $m = [regex]::Match($Text, '\bclass\s+DingdangLoginActivity\b')
    if (-not $m.Success) { return -1 }
    $brace = $Text.IndexOf('{', $m.Index + $m.Length)
    if ($brace -lt 0) { return -1 }

    $depth = 0
    $state = "normal"
    $escaped = $false
    $i = $brace
    while ($i -lt $Text.Length) {
        $ch = $Text[$i]
        $nxt = if ($i + 1 -lt $Text.Length) { $Text[$i + 1] } else { [char]0 }
        $tri = if ($i + 3 -le $Text.Length) { $Text.Substring($i, 3) } else { "" }

        switch ($state) {
            "normal" {
                if ($tri -eq '"""') { $state = "raw"; $i += 3; continue }
                if ($ch -eq '"') { $state = "string"; $escaped = $false; $i++; continue }
                if ($ch -eq "'") { $state = "char"; $escaped = $false; $i++; continue }
                if ($ch -eq '/' -and $nxt -eq '/') { $state = "line"; $i += 2; continue }
                if ($ch -eq '/' -and $nxt -eq '*') { $state = "block"; $i += 2; continue }
                if ($ch -eq '{') { $depth++ }
                elseif ($ch -eq '}') {
                    $depth--
                    if ($depth -eq 0) { return $i }
                }
                $i++
            }
            "string" {
                if ($escaped) { $escaped = $false }
                elseif ($ch -eq '\') { $escaped = $true }
                elseif ($ch -eq '"') { $state = "normal" }
                $i++
            }
            "char" {
                if ($escaped) { $escaped = $false }
                elseif ($ch -eq '\') { $escaped = $true }
                elseif ($ch -eq "'") { $state = "normal" }
                $i++
            }
            "raw" {
                if ($tri -eq '"""') { $state = "normal"; $i += 3 } else { $i++ }
            }
            "line" {
                if ($ch -eq "`n" -or $ch -eq "`r") { $state = "normal" }
                $i++
            }
            "block" {
                if ($ch -eq '*' -and $nxt -eq '/') { $state = "normal"; $i += 2 } else { $i++ }
            }
        }
    }
    return -1
}

function Normalize-Lf([string]$Text) {
    return $Text -replace "`r`n", "`n" -replace "`r", "`n"
}

$Root = (Resolve-Path $RepoRoot).Path
$Target = Find-TargetFile $Root
$Content = Normalize-Lf ([System.IO.File]::ReadAllText($Target, [System.Text.Encoding]::UTF8))

$Stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$BackupDir = Join-Path $Root "ddmng_patch_backups\V1.2.0.4_$Stamp"
New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
$BackupPath = Join-Path $BackupDir "DingdangLoginActivity.kt"
Copy-Item -Path $Target -Destination $BackupPath -Force

$Report = New-Object System.Collections.Generic.List[string]

$End = Find-ClassEnd $Content
if ($End -ge 0) {
    $Tail = $Content.Substring($End + 1)
    $Suspicious = $Tail.Trim().Length -gt 0 -and (
        $Tail.Contains("statusText") -or $Tail.Contains("connectionBadge") -or $Tail.Contains("connectionSubText") -or
        $Tail.Contains("LinearLayout") -or $Tail.Contains("TextView") -or $Tail.Contains("resources") -or
        $Tail.Contains("accent") -or (($Tail.Trim() -split "`n").Count -ge 3)
    )
    if ($Suspicious) {
        $Content = $Content.Substring(0, $End + 1).TrimEnd() + "`n"
        $Report.Add("tail_cleanup=True: removed trailing code after class end")
    } else {
        $Report.Add("tail_cleanup=False")
    }
} else {
    $Report.Add("tail_cleanup=False: class end not found")
}

if (-not $Content.Contains("DINGDANG_FIXED_BACKEND_BASE")) {
    $End = Find-ClassEnd $Content
    if ($End -lt 0) { throw "DingdangLoginActivity class brace matching failed before constants injection" }
    $Constants = @"
        // DdmNG V1.2.0.4: backend base is fixed and hidden from users.
        private const val DINGDANG_FIXED_BACKEND_BASE = "$DefaultBackend"
        private const val DINGDANG_BACKEND_DISPLAY_TEXT = "$DisplayText"
"@
    $ClassText = $Content.Substring(0, $End)
    $Comp = [regex]::Match($ClassText, 'companion\s+object\s*\{')
    if ($Comp.Success) {
        $InsertAt = $Comp.Index + $Comp.Length
        $Content = $Content.Substring(0, $InsertAt) + "`n" + $Constants + $Content.Substring($InsertAt)
    } else {
        $Block = "`n    companion object {`n" + $Constants + "    }`n"
        $Content = $Content.Substring(0, $End).TrimEnd() + $Block + $Content.Substring($End)
    }
    $Report.Add("constants_added=True")
} else {
    $Report.Add("constants_added=False")
}

$Before = $Content
$Name = '(?:backendBase|backendUrl|backendBaseUrl|baseUrl|apiBaseUrl|apiBase|serviceBase|serviceBaseUrl|serverBase|serverBaseUrl|backendHost|apiHost)'
$Content = [regex]::Replace($Content, "(?m)^(\s*(?:private\s+|internal\s+|protected\s+)?(?:val|var)\s+($Name)\s*(?::\s*String)?\s*=\s*)(?!DINGDANG_FIXED_BACKEND_BASE\b).+$", '${1}DINGDANG_FIXED_BACKEND_BASE')
$Content = [regex]::Replace($Content, '(getString\s*\(\s*[^\n;]*(?:backend|base_url|baseUrl|server|service)[^\n;]*,\s*)""(\s*\))', '${1}DINGDANG_FIXED_BACKEND_BASE${2}', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
$Content = [regex]::Replace($Content, '(?m)^(\s*(?:private\s+)?(?:val|var)\s+\w*(?:Backend|BaseUrl|ServerBase|ServiceBase)\w*\s*(?::\s*String)?\s*=\s*)""', '${1}DINGDANG_FIXED_BACKEND_BASE')
$Report.Add("backend_assignment_changes=" + ($(if ($Content -ne $Before) { "some" } else { "0" })))

$Lines = $Content -split "`n", -1
$GuardChanged = 0
for ($i = 0; $i -lt $Lines.Count; $i++) {
    $line = $Lines[$i]
    if ($line -match 'if\s*\(.*(?:backend|baseUrl|apiBase|serverBase|serviceBase).*(?:isBlank|isEmpty|isNullOrBlank|isNullOrEmpty).*\)') {
        $endWin = [Math]::Min($Lines.Count - 1, $i + 9)
        $window = ($Lines[$i..$endWin] -join "`n")
        if ($window.Contains("后端服务地址未配置") -or $window.Contains("服务地址未配置") -or $window.ToLower().Contains("backend")) {
            $indent = ([regex]::Match($line, '^\s*')).Value
            $Lines[$i] = $indent + "if (false) { // DdmNG V1.2.0.4: fixed backend base is always configured"
            $GuardChanged++
        }
    }
}
$Content = $Lines -join "`n"
$Report.Add("blank_backend_guards_disabled=$GuardChanged")

$Lines = $Content -split "`n", -1
$HideChanged = 0
for ($i = 0; $i -lt $Lines.Count; $i++) {
    $line = $Lines[$i]
    $low = $line.ToLower()
    $isBackend = $low.Contains("backend") -or $low.Contains("baseurl") -or $low.Contains("base_url") -or $low.Contains("serverbase") -or $low.Contains("servicebase") -or $low.Contains("apibase")
    $isDisplay = $low.Contains("display") -or $low.Contains("domain") -or $low.Contains("servicedomain") -or $low.Contains("backenddisplay") -or $line.Contains("DINGDANG_BACKEND_DISPLAY_TEXT")
    if ($isBackend -and -not $isDisplay) {
        $new = $line.Replace("View.VISIBLE", "View.GONE")
        $new = [regex]::Replace($new, '=\s*true\b', '= false')
        if ($new -ne $line) { $HideChanged++; $Lines[$i] = $new }
    }
}
$Content = $Lines -join "`n"
$Report.Add("backend_input_visibility_changes=$HideChanged")
$Report.Add("display_text_present=" + $Content.Contains($DisplayText))

if ((Find-ClassEnd $Content) -lt 0) { throw "DingdangLoginActivity class brace matching failed after patch; backup preserved at $BackupPath" }

[System.IO.File]::WriteAllText($Target, $Content, [System.Text.UTF8Encoding]::new($false))
$Manifest = Join-Path $BackupDir "patch_report.txt"
[System.IO.File]::WriteAllText($Manifest, "DdmNG APP LegacyXTLS V1.2.0.4 hidden backend base patch`n" + "target=$Target`n" + "backup=$BackupPath`n" + ($Report -join "`n") + "`n", [System.Text.UTF8Encoding]::new($false))

Write-Host "[OK] Patched: $Target"
Write-Host "[OK] Backup: $BackupPath"
foreach ($item in $Report) { Write-Host "[INFO] $item" }
Write-Host "[INFO] Fixed backend base: $DefaultBackend"
