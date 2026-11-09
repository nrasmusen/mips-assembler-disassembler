// Checks the Assembler's branch/jump math and pseudo expansions with cases from FizzBuzz
public class AssemblerSelfTest {

    private static int failures = 0;

    public static void main(String[] args) {
        // 1. branchOffset: bne $t1, $t0, loop   (bne at 0x00400014, loop at 0x00400008) -> backward
        run("branchOffset backward (bne -> loop)", () ->
            check(Assembler.branchOffset(0x00400014, 0x00400008), -4));
        //    beq $t3, $zero, PrintFizzBuzz       (beq at 0x00400028, target 0x00400050)  -> forward
        run("branchOffset forward (beq -> PrintFizzBuzz)", () ->
            check(Assembler.branchOffset(0x00400028, 0x00400050), 9));

        // 2. jumpField: j PrintItem   (PrintItem at 0x0040001C)
        run("jumpField (j PrintItem)", () ->
            check(Assembler.jumpField(0x0040001C), 0x00100007));

        // 3. expandLi: li $t1, 1   ->  addiu $t1, $zero, 1
        run("expandLi (li $t1, 1)", () ->
            checkWords(Assembler.expandLi(9, 1), 0x24090001));

        // 4. expandMove: move $a0, $t1  ->  addu $a0, $zero, $t1
        run("expandMove (move $a0, $t1)", () ->
            checkWords(Assembler.expandMove(4, 9), 0x00092021));

        // 5. expandLa: la $a0, fizzBuzz  (fizzBuzz at 0x1001000A)
        //    ->  lui $at, 0x1001  ;  ori $a0, $at, 0x000A
        run("expandLa (la $a0, fizzBuzz)", () ->
            checkWords(Assembler.expandLa(4, 0x1001000A), 0x3C011001, 0x3424000A));

        System.out.println(failures == 0 ? "\nAll passed." : "\n" + failures + " failed.");
    }

    private interface Check { String run(); }

    private static void run(String label, Check c) {
        String result;
        try {
            result = c.run();
        } catch (UnsupportedOperationException e) {
            result = "TODO  (" + e.getMessage() + ")";
        }
        if (!result.startsWith("PASS")) failures++;
        System.out.printf("%-46s %s%n", label, result);
    }

    private static String check(int got, int expected) {
        return (got == expected ? "PASS" : "FAIL") + "  got " + got + " (" + MipsIsa.toHex32(got)
                + ")  expected " + expected + " (" + MipsIsa.toHex32(expected) + ")";
    }

    private static String checkWords(int[] got, int... expected) {
        StringBuilder g = new StringBuilder(), e = new StringBuilder();
        for (int w : got) g.append(MipsIsa.toHex32(w)).append(' ');
        for (int w : expected) e.append(MipsIsa.toHex32(w)).append(' ');
        boolean ok = java.util.Arrays.equals(got, expected);
        return (ok ? "PASS" : "FAIL") + "  got [ " + g + "]  expected [ " + e + "]";
    }
}
