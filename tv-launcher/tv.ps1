# My Home TV launcher helper for Windows (PowerShell).
#
#   .\tv.ps1 backup     Save a snapshot of the TV's apps and settings (changes nothing)
#   .\tv.ps1 install    Back up, then install My Home and switch on its Home-button service
#   .\tv.ps1 revert     Put the TV back how it was: switch the service off and uninstall My Home
#   .\tv.ps1 status     Show what's installed and switched on right now
#
# Options:  -TV 192.168.1.42  -Port 5555  -Apk C:\path\MyHome.apk
# If Windows blocks the script, run:  powershell -ExecutionPolicy Bypass -File .\tv.ps1 install
param(
    [Parameter(Position = 0)][string]$Command = "",
    [string]$TV = "192.168.1.42",
    [int]$Port = 5555,
    [string]$Apk = ""
)
# "Continue" so adb messages on stderr (normal) do not stop the script in Windows PowerShell 5.1.
$ErrorActionPreference = "Continue"

$Pkg = "com.myhome.tvlauncher"
$Service = "$Pkg/$Pkg.HomeButtonService"
$ApkUrl = "https://github.com/rmwatson/Tutorial/releases/download/my-home-latest/MyHome.apk"
$Dir = $PSScriptRoot
$Backup = Join-Path $Dir "tv-backup"
$Serial = "${TV}:$Port"
$script:Adb = $null

function Say($text) { Write-Host ""; Write-Host $text -ForegroundColor Cyan }

function Find-Adb {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $script:Adb = $cmd.Source; return }
    $local = Join-Path $Dir "platform-tools\adb.exe"
    if (-not (Test-Path $local)) {
        Say "Downloading Google's Android platform tools (adb)..."
        $zip = Join-Path $Dir "platform-tools.zip"
        Invoke-WebRequest -UseBasicParsing -Uri "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -OutFile $zip
        Expand-Archive -Force $zip -DestinationPath $Dir
        Remove-Item $zip
    }
    $script:Adb = $local
}

function TvSh { (& $script:Adb -s $Serial shell @args) | ForEach-Object { $_.TrimEnd("`r") } }

function Connect-TV {
    Find-Adb
    Say "Connecting to the TV at $Serial..."
    & $script:Adb connect $Serial | Out-Null
    for ($i = 0; $i -lt 30; $i++) {
        $state = (& $script:Adb -s $Serial get-state 2>&1) | Out-String
        if ($state -match "unauthorized") {
            Write-Host "Look at the TV: choose 'Always allow from this computer' and press OK."
        } elseif ($state.Trim() -eq "device") {
            Write-Host "Connected."; return
        } else {
            & $script:Adb connect $Serial 2>&1 | Out-Null
        }
        Start-Sleep -Seconds 2
    }
    Write-Host "Couldn't connect to $Serial."
    Write-Host "Check: same Wi-Fi, TV switched on, Settings > System > Developer options > USB debugging is ON."
    Write-Host "If the TV shows 'Wireless debugging' with its own port, run e.g.:  .\tv.ps1 $Command -Port 37123"
    exit 1
}

function Take-Snapshot($out) {
    New-Item -ItemType Directory -Force $out | Out-Null
    @((TvSh getprop ro.product.model), (TvSh getprop ro.build.fingerprint)) | Set-Content "$out\model.txt"
    TvSh pm list packages -f | Set-Content "$out\packages.txt"
    TvSh pm list packages -d | Set-Content "$out\packages-disabled.txt"
    TvSh settings list system | Set-Content "$out\settings-system.txt"
    TvSh settings list secure | Set-Content "$out\settings-secure.txt"
    TvSh settings list global | Set-Content "$out\settings-global.txt"
    TvSh settings get secure enabled_accessibility_services | Set-Content "$out\enabled_accessibility_services.txt"
    TvSh settings get secure accessibility_enabled | Set-Content "$out\accessibility_enabled.txt"
    TvSh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | Set-Content "$out\home-app.txt"
}

function Do-Backup {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    Say "Backing up the TV to tv-backup\$stamp ..."
    Take-Snapshot (Join-Path $Backup $stamp)
    # The very first snapshot is kept forever as "original": revert always goes back to it.
    $orig = Join-Path $Backup "original"
    if (-not (Test-Path $orig)) {
        Copy-Item -Recurse (Join-Path $Backup $stamp) $orig
        Write-Host "Saved as the original state too (tv-backup\original)."
    }
    Write-Host ("Model: " + (Get-Content "$Backup\$stamp\model.txt" -TotalCount 1))
    Write-Host ("Apps installed: " + (Get-Content "$Backup\$stamp\packages.txt").Count)
}

function Do-Install {
    Do-Backup
    $file = $Apk
    if (-not $file) {
        $file = Join-Path $Dir "MyHome.apk"
        Say "Downloading the newest My Home build..."
        Invoke-WebRequest -UseBasicParsing -Uri $ApkUrl -OutFile $file
    }
    Say "Installing My Home..."
    & $script:Adb -s $Serial install -r $file
    if ($LASTEXITCODE -ne 0) { throw "Install failed" }

    Say "Switching on the Home-button service..."
    $cur = "" + (TvSh settings get secure enabled_accessibility_services)
    if (-not $cur -or $cur -eq "null") {
        TvSh settings put secure enabled_accessibility_services $Service | Out-Null
    } elseif ((":$cur:") -notlike "*:${Service}:*") {
        TvSh settings put secure enabled_accessibility_services "${cur}:$Service" | Out-Null
    }
    TvSh settings put secure accessibility_enabled 1 | Out-Null

    TvSh am start -n "$Pkg/.MainActivity" | Out-Null
    Say "Done! My Home is open on the TV. Press Home on the remote to try it."
    Write-Host "To undo everything:  .\tv.ps1 revert"
}

function Do-Revert {
    Say "Putting the TV back how it was..."
    $cur = "" + (TvSh settings get secure enabled_accessibility_services)
    # Remove only our service, keeping anything else that's switched on.
    $keep = @($cur.Split(":") | Where-Object { $_ -and $_ -ne $Service -and $_ -ne "null" })
    if ($keep.Count -eq 0) {
        TvSh settings delete secure enabled_accessibility_services | Out-Null
    } else {
        TvSh settings put secure enabled_accessibility_services ($keep -join ":") | Out-Null
    }
    $origFile = Join-Path $Backup "original\accessibility_enabled.txt"
    if (Test-Path $origFile) {
        $orig = ("" + (Get-Content $origFile -TotalCount 1)).Trim()
        if (-not $orig -or $orig -eq "null") {
            TvSh settings delete secure accessibility_enabled | Out-Null
        } else {
            TvSh settings put secure accessibility_enabled $orig | Out-Null
        }
    }
    if ((TvSh pm list packages) -contains "package:$Pkg") {
        & $script:Adb -s $Serial uninstall $Pkg
    }
    TvSh input keyevent KEYCODE_HOME | Out-Null

    $origDir = Join-Path $Backup "original"
    if (Test-Path $origDir) {
        $now = Join-Path $Backup ("after-revert-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
        Take-Snapshot $now
        Say "Compared with the original backup:"
        $d1 = Compare-Object (Get-Content "$origDir\packages.txt") (Get-Content "$now\packages.txt")
        $d2 = Compare-Object (Get-Content "$origDir\enabled_accessibility_services.txt") (Get-Content "$now\enabled_accessibility_services.txt")
        if (-not $d1 -and -not $d2) {
            Write-Host "Apps and accessibility settings match the original. The TV is back to how it was."
        } else {
            Write-Host "Differences (<= was there originally, => is there now):"
            $d1; $d2
        }
    }
}

function Do-Status {
    Say ("TV: " + (TvSh getprop ro.product.model))
    if ((TvSh pm list packages) -contains "package:$Pkg") {
        Write-Host "My Home: installed"
    } else {
        Write-Host "My Home: not installed"
    }
    Write-Host ("Accessibility services on: " + (TvSh settings get secure enabled_accessibility_services))
}

switch ($Command) {
    "backup"  { Connect-TV; Do-Backup }
    "install" { Connect-TV; Do-Install }
    "revert"  { Connect-TV; Do-Revert }
    "status"  { Connect-TV; Do-Status }
    default   { Get-Content $PSCommandPath -TotalCount 9 | Select-Object -Skip 1 }
}
