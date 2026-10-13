param([ValidateRange(1,65535)][int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$installed = Get-Command cloudflared -ErrorAction SilentlyContinue
if ($installed) {
    $executable = $installed.Source
} else {
    $projectRoot = Split-Path -Parent $PSScriptRoot
    $runtimeDirectory = Join-Path $projectRoot '.local\cloudflared'
    $executable = Join-Path $runtimeDirectory 'cloudflared.exe'
    if (-not (Test-Path -LiteralPath $executable)) {
        New-Item -ItemType Directory -Path $runtimeDirectory -Force | Out-Null
        Write-Host 'Downloading cloudflared from the official Cloudflare GitHub release...'
        $download = Join-Path $runtimeDirectory 'cloudflared.download'
        Invoke-WebRequest -Uri 'https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe' -OutFile $download
        Move-Item -LiteralPath $download -Destination $executable
    }
}
Write-Host 'Keep STS and this terminal running. Press Ctrl+C to stop the tunnel.'
Write-Host 'Mattermost Request URL: https://<printed-host>/api/mattermost/study (POST)'
& $executable tunnel --url "http://127.0.0.1:$Port"