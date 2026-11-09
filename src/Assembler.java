import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Two-pass assembler: .asm -> <outBase>.mhc-ascii and <outBase>.mhc-bin
// Usage: java -cp build Assembler <input.asm> [outBase] [--list]
public class Assembler {

    public static final int TEXT_BASE = 0x00400000;
    public static final int DATA_BASE = 0x10010000;

    // ---- Branch/jump math and pseudoinstruction expansions ----

    private static final int REG_ZERO = 0;   // $zero: always reads as 0
    private static final int REG_AT   = 1;   // $at: assembler temporary, used by la

    // beq/bne offset: instructions from pc + 4 to the target (can be negative)
    static int branchOffset(int pc, int target) {
        return (target - (pc + 4)) / 4;
    }

    // j address field: byte address -> word address
    static int jumpField(int target) {
        return target >>> 2;
    }

    // li $rt, imm  ->  addiu $rt, $zero, imm
    static int[] expandLi(int rt, int imm) {
        return new int[] {
            MipsIsa.encodeI(MipsIsa.OP_ADDIU, REG_ZERO, rt, imm)
        };
    }

    // la $rd, address  ->  lui $at, upper16 ; ori $rd, $at, lower16
    static int[] expandLa(int rd, int address) {
        int upper16 = address >>> 16;      // top half:    0x1001000A -> 0x1001
        int lower16 = address & 0xFFFF;    // bottom half: 0x1001000A -> 0x000A
        return new int[] {
            MipsIsa.encodeI(MipsIsa.OP_LUI, REG_ZERO, REG_AT, upper16),
            MipsIsa.encodeI(MipsIsa.OP_ORI, REG_AT, rd, lower16)
        };
    }

    // move $rd, $rs  ->  addu $rd, $zero, $rs
    static int[] expandMove(int rd, int rs) {
        return new int[] {
            MipsIsa.encodeR(REG_ZERO, rs, rd, 0, MipsIsa.FN_ADDU)
        };
    }

    // ---- Assembler state ----

    private static final Set<String> SUPPORTED = Set.of(
        "add", "addu", "sub", "div", "mfhi", "syscall",
        "addi", "addiu", "ori", "lui", "beq", "bne", "j",
        "li", "la", "move");

    // Real instructions a mnemonic becomes; pass 1 uses this to place labels
    static int sizeInWords(String mnemonic) {
        return mnemonic.equals("la") ? 2 : 1;
    }

    // One .text instruction: saved in pass 1, encoded in pass 2
    private record Stmt(int lineNo, String mnemonic, String[] ops, int address) {}

    private static final Pattern LABEL = Pattern.compile("^([A-Za-z_][A-Za-z0-9_.]*)\\s*:");

    private final List<Stmt> textStmts = new ArrayList<>();
    private final ByteArrayOutputStream data = new ByteArrayOutputStream();
    private final Map<String, Integer> labels = new HashMap<>();
    private final List<Integer> words = new ArrayList<>();   // encoded .text
    private final List<Integer> wordLine = new ArrayList<>(); // source line of each word (for --list)

    public void assemble(List<String> lines) throws AsmException {
        pass1(lines);
        pass2();
    }

    // Pass 1: record label addresses, collect instructions, write data bytes
    private void pass1(List<String> lines) throws AsmException {
        boolean inText = true;   // MARS starts in .text if no directive is given
        int textPc = TEXT_BASE;

        for (int i = 0; i < lines.size(); i++) {
            int lineNo = i + 1;
            String line = stripComment(lines.get(i), lineNo).trim();

            // labels like "loop:" or "fizz: .asciiz ..."
            Matcher m;
            while ((m = LABEL.matcher(line)).find()) {
                String name = m.group(1);
                if (labels.containsKey(name)) {
                    throw new AsmException(lineNo, "duplicate label '" + name + "'");
                }
                labels.put(name, inText ? textPc : DATA_BASE + data.size());
                line = line.substring(m.end()).trim();
            }
            if (line.isEmpty()) continue;

            String[] parts = line.split("\\s+", 2);
            String head = parts[0];
            String rest = parts.length > 1 ? parts[1].trim() : "";

            if (head.startsWith(".")) {
                switch (head) {
                    case ".text" -> { noArgs(head, rest, lineNo); inText = true; }
                    case ".data" -> { noArgs(head, rest, lineNo); inText = false; }
                    case ".asciiz", ".ascii" -> {
                        if (inText) {
                            throw new AsmException(lineNo, head + " is only allowed in the .data segment");
                        }
                        data.writeBytes(parseString(rest, lineNo));
                        if (head.equals(".asciiz")) data.write(0);   // null terminator
                    }
                    case ".byte" -> {
                        if (inText) {
                            throw new AsmException(lineNo, ".byte is only allowed in the .data segment");
                        }
                        if (rest.isEmpty()) throw new AsmException(lineNo, ".byte needs at least one value");
                        for (String v : splitOperands(rest, lineNo)) {
                            data.write(parseValue(v, lineNo, ".byte", -128, 255));
                        }
                    }
                    case ".globl" -> { /* no effect for a single-file program */ }
                    default -> throw new AsmException(lineNo, "unsupported directive '" + head
                            + "' (supported: .text, .data, .ascii, .asciiz, .byte, .globl)");
                }
                continue;
            }

            String mnemonic = head.toLowerCase();
            if (!inText) {
                throw new AsmException(lineNo, "instruction '" + head + "' is in the .data segment (add .text before it)");
            }
            if (!SUPPORTED.contains(mnemonic)) {
                throw new AsmException(lineNo, "unsupported instruction '" + head + "'");
            }
            String[] ops = rest.isEmpty() ? new String[0] : splitOperands(rest, lineNo);
            textStmts.add(new Stmt(lineNo, mnemonic, ops, textPc));
            textPc += 4 * sizeInWords(mnemonic);
        }
    }

    // Pass 2: all labels are known, so encode each instruction
    private void pass2() throws AsmException {
        for (Stmt s : textStmts) {
            int[] encoded = encode(s);
            int expected = sizeInWords(s.mnemonic());
            if (encoded.length != expected) {
                // a size mismatch would shift every later label
                throw new IllegalStateException(s.mnemonic() + " produced " + encoded.length
                        + " instruction(s) but pass 1 reserved " + expected);
            }
            for (int w : encoded) {
                words.add(w);
                wordLine.add(s.lineNo());
            }
        }
    }

    private int[] encode(Stmt s) throws AsmException {
        switch (s.mnemonic()) {
            case "add", "addu", "sub" -> {
                expect(s, 3, "rd, rs, rt");
                int funct = switch (s.mnemonic()) {
                    case "add" -> MipsIsa.FN_ADD;
                    case "addu" -> MipsIsa.FN_ADDU;
                    default -> MipsIsa.FN_SUB;
                };
                return one(MipsIsa.encodeR(reg(s, 1), reg(s, 2), reg(s, 0), 0, funct));
            }
            case "div" -> {
                expect(s, 2, "rs, rt");
                return one(MipsIsa.encodeR(reg(s, 0), reg(s, 1), 0, 0, MipsIsa.FN_DIV));
            }
            case "mfhi" -> {
                expect(s, 1, "rd");
                return one(MipsIsa.encodeR(0, 0, reg(s, 0), 0, MipsIsa.FN_MFHI));
            }
            case "syscall" -> {
                expect(s, 0, "no operands");
                return one(MipsIsa.encodeR(0, 0, 0, 0, MipsIsa.FN_SYSCALL));
            }
            case "addi", "addiu" -> {
                expect(s, 3, "rt, rs, imm");
                int op = s.mnemonic().equals("addi") ? MipsIsa.OP_ADDI : MipsIsa.OP_ADDIU;
                return one(MipsIsa.encodeI(op, reg(s, 1), reg(s, 0), imm(s, 2, -32768, 32767)));
            }
            case "ori" -> {
                expect(s, 3, "rt, rs, imm");
                return one(MipsIsa.encodeI(MipsIsa.OP_ORI, reg(s, 1), reg(s, 0), imm(s, 2, 0, 65535)));
            }
            case "lui" -> {
                expect(s, 2, "rt, imm");
                return one(MipsIsa.encodeI(MipsIsa.OP_LUI, 0, reg(s, 0), imm(s, 1, 0, 65535)));
            }
            case "beq", "bne" -> {
                expect(s, 3, "rs, rt, label");
                int op = s.mnemonic().equals("beq") ? MipsIsa.OP_BEQ : MipsIsa.OP_BNE;
                int target = label(s, 2);
                int offset = branchOffset(s.address(), target);
                if (offset < -32768 || offset > 32767) {
                    throw new AsmException(s.lineNo(), "branch target '" + s.ops()[2] + "' is too far away");
                }
                return one(MipsIsa.encodeI(op, reg(s, 0), reg(s, 1), offset));
            }
            case "j" -> {
                expect(s, 1, "label");
                int target = label(s, 0);
                if (((s.address() + 4) & 0xF0000000) != (target & 0xF0000000)) {
                    throw new AsmException(s.lineNo(), "jump target '" + s.ops()[0] + "' is outside the 256 MB region");
                }
                return one(MipsIsa.encodeJ(MipsIsa.OP_J, jumpField(target)));
            }
            case "li" -> {
                expect(s, 2, "rt, imm");
                return expandLi(reg(s, 0), imm(s, 1, -32768, 32767));
            }
            case "la" -> {
                expect(s, 2, "rd, label");
                return expandLa(reg(s, 0), label(s, 1));
            }
            case "move" -> {
                expect(s, 2, "rd, rs");
                return expandMove(reg(s, 0), reg(s, 1));
            }
            default -> throw new AsmException(s.lineNo(), "unsupported instruction '" + s.mnemonic() + "'");
        }
    }

    // ---- Operand helpers (each throws a clear error on bad input) ----
    private static int[] one(int word) { return new int[] { word }; }

    private static void expect(Stmt s, int count, String form) throws AsmException {
        if (s.ops().length != count) {
            throw new AsmException(s.lineNo(), "'" + s.mnemonic() + "' expects " + count + " operand"
                    + (count == 1 ? "" : "s") + " (" + form + ") but got " + s.ops().length);
        }
    }

    private static int reg(Stmt s, int i) throws AsmException {
        String name = s.ops()[i];
        int n = MipsIsa.regNumber(name);
        if (n < 0) {
            String hint = name.startsWith("$") ? "" : " (register names start with '$')";
            throw new AsmException(s.lineNo(), "unknown register '" + name + "'" + hint);
        }
        return n;
    }

    private int label(Stmt s, int i) throws AsmException {
        String name = s.ops()[i];
        Integer addr = labels.get(name);
        if (addr == null) {
            throw new AsmException(s.lineNo(), "undefined label '" + name + "'");
        }
        return addr;
    }

    private static int imm(Stmt s, int i, int min, int max) throws AsmException {
        return parseValue(s.ops()[i], s.lineNo(), "'" + s.mnemonic() + "'", min, max);
    }

    // decimal, hex (0x..), negative, or character ('a', '\n') value, range-checked
    private static int parseValue(String text, int lineNo, String what, int min, int max) throws AsmException {
        long value;
        if (text.startsWith("'")) {
            byte[] b = parseQuoted(text, '\'', lineNo);
            if (b.length != 1) {
                throw new AsmException(lineNo, "character literal " + text + " must contain exactly one character");
            }
            value = b[0];
        } else {
            try {
                boolean neg = text.startsWith("-");
                String digits = neg ? text.substring(1) : text;
                value = digits.toLowerCase().startsWith("0x")
                        ? Long.parseLong(digits.substring(2), 16)
                        : Long.parseLong(digits);
                if (neg) value = -value;
            } catch (NumberFormatException e) {
                throw new AsmException(lineNo, "expected a number but found '" + text + "'");
            }
        }
        if (value < min || value > max) {
            throw new AsmException(lineNo, "value " + text + " is out of range for "
                    + what + " (" + min + " to " + max + ")");
        }
        return (int) value;
    }

    // ---- Text parsing ----

    // remove a # comment, but not a # inside quotes
    private static String stripComment(String line, int lineNo) {
        char quote = 0;   // the quote we're inside, or 0
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    // split "$t1, $t1, 1" on commas (not commas inside quotes)
    private static String[] splitOperands(String text, int lineNo) throws AsmException {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == '\\' && i + 1 < text.length()) cur.append(text.charAt(++i));
                else if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') {
                quote = c;
                cur.append(c);
            } else if (c == ',') {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString().trim());
        for (String op : out) {
            if (op.isEmpty()) throw new AsmException(lineNo, "empty operand (check for an extra comma)");
        }
        return out.toArray(new String[0]);
    }

    private static byte[] parseString(String text, int lineNo) throws AsmException {
        if (!text.startsWith("\"")) {
            throw new AsmException(lineNo, "expected a \"quoted string\" but found '" + text + "'");
        }
        return parseQuoted(text, '"', lineNo);
    }

    // decode a quoted literal and its escape sequences
    private static byte[] parseQuoted(String text, char quote, int lineNo) throws AsmException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == quote) {
                if (i != text.length() - 1) {
                    throw new AsmException(lineNo, "unexpected text after closing " + quote + ": '"
                            + text.substring(i + 1).trim() + "'");
                }
                return out.toByteArray();
            }
            if (c == '\\') {
                if (++i >= text.length()) break;
                char e = text.charAt(i);
                out.write(switch (e) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    case '0' -> 0;
                    case '\\' -> '\\';
                    case '"' -> '"';
                    case '\'' -> '\'';
                    default -> throw new AsmException(lineNo, "unknown escape sequence '\\" + e + "'");
                });
            } else if (c > 127) {
                throw new AsmException(lineNo, "non-ASCII character '" + c + "' is not supported");
            } else {
                out.write(c);
            }
        }
        throw new AsmException(lineNo, "missing closing " + quote + " in " + text);
    }

    private static void noArgs(String directive, String rest, int lineNo) throws AsmException {
        if (!rest.isEmpty()) {
            throw new AsmException(lineNo, directive + " with an address is not supported (fixed at "
                    + (directive.equals(".text") ? "0x00400000" : "0x10010000") + ")");
        }
    }

    // ---- Output ----

    public static final int HEADER_SIZE = 20;

    // Both formats hold these bytes: header (magic, text addr/len, data addr/len),
    // then instructions, then data. DataOutputStream writes big-endian.
    byte[] toBytes() {
        byte[] dataBytes = data.toByteArray();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buf)) {
            out.writeBytes("MHC1");
            out.writeInt(TEXT_BASE);
            out.writeInt(words.size() * 4);
            out.writeInt(DATA_BASE);
            out.writeInt(dataBytes.length);
            for (int w : words) out.writeInt(w);
            out.write(dataBytes);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return buf.toByteArray();
    }

    void writeBin(Path path) throws IOException {
        Files.write(path, toBytes());
    }

    // mhc-ascii: the same bytes as 0/1 characters; 32 bits per line for the
    // header and instructions, 8 bits per line for data
    void writeAscii(Path path) throws IOException {
        byte[] bytes = toBytes();
        int codeEnd = HEADER_SIZE + words.size() * 4;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            String bits = Integer.toBinaryString(bytes[i] & 0xFF);
            sb.append("0".repeat(8 - bits.length())).append(bits);
            boolean endOfWord = (i + 1) % 4 == 0;
            if (i >= codeEnd || endOfWord) sb.append('\n');
        }
        Files.writeString(path, sb.toString(), StandardCharsets.US_ASCII);
    }

    void printListing(List<String> lines) {
        System.out.println("Address     Machine code  Source");
        for (int i = 0; i < words.size(); i++) {
            int line = wordLine.get(i);
            boolean firstOfLine = i == 0 || wordLine.get(i - 1) != line;
            System.out.printf("0x%08X  0x%08X    %s%n", TEXT_BASE + 4 * i, words.get(i),
                    firstOfLine ? lines.get(line - 1).trim() : "  (cont.)");
        }
    }

    public static void main(String[] args) {
        List<String> positional = new ArrayList<>();
        boolean list = false;
        for (String a : args) {
            if (a.equals("--list")) list = true;
            else positional.add(a);
        }
        if (positional.isEmpty() || positional.size() > 2) {
            System.err.println("usage: java -cp build Assembler <input.asm> [outBase] [--list]");
            System.exit(2);
        }
        Path in = Path.of(positional.get(0));
        String outBase = positional.size() == 2
                ? positional.get(1)
                : positional.get(0).replaceFirst("\\.[^./\\\\]*$", "");

        List<String> lines;
        try {
            lines = Files.readAllLines(in, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            System.err.println("error: file not found: " + in);
            System.exit(1);
            return;
        } catch (IOException e) {
            System.err.println("error: cannot read " + in + " as a text file (" + e + ")");
            System.exit(1);
            return;
        }

        Assembler asm = new Assembler();
        try {
            asm.assemble(lines);
        } catch (AsmException e) {
            System.err.printf("%s:%d: error: %s%n", in.getFileName(), e.line, e.getMessage());
            if (e.line > 0) System.err.println("    " + lines.get(e.line - 1).trim());
            System.exit(1);
        }

        Path asciiPath = Path.of(outBase + ".mhc-ascii");
        Path binPath = Path.of(outBase + ".mhc-bin");
        try {
            if (asciiPath.getParent() != null) Files.createDirectories(asciiPath.getParent());
            asm.writeAscii(asciiPath);
            asm.writeBin(binPath);
            if (list) asm.printListing(lines);
            System.out.printf("Assembled %d instructions (%d bytes) + %d data bytes%n",
                    asm.words.size(), asm.words.size() * 4, asm.data.size());
            System.out.printf("  %s  (%d bytes)%n  %s  (%d bytes)%n",
                    asciiPath, Files.size(asciiPath), binPath, Files.size(binPath));
        } catch (IOException e) {
            System.err.println("error: cannot write output: " + e);
            System.exit(1);
        }
    }
}
