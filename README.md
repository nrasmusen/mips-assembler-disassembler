# MIPS Assembler & Disassembler

A two-pass **assembler** and a **disassembler** for a subset of MIPS32, written in Java.
The assembler turns a MARS-style `.asm` program into machine code in two formats; the
disassembler turns either format back into assembly that runs in MARS and reassembles
to the exact same bytes.

```
fizzBuzzMIPS.asm ──Assembler──► fizzbuzz.mhc-bin   ──Disassembler──► fizzbuzz_reconstructed.asm
                               fizzbuzz.mhc-ascii ──Disassembler──┘           │
                                                                              └─Assembler──► identical bytes ✓
```

Built for CS240 (Computer Organization) using my earlier MIPS FizzBuzz program as the input.

**Verified:** the assembler's 45 instructions match MARS 4.5's own assembly of the same
file word-for-word, and the reconstructed program prints the identical 1–100 FizzBuzz
output in MARS (34/34 automated checks, see [`tests/evidence.txt`](tests/evidence.txt)).

---

## Quick start

Requires **Java 17+** (developed with JDK 24). No build tool or libraries.

```sh
# 1. Compile (works in PowerShell, cmd, bash, zsh)
javac -d build src/*.java

# 2. Assemble -> out/fizzbuzz.mhc-ascii and out/fizzbuzz.mhc-bin
java -cp build Assembler fizzBuzzMIPS.asm out/fizzbuzz

#    add --list to print an address / machine code / source listing
java -cp build Assembler fizzBuzzMIPS.asm out/fizzbuzz --list

# 3. Disassemble either format -> .asm
java -cp build Disassembler out/fizzbuzz.mhc-bin   out/fizzbuzz_reconstructed.asm
java -cp build Disassembler out/fizzbuzz.mhc-ascii out/fizzbuzz_reconstructed.asm

# 4. Run the reconstructed program in MARS (optional)
java -jar Mars4_5.jar nc out/fizzbuzz_reconstructed.asm
```

| Program | Arguments | Output |
|---|---|---|
| `Assembler` | `<input.asm> [outBase] [--list]` | `<outBase>.mhc-ascii` and `<outBase>.mhc-bin` (`outBase` defaults to the input name without `.asm`) |
| `Disassembler` | `<input.mhc-bin \| input.mhc-ascii> <output.asm>` | a MARS-compatible `.asm` file. The format is detected from the file contents, not the extension. |

Both programs exit with code `0` on success and `1` on invalid input (with a message on stderr).

### Run all tests

```powershell
powershell -ExecutionPolicy Bypass -File run_tests.ps1
```

This compiles everything, runs the unit self-tests, assembles FizzBuzz, disassembles **both** formats,
reassembles the result and byte-compares it, feeds 14 invalid inputs to the two programs, and writes
everything to `tests/evidence.txt`. If `Mars4_5.jar` is in the project folder, it also runs the original
and reconstructed programs in MARS and compares their output and machine code.
([MARS 4.5](https://github.com/dpetersanderson/MARS/releases) is not included in this repository.)

---

## Supported features

Exactly what `fizzBuzzMIPS.asm` uses, plus `add`/`sub`. Anything else is rejected with an error.

**Instructions**

| Format | Instructions | Encoding |
|---|---|---|
| R | `add`, `addu`, `sub` | `op rd, rs, rt` |
| R | `div rs, rt` · `mfhi rd` · `syscall` | |
| I | `addi`, `addiu`, `ori` | `op rt, rs, imm` (`ori` imm is unsigned) |
| I | `lui rt, imm` | |
| I | `beq`, `bne` | `op rs, rt, label` (PC-relative word offset) |
| J | `j label` | 26-bit word address |

**Pseudoinstructions** (expanded the same way MARS expands them)

| Pseudo | Expands to |
|---|---|
| `li $rt, imm` | `addiu $rt, $zero, imm` (16-bit signed immediates) |
| `la $rd, label` | `lui $at, upper16` + `ori $rd, $at, lower16` (2 instructions) |
| `move $rd, $rs` | `addu $rd, $zero, $rs` |

**Directives:** `.text`, `.data`, `.asciiz`, `.ascii`, `.byte`, `.globl` (ignored)

**Operands:** register names (`$t0`) or numbers (`$8`); decimal, negative, and hex (`0x1F`)
immediates; character literals with escapes (`'\n'`); labels in both segments; `#` comments
(a `#` inside a string or character literal is not a comment).

---

## Memory layout and byte order

| | Value |
|---|---|
| Text (code) segment starts at | `0x00400000` (MARS default) |
| Data segment starts at | `0x10010000` (MARS default) |
| Byte order in both output files | **big-endian** (most significant byte first) |

Using the MARS defaults means jump targets and `la` addresses come out identical to MARS.
(MARS itself stores memory little-endian; big-endian applies only to these files, so bytes
appear in the same order you'd write the binary by hand.)

---

## Output file formats

Both formats hold **the same bytes**. They differ only in how each bit is stored.

### `.mhc-bin`: raw binary

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | magic number: the ASCII bytes `MHC1` |
| 4 | 4 | text segment start address |
| 8 | 4 | text segment length in bytes (multiple of 4) |
| 12 | 4 | data segment start address |
| 16 | 4 | data segment length in bytes |
| 20 | text length | instructions, 4 bytes each |
| 20 + text length | data length | data bytes |

All header fields are unsigned 32-bit big-endian integers.

**The header is what separates instructions from data.** Data bytes can look like valid
instructions (`"Fizz"` = `46 69 7A 7A`), so without the lengths the disassembler could not
tell where code ends.

### `.mhc-ascii`: readable binary

The exact same bytes as `.mhc-bin`, with each byte written as eight `0`/`1` characters. The
file contains only `0`, `1`, and line breaks. Line breaks are for readability and are ignored
when reading: the 5 header words and each instruction take one 32-character line, and each data
byte takes one 8-character line.

```
01001101010010000100001100110001   "MHC1"
00000000010000000000000000000000   text start 0x00400000
00000000000000000000000010110100   text length 180
00010000000000010000000000000000   data start 0x10010000
00000000000000000000000000010011   data length 19
00100100000010010000000000000001   addiu $t1, $zero, 1   (li $t1, 1)
...
01000110                           'F'
```

For FizzBuzz: **219 bytes** as `.mhc-bin`, **1,821 bytes** as `.mhc-ascii` (8 characters per
byte plus line breaks).

---

## How it works

### Assembler (`src/Assembler.java`)

1. **Pass 1:** strip comments, record each label's address, size each instruction
   (`la` = 2 words, everything else 1), and write `.asciiz` bytes into the data segment.
   Labels can be used before they're defined (`j PrintItem` appears above `PrintItem:`),
   which is why two passes are needed.
2. **Pass 2:** expand pseudoinstructions and encode each instruction, now that all
   label addresses are known:
   - branch offset = `(target − (PC + 4)) / 4`
   - jump field = `target >>> 2`
   - `la` splits the address: `upper16 = addr >>> 16`, `lower16 = addr & 0xFFFF`
3. **Write** the header plus bytes as `.mhc-bin`, and the same bytes as `0`/`1` text as `.mhc-ascii`.

Encoding shifts each field into place and ORs them together (`src/MipsIsa.java`):

```java
((opcode & 0x3F) << 26) | ((rs & 0x1F) << 21) | ((rt & 0x1F) << 16) | (imm & 0xFFFF)
```

### Disassembler (`src/Disassembler.java`)

1. **Load:** a file starting with `MHC1` is binary. Otherwise it must be `0`/`1` text, and every
   8 characters become one byte. From here on, both formats are handled identically.
2. **Read the header**, check that the file size matches, and split the bytes into instructions and data.
3. **Decode** each word: shift right, then mask (`rs = (word >>> 21) & 0x1F`).
4. **Label** every branch and jump target (`L_0040001C`).
5. **Write** `.data` (null-terminated text becomes `.asciiz`, anything else `.byte`), then `.text`
   as plain instructions (the output file contains no comments).

The reconstructed program uses real instructions only (`la` appears as `lui`/`ori`), so it
reassembles to identical bytes. Original comments, label names, and pseudoinstruction
spellings are not recoverable from machine code.

---

## Error handling

Errors give the file, line, and reason, and the program exits with code 1:

```
unsupported_instruction.asm:2: error: unsupported instruction 'mul'
bad_register.asm:2: error: unknown register '$t10'
undefined_label.asm:2: error: undefined label 'Nowhere'
immediate_out_of_range.asm:2: error: value 70000 is out of range for 'addi' (-32768 to 32767)
wrong_operand_count.asm:2: error: 'add' expects 3 operands (rd, rs, rt) but got 2
unsupported_directive.asm:2: error: unsupported directive '.word' (supported: ...)
bad_character.mhc-ascii:2: error: not a valid mhc file: found '2' (...)
truncated.mhc-bin: error: header says 219 bytes total but the file has 40 (file is truncated)
unknown_opcode.mhc-bin: error: unsupported instruction word 0xFC000000 at 0x00400000 (opcode 0x3F)
```

The full list of invalid-input tests is in [`tests/bad/`](tests/bad).

---

## Project layout

```
src/
  MipsIsa.java            register table, opcode/funct codes, encodeR/I/J
  Assembler.java          .asm -> .mhc-ascii + .mhc-bin
  Disassembler.java       .mhc-ascii or .mhc-bin -> .asm
  AsmException.java       input error with a line number
  AssemblerSelfTest.java  unit checks for branch/jump math and pseudo expansions
fizzBuzzMIPS.asm          the original program
out/                      generated machine code and reconstructed assembly
tests/
  part1.asm               the hand-assembled instructions, checked against the assembler
  bad/                    invalid inputs for both programs
  evidence.txt            output of the latest run_tests.ps1
docs/part1_part2.md       hand assembly of four instructions; ASCII vs. binary output
run_tests.ps1             runs every check and writes tests/evidence.txt
```

## Limitations

- Only the instructions, pseudoinstructions, and directives listed above.
- `li` accepts 16-bit signed immediates only (no `lui`/`ori` expansion for larger values).
- Segment start addresses are fixed (`.text`/`.data` with an address argument are rejected).
- One error is reported per run (the first one found).

## Acknowledgments

- [MARS 4.5](https://github.com/dpetersanderson/MARS) MIPS simulator (Missouri State University):
  reference for pseudoinstruction expansion and default addresses, and used to verify output.
- MIPS32 instruction encodings from the MIPS reference ("green sheet") and course readings.

## AI Use

I used Claude AI from Anthropic (through Claude Code) for a lot of this project, specifically:

- Reading through the assignment specs with me and laying out a plan to get it done step by step. I went with Java since it's the language I know best, so the code would be easier for me to understand.
- For Parts 1 and 2 I did the hand assembly myself. Claude gave me the register numbers, opcodes and funct codes, explained how the R-type and I-type fields are laid out, and checked my answers as I went. When I got something wrong (like thinking `$t0` was register 0, or rebuilding bytes from the fields instead of just slicing the 32-bit word), it explained why. It also explained the difference between mhc-ascii and mhc-bin, and I wrote that explanation in my own words.
- For the encoders, Claude set up the register and opcode tables, a self-test and `encodeI` as an example, and I wrote `encodeR` and `encodeJ`. The self-test caught a mask mistake I made, which I fixed.
- For the assembler, Claude wrote the parsing, the two-pass setup, the error messages and the file output. I wrote the branch offset calculation, and because I was running short on time Claude finished the jump field and the `li`/`la`/`move` expansions and explained each one to me.
- Because of the deadline, Claude wrote the disassembler, the test script and the bad-input tests, then walked me through every file with line numbers so I could follow what each part does.
- I added the MARS simulator to the project, and Claude used it to check that the reconstructed program prints the same FizzBuzz output and that our machine code matches what MARS produces.
- Claude also wrote this README and the project report PDF.
- I read through all of the walkthroughs and the code Claude wrote to make sure I understood what each part was doing.
