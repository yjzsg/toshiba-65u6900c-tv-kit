$ErrorActionPreference = 'Stop'
Set-Location C:\Temp\homekey

$jdk  = 'C:\Temp\build\jdk-17.0.20.1+1'
$bt   = 'C:\Temp\build\bt\android-14'
$jar  = 'C:\Temp\build\plat\android-6.0\android.jar'
$ks   = 'C:\Temp\livetv2\plx.jks'
$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"

$W = 'C:\Temp\homekey\build'
Remove-Item -Recurse -Force $W -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $W, "$W\classes", "$W\dex" | Out-Null

Write-Host '[1/5] javac'
$sources = Get-ChildItem -Recurse -Filter *.java src | ForEach-Object { $_.FullName }
& "$jdk\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -bootclasspath $jar -d "$W\classes" $sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Host '[2/5] d8'
$classFiles = Get-ChildItem -Recurse -Filter *.class "$W\classes" | ForEach-Object { $_.FullName }
& cmd /c "`"$bt\d8.bat`" --lib `"$jar`" --release --min-api 21 --output `"build\dex`" $($classFiles -join ' ')"
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Write-Host '[3/5] aapt2 compile+link'
& "$bt\aapt2.exe" compile --dir res -o "$W\res.zip"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }
& "$bt\aapt2.exe" link -o "$W\unsigned.apk" -I $jar --manifest AndroidManifest.xml "$W\res.zip" --min-sdk-version 21 --target-sdk-version 22
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Host '[4/5] add classes.dex'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$W\unsigned.apk", 'Update')
$e = $zip.GetEntry('classes.dex')
if ($e) { $e.Delete() }
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$W\dex\classes.dex", 'classes.dex', 'Optimal') | Out-Null
$zip.Dispose()

Write-Host '[5/5] zipalign + sign'
& "$bt\zipalign.exe" -p -f 4 "$W\unsigned.apk" "$W\aligned.apk"
& cmd /c "`"$bt\apksigner.bat`" sign --ks `"$ks`" --ks-pass pass:android --key-pass pass:android --ks-key-alias plx --out `"build\homekey.apk`" `"build\aligned.apk`""
if ($LASTEXITCODE -ne 0) { throw 'sign failed' }

Copy-Item build\homekey.apk C:\Temp\homekey.apk -Force
Write-Host "OK -> C:\Temp\homekey.apk ($((Get-Item C:\Temp\homekey.apk).Length) bytes)"
