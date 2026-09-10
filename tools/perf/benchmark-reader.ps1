[CmdletBinding()]
param(
    [Parameter()]
    [ValidateNotNullOrEmpty()]
    [string] $BaseUrl = "http://localhost:8080",

    [Parameter(Mandatory = $true)]
    [Guid] $VolumeId,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $ChapterSlug,

    [Parameter(Mandatory = $true)]
    [Guid] $ChapterId,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $VoiceKey,

    [Parameter(Mandatory = $true)]
    [Guid] $MediaAssetId,

    [Parameter()]
    [ValidateRange(1, 1000)]
    [int] $WarmSamples = 10,

    [Parameter()]
    [ValidateRange(1, 100)]
    [int] $ColdSamples = 3,

    [Parameter()]
    [ValidateRange(0, 3600)]
    [double] $ColdPauseSeconds = 0,

    [Parameter()]
    [ValidateRange(1, 600)]
    [int] $RequestTimeoutSeconds = 120,

    [Parameter()]
    [ValidateNotNullOrEmpty()]
    [string] $OutputDirectory,

    [Parameter()]
    [switch] $Authenticated,

    [Parameter()]
    [System.Security.SecureString] $BrowserCookieHeader
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not $PSBoundParameters.ContainsKey("OutputDirectory")) {
    $OutputDirectory = Join-Path -Path $PSScriptRoot -ChildPath "results"
}

Add-Type -AssemblyName System.Net.Http

$InvariantCulture = [System.Globalization.CultureInfo]::InvariantCulture
$RequestedMediaRange = "bytes=0-65535"

function Add-BrowserCookiesToContainer {
    param(
        [Parameter(Mandatory = $true)]
        [System.Net.CookieContainer] $CookieContainer,

        [Parameter(Mandatory = $true)]
        [Uri] $Origin,

        [Parameter(Mandatory = $true)]
        [string] $HeaderValue
    )

    $cookieCount = 0
    foreach ($cookiePair in $HeaderValue.Split(';')) {
        $trimmedPair = $cookiePair.Trim()
        if ([string]::IsNullOrWhiteSpace($trimmedPair)) {
            continue
        }

        $separatorIndex = $trimmedPair.IndexOf('=')
        if ($separatorIndex -le 0) {
            throw "Invalid browser cookie header."
        }

        $cookieName = $trimmedPair.Substring(0, $separatorIndex).Trim()
        $cookieValue = $trimmedPair.Substring($separatorIndex + 1).Trim()
        if ([string]::IsNullOrWhiteSpace($cookieName)) {
            throw "Invalid browser cookie header."
        }

        $cookie = [System.Net.Cookie]::new($cookieName, $cookieValue, "/")
        $CookieContainer.Add($Origin, $cookie)
        $cookieCount++
    }

    if ($cookieCount -eq 0) {
        throw "Invalid browser cookie header."
    }
}

function New-AuthenticatedCookieContainer {
    param(
        [Parameter(Mandatory = $true)]
        [System.Security.SecureString] $SecureHeader,

        [Parameter(Mandatory = $true)]
        [Uri] $Origin
    )

    $unmanagedHeader = [IntPtr]::Zero
    $plainHeader = $null
    try {
        $unmanagedHeader = [Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode(
            $SecureHeader
        )
        $plainHeader = [Runtime.InteropServices.Marshal]::PtrToStringUni($unmanagedHeader)
        $container = [System.Net.CookieContainer]::new()
        Add-BrowserCookiesToContainer `
            -CookieContainer $container `
            -Origin $Origin `
            -HeaderValue $plainHeader
        return $container
    }
    catch {
        throw "Authenticated session setup failed."
    }
    finally {
        $plainHeader = $null
        if ($unmanagedHeader -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeGlobalAllocUnicode($unmanagedHeader)
        }
    }
}

function Invoke-AuthenticationPreflight {
    param(
        [Parameter(Mandatory = $true)]
        [System.Net.Http.HttpClient] $Client
    )

    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::Get,
        [Uri] ($script:NormalizedBaseUrl + "/novel/history")
    )
    $request.Headers.Accept.ParseAdd("text/html")
    $response = $null
    try {
        $response = $Client.SendAsync($request).GetAwaiter().GetResult()
        if ([int] $response.StatusCode -ne 200) {
            throw "Authenticated session preflight failed."
        }
    }
    catch {
        throw "Authenticated session preflight failed."
    }
    finally {
        if ($null -ne $response) {
            $response.Dispose()
        }
        $request.Dispose()
    }
}

function Get-ChapterCsrfContext {
    param(
        [Parameter(Mandatory = $true)]
        [System.Net.Http.HttpClient] $Client,

        [Parameter(Mandatory = $true)]
        [string] $EncodedChapterSlug
    )

    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::Get,
        [Uri] ($script:NormalizedBaseUrl + "/novel/chapters/" + $EncodedChapterSlug)
    )
    $request.Headers.Accept.ParseAdd("text/html")
    $response = $null
    try {
        $response = $Client.SendAsync($request).GetAwaiter().GetResult()
        if ([int] $response.StatusCode -ne 200) {
            throw "CSRF acquisition failed."
        }

        $html = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $tokenMatch = [regex]::Match(
            $html,
            '<meta\b[^>]*\bname\s*=\s*"_csrf"[^>]*\bcontent\s*=\s*"(?<value>[^"]+)"',
            [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
        )
        $headerMatch = [regex]::Match(
            $html,
            '<meta\b[^>]*\bname\s*=\s*"_csrf_header"[^>]*\bcontent\s*=\s*"(?<value>[^"]+)"',
            [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
        )
        if (-not $tokenMatch.Success -or -not $headerMatch.Success) {
            throw "CSRF acquisition failed."
        }

        $token = [System.Net.WebUtility]::HtmlDecode($tokenMatch.Groups['value'].Value)
        $headerName = [System.Net.WebUtility]::HtmlDecode($headerMatch.Groups['value'].Value)
        if ([string]::IsNullOrWhiteSpace($token) -or
                [string]::IsNullOrWhiteSpace($headerName) -or
                $token.Contains("`r") -or
                $token.Contains("`n") -or
                $headerName.Contains("`r") -or
                $headerName.Contains("`n")) {
            throw "CSRF acquisition failed."
        }

        return [pscustomobject]@{
            HeaderName = $headerName
            Token = $token
        }
    }
    catch {
        throw "CSRF acquisition failed."
    }
    finally {
        if ($null -ne $response) {
            $response.Dispose()
        }
        $request.Dispose()
    }
}

function New-ReaderStateRequest {
    param(
        [Parameter(Mandatory = $true)]
        [Uri] $Uri,

        [Parameter(Mandatory = $true)]
        [string] $CsrfHeaderName,

        [Parameter(Mandatory = $true)]
        [string] $CsrfToken
    )

    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::Post,
        $Uri
    )
    $request.Headers.Accept.ParseAdd("*/*")
    $request.Content = [System.Net.Http.ByteArrayContent]::new([byte[]]::new(0))
    $request.Content.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new(
        "application/json"
    )
    if (-not $request.Headers.TryAddWithoutValidation($CsrfHeaderName, $CsrfToken)) {
        $request.Dispose()
        throw "CSRF request setup failed."
    }
    return $request
}

function Invoke-ReaderStatePreflight {
    param(
        [Parameter(Mandatory = $true)]
        [System.Net.Http.HttpClient] $Client,

        [Parameter(Mandatory = $true)]
        [Guid] $TargetChapterId,

        [Parameter(Mandatory = $true)]
        [string] $CsrfHeaderName,

        [Parameter(Mandatory = $true)]
        [string] $CsrfToken
    )

    foreach ($statePath in @("progress", "history")) {
        $request = $null
        $response = $null
        try {
            $request = New-ReaderStateRequest `
                -Uri ([Uri] ($script:NormalizedBaseUrl + "/novel/chapters/" + $TargetChapterId + "/" + $statePath)) `
                -CsrfHeaderName $CsrfHeaderName `
                -CsrfToken $CsrfToken
            $response = $Client.SendAsync($request).GetAwaiter().GetResult()
            if ([int] $response.StatusCode -ne 204) {
                throw "Authenticated state preflight failed."
            }
        }
        catch {
            throw "Authenticated state preflight failed."
        }
        finally {
            if ($null -ne $response) {
                $response.Dispose()
            }
            if ($null -ne $request) {
                $request.Dispose()
            }
        }
    }
}

function Get-HeaderValue {
    param(
        [Parameter(Mandatory = $true)]
        [System.Net.Http.HttpResponseMessage] $Response,

        [Parameter(Mandatory = $true)]
        [string] $Name
    )

    $values = [System.Collections.Generic.IEnumerable[string]] $null
    if ($Response.Headers.TryGetValues($Name, [ref] $values)) {
        return [string]::Join(", ", $values)
    }
    if ($null -ne $Response.Content -and $Response.Content.Headers.TryGetValues($Name, [ref] $values)) {
        return [string]::Join(", ", $values)
    }
    return $null
}

function ConvertFrom-ServerTiming {
    param(
        [AllowNull()]
        [string] $HeaderValue
    )

    $metrics = @{}
    if ([string]::IsNullOrWhiteSpace($HeaderValue)) {
        return $metrics
    }

    $entries = [regex]::Split($HeaderValue, ',(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)')
    foreach ($entry in $entries) {
        $durationMatch = [regex]::Match(
            $entry,
            '^\s*(?<name>[A-Za-z][A-Za-z0-9_-]*)\s*;\s*dur=(?<duration>[0-9]+(?:\.[0-9]+)?)'
        )
        if (-not $durationMatch.Success) {
            continue
        }

        $name = $durationMatch.Groups['name'].Value.ToLowerInvariant()
        $duration = [double]::Parse(
            $durationMatch.Groups['duration'].Value,
            $InvariantCulture
        )

        $count = $null
        $countMatch = [regex]::Match($entry, 'desc\s*=\s*\"(?<count>[0-9]+)\s+')
        if ($countMatch.Success) {
            $count = [int]::Parse($countMatch.Groups['count'].Value, $InvariantCulture)
        }

        $metrics[$name] = [pscustomobject]@{
            DurationMilliseconds = $duration
            Count = $count
        }
    }

    return $metrics
}

function Add-ValidationMessage {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [System.Collections.Generic.List[string]] $Messages,

        [Parameter(Mandatory = $true)]
        [string] $Message
    )

    [void] $Messages.Add($Message)
}

function Invoke-BenchmarkSample {
    param(
        [Parameter(Mandatory = $true)]
        [System.Net.Http.HttpClient] $Client,

        [Parameter(Mandatory = $true)]
        [object] $Endpoint,

        [Parameter(Mandatory = $true)]
        [ValidateSet("cold-ish", "warm")]
        [string] $SampleType,

        [Parameter(Mandatory = $true)]
        [int] $SampleNumber,

        [AllowNull()]
        [string] $CsrfHeaderName,

        [AllowNull()]
        [string] $CsrfToken
    )

    $validationErrors = [System.Collections.Generic.List[string]]::new()
    $warnings = [System.Collections.Generic.List[string]]::new()
    if ($Endpoint.Method -eq "POST") {
        $request = New-ReaderStateRequest `
            -Uri ([Uri] ($script:NormalizedBaseUrl + $Endpoint.Path)) `
            -CsrfHeaderName $CsrfHeaderName `
            -CsrfToken $CsrfToken
    } else {
        $request = [System.Net.Http.HttpRequestMessage]::new(
            [System.Net.Http.HttpMethod]::Get,
            [Uri] ($script:NormalizedBaseUrl + $Endpoint.Path)
        )
        $request.Headers.Accept.ParseAdd($Endpoint.Accept)
    }
    if ($Endpoint.IsMediaRange) {
        $request.Headers.Range = [System.Net.Http.Headers.RangeHeaderValue]::new(0, 65535)
    }

    $timestamp = [DateTimeOffset]::UtcNow.ToString("o", $InvariantCulture)
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $response = $null
    $statusCode = $null
    $responseSizeBytes = $null
    $serverTiming = $null
    $contentRange = $null
    $acceptRanges = $null

    try {
        $response = $Client.SendAsync(
            $request,
            [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead
        ).GetAwaiter().GetResult()

        $statusCode = [int] $response.StatusCode
        $serverTiming = Get-HeaderValue -Response $response -Name "Server-Timing"
        $contentRange = Get-HeaderValue -Response $response -Name "Content-Range"
        $acceptRanges = Get-HeaderValue -Response $response -Name "Accept-Ranges"

        $responseBytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        $responseSizeBytes = [long] $responseBytes.LongLength
    }
    catch {
        Add-ValidationMessage -Messages $validationErrors -Message (
            "Request failed: " + $_.Exception.GetType().Name
        )
    }
    finally {
        $stopwatch.Stop()
        if ($null -ne $response) {
            $response.Dispose()
        }
        $request.Dispose()
    }

    if ($null -ne $statusCode -and $statusCode -ne $Endpoint.ExpectedStatus) {
        Add-ValidationMessage -Messages $validationErrors -Message (
            "Unexpected HTTP status: expected {0}, observed {1}" -f $Endpoint.ExpectedStatus, $statusCode
        )
    }

    if ([string]::IsNullOrWhiteSpace($serverTiming)) {
        Add-ValidationMessage -Messages $validationErrors -Message "Missing Server-Timing header"
    }

    $parsedTiming = ConvertFrom-ServerTiming -HeaderValue $serverTiming
    $primaryMetricName = $Endpoint.PrimaryMetric
    foreach ($requiredMetric in @($primaryMetricName, "conn", "sql", "tx", "app")) {
        if (-not $parsedTiming.ContainsKey($requiredMetric)) {
            Add-ValidationMessage -Messages $validationErrors -Message (
                "Missing or malformed Server-Timing metric: " + $requiredMetric
            )
        }
    }

    foreach ($countedMetric in @("conn", "sql", "tx")) {
        if ($parsedTiming.ContainsKey($countedMetric) -and $null -eq $parsedTiming[$countedMetric].Count) {
            Add-ValidationMessage -Messages $validationErrors -Message (
                "Missing Server-Timing count for metric: " + $countedMetric
            )
        }
    }

    if ($Endpoint.IsMediaRange) {
        if ($contentRange -notmatch '^bytes\s+0-[0-9]+/[0-9]+$') {
            Add-ValidationMessage -Messages $validationErrors -Message "Missing or unexpected Content-Range"
        }
        if ($acceptRanges -ne "bytes") {
            Add-ValidationMessage -Messages $validationErrors -Message "Missing or unexpected Accept-Ranges"
        }
    }

    $primaryDuration = $null
    $connCount = $null
    $connDuration = $null
    $sqlCount = $null
    $sqlDuration = $null
    $txCount = $null
    $txDuration = $null
    $appDuration = $null

    if ($parsedTiming.ContainsKey($primaryMetricName)) {
        $primaryDuration = $parsedTiming[$primaryMetricName].DurationMilliseconds
    }
    if ($parsedTiming.ContainsKey("conn")) {
        $connCount = $parsedTiming["conn"].Count
        $connDuration = $parsedTiming["conn"].DurationMilliseconds
    }
    if ($parsedTiming.ContainsKey("sql")) {
        $sqlCount = $parsedTiming["sql"].Count
        $sqlDuration = $parsedTiming["sql"].DurationMilliseconds
    }
    if ($parsedTiming.ContainsKey("tx")) {
        $txCount = $parsedTiming["tx"].Count
        $txDuration = $parsedTiming["tx"].DurationMilliseconds
    }
    if ($parsedTiming.ContainsKey("app")) {
        $appDuration = $parsedTiming["app"].DurationMilliseconds
    }

    $sqlCountMatchesExpected = $null
    if ($null -ne $Endpoint.ExpectedSqlCount -and $null -ne $sqlCount) {
        $sqlCountMatchesExpected = ($sqlCount -eq $Endpoint.ExpectedSqlCount)
        if (-not $sqlCountMatchesExpected) {
            [void] $warnings.Add(
                "SQL count mismatch: expected {0}, observed {1}" -f $Endpoint.ExpectedSqlCount, $sqlCount
            )
        }
    }

    return [pscustomobject]@{
        TimestampUtc = $timestamp
        Endpoint = $Endpoint.Label
        SampleType = $SampleType
        SampleNumber = $SampleNumber
        IsValid = ($validationErrors.Count -eq 0)
        ValidationErrors = [string]::Join(" | ", $validationErrors)
        Warnings = [string]::Join(" | ", $warnings)
        ExpectedHttpStatus = $Endpoint.ExpectedStatus
        HttpStatus = $statusCode
        ResponseSizeBytes = $responseSizeBytes
        ClientElapsedMilliseconds = [Math]::Round($stopwatch.Elapsed.TotalMilliseconds, 3)
        ServerTiming = $serverTiming
        PrimaryServerMetric = $primaryMetricName
        PrimaryServerMilliseconds = $primaryDuration
        ConnectionAcquisitionCount = $connCount
        ConnectionAcquisitionMilliseconds = $connDuration
        SqlCount = $sqlCount
        SqlMilliseconds = $sqlDuration
        TxControlCount = $txCount
        TxMilliseconds = $txDuration
        AppMilliseconds = $appDuration
        ExpectedSqlCount = $Endpoint.ExpectedSqlCount
        SqlCountMatchesExpected = $sqlCountMatchesExpected
        RequestedRange = $(if ($Endpoint.IsMediaRange) { $RequestedMediaRange } else { $null })
        ContentRange = $contentRange
        AcceptRanges = $acceptRanges
    }
}

function Get-Percentile {
    param(
        [Parameter(Mandatory = $true)]
        [double[]] $Values,

        [Parameter(Mandatory = $true)]
        [ValidateRange(0, 1)]
        [double] $Percentile
    )

    $sorted = @($Values | Sort-Object)
    if ($sorted.Count -eq 1) {
        return [double] $sorted[0]
    }

    $rank = ($sorted.Count - 1) * $Percentile
    $lowerIndex = [int] [Math]::Floor($rank)
    $upperIndex = [int] [Math]::Ceiling($rank)
    if ($lowerIndex -eq $upperIndex) {
        return [double] $sorted[$lowerIndex]
    }

    $weight = $rank - $lowerIndex
    return ([double] $sorted[$lowerIndex]) + (
        (([double] $sorted[$upperIndex]) - ([double] $sorted[$lowerIndex])) * $weight
    )
}

function Get-StatisticSet {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [object[]] $Rows,

        [Parameter(Mandatory = $true)]
        [string] $PropertyName
    )

    $values = [System.Collections.Generic.List[double]]::new()
    foreach ($row in $Rows) {
        $value = $row.$PropertyName
        if ($null -ne $value) {
            [void] $values.Add([double] $value)
        }
    }

    if ($values.Count -eq 0) {
        return [pscustomobject]@{ Min = $null; P50 = $null; P90 = $null; Max = $null; Mean = $null }
    }

    $array = $values.ToArray()
    $measurement = $array | Measure-Object -Minimum -Maximum -Average
    return [pscustomobject]@{
        Min = [Math]::Round([double] $measurement.Minimum, 3)
        P50 = [Math]::Round((Get-Percentile -Values $array -Percentile 0.5), 3)
        P90 = [Math]::Round((Get-Percentile -Values $array -Percentile 0.9), 3)
        Max = [Math]::Round([double] $measurement.Maximum, 3)
        Mean = [Math]::Round([double] $measurement.Average, 3)
    }
}

function New-BenchmarkSummary {
    param(
        [Parameter(Mandatory = $true)]
        [object[]] $Rows,

        [Parameter(Mandatory = $true)]
        [object[]] $Endpoints
    )

    $summary = [System.Collections.Generic.List[object]]::new()
    foreach ($endpoint in $Endpoints) {
        $warmRows = @($Rows | Where-Object {
            $_.Endpoint -eq $endpoint.Label -and $_.SampleType -eq "warm"
        })
        $validRows = @($warmRows | Where-Object { $_.IsValid })

        $client = Get-StatisticSet -Rows $validRows -PropertyName "ClientElapsedMilliseconds"
        $primary = Get-StatisticSet -Rows $validRows -PropertyName "PrimaryServerMilliseconds"
        $sql = Get-StatisticSet -Rows $validRows -PropertyName "SqlMilliseconds"
        $tx = Get-StatisticSet -Rows $validRows -PropertyName "TxMilliseconds"
        $app = Get-StatisticSet -Rows $validRows -PropertyName "AppMilliseconds"

        $sqlCounts = @($validRows | ForEach-Object { $_.SqlCount } | Where-Object { $null -ne $_ } | Sort-Object -Unique)
        $txCounts = @($validRows | ForEach-Object { $_.TxControlCount } | Where-Object { $null -ne $_ } | Sort-Object -Unique)
        $sqlMismatchCount = @($validRows | Where-Object { $_.SqlCountMatchesExpected -eq $false }).Count

        [void] $summary.Add([pscustomobject]@{
            Endpoint = $endpoint.Label
            PrimaryServerMetric = $endpoint.PrimaryMetric
            ValidWarmCount = $validRows.Count
            InvalidWarmCount = $warmRows.Count - $validRows.Count
            ExpectedSqlCount = $endpoint.ExpectedSqlCount
            ObservedSqlCounts = [string]::Join("|", $sqlCounts)
            StableSqlCount = ($sqlCounts.Count -eq 1)
            SqlCountMismatchCount = $sqlMismatchCount
            ObservedTxControlCounts = [string]::Join("|", $txCounts)
            StableTxControlCount = ($txCounts.Count -eq 1)
            ClientMinMs = $client.Min
            ClientP50Ms = $client.P50
            ClientP90Ms = $client.P90
            ClientMaxMs = $client.Max
            ClientMeanMs = $client.Mean
            ServerMinMs = $primary.Min
            ServerP50Ms = $primary.P50
            ServerP90Ms = $primary.P90
            ServerMaxMs = $primary.Max
            ServerMeanMs = $primary.Mean
            SqlMinMs = $sql.Min
            SqlP50Ms = $sql.P50
            SqlP90Ms = $sql.P90
            SqlMaxMs = $sql.Max
            SqlMeanMs = $sql.Mean
            TxMinMs = $tx.Min
            TxP50Ms = $tx.P50
            TxP90Ms = $tx.P90
            TxMaxMs = $tx.Max
            TxMeanMs = $tx.Mean
            AppMinMs = $app.Min
            AppP50Ms = $app.P50
            AppP90Ms = $app.P90
            AppMaxMs = $app.Max
            AppMeanMs = $app.Mean
        })
    }

    return $summary.ToArray()
}

$baseUri = $null
if (-not [Uri]::TryCreate($BaseUrl, [UriKind]::Absolute, [ref] $baseUri) -or
        ($baseUri.Scheme -ne "http" -and $baseUri.Scheme -ne "https")) {
    throw "BaseUrl must be an absolute HTTP or HTTPS URL."
}
if (-not [string]::IsNullOrEmpty($baseUri.UserInfo)) {
    throw "BaseUrl must not contain credentials."
}
$script:NormalizedBaseUrl = $BaseUrl.TrimEnd('/')

$encodedChapterSlug = [Uri]::EscapeDataString($ChapterSlug)
$encodedVoiceKey = [Uri]::EscapeDataString($VoiceKey)
$isAuthenticatedMode = $Authenticated.IsPresent

if (-not $isAuthenticatedMode -and $PSBoundParameters.ContainsKey("BrowserCookieHeader")) {
    throw "BrowserCookieHeader requires -Authenticated."
}

if ($isAuthenticatedMode) {
    $expectedSqlCounts = @{
        Home = 2
        Novel = $null
        ChapterList = 3
        ChapterHtml = 9
        VoiceCatalog = 3
        PlaybackMetadata = 6
        MediaRange = 4
        ProgressWrite = $null
        HistoryWrite = $null
    }
} else {
    $expectedSqlCounts = @{
        Home = $null
        Novel = 3
        ChapterList = 1
        ChapterHtml = 5
        VoiceCatalog = 1
        PlaybackMetadata = 4
        MediaRange = 2
    }
}

$endpoints = @(
    [pscustomobject]@{ Label = "home"; Method = "GET"; Path = "/"; ExpectedStatus = 200; PrimaryMetric = "total"; ExpectedSqlCount = $expectedSqlCounts.Home; IsMediaRange = $false; Accept = "text/html" },
    [pscustomobject]@{ Label = "novel"; Method = "GET"; Path = "/novel"; ExpectedStatus = 200; PrimaryMetric = "total"; ExpectedSqlCount = $expectedSqlCounts.Novel; IsMediaRange = $false; Accept = "text/html" },
    [pscustomobject]@{ Label = "chapter-list"; Method = "GET"; Path = "/novel/volumes/$VolumeId/chapters"; ExpectedStatus = 200; PrimaryMetric = "total"; ExpectedSqlCount = $expectedSqlCounts.ChapterList; IsMediaRange = $false; Accept = "text/html" },
    [pscustomobject]@{ Label = "chapter-html"; Method = "GET"; Path = "/novel/chapters/$encodedChapterSlug"; ExpectedStatus = 200; PrimaryMetric = "total"; ExpectedSqlCount = $expectedSqlCounts.ChapterHtml; IsMediaRange = $false; Accept = "text/html" },
    [pscustomobject]@{ Label = "voice-catalog"; Method = "GET"; Path = "/api/novel/narration/voices"; ExpectedStatus = 200; PrimaryMetric = "total"; ExpectedSqlCount = $expectedSqlCounts.VoiceCatalog; IsMediaRange = $false; Accept = "application/json" },
    [pscustomobject]@{ Label = "playback-metadata"; Method = "GET"; Path = "/api/novel/chapters/$ChapterId/narration/playback?voiceKey=$encodedVoiceKey"; ExpectedStatus = 200; PrimaryMetric = "total"; ExpectedSqlCount = $expectedSqlCounts.PlaybackMetadata; IsMediaRange = $false; Accept = "application/json" },
    [pscustomobject]@{ Label = "media-range"; Method = "GET"; Path = "/media/assets/$MediaAssetId/content"; ExpectedStatus = 206; PrimaryMetric = "firstbyte"; ExpectedSqlCount = $expectedSqlCounts.MediaRange; IsMediaRange = $true; Accept = "audio/*, */*" }
)

if ($isAuthenticatedMode) {
    $endpoints += @(
        [pscustomobject]@{ Label = "progress-write"; Method = "POST"; Path = "/novel/chapters/$ChapterId/progress"; ExpectedStatus = 204; PrimaryMetric = "total"; ExpectedSqlCount = $expectedSqlCounts.ProgressWrite; IsMediaRange = $false; Accept = "*/*" },
        [pscustomobject]@{ Label = "history-write"; Method = "POST"; Path = "/novel/chapters/$ChapterId/history"; ExpectedStatus = 204; PrimaryMetric = "total"; ExpectedSqlCount = $expectedSqlCounts.HistoryWrite; IsMediaRange = $false; Accept = "*/*" }
    )
}

$cookieContainer = $null
$promptedCookieHeader = $false
if ($isAuthenticatedMode) {
    if ($null -eq $BrowserCookieHeader) {
        $BrowserCookieHeader = Read-Host `
            -Prompt "Paste the browser Cookie request header" `
            -AsSecureString
        $promptedCookieHeader = $true
    }

    try {
        if ($null -eq $BrowserCookieHeader -or $BrowserCookieHeader.Length -eq 0) {
            throw "Authenticated session setup failed."
        }
        $cookieContainer = New-AuthenticatedCookieContainer `
            -SecureHeader $BrowserCookieHeader `
            -Origin $baseUri
    }
    finally {
        if ($promptedCookieHeader -and $null -ne $BrowserCookieHeader) {
            $BrowserCookieHeader.Dispose()
        }
        $BrowserCookieHeader = $null
    }
}

$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.UseCookies = $isAuthenticatedMode
if ($isAuthenticatedMode) {
    $handler.CookieContainer = $cookieContainer
}
$handler.UseDefaultCredentials = $false
$handler.AllowAutoRedirect = $false
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds($RequestTimeoutSeconds)
if ($isAuthenticatedMode) {
    $client.DefaultRequestHeaders.UserAgent.ParseAdd("KiemLai-Authenticated-Perf-Collector/1.0")
} else {
    $client.DefaultRequestHeaders.UserAgent.ParseAdd("KiemLai-Anonymous-Perf-Collector/1.0")
}

$rows = [System.Collections.Generic.List[object]]::new()
$csrfHeaderName = $null
$csrfToken = $null
try {
    if ($isAuthenticatedMode) {
        Invoke-AuthenticationPreflight -Client $client
        $csrfContext = Get-ChapterCsrfContext `
            -Client $client `
            -EncodedChapterSlug $encodedChapterSlug
        $csrfHeaderName = $csrfContext.HeaderName
        $csrfToken = $csrfContext.Token
        Invoke-ReaderStatePreflight `
            -Client $client `
            -TargetChapterId $ChapterId `
            -CsrfHeaderName $csrfHeaderName `
            -CsrfToken $csrfToken
    }

    $hasIssuedRequest = $false
    for ($sampleNumber = 1; $sampleNumber -le $ColdSamples; $sampleNumber++) {
        foreach ($endpoint in $endpoints) {
            if ($hasIssuedRequest -and $ColdPauseSeconds -gt 0) {
                Start-Sleep -Milliseconds ([int] [Math]::Round($ColdPauseSeconds * 1000))
            }
            Write-Host ("cold-ish {0}/{1}: {2}" -f $sampleNumber, $ColdSamples, $endpoint.Label)
            $sampleParameters = @{
                Client = $client
                Endpoint = $endpoint
                SampleType = "cold-ish"
                SampleNumber = $sampleNumber
                CsrfHeaderName = $csrfHeaderName
                CsrfToken = $csrfToken
            }
            [void] $rows.Add((Invoke-BenchmarkSample @sampleParameters))
            $hasIssuedRequest = $true
        }
    }

    for ($sampleNumber = 1; $sampleNumber -le $WarmSamples; $sampleNumber++) {
        foreach ($endpoint in $endpoints) {
            Write-Host ("warm {0}/{1}: {2}" -f $sampleNumber, $WarmSamples, $endpoint.Label)
            $sampleParameters = @{
                Client = $client
                Endpoint = $endpoint
                SampleType = "warm"
                SampleNumber = $sampleNumber
                CsrfHeaderName = $csrfHeaderName
                CsrfToken = $csrfToken
            }
            [void] $rows.Add((Invoke-BenchmarkSample @sampleParameters))
        }
    }
}
finally {
    $csrfToken = $null
    $csrfHeaderName = $null
    $client.Dispose()
    $handler.Dispose()
    $cookieContainer = $null
}

$resolvedOutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
[void] [System.IO.Directory]::CreateDirectory($resolvedOutputDirectory)
$fileTimestamp = [DateTimeOffset]::Now.ToString("yyyyMMdd-HHmmss-fff", $InvariantCulture)
$outputMode = $(if ($isAuthenticatedMode) { "authenticated" } else { "anonymous" })
$rawPath = Join-Path $resolvedOutputDirectory ("reader-{0}-{1}-raw.csv" -f $outputMode, $fileTimestamp)
$summaryPath = Join-Path $resolvedOutputDirectory ("reader-{0}-{1}-summary.csv" -f $outputMode, $fileTimestamp)
if ((Test-Path -LiteralPath $rawPath) -or (Test-Path -LiteralPath $summaryPath)) {
    throw "Refusing to overwrite an existing benchmark output. Run the collector again for a new timestamp."
}

$rawRows = $rows.ToArray()
$summaryRows = New-BenchmarkSummary -Rows $rawRows -Endpoints $endpoints
$rawRows | Export-Csv -LiteralPath $rawPath -NoTypeInformation -Encoding UTF8
$summaryRows | Export-Csv -LiteralPath $summaryPath -NoTypeInformation -Encoding UTF8

Write-Host "Raw samples: $rawPath"
Write-Host "Warm summary: $summaryPath"
