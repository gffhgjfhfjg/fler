# add-bom.ps1: convert target script to UTF-8 with BOM (fix PS 5.1 ANSI misread)
$path = Join-Path (Split-Path -Parent $PSScriptRoot) "scripts\build-unicorn.ps1"
$content = Get-Content $path -Raw -Encoding UTF8
$utf8Bom = New-Object System.Text.UTF8Encoding($true)
[System.IO.File]::WriteAllText($path, $content, $utf8Bom)
Write-Host "BOM added: $path"
