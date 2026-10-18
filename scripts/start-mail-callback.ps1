$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
docker compose -p mindbridge-callback -f docker-compose.callback.yml up -d
if ($LASTEXITCODE -ne 0) { throw 'Callback containers failed to start.' }
$callbackBase = $null
for ($attempt = 0; $attempt -lt 20; $attempt++) {
    $logText = (docker compose -p mindbridge-callback -f docker-compose.callback.yml logs --no-color callback-tunnel 2>&1) -join "`n"
    $urls = [regex]::Matches($logText,'https://[a-z0-9-]+\.trycloudflare\.com')
    if ($urls.Count) { $callbackBase=$urls[$urls.Count-1].Value; break }
    Start-Sleep -Seconds 2
}
if (-not $callbackBase) { throw 'No tunnel URL yet. Inspect callback-tunnel logs and retry.' }
& (Join-Path $PSScriptRoot 'configure-resend-callback.ps1') -CallbackBaseUrl $callbackBase
$compose = @('--env-file','.env.resend.local','-f','docker-compose.yml','-f','docker-compose.media.yml','-f','docker-compose.local.yml','-f','docker-compose.callback-app.yml')
$mcpEnabled = [IO.File]::ReadAllText((Join-Path $projectRoot '.env.resend.local')) -match '(?m)^MCP_REMOTE_ENABLED=true\s*$'
if ($mcpEnabled) { $compose += @('-f','docker-compose.mcp.yml') }
docker compose @compose up -d --no-deps --no-build app
if ($LASTEXITCODE -ne 0) { throw 'Application callback configuration failed.' }
Write-Output 'Callback configured. The application may take a moment to restart.'
Write-Output 'The previously selected mail transport is preserved. No test mail is created by this script.'
Write-Output ('Callback URL: '+$callbackBase+'/api/mail/webhooks/resend')
