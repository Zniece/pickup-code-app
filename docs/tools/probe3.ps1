$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
$UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36'

$probes = [ordered]@{
  sf       = @('https://www.sf-express.com/')
  cainiao  = @('https://www.cainiao.com/')
  ems      = @('http://www.ems.com.cn/','https://www.ems.com.cn/')
  luckin   = @('https://www.luckincoffee.com/')
  domino   = @('https://www.dominos.com.cn/')
  haidilao = @('https://www.haidilao.com/')
  bkh      = @('https://www.burgerking.com.cn/')
  hls      = @('https://www.hls1.com/')
  taobao   = @('https://www.taobao.com/')
  eleme    = @('https://www.ele.me/')
  kfc      = @('https://www.kfc.com.cn/')
  sto      = @('https://www.sto.cn/')
}

foreach ($key in $probes.Keys) {
  $html = ''; $base = ''
  foreach ($u in $probes[$key]) {
    try {
      $r = Invoke-WebRequest -Uri $u -UseBasicParsing -TimeoutSec 18 -Headers @{ 'User-Agent'=$UA; 'Accept-Language'='zh-CN,zh;q=0.9' }
      $html = $r.Content; $base = $u; break
    } catch { Write-Output "$key : fetch $u FAIL" }
  }
  if ($html -eq '') { continue }
  Write-Output "== $key ($base, $($html.Length)b) =="
  # all asset-ish urls in html
  $all = [regex]::Matches($html, '(?:src|href|content)=["'']([^"'']+\.(?:png|jpe?g|webp|svg|ico)(?:\?[^"'']*)?)["'']') |
    ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ -notmatch 'data:|base64' } | Select-Object -Unique -First 12
  foreach ($a in $all) {
    if ($a -match '^https?://') { $u2 = $a }
    elseif ($a -match '^//') { $u2 = "https:$a" }
    elseif ($a.StartsWith('/')) { $u2 = "https://$([Uri]$base).Authority$a" }
    else { try { $u2 = ([Uri]::new([Uri]$base,$a).AbsoluteUri) } catch { $u2 = $a } }
    # try HEAD-ish download to get size
    try {
      $rr = Invoke-WebRequest -Uri $u2 -UseBasicParsing -TimeoutSec 10 -Method Head -Headers @{ 'User-Agent'=$UA }
      "  $($rr.Headers['Content-Length'])B  $u2"
    } catch { "  ?B  $u2" }
  }
  # common paths
  foreach ($p in @('/favicon.ico','/logo.png','/images/logo.png','/static/logo.png','/assets/logo.png')) {
    $u2 = "https://$([Uri]$base).Authority$p"
    try {
      $rr = Invoke-WebRequest -Uri $u2 -UseBasicParsing -TimeoutSec 8 -Method Head -Headers @{ 'User-Agent'=$UA }
      "  CAND $($rr.Headers['Content-Length'])B  $u2"
    } catch {}
  }
}
Write-Output "DONE3"
