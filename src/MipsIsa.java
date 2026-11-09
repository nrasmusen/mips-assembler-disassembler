import java.util.HashMap;
import java.util.Map;

// Shared MIPS tables and encoders, used by the Assembler and Disassembler.
public final class MipsIsa {

    private MipsIsa() {}

    // Register names, indexed by register number
    public static final String[] REG_NAMES = {
        "$zero", "$at", "$v0", "$v1", "$a0", "$a1", "$a2", "$a3",
        "$t0",   "$t1", "$t2", "$t3", "$t4", "$t5", "$t6", "$t7",
        "$s0",   "$s1", "$s2", "$s3", "$s4", "$s5", "$s6", "$s7",
        "$t8",   "$t9", "$k0", "$k1", "$gp", "$sp", "$fp", "$ra"
    };

    private static final Map<String, Integer> REGS = new HashMap<>();
    static {
        for (int i = 0; i < 32; i++) {
            REGS.put(REG_NAMES[i], i);   // "$t0" -> 8
            REGS.put("$" + i, i);        // "$8"  -> 8
        }
    }

    // Register number for "$t0" or "$8", or -1 if unknown
    public static int regNumber(String name) {
        Integer n = REGS.get(name);
        return n == null ? -1 : n;
    }

    // Opcodes (bits 31-26)
    public static final int OP_RTYPE = 0x00;
    public static final int OP_J     = 0x02;
    public static final int OP_BEQ   = 0x04;
    public static final int OP_BNE   = 0x05;
    public static final int OP_ADDI  = 0x08;
    public static final int OP_ADDIU = 0x09;
    public static final int OP_ORI   = 0x0D;
    public static final int OP_LUI   = 0x0F;

    // R-type funct codes (bits 5-0)
    public static final int FN_SYSCALL = 0x0C;
    public static final int FN_MFHI    = 0x10;
    public static final int FN_DIV     = 0x1A;
    public static final int FN_ADD     = 0x20;
    public static final int FN_ADDU    = 0x21;
    public static final int FN_SUB     = 0x22;

    // Encoders: mask each field to its width, shift it to its start bit, OR together.
    // R: opcode(6) rs(5) rt(5) rd(5) shamt(5) funct(6)
    public static int encodeR(int rs, int rt, int rd, int shamt, int funct) {
        return ((rs & 0x1F) << 21) | ((rt & 0x1F) << 16) | ((rd & 0x1F) << 11) | ((shamt & 0x1F) << 6) | (funct & 0x3F);
    }

    // I: opcode(6) rs(5) rt(5) imm(16); & 0xFFFF keeps negative offsets to 16 bits
    public static int encodeI(int opcode, int rs, int rt, int imm) {
        return ((opcode & 0x3F) << 26) | ((rs & 0x1F) << 21) | ((rt & 0x1F) << 16) | (imm & 0xFFFF);
    }

    // J: opcode(6) address(26), where address = target >>> 2
    public static int encodeJ(int opcode, int address) {
        return ((opcode & 0x3F) << 26) | (address & 0x3FFFFFF);
    }

    // 32-character binary string with leading zeros
    public static String toBinary32(int word) {
        String s = Integer.toBinaryString(word);
        return "0".repeat(32 - s.length()) + s;
    }

    public static String toHex32(int word) {
        return String.format("0x%08X", word);
    }

    // Self-test against the hand-assembled Part 1 answers: java -cp build MipsIsa
    public static void main(String[] args) {
        int t0 = regNumber("$t0"), t1 = regNumber("$t1"), t2 = regNumber("$t2"),
            t3 = regNumber("$t3"), zero = regNumber("$zero");

        check("addi $t0, $zero, 3", encodeI(OP_ADDI, zero, t0, 3),   0x20080003);
        check("addi $t1, $zero, 5", encodeI(OP_ADDI, zero, t1, 5),   0x20090005);
        check("add  $t2, $t0, $t1", encodeR(t0, t1, t2, 0, FN_ADD),  0x01095020);
        check("sub  $t3, $t1, $t0", encodeR(t1, t0, t3, 0, FN_SUB),  0x01285822);
        check("bne with offset -1 (negative imm)", encodeI(OP_BNE, t1, t0, -1), 0x1528FFFF);
        check("j 0x00400010",                      encodeJ(OP_J, 0x00400010 >>> 2), 0x08100004);
    }

    private static void check(String label, int actual, int expected) {
        String status = actual == expected ? "PASS" : "FAIL";
        System.out.printf("%s  %-36s got %s  expected %s%n",
                status, label, toHex32(actual), toHex32(expected));
    }
}
