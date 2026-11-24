# Install the debug build on a device or emulator, launch Sarah Monitor, and screenshot every display
# (phone screen plus the Samsung DeX screen when DeX is running).
#   .\tools\device-shots.ps1                  # the only connected device
#   .\tools\device-shots.ps1 -Serial emulator-5554 -Build
#   .\tools\device-shots.ps1 -Serial 192.168.0.50:41234 -NoInstall -Tag landscape
# PNGs land in app/build/device-shots/<serial>-<tag>-display<id>.png
param(
  [string]$Serial = "",
  [string]$Tag = "now",
  [switch]$Build,
  [switch]$NoInstall,
  [switch]$NoLaunch
)
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$adb = "$sdk\platform-tools\adb.exe"
$pkg = "com.pulsepointlabs.sarahmonitor"
$sargs = if ($Serial) { @("-s", $Serial) } else { @() }

if ($Build) {
  if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr" }
  $env:Path = $env:Path.Replace('"', '')   # a stray quote in PATH breaks Gradle's test/exec JVMs
  Push-Location $root; try { & .\gradlew.bat :app:assembleDebug --console=plain -q } finally { Pop-Location }
}
if (-not $NoInstall) {
  & $adb @sargs install -r "$root\app\build\outputs\apk\debug\app-debug.apk"
  foreach ($p in "RECORD_AUDIO","POST_NOTIFICATIONS","BLUETOOTH_SCAN","BLUETOOTH_CONNECT",
                 "ACCESS_FINE_LOCATION","ACCESS_COARSE_LOCATION","CAMERA","ACTIVITY_RECOGNITION","BODY_SENSORS") {
    & $adb @sargs shell pm grant $pkg "android.permission.$p" 2>$null | Out-Null
  }
}
if (-not $NoLaunch) {
  & $adb @sargs shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null
  Start-Sleep -Seconds 6
}

$name = if ($Serial) { $Serial -replace '[:.]', '_' } else { ((& $adb get-serialno) -replace '[:.]', '_').Trim() }
$out = "$root\app\build\device-shots"; New-Item -ItemType Directory -Force $out | Out-Null
$ids = & $adb @sargs shell dumpsys SurfaceFlinger --display-id 2>$null |
  ForEach-Object { if ($_ -match '^Display (\d+)') { $Matches[1] } }
if (-not $ids) { $ids = @("") }
foreach ($id in $ids) {
  $file = "$out\$name-$Tag-display$id.png"
  $dflag = if ($id) { "-d $id" } else { "" }
  $sflag = if ($Serial) { "-s $Serial" } else { "" }
  cmd /c "`"$adb`" $sflag exec-out screencap -p $dflag > `"$file`""
  if ((Get-Item $file).Length -gt 1000) { "saved $file" } else { Remove-Item $file; "display ${id}: no image" }
}
