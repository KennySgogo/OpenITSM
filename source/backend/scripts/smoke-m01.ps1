param([int]$Port = 18080)
$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
$database = $env:OPENITSM_TEST_DATABASE
if ($database -notmatch '^openitsm_m01_test_[a-f0-9]{32}$') { throw 'Smoke requires the isolated M01 test database' }
if (!$env:OPENITSM_TEST_ADMIN_JDBC_URL -or !$env:OPENITSM_TEST_DB_USER -or !$env:OPENITSM_TEST_DB_PASSWORD) { throw 'Missing process-only test connection settings' }
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
try { $listener.Start() } finally { $listener.Stop() }
$originalUrl = $env:OPENITSM_DB_URL
$originalUser = $env:OPENITSM_DB_USER
$originalPassword = $env:OPENITSM_DB_PASSWORD
$appProcess = $null
$client = $null
$handler = $null
try {
    $adminUrl = $env:OPENITSM_TEST_ADMIN_JDBC_URL
    $env:OPENITSM_DB_URL = $adminUrl.Substring(0, $adminUrl.LastIndexOf('/') + 1) + $database
    $env:OPENITSM_DB_USER = $env:OPENITSM_TEST_DB_USER
    $env:OPENITSM_DB_PASSWORD = $env:OPENITSM_TEST_DB_PASSWORD
    $jar = Join-Path $backendRoot 'target/backend-0.1.0-SNAPSHOT.jar'
    $java = (Get-Command java).Source
    $appProcess = Start-Process -FilePath $java -ArgumentList @('-jar', $jar, '--spring.profiles.active=local', '--spring.config.import=', "--server.port=$Port") -WindowStyle Hidden -PassThru -WorkingDirectory $backendRoot -RedirectStandardOutput (Join-Path $backendRoot 'target/smoke-stdout.log') -RedirectStandardError (Join-Path $backendRoot 'target/smoke-stderr.log')
    $handler = [System.Net.Http.HttpClientHandler]::new()
    $handler.CookieContainer = [System.Net.CookieContainer]::new()
    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.BaseAddress = [Uri]"http://127.0.0.1:$Port"
    $client.Timeout = [TimeSpan]::FromSeconds(2)
    $ready = $false
    $deadline = [DateTime]::UtcNow.AddSeconds(25)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($appProcess.HasExited) { throw 'Jar exited before readiness; inspect smoke logs' }
        try {
            $reply = $client.GetAsync('/api/v1/auth/csrf').GetAwaiter().GetResult()
            $ready = [int]$reply.StatusCode -eq 200
            $reply.Dispose()
            if ($ready) { break }
        } catch { }
        Start-Sleep -Milliseconds 250
    }
    if (!$ready) { throw 'Jar readiness deadline exceeded' }
    $anonymous = $client.GetStringAsync('/api/v1/auth/csrf').GetAwaiter().GetResult() | ConvertFrom-Json
    if (!$anonymous.data.csrfToken) { throw 'Missing anonymous CSRF token' }
    $me = $client.GetAsync('/api/v1/auth/me').GetAwaiter().GetResult()
    $meBody = $me.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
    if ([int]$me.StatusCode -ne 401 -or $meBody.error.code -ne 'SESSION_EXPIRED') { throw 'Unauthenticated contract failed' }
    $me.Dispose()
    $docs = $client.GetStringAsync('/v3/api-docs').GetAwaiter().GetResult() | ConvertFrom-Json
    if (!$docs.paths.'/api/v1/auth/login' -or !$docs.paths.'/api/v1/users/{id}/disable') { throw 'Missing actual M01 OpenAPI paths' }
    $csrf = $client.GetStringAsync('/api/v1/auth/csrf').GetAwaiter().GetResult() | ConvertFrom-Json
    $message = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, '/api/v1/auth/login')
    $message.Headers.Add('Origin', 'http://localhost:5173')
    $message.Headers.Add('X-CSRF-Token', $csrf.data.csrfToken)
    $message.Headers.TransferEncodingChunked = $true
    $message.Content = [System.Net.Http.StringContent]::new((' ' * (256 * 1024 + 1)) + '{}', [System.Text.Encoding]::UTF8, 'application/json')
    $reply = $client.SendAsync($message).GetAwaiter().GetResult()
    if ([int]$reply.StatusCode -ne 413) { throw 'Real HTTP chunked size limit failed' }
    $reply.Dispose()
    $message.Dispose()
    # Uses only the public test fixture left by M01IntegrationTest in this isolated test database.
    $loginMessage = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, '/api/v1/auth/login')
    $loginMessage.Headers.Add('Origin', 'http://localhost:5173')
    $loginMessage.Headers.Add('X-CSRF-Token', $csrf.data.csrfToken)
    $loginMessage.Content = [System.Net.Http.StringContent]::new('{"email":"admin@example.com","password":"TestPassword1234"}', [System.Text.Encoding]::UTF8, 'application/json')
    $loginReply = $client.SendAsync($loginMessage).GetAwaiter().GetResult()
    if ([int]$loginReply.StatusCode -ne 200) { throw 'Test fixture administrator login failed' }
    $loginBody = $loginReply.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
    if ($loginBody.data.defaultPath -ne '/dashboard') { throw 'Administrator route contract failed' }
    $loginReply.Dispose()
    $loginMessage.Dispose()
    $memberReply = $client.GetAsync('/api/v1/users').GetAwaiter().GetResult()
    $memberBody = $memberReply.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
    if ([int]$memberReply.StatusCode -ne 200 -or $memberBody.data.pageSize -ne 20) { throw 'Authenticated member query failed' }
    $memberReply.Dispose()
    $logoutMessage = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, '/api/v1/auth/logout')
    $logoutMessage.Headers.Add('Origin', 'http://localhost:5173')
    $logoutMessage.Headers.Add('X-CSRF-Token', $loginBody.data.csrfToken)
    $logoutReply = $client.SendAsync($logoutMessage).GetAwaiter().GetResult()
    if ([int]$logoutReply.StatusCode -ne 204) { throw 'Logout failed' }
    $logoutReply.Dispose()
    $logoutMessage.Dispose()
    $afterLogout = $client.GetAsync('/api/v1/auth/me').GetAwaiter().GetResult()
    if ([int]$afterLogout.StatusCode -ne 401) { throw 'Session remained usable after logout' }
    $afterLogout.Dispose()
    Write-Output 'PASS: jar startup, anonymous CSRF/401, OpenAPI routes, chunked 413, login, member query, logout/revocation'
} finally {
    if ($client) { $client.Dispose() }
    if ($handler) { $handler.Dispose() }
    if ($appProcess -and !$appProcess.HasExited) { Stop-Process -Id $appProcess.Id; $appProcess.WaitForExit() }
    $env:OPENITSM_DB_URL = $originalUrl
    $env:OPENITSM_DB_USER = $originalUser
    $env:OPENITSM_DB_PASSWORD = $originalPassword
}
