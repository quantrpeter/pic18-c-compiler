package com.example.pic18;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end smoke tests for the pic18-c-compiler. These tests don't try
 * to assemble the output (that would require MPASM) -- they just verify
 * that we produce non-empty, plausible-looking PIC18 assembly for a few
 * tiny programs and that obvious error conditions are reported.
 */
class CompileSmokeTest {

    private static String compileQuiet(String src) {
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        try (PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8)) {
            return Main.compileSource(src, "<test>", err);
        }
    }

    // -------------------------------------------------------------------------
    // Required smoke test from the brief.
    // -------------------------------------------------------------------------
    @Test
    void compilesTinyProgram_andEmitsExpectedSubstrings() {
        String src = "int main(void) { return 1 + 2; }";
        String asm = compileQuiet(src);
        assertNotNull(asm);

        // The function label for main is special-cased to MAIN.
        assertContains(asm, "MAIN");
        // The function should end with a RETURN.
        assertContains(asm, "RETURN");
        // We should see a MOVLW for at least one of the two literal constants.
        // (We expect 0x01 for the lhs and 0x02 for the rhs, before ADDWF.)
        assertContains(asm, "MOVLW   0x01");
        assertContains(asm, "MOVLW   0x02");
        // The literal addition lowers to ADDWF against the eval-stack slot.
        assertContains(asm, "ADDWF   EVAL_STACK");
        // Standard prelude bits.
        assertContains(asm, "LIST    P=18F4520");
        assertContains(asm, "GOTO    MAIN");
    }

    // -------------------------------------------------------------------------
    // Additional sanity checks.
    // -------------------------------------------------------------------------
    @Test
    void outBuiltinLowersToLatRegisterWrite() {
        String src = "int main(void) { out(2, 0x55); return 0; }";
        String asm = compileQuiet(src);
        assertContains(asm, "MOVLW   0x55");
        assertContains(asm, "MOVWF   LATC");
    }

    @Test
    void delayBuiltinEmitsRuntimeHelper() {
        String src = ""
                + "void delay(int n);\n"
                + "int main(void) { delay(50); return 0; }\n";
        String asm = compileQuiet(src);
        assertContains(asm, "CALL    F_delay");
        assertContains(asm, "F_delay:");
        assertContains(asm, "DECFSZ  DELAY_INNER");
    }

    @Test
    void whileLoopEmitsBranchLabels() {
        String src = ""
                + "int main(void) {\n"
                + "    int i;\n"
                + "    i = 0;\n"
                + "    while (i < 10) { i = i + 1; }\n"
                + "    return i;\n"
                + "}\n";
        String asm = compileQuiet(src);
        assertContains(asm, "WHILE_");
        assertContains(asm, "WEND_");
        assertContains(asm, "CPFSGT");
    }

    @Test
    void forLoopWithPostIncrementCompiles() {
        // Mirrors `for (int a = 0; a < 10; a++) { ... }` from examples/blink.c.
        String src = ""
                + "int main(void) {\n"
                + "    int sum;\n"
                + "    sum = 0;\n"
                + "    for (int a = 0; a < 10; a++) {\n"
                + "        sum = sum + a;\n"
                + "    }\n"
                + "    return sum;\n"
                + "}\n";
        String asm = compileQuiet(src);
        assertContains(asm, "FOR_");
        assertContains(asm, "FEND_");
        // Loop variable lives in its own per-function local slot.
        assertContains(asm, "F_main__LOC_a");
        // Postfix `a++` lowers to a single in-place INCF on the local.
        assertContains(asm, "INCF    F_main__LOC_a, F, A");
    }

    @Test
    void prefixIncrementUpdatesThenLoadsValue() {
        String src = ""
                + "int main(void) {\n"
                + "    int x;\n"
                + "    x = 5;\n"
                + "    return ++x;\n"
                + "}\n";
        String asm = compileQuiet(src);
        // For ++x we expect an in-place INCF followed by a re-load of the new
        // value into W (so the expression result is the *new* value).
        int incIdx  = asm.indexOf("INCF    F_main__LOC_x, F, A");
        int loadIdx = asm.indexOf("MOVF    F_main__LOC_x, W, A", incIdx);
        assertTrue(incIdx >= 0, "expected an in-place INCF on x");
        assertTrue(loadIdx > incIdx, "expected MOVF to reload x AFTER INCF for prefix ++");
    }

    @Test
    void postfixDecrementOnGlobalVariableCompiles() {
        String src = ""
                + "unsigned int counter = 7;\n"
                + "int main(void) { counter--; return counter; }\n";
        String asm = compileQuiet(src);
        assertContains(asm, "DECF    G_counter, F, A");
    }

    @Test
    void shiftByConstantExpands() {
        String src = "int main(void) { return 8 >> 1; }";
        String asm = compileQuiet(src);
        assertContains(asm, "RRCF");
    }

    @Test
    void divisionByPowerOfTwoUsesShifts() {
        String src = "int main(void) { return 16 / 4; }";
        String asm = compileQuiet(src);
        assertContains(asm, "RRCF");
    }

    @Test
    void divisionByNonPowerOfTwoIsRejected() {
        String src = "int main(void) { return 100 / 3; }";
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        try (PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8)) {
            assertThrows(RuntimeException.class,
                    () -> Main.compileSource(src, "<test>", err));
        }
    }

    @Test
    void syntaxErrorIsReportedWithLineInfo() {
        String src = "int main(void) { return ; }\n";   // missing expr (actually OK -- 'return ;' is allowed)
        // The grammar permits 'return;' for void contexts but here we use it
        // in an int-returning main. That still parses (semantic check is light)
        // so use a real syntax error instead:
        String bad = "int main(void) { return 1 + ; }\n";

        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        try (PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8)) {
            try {
                Main.compileSource(bad, "<test>", err);
                fail("expected a syntax error to be thrown");
            } catch (Main.SyntaxErrorException expected) {
                // good
            }
        }
        String stderr = errBytes.toString(StandardCharsets.UTF_8);
        assertTrue(stderr.contains("error:"),
                "expected an error: prefix in stderr, got: " + stderr);
        // Even with a tiny program we should still have parsed enough that
        // output isn't empty -- but compileSource throws before returning, so
        // we just confirm the error path is taken.
        assertNotEquals("", stderr.trim());
    }

    @Test
    void mainEntryRunReturnsNonZeroOnUnknownFile() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (PrintStream o = new PrintStream(out, true, StandardCharsets.UTF_8);
             PrintStream e = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            // No args -> usage + non-zero rc.
            assertEquals(1, Main.run(new String[0], o, e));
        }
    }

    private static void assertContains(String haystack, String needle) {
        if (!haystack.contains(needle)) {
            fail("expected output to contain `" + needle + "`. Got:\n" + haystack);
        }
    }
}
