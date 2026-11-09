# Builds both programs, runs every check, and saves the results to tests/evidence.txt
# Usage: powershell -ExecutionPolicy Bypass -File run_tests.ps1
# MARS checks run only if Mars4_5.jar is in this folder.

Set-Location $PSScriptRoot
$log = 'tests/evidence.txt'
Remove-Item $log -ErrorAction SilentlyContinue
$script:failures = 0
$script:lastOut = @()

function Say([string]$text) {
    Write-Host $text
    Add-Content -Path $log -Value $text -Encoding ascii
}

# run through cmd.exe so stdout and stderr are captured together as text
function Run([string]$cmd) {
    Say "> $cmd"
    $script:lastOut = @(cmd /c "$cmd 2>&1")
    $code = $LASTEXITCODE
    foreach ($line in $script:lastOut) { Say "    $line" }
    Say "    (exit code $code)"
    return $code
}

function Check([string]$name, [bool]$ok) {
    if ($ok) { Say "PASS  $name" } else { Say "FAIL  $name"; $script:failures++ }
}

function Section([string]$title) {
    Say ""
    Say ("=" * 70)
    Say $title
    Say ("=" * 70)
}

function SameBytes($a, $b) { (Get-FileHash $a).Hash -eq (Get-FileHash $b).Hash }

Say "Test run: $(Get-Date -Format 'yyyy-MM-dd HH:mm')"
Say "Java: $((cmd /c 'java -version 2>&1') | Select-Object -First 1)"

Section "1. Build"
$code = Run "javac -d build src/AsmException.java src/MipsIsa.java src/Assembler.java src/Disassembler.java src/AssemblerSelfTest.java"
Check "all sources compile" ($code -eq 0)
if ($code -ne 0) { Say "Build failed; stopping."; exit 1 }

Section "2. Unit self-tests (encoders and assembler helpers)"
$code = Run "java -cp build MipsIsa"
Check "MipsIsa encoders match the hand-assembled Part 1 answers" (($code -eq 0) -and -not ($script:lastOut -match 'FAIL'))
$code = Run "java -cp build AssemblerSelfTest"
Check "branch/jump math and pseudoinstruction expansions" (($code -eq 0) -and -not ($script:lastOut -match 'FAIL|TODO'))

Section "3. Part 1 instructions through the assembler"
$code = Run "java -cp build Assembler tests/part1.asm out/part1 --list"
$text = $script:lastOut -join "`n"
Check "assembler output equals hand assembly (20080003 20090005 01095020 01285822)" (
    ($code -eq 0) -and $text.Contains('0x20080003') -and $text.Contains('0x20090005') -and
    $text.Contains('0x01095020') -and $text.Contains('0x01285822'))

Section "4. Assemble FizzBuzz (Part 3)"
$code = Run "java -cp build Assembler fizzBuzzMIPS.asm out/fizzbuzz --list"
Check "fizzBuzzMIPS.asm assembles" ($code -eq 0)
Check "out/fizzbuzz.mhc-ascii exists" (Test-Path out/fizzbuzz.mhc-ascii)
Check "out/fizzbuzz.mhc-bin exists" (Test-Path out/fizzbuzz.mhc-bin)
$asciiSize = (Get-Item out/fizzbuzz.mhc-ascii).Length
$binSize = (Get-Item out/fizzbuzz.mhc-bin).Length
Say "Sizes: mhc-ascii = $asciiSize bytes, mhc-bin = $binSize bytes"
$asciiText = Get-Content out/fizzbuzz.mhc-ascii -Raw
Check "mhc-ascii contains only '0', '1', and line breaks" ($asciiText -match '^[01\r\n]+$')
$bits = ($asciiText -replace '\s', '').Length
Check "mhc-ascii has exactly 8 characters per mhc-bin byte ($bits = 8 x $binSize)" ($bits -eq 8 * $binSize)

Section "5. Disassemble BOTH formats (Part 4)"
$code = Run "java -cp build Disassembler out/fizzbuzz.mhc-bin out/fizzbuzz_reconstructed.asm"
Check "disassemble mhc-bin" ($code -eq 0)
$code = Run "java -cp build Disassembler out/fizzbuzz.mhc-ascii out/fizzbuzz_reconstructed_from_ascii.asm"
Check "disassemble mhc-ascii" ($code -eq 0)
Check "both formats produce the same assembly" (SameBytes out/fizzbuzz_reconstructed.asm out/fizzbuzz_reconstructed_from_ascii.asm)

Section "6. Reassemble the reconstructed program: bytes must match"
$code = Run "java -cp build Assembler out/fizzbuzz_reconstructed.asm out/reassembled"
Check "reconstructed program assembles" ($code -eq 0)
Check "reassembled mhc-bin is byte-identical to the original" (SameBytes out/fizzbuzz.mhc-bin out/reassembled.mhc-bin)
Check "reassembled mhc-ascii is identical to the original" (SameBytes out/fizzbuzz.mhc-ascii out/reassembled.mhc-ascii)

Section "7. MARS: same FizzBuzz output and same machine code"
if (Test-Path Mars4_5.jar) {
    $code = Run "java -jar Mars4_5.jar nc fizzBuzzMIPS.asm"
    $origOut = $script:lastOut -join "`n"
    $code2 = Run "java -jar Mars4_5.jar nc out/fizzbuzz_reconstructed.asm"
    $reconOut = $script:lastOut -join "`n"
    Check "original runs in MARS" ($code -eq 0)
    Check "reconstructed runs in MARS" ($code2 -eq 0)
    Check "reconstructed program prints exactly the same output as the original" (($origOut -eq $reconOut) -and $origOut.Length -gt 0)

    $code = Run "java -jar Mars4_5.jar nc a dump .text HexText out/mars_text.txt fizzBuzzMIPS.asm"
    $mars = @(Get-Content out/mars_text.txt | ForEach-Object { $_.Trim().ToLower() } | Where-Object { $_ })
    $b = [IO.File]::ReadAllBytes("$PSScriptRoot/out/fizzbuzz.mhc-bin")
    $ours = @()
    for ($i = 20; $i -lt 20 + 180; $i += 4) { $ours += ('{0:x2}{1:x2}{2:x2}{3:x2}' -f $b[$i], $b[$i+1], $b[$i+2], $b[$i+3]) }
    Check "our .text words equal MARS's own assembly ($($mars.Count) words)" (($ours -join ',') -eq ($mars -join ','))
} else {
    Say "SKIPPED: Mars4_5.jar not found in the project folder."
    Say "  Download MARS 4.5 and place Mars4_5.jar here, then rerun to include these checks."
}

Section "8. Invalid input produces useful errors"
foreach ($f in Get-ChildItem tests/bad/*.asm | Sort-Object Name) {
    $code = Run "java -cp build Assembler tests/bad/$($f.Name) out/bad"
    Check "assembler rejects $($f.Name) with an error message" (($code -eq 1) -and ($script:lastOut -match 'error:'))
}
foreach ($f in Get-ChildItem tests/bad/*.mhc-* | Sort-Object Name) {
    $code = Run "java -cp build Disassembler tests/bad/$($f.Name) out/bad.asm"
    Check "disassembler rejects $($f.Name) with an error message" (($code -eq 1) -and ($script:lastOut -match 'error:'))
}
$code = Run "java -cp build Assembler does_not_exist.asm"
Check "assembler reports a missing input file" (($code -eq 1) -and ($script:lastOut -match 'not found'))
Remove-Item out/bad.* -ErrorAction SilentlyContinue

Section "Summary"
if ($script:failures -eq 0) { Say "ALL CHECKS PASSED" } else { Say "$($script:failures) CHECK(S) FAILED" }
exit $script:failures
