import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.TreeSet;

// Disassembler: .mhc-bin or .mhc-ascii -> .asm that reassembles to the same bytes
// Usage: java -cp build Disassembler <input.mhc-bin|input.mhc-ascii> <output.asm>
public class Disassembler {

    // ---- Step 1: load either format into the same byte array ----

    static byte[] load(byte[] file) throws AsmException {
        if (startsWithMagic(file)) {
            return file;                 // mhc-bin is already raw bytes
        }
        return asciiToBytes(file);       // otherwise it must be mhc-ascii
    }

    private static boolean startsWithMagic(byte[] b) {
        return b.length >= 4 && b[0] == 'M' && b[1] == 'H' && b[2] == 'C' && b[3] == '1';
    }

    // mhc-ascii -> bytes: skip whitespace, every 8 '0'/'1' characters become one byte
    static byte[] asciiToBytes(byte[] file) throws AsmException {
        StringBuilder bits = new StringBuilder();
        int line = 1;
        for (byte b : file) {
            char c = (char) (b & 0xFF);
            if (c == '0' || c == '1') {
                bits.append(c);
            } else if (c == '\n') {
                line++;
            } else if (c != '\r' && c != ' ' && c != '\t') {
                throw new AsmException(line, "not a valid mhc file: found '" + printable(c)
                        + "' (an mhc-ascii file may only contain 0, 1, and whitespace;"
                        + " an mhc-bin file must start with \"MHC1\")");
            }
        }
        if (bits.length() % 8 != 0) {
            throw new AsmException(0, "mhc-ascii file has " + bits.length()
                    + " bits, which is not a whole number of bytes (must be a multiple of 8)");
        }
        byte[] out = new byte[bits.length() / 8];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(bits.substring(i * 8, i * 8 + 8), 2);
        }
        if (!startsWithMagic(out)) {
            throw new AsmException(0, "mhc-ascii file does not begin with the MHC1 header");
        }
        return out;
    }

    private static String printable(char c) {
        return (c >= 32 && c < 127) ? String.valueOf(c) : String.format("\\x%02X", (int) c);
    }

    // ---- Step 2: read the header, split into instructions and data ----

    int textBase, dataBase;
    int[] words;
    byte[] data;

    void parseImage(byte[] img) throws AsmException {
        if (img.length < Assembler.HEADER_SIZE) {
            throw new AsmException(0, "file is too short to hold the 20-byte header (" + img.length + " bytes)");
        }
        ByteBuffer buf = ByteBuffer.wrap(img);   // reads big-endian by default
        buf.getInt();                            // magic, already checked
        textBase = buf.getInt();
        int textLen = buf.getInt();
        dataBase = buf.getInt();
        int dataLen = buf.getInt();

        if (textLen < 0 || textLen % 4 != 0) {
            throw new AsmException(0, "header text length " + textLen + " is not a multiple of 4");
        }
        if (dataLen < 0) {
            throw new AsmException(0, "header data length " + dataLen + " is negative");
        }
        long expected = (long) Assembler.HEADER_SIZE + textLen + dataLen;
        if (img.length != expected) {
            throw new AsmException(0, "header says " + expected + " bytes total but the file has "
                    + img.length + (img.length < expected ? " (file is truncated)" : " (extra bytes at end)"));
        }

        words = new int[textLen / 4];
        for (int i = 0; i < words.length; i++) words[i] = buf.getInt();
        data = new byte[dataLen];
        buf.get(data);
    }

    // ---- Step 3: decode fields (the reverse of encoding: shift right, then mask) ----

    record Decoded(int address, int word) {
        int opcode() { return (word >>> 26) & 0x3F; }
        int rs()     { return (word >>> 21) & 0x1F; }
        int rt()     { return (word >>> 16) & 0x1F; }
        int rd()     { return (word >>> 11) & 0x1F; }
        int shamt()  { return (word >>> 6)  & 0x1F; }
        int funct()  { return  word         & 0x3F; }
        int immU()   { return  word         & 0xFFFF; }   // unsigned
        int immS()   { return (short) word; }              // sign-extended
        int addr26() { return  word         & 0x3FFFFFF; }

        int branchTarget() { return address + 4 + immS() * 4; }                          // undoes branchOffset
        int jumpTarget()   { return ((address + 4) & 0xF0000000) | (addr26() << 2); }    // undoes jumpField
    }

    private int addressOf(int index) { return textBase + 4 * index; }

    // ---- Step 4: give every branch/jump target a label ----

    private final TreeSet<Integer> codeLabels = new TreeSet<>();

    void collectLabels() throws AsmException {
        int textEnd = textBase + 4 * words.length;
        for (int i = 0; i < words.length; i++) {
            Decoded d = new Decoded(addressOf(i), words[i]);
            Integer target = switch (d.opcode()) {
                case MipsIsa.OP_BEQ, MipsIsa.OP_BNE -> d.branchTarget();
                case MipsIsa.OP_J -> d.jumpTarget();
                default -> null;
            };
            if (target == null) continue;
            if (target < textBase || target >= textEnd) {
                throw new AsmException(0, String.format(
                        "instruction at 0x%08X jumps to 0x%08X, which is outside the program", d.address(), target));
            }
            codeLabels.add(target);
        }
    }

    private static String label(int address) { return String.format("L_%08X", address); }

    // ---- Step 5: decoded word -> assembly text ----

    private static String r(int n) { return MipsIsa.REG_NAMES[n]; }

    String format(Decoded d) throws AsmException {
        int op = d.opcode();
        if (op == MipsIsa.OP_RTYPE) {
            String s = switch (d.funct()) {
                case MipsIsa.FN_ADD  -> "add   " + r(d.rd()) + ", " + r(d.rs()) + ", " + r(d.rt());
                case MipsIsa.FN_ADDU -> "addu  " + r(d.rd()) + ", " + r(d.rs()) + ", " + r(d.rt());
                case MipsIsa.FN_SUB  -> "sub   " + r(d.rd()) + ", " + r(d.rs()) + ", " + r(d.rt());
                case MipsIsa.FN_DIV  -> "div   " + r(d.rs()) + ", " + r(d.rt());
                case MipsIsa.FN_MFHI -> "mfhi  " + r(d.rd());
                case MipsIsa.FN_SYSCALL -> "syscall";
                default -> throw unknown(d, String.format("R-type funct 0x%02X", d.funct()));
            };
            requireCanonical(d, s);
            return s;
        }
        String s = switch (op) {
            case MipsIsa.OP_ADDI  -> "addi  " + r(d.rt()) + ", " + r(d.rs()) + ", " + d.immS();
            case MipsIsa.OP_ADDIU -> "addiu " + r(d.rt()) + ", " + r(d.rs()) + ", " + d.immS();
            case MipsIsa.OP_ORI   -> "ori   " + r(d.rt()) + ", " + r(d.rs()) + ", " + String.format("0x%04X", d.immU());
            case MipsIsa.OP_LUI   -> "lui   " + r(d.rt()) + ", " + String.format("0x%04X", d.immU());
            case MipsIsa.OP_BEQ   -> "beq   " + r(d.rs()) + ", " + r(d.rt()) + ", " + label(d.branchTarget());
            case MipsIsa.OP_BNE   -> "bne   " + r(d.rs()) + ", " + r(d.rt()) + ", " + label(d.branchTarget());
            case MipsIsa.OP_J     -> "j     " + label(d.jumpTarget());
            default -> throw unknown(d, String.format("opcode 0x%02X", op));
        };
        if (op == MipsIsa.OP_LUI && d.rs() != 0) requireCanonical(d, s);
        return s;
    }

    // Unused fields must be zero, or reassembling would not give the same word
    private void requireCanonical(Decoded d, String text) throws AsmException {
        int f = d.funct();
        int expected = switch (f) {
            case MipsIsa.FN_ADD, MipsIsa.FN_ADDU, MipsIsa.FN_SUB -> MipsIsa.encodeR(d.rs(), d.rt(), d.rd(), 0, f);
            case MipsIsa.FN_DIV  -> MipsIsa.encodeR(d.rs(), d.rt(), 0, 0, f);
            case MipsIsa.FN_MFHI -> MipsIsa.encodeR(0, 0, d.rd(), 0, f);
            case MipsIsa.FN_SYSCALL -> MipsIsa.encodeR(0, 0, 0, 0, f);
            default -> MipsIsa.encodeI(MipsIsa.OP_LUI, 0, d.rt(), d.immU());
        };
        if (expected != d.word()) {
            throw new AsmException(0, String.format(
                    "word %s at 0x%08X looks like '%s' but has nonzero bits in unused fields",
                    MipsIsa.toHex32(d.word()), d.address(), text.replaceAll("\\s+", " ")));
        }
    }

    private static AsmException unknown(Decoded d, String what) {
        return new AsmException(0, String.format("unsupported instruction word %s at 0x%08X (%s)",
                MipsIsa.toHex32(d.word()), d.address(), what));
    }

    // ---- Step 6: write the .asm (data first, then text, like the original) ----

    String render() throws AsmException {
        StringBuilder out = new StringBuilder();
        out.append(".data\n");
        renderData(out);
        out.append("\n.text\n");
        for (int i = 0; i < words.length; i++) {
            int addr = addressOf(i);
            if (codeLabels.contains(addr)) out.append(label(addr)).append(":\n");
            out.append("    ").append(format(new Decoded(addr, words[i]))).append('\n');
        }
        return out.toString();
    }

    // null-terminated printable text -> .asciiz; any other byte -> .byte
    private void renderData(StringBuilder out) {
        int i = 0;
        while (i < data.length) {
            int end = i;
            while (end < data.length && data[end] != 0 && isTextByte(data[end])) end++;
            String label = String.format("D_%08X: ", dataBase + i);
            if (end < data.length && data[end] == 0) {
                out.append(label).append(".asciiz \"").append(escape(data, i, end)).append("\"\n");
                i = end + 1;
            } else {
                out.append(label).append(".byte ").append(data[i] & 0xFF).append('\n');
                i++;
            }
        }
    }

    private static boolean isTextByte(byte b) {
        return (b >= 32 && b < 127) || b == '\n' || b == '\t' || b == '\r';
    }

    private static String escape(byte[] b, int from, int to) {
        StringBuilder s = new StringBuilder();
        for (int i = from; i < to; i++) {
            char c = (char) b[i];
            switch (c) {
                case '\n' -> s.append("\\n");
                case '\t' -> s.append("\\t");
                case '\r' -> s.append("\\r");
                case '"'  -> s.append("\\\"");
                case '\\' -> s.append("\\\\");
                default   -> s.append(c);
            }
        }
        return s.toString();
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: java -cp build Disassembler <input.mhc-bin|input.mhc-ascii> <output.asm>");
            System.exit(2);
        }
        Path in = Path.of(args[0]);
        Path outPath = Path.of(args[1]);
        try {
            byte[] file = Files.readAllBytes(in);
            Disassembler dis = new Disassembler();
            dis.parseImage(load(file));
            dis.collectLabels();
            String asm = dis.render();

            if (outPath.getParent() != null) Files.createDirectories(outPath.getParent());
            Files.writeString(outPath, asm, StandardCharsets.US_ASCII);
            System.out.printf("Disassembled %d instructions + %d data bytes (%s format) -> %s%n",
                    dis.words.length, dis.data.length, startsWithMagic(file) ? "mhc-bin" : "mhc-ascii", outPath);
        } catch (NoSuchFileException e) {
            System.err.println("error: file not found: " + in);
            System.exit(1);
        } catch (AsmException e) {
            System.err.println(in.getFileName() + (e.line > 0 ? ":" + e.line : "") + ": error: " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("error: " + e);
            System.exit(1);
        }
    }
}
