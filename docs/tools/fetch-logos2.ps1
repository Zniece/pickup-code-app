$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.Drawing

$tmp = Split-Path -Parent $MyInvocation.MyCommand.Path
$outDir = Join-Path $tmp 'out'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36'

$probes = [ordered]@{
  sf      = @('https://www.sf-express.com/','https://www.sf-express.com/chn/sc/')
  cainiao = @('https://www.cainiao.com/','https://global.cainiao.com/')
  fengchao= @('https://www.fengchao.com/')
  ems     = @('https://www.ems.com.cn/','https://www.china-post.com/')
  luckin  = @('https://www.luckincoffee.com/','https://www.luckincoffee.com.cn/')
  taobao  = @('https://www.taobao.com/')
  domino  = @('https://www.dominos.com.cn/')
  haidilao= @('https://www.haidilao.com/')
  bkh     = @('https://www.burgerking.com.cn/')
  zhy     = @('https://www.zhouheiya.com.cn/','https://www.zhouheiya.cn/')
  ph      = @('https://www.pizzahut.com.cn/')
  hls     = @('https://www.hls1.com/')
  chagee  = @('https://www.bwcj.cn/','https://www.chaagee.com/')
  guming  = @('https://www.guming.com.cn/','https://www.guming.cn/')
  eleme   = @('https://www.ele.me/')
  kfc     = @('https://www.kfc.com.cn/','https://www.kfc.com.cn/kfccda/index.aspx')
  sto     = @('https://www.sto.cn/')
}

function Get-Candidates($html, $baseUrl) {
  $cands = [System.Collections.Generic.List[string]]::new()
  foreach ($m in [regex]::Matches($html, '<meta[^>]+property=["'']og:image["''][^>]+content=["'']([^"'']+)')) { $cands.Add($m.Groups[1].Value) }
  foreach ($m in [regex]::Matches($html, '<meta[^>]+itemprop=["'']image["''][^>]+content=["'']([^"'']+)')) { $cands.Add($m.Groups[1].Value) }
  foreach ($m in [regex]::Matches($html, '<link[^>]+rel=["''][^"'']*icon[^"'']*["''][^>]+href=["'']([^"'']+)')) { $cands.Add($m.Groups[1].Value) }
  foreach ($m in [regex]::Matches($html, '<img[^>]+src=["'']([^"'']*(?:logo|Logo|LOGO)[^"'']*)["'']')) { $cands.Add($m.Groups[1].Value) }
  $resolved = [System.Collections.Generic.List[string]]::new()
  foreach ($c in $cands) {
    if ($c -match '^https?://') { $resolved.Add($c) }
    elseif ($c -match '^//') { $u=[Uri]$baseUrl; $resolved.Add("$($u.Scheme):$c") }
    elseif ($c.StartsWith('/')) { $u=[Uri]$baseUrl; $resolved.Add("$($u.Scheme)://$($u.Authority)$c") }
    elseif ($c -ne '') { try { $resolved.Add(([Uri]::new([Uri]$baseUrl,$c).AbsoluteUri)) } catch {} }
  }
  return $resolved | Select-Object -Unique
}

function Try-Save($url, $dest, $base) {
  try {
    $args = @{ Uri=$url; UseBasicParsing=$true; TimeoutSec=15; Headers=@{'User-Agent'=$UA; 'Referer'=$base} }
    if ($PSVersionTable.PSVersion.Major -ge 7) { $args['SkipCertificateCheck'] = $true }
    $ir = Invoke-WebRequest @args
    $ct = $ir.Headers['Content-Type']
    if ($ct -match 'image/(png|jpe?g|gif|webp|ico|x-icon)|octet-stream') {
      $raw = Join-Path $tmp 'probe2.raw'
      [IO.File]::WriteAllBytes($raw, $ir.Content)
      $img = [System.Drawing.Image]::FromFile($raw)
      try {
        $out = New-Object System.Drawing.Bitmap 128,128
        $g = [System.Drawing.Graphics]::FromImage($out)
        $g.Clear([System.Drawing.Color]::Transparent)
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
        $scale = [Math]::Min(120.0/$img.Width,120.0/$img.Height)
        $w=[Math]::Max(1,[int]($img.Width*$scale)); $h=[Math]::Max(1,[int]($img.Height*$scale))
        $g.DrawImage($img,[int]((128-$w)/2),[int]((128-$h)/2),$w,$h)
        $g.Dispose()
        $out.Save($dest,[System.Drawing.Imaging.ImageFormat]::Png)
        $out.Dispose()
        return "OK($($img.Width)x$($img.Height))"
      } finally { $img.Dispose() }
    }
    return "SKIP($ct)"
  } catch { return "FAIL" }
}

foreach ($key in $probes.Keys) {
  $dest = Join-Path $outDir "$key.png"
  if (Test-Path $dest) { Write-Output "$key : already have" ; continue }
  $html = ''; $base = ''
  foreach ($u in $probes[$key]) {
    try {
      $r = Invoke-WebRequest -Uri $u -UseBasicParsing -TimeoutSec 18 -Headers @{ 'User-Agent'=$UA; 'Accept-Language'='zh-CN,zh;q=0.9' }
      $html = $r.Content; $base = $u; break
    } catch {}
  }
  if ($html -eq '') { Write-Output "$key : all fetch FAIL"; continue }
  $cands = @(Get-Candidates $html $base)
  Write-Output "$key : candidates = $($cands -join ' | ')"
  foreach ($c in $cands) {
    $res = Try-Save $c $dest $base
    Write-Output "   -> $res : $c"
    if ($res.StartsWith('OK')) { break }
  }
}
Write-Output "DONE2"
