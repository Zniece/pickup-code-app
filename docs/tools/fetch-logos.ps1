$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.Drawing

$tmp = Split-Path -Parent $MyInvocation.MyCommand.Path
$outDir = Join-Path $tmp 'out'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36'

$brands = [ordered]@{
  yunda   = 'https://www.yunda56.com/'
  zto     = 'https://www.zto.com/'
  yto     = 'https://www.yto.net.cn/'
  sto     = 'https://www.sto.cn/'
  sf      = 'https://www.sf-express.com/'
  jt      = 'https://www.jtexpress.com.cn/'
  jd      = 'https://www.jdl.com/'
  cainiao = 'https://www.cainiao.com/'
  hivebox = 'https://www.hivebox.cn/'
  ems     = 'https://www.ems.com.cn/'
  deppon  = 'https://www.deppon.com/'
  luckin  = 'https://www.luckincoffee.com/'
  mixue   = 'https://www.mxbc.com/'
  heytea  = 'https://www.heytea.com/'
  chagee  = 'https://www.bwcj.cn/'
  guming  = 'https://www.guming.com.cn/'
  mcd     = 'https://www.mcdonalds.com.cn/'
  kfc     = 'https://www.kfc.com.cn/'
  sbux    = 'https://www.starbucks.com.cn/'
  cotti   = 'https://www.cotti.cn/'
  hls     = 'https://www.hls1.com/'
  bkh     = 'https://www.burgerking.com.cn/'
  ph      = 'https://www.pizzahut.com.cn/'
  domino  = 'https://www.dominos.com.cn/'
  haidilao= 'https://www.haidilao.com/'
  lxj     = 'https://www.laoxiangji.com.cn/'
  juewei  = 'https://www.juewei.cn/'
  zhy     = 'https://www.zhouheiya.com.cn/'
  holiland= 'https://www.holiland.com/'
  meituan = 'https://www.meituan.com/'
  eleme   = 'https://www.ele.me/'
  taobao  = 'https://www.taobao.com/'
}

function Get-Candidates($html, $baseUrl) {
  $cands = [System.Collections.Generic.List[string]]::new()
  # og:image
  foreach ($m in [regex]::Matches($html, '<meta[^>]+property=["'']og:image["''][^>]+content=["'']([^"'']+)')) { $cands.Add($m.Groups[1].Value) }
  # apple-touch-icon / icon
  foreach ($m in [regex]::Matches($html, '<link[^>]+rel=["''][^"'']*icon[^"'']*["''][^>]+href=["'']([^"'']+)')) { $cands.Add($m.Groups[1].Value) }
  # img src containing logo
  foreach ($m in [regex]::Matches($html, '<img[^>]+src=["'']([^"'']*(?:logo|Logo|LOGO)[^"'']*)["'']')) { $cands.Add($m.Groups[1].Value) }
  $resolved = [System.Collections.Generic.List[string]]::new()
  foreach ($c in $cands) {
    if ($c -match '^(https?:)?//') { $resolved.Add($c) }
    elseif ($c.StartsWith('/')) {
      $u = [Uri]$baseUrl
      $resolved.Add("$($u.Scheme)://$($u.Authority)$c")
    }
    elseif ($c -ne '') {
      $resolved.Add(([Uri]::new([Uri]$baseUrl, $c).AbsoluteUri))
    }
  }
  return $resolved | Select-Object -Unique
}

function Save-Normalized($srcBytes, $dest) {
  $raw = Join-Path $tmp 'probe.raw'
  [IO.File]::WriteAllBytes($raw, $srcBytes)
  $img = [System.Drawing.Image]::FromFile($raw)
  try {
    $out = New-Object System.Drawing.Bitmap 128,128
    $g = [System.Drawing.Graphics]::FromImage($out)
    $g.Clear([System.Drawing.Color]::Transparent)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $scale = [Math]::Min(120.0/$img.Width, 120.0/$img.Height)
    $w = [Math]::Max(1, [int]($img.Width*$scale)); $h = [Math]::Max(1, [int]($img.Height*$scale))
    $x = [int]((128-$w)/2); $y = [int]((128-$h)/2)
    $g.DrawImage($img, $x, $y, $w, $h)
    $g.Dispose()
    $out.Save($dest, [System.Drawing.Imaging.ImageFormat]::Png)
    $out.Dispose()
    return $true
  } finally { $img.Dispose() }
}

foreach ($key in $brands.Keys) {
  $base = $brands[$key]
  $dest = Join-Path $outDir "$key.png"
  if (Test-Path $dest) { Write-Output "$key : already have" ; continue }
  try {
    $r = Invoke-WebRequest -Uri $base -UseBasicParsing -TimeoutSec 18 -Headers @{ 'User-Agent' = $UA; 'Accept-Language' = 'zh-CN,zh;q=0.9' }
    $html = $r.Content
    $cands = @(Get-Candidates $html $base)
    $got = $false
    foreach ($c in $cands) {
      try {
        $ir = Invoke-WebRequest -Uri $c -UseBasicParsing -TimeoutSec 15 -Headers @{ 'User-Agent' = $UA; 'Referer' = $base }
        $ct = $ir.Headers['Content-Type']
        if ($ct -match 'image/(png|jpe?g|gif|webp|ico|x-icon)|octet-stream') {
          if (Save-Normalized $ir.Content $dest) {
            Write-Output "$key : OK from $c"
            $got = $true
            break
          }
        }
      } catch {}
    }
    if (-not $got) { Write-Output "$key : no usable logo found (candidates: $($cands -join ' | '))" }
  } catch {
    Write-Output "$key : FAIL fetch $($_.Exception.Message.Substring(0,[Math]::Min(60,$_.Exception.Message.Length)))"
  }
}
Write-Output "DONE -> $outDir"
