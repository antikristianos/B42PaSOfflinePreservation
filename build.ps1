[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$GameRoot,
    [Parameter(Mandatory=$true)][string]$ZombieBuddyJar,
    [Parameter(Mandatory=$true)][string]$JdkRoot,
    [string[]]$AdditionalClasspath=@()
)
$ErrorActionPreference='Stop'
$configuration=Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'build.json') | ConvertFrom-Json
$dependencies=@((Join-Path $GameRoot 'projectzomboid.jar'),$ZombieBuddyJar)+$AdditionalClasspath
foreach($expected in $configuration.Dependencies){
    $matching=@($dependencies | Where-Object {(Split-Path $_ -Leaf) -eq $expected.Path})
    if($matching.Count -ne 1 -or (Get-FileHash -LiteralPath $matching[0] -Algorithm SHA256).Hash -ne $expected.Sha256){
        throw ('Use the recorded dependency version: '+$expected.Path+' SHA-256 '+$expected.Sha256)
    }
}
$output=Join-Path $PSScriptRoot ('build/'+[Guid]::NewGuid().ToString('N'))
$classes=Join-Path $output 'classes'
[IO.Directory]::CreateDirectory($classes)|Out-Null
$sources=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Recurse -File -Filter '*.java' | Sort-Object FullName | ForEach-Object FullName)
$classpath=$dependencies -join [IO.Path]::PathSeparator
& (Join-Path $JdkRoot 'bin/javac.exe') --release $configuration.JavaRelease -encoding UTF-8 -classpath $classpath -d $classes @sources
if($LASTEXITCODE){throw 'Java compilation failed'}
$resources=Join-Path $PSScriptRoot 'resources'
if(Test-Path -LiteralPath $resources){
    foreach($file in Get-ChildItem -LiteralPath $resources -Recurse -File){
        $relative=$file.FullName.Substring($resources.Length+1)
        $target=Join-Path $classes $relative
        [IO.Directory]::CreateDirectory((Split-Path $target -Parent))|Out-Null
        Copy-Item -LiteralPath $file.FullName -Destination $target
    }
}
$actual=@(Get-ChildItem -LiteralPath $classes -Recurse -File)
if($actual.Count -ne $configuration.Entries.Count){throw 'Rebuilt class/resource file list differs'}
foreach($expected in $configuration.Entries){
    $file=Join-Path $classes $expected.Path
    if(-not(Test-Path -LiteralPath $file) -or (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $expected.Sha256){
        throw ('Rebuilt class/resource differs: '+$expected.Path)
    }
}
$jar=Join-Path $output $configuration.JarName
& (Join-Path $JdkRoot 'bin/jar.exe') --create --file $jar --date=2000-01-01T00:00:00Z --manifest (Join-Path $PSScriptRoot 'MANIFEST.MF') -C $classes .
if($LASTEXITCODE){throw 'JAR creation failed'}
Write-Output ('PASS: every class/resource matches the Workshop artifact. Unsigned rebuild: '+$jar)
