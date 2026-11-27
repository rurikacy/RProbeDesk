param([Parameter(Mandatory)][string]$Cache, [Parameter(Mandatory)][string]$Destination)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$env:PSModulePath = (Join-Path $PSHOME 'Modules') + ';' + $env:PSModulePath
New-Item -ItemType Directory -Force $Cache | Out-Null

function Fetch([string]$name, [string]$hash, [string]$url) {
    $path = Join-Path $Cache $name
    if (!(Test-Path -LiteralPath $path) -or (Get-FileHash -LiteralPath $path).Hash -ne $hash) {
        $temp = "$path.$([guid]::NewGuid()).tmp"
        try {
            & curl.exe --fail --location --retry 2 --max-time 300 $url -o $temp
            if ($LASTEXITCODE -ne 0 -or (Get-FileHash -LiteralPath $temp).Hash -ne $hash) {
                throw "Unable to verify $name. Place the original archive with SHA256 $hash in $Cache."
            }
            Move-Item -Force -LiteralPath $temp -Destination $path
        } finally { if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp } }
    }
    return $path
}

$archive = Fetch 'scrcpy-win64-v3.3.4.zip' 'd8a155b7c180b7ca4cdadd40712b8750b63f3aab48cb5b8a2a39ac2d0d4c5d38' 'https://github.com/Genymobile/scrcpy/releases/download/v3.3.4/scrcpy-win64-v3.3.4.zip'
$sources = @(
    Fetch 'scrcpy-v3.3.4-source.tar.gz' '03b72d1f71ca7783cff2995ca7cd8c67b9b75038a3cc35e443a1913e951b49dd' 'https://codeload.github.com/Genymobile/scrcpy/tar.gz/refs/tags/v3.3.4'
    Fetch 'ffmpeg-7.1.1.tar.xz' '733984395e0dbbe5c046abda2dc49a5544e7e0e1e2366bba849222ae9e3a03b1' 'https://ffmpeg.org/releases/ffmpeg-7.1.1.tar.xz'
    Fetch 'libusb-1.0.29.tar.bz2' '5977fc950f8d1395ccea9bd48c06b3f808fd3c2c961b44b0c2e6e29fc3a70a85' 'https://sources.buildroot.net/libusb/libusb-1.0.29.tar.bz2'
    Fetch 'dav1d-1.5.0.tar.gz' '78b15d9954b513ea92d27f39362535ded2243e1b0924fde39f37a31ebed5f76b' 'https://code.videolan.org/videolan/dav1d/-/archive/1.5.0/dav1d-1.5.0.tar.gz'
)

$unpacked = Join-Path $Cache 'scrcpy'
Expand-Archive -LiteralPath $archive -DestinationPath $unpacked -Force
$tools = Join-Path $Destination 'scrcpy'
$sourceDir = Join-Path $Destination 'sources'
New-Item -ItemType Directory -Force $tools, $sourceDir | Out-Null
$names = @('adb.exe','AdbWinApi.dll','AdbWinUsbApi.dll','scrcpy.exe','scrcpy-server','icon.png',
           'avcodec-61.dll','avformat-61.dll','avutil-59.dll','swresample-5.dll','SDL2.dll','libusb-1.0.dll')
foreach ($name in $names) {
    $source = Join-Path $unpacked "scrcpy-win64-v3.3.4/$name"
    $target = Join-Path $tools $name
    if (!(Test-Path -LiteralPath $target) -or (Get-FileHash -LiteralPath $target).Hash -ne (Get-FileHash -LiteralPath $source).Hash) {
        Copy-Item -LiteralPath $source -Destination $target
    }
}
if ((Get-FileHash (Join-Path $tools 'scrcpy-server')).Hash -ne '8588238c9a5a00aa542906b6ec7e6d5541d9ffb9b5d0f6e1bc0e365e2303079e') {
    throw 'scrcpy-server does not match the v3.3.4 host'
}
Copy-Item -LiteralPath $sources -Destination $sourceDir
$licenses = Join-Path $Destination 'licenses'
New-Item -ItemType Directory -Force $licenses | Out-Null
Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'licenses') -File | Copy-Item -Destination $licenses
