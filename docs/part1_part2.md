# Parts 1 & 2: Hand Assembly

## Reference

| Register | Number |
|---|---|
| `$zero` | 0 |
| `$t0` | 8 |
| `$t1` | 9 |
| `$t2` | 10 |
| `$t3` | 11 |

| Instruction | Format | opcode | funct |
|---|---|---|---|
| `addi` | I | 8 (`001000`) | — |
| `add` | R | 0 (`000000`) | 32 (`100000`) |
| `sub` | R | 0 (`000000`) | 34 (`100010`) |

Field layouts:
- **R-type:** `opcode(6) | rs(5) | rt(5) | rd(5) | shamt(5) | funct(6)`, assembly order `op rd, rs, rt`
- **I-type:** `opcode(6) | rs(5) | rt(5) | immediate(16)`, assembly order `op rt, rs, imm`

---

## Part 1: Instructions → 32-bit machine code

### 1. `addi $t0, $zero, 3`
Format: **I-type** (destination `$t0` goes in `rt`; `$zero` is the source `rs`)

| Field | Value (decimal) | Binary |
|---|---|---|
| opcode | 8 | 001000 |
| rs | 0 ($zero) | 00000 |
| rt | 8 ($t0) | 01000 |
| immediate | 3 | 0000000000000011 |

32-bit: 00100000000010000000000000000011

### 2. `addi $t1, $zero, 5`
Format: I-Type

| Field | Value (decimal) | Binary |
|---|---|---|
| opcode | 8 | 001000 |
| rs | 0 ($zero) | 00000 |
| rt | 9 ($t1) | 01001 |
| immediate | 5 | 0000000000000101 |

32-bit: 00100000000010010000000000000101

### 3. `add $t2, $t0, $t1`
Format: R-Type

| Field | Value (decimal) | Binary |
|---|---|---|
| opcode | 0 | 000000 |
| rs | 8 ($t0) | 01000 |
| rt | 9 ($t1) | 01001 |
| rd | 10 ($t2) | 01010 |
| shamt | 0 | 00000 |
| funct | 32 | 100000 |

32-bit: 00000001000010010101000000100000

### 4. `sub $t3, $t1, $t0`
Format: R-Type

| Field | Value (decimal) | Binary |
|---|---|---|
| opcode | 0 | 000000 |
| rs | 9 ($t1) | 01001 |
| rt | 8 ($t0) | 01000 |
| rd | 11 ($t3) | 01011 |
| shamt | 0 (no shift) | 00000 |
| funct | 34 | 100010 |

32-bit: 00000001001010000101100000100010

---

## Part 2: Bytes and hex

| Instruction | Byte 1 | Byte 2 | Byte 3 | Byte 4 | Hex word |
|---|---|---|---|---|---|
| `addi $t0, $zero, 3` | 0010 0000 (0x20) | 0000 1000 (0x08) | 0000 0000 (0x00) | 0000 0011 (0x03) | `0x20080003` |
| `addi $t1, $zero, 5` | 0010 0000 (0x20) | 0000 1001 (0x09) | 0000 0000 (0x00) | 0000 0101 (0x05) | `0x20090005` |
| `add $t2, $t0, $t1` | 0000 0001 (0x01) | 0000 1001 (0x09) | 0101 0000 (0x50) | 0010 0000 (0x20) | `0x01095020` |
| `sub $t3, $t1, $t0` | 0000 0001 (0x01) | 0010 1000 (0x28) | 0101 1000 (0x58) | 0010 0010 (0x22) | `0x01285822` |

(Write each byte as 8 bits and its hex, e.g. `00100000 = 0x20`.)

### mhc-ascii vs mhc-bin

mhc-ascii stores each 0 and 1 in machine code as text characters from the ascii table requiring 32 text character bytes per instruction, the mhc-bin stores the machine code as bytes requiring only 4 bytes per instruction. Biggest difference is that if you open the mhc-ascii in notepad you'll see the binary as characters making debugging possible, however it's 8x as large. mhc-bin is machine code in bytes that is actually loaded into memory and executed, and when open in notepad shows up as gibberish because notepad reads each byte as an ascii character, which isn't how it's intended to be read.