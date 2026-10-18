param([Parameter(Mandatory=$true)][string]$CallbackBaseUrl)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$envPath = Join-Path $projectRoot '.env.resend.local'
$statePath = Join-Path $projectRoot 'data/resend-callback.json'
if ($CallbackBaseUrl -notmatch '^https://[a-z0-9-]+\.trycloudflare\.com$') {
    throw 'Expected the HTTPS Quick Tunnel URL created for this project.'
}
$settings = @{}
foreach ($line in [IO.File]::ReadAllLines($envPath)) {
    if ($line -match '^\s*([A-Z_]+)\s*=(.*)$') {
        $settings[$matches[1]] = $matches[2].Trim().Trim('"').Trim("'")
    }
}
if ([string]::IsNullOrWhiteSpace($settings['RESEND_API_KEY'])) { throw 'Missing local API key.' }
$headers = @{Authorization=('Bearer '+$settings['RESEND_API_KEY'])}
function Invoke-Provider([string]$Method, [string]$Path, $Body) {
    try {
        $args = @{Uri=('https://api.resend.com'+$Path);Method=$Method;Headers=$headers;TimeoutSec=25}
        if ($null -ne $Body) {
            $args.ContentType = 'application/json'
            $args.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Compress))
        }
        return Invoke-RestMethod @args
    } catch {
        $code = 'unknown'
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
        throw "Resend $Method $Path failed (HTTP $code). No credential values displayed."
    }
}
function Save-Setting([string]$Name, [string]$Value) {
    if ($Value.Contains("`n") -or $Value.Contains("`r")) { throw 'Unexpected multiline setting.' }
    $text = [IO.File]::ReadAllText($envPath)
    $pattern = '(?m)^'+[regex]::Escape($Name)+'=.*$'
    if ([regex]::IsMatch($text,$pattern)) {
        $text = [regex]::Replace($text,$pattern,[Text.RegularExpressions.MatchEvaluator]{param($m) $Name+'='+$Value})
    } else { $text = $text.TrimEnd()+[Environment]::NewLine+$Name+'='+$Value+[Environment]::NewLine }
    [IO.File]::WriteAllText($envPath,$text,[Text.UTF8Encoding]::new($false))
}
$endpoint = $CallbackBaseUrl+'/api/mail/webhooks/resend'
$events = @('email.sent','email.delivered','email.delivery_delayed','email.bounced','email.failed','email.complained','email.suppressed')
if (Test-Path -LiteralPath $statePath) {
    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    if ($state.webhookId -notmatch '^[a-zA-Z0-9-]+$') { throw 'Invalid saved webhook ID.' }
    if ([string]::IsNullOrWhiteSpace($settings['RESEND_WEBHOOK_SECRET'])) { throw 'Saved webhook has no local signing secret. Recover it from Resend before continuing.' }
    $null = Invoke-Provider 'PATCH' ('/webhooks/'+$state.webhookId) @{endpoint=$endpoint;events=$events;status='enabled'}
    $state.endpoint = $endpoint
} else {
    $list = Invoke-Provider 'GET' '/webhooks' $null
    if (@($list.data).Count -gt 0) { throw 'Account already has webhooks. Inspect existing configuration before creating another.' }
    $created = Invoke-Provider 'POST' '/webhooks' @{endpoint=$endpoint;events=$events}
    if ([string]::IsNullOrWhiteSpace($created.id) -or [string]::IsNullOrWhiteSpace($created.signing_secret)) {
        throw 'Webhook creation response incomplete. Inspect provider before retrying.'
    }
    Save-Setting 'RESEND_WEBHOOK_SECRET' $created.signing_secret
    $state = [pscustomobject]@{webhookId=$created.id;endpoint=$endpoint}
}
Save-Setting 'RESEND_WEBHOOK_URL' $endpoint
[IO.Directory]::CreateDirectory((Split-Path $statePath)) | Out-Null
[IO.File]::WriteAllText($statePath,($state | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
Write-Output ('Webhook configured: '+$state.webhookId)
Write-Output ('Callback endpoint: '+$endpoint)
Write-Output 'Signing secret saved locally without displaying it. Existing mail sender is unchanged.'
