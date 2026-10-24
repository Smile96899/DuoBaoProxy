# Builds core + GUI and packs them into a Windows installer with Inno Setup.
# Usage: powershell -ExecutionPolicy Bypass -File package.ps1
#        set DBP_SIGN_CERT=<cert thumbprint> first to sign everything
# Output: installer\output\DuoBaoProxy-<version>-windows-x64-setup.exe
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$Qt = 'C:\Qt\6.8.3\msvc2022_64'
$Iscc = 'C:\Program Files (x86)\Inno Setup 6\ISCC.exe'
$Redist = Get-ChildItem 'C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Redist\MSVC\14.*' |
    Sort-Object Name | Select-Object -Last 1
$GeoUrl = 'https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download'

# The version lives in CMakeLists.txt: project(... VERSION x.y ...)
$Version = [regex]::Match((Get-Content CMakeLists.txt -Raw), 'project\([^)]*VERSION\s+([0-9.]+)').Groups[1].Value
if (-not $Version) { throw 'version not found in CMakeLists.txt' }
Write-Host "== DuoBao Proxy $Version"

function Check($what) { if ($LASTEXITCODE -ne 0) { throw "$what failed ($LASTEXITCODE)" } }

Write-Host '== core'
$env:VERSION = $Version
& 'C:\Program Files\Git\bin\bash.exe' ../go/build_core.sh; Check 'core build' # not WindowsApps\bash.exe (WSL)

Write-Host '== gui'
cmd /c "$PSScriptRoot\build.cmd" Release; Check 'gui build'

Write-Host '== stage'
$Stage = Join-Path $PSScriptRoot 'installer\stage'
if (Test-Path $Stage) { Remove-Item -Recurse -Force $Stage }
New-Item -ItemType Directory -Force $Stage | Out-Null
Copy-Item build\DuoBaoProxy.exe, ..\go\dist\windows-amd64\DuoBaoProxyCore.exe $Stage
& "$Qt\bin\windeployqt.exe" --qmldir qml --release --no-translations --no-opengl-sw --no-compiler-runtime "$Stage\DuoBaoProxy.exe"; Check 'windeployqt'
# App-local MSVC runtime, so machines without the VC++ redistributable still start.
Copy-Item "$($Redist.FullName)\x64\Microsoft.VC143.CRT\*.dll" $Stage

# xray reads geosite.dat / geoip.dat from the program folder (the default rules use geosite:cn).
$Cache = Join-Path $PSScriptRoot 'installer\cache'
New-Item -ItemType Directory -Force $Cache | Out-Null
foreach ($f in 'geosite.dat', 'geoip.dat') {
    if (-not (Test-Path "$Cache\$f")) {
        Write-Host "   downloading $f"
        Invoke-WebRequest "$GeoUrl/$f" -OutFile "$Cache\$f.part" -UseBasicParsing
        Move-Item "$Cache\$f.part" "$Cache\$f"
    }
    Copy-Item "$Cache\$f" $Stage
}

$IsccArgs = @('/Qp', "/DAppVersion=$Version", "/DSrcDir=$Stage")
if ($env:DBP_SIGN_CERT) {
    # Thumbprint of a code-signing cert in CurrentUser\My. Signs the binaries here;
    # Inno Setup signs setup.exe and the uninstaller through the same command.
    Write-Host '== sign'
    $SignTool = Get-ChildItem 'C:\Program Files (x86)\Windows Kits\10\bin\10.*\x64\signtool.exe' |
        Sort-Object FullName | Select-Object -Last 1 -ExpandProperty FullName
    $SignArgs = @('sign', '/sha1', $env:DBP_SIGN_CERT, '/fd', 'SHA256', '/tr', 'http://timestamp.digicert.com', '/td', 'SHA256')
    & $SignTool @SignArgs "$Stage\DuoBaoProxy.exe" "$Stage\DuoBaoProxyCore.exe"; Check 'signtool'
    # $q is Inno's quote; real quotes get mangled passing through Windows PowerShell.
    $IsccArgs += '/DSign', "/Sdbp=`$q$SignTool`$q $($SignArgs -join ' ') `$f"
}

Write-Host '== installer'
& $Iscc @IsccArgs installer\DuoBaoProxy.iss; Check 'ISCC'
Get-ChildItem installer\output\*.exe | Select-Object Name, @{n = 'MB'; e = { [math]::Round($_.Length / 1MB, 1) } }
