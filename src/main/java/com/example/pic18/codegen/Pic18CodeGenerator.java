package com.example.pic18.codegen;

import com.example.pic18.grammar.MiniCBaseVisitor;
import com.example.pic18.grammar.MiniCParser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generates MPASM-style PIC18 assembly (targeted at the PIC18F4520) from a
 * MiniC parse tree.
 *
 * <h2>Design overview</h2>
 *
 * The generator is intentionally simple and teaching-oriented; clarity beats
 * efficiency. The code shape is:
 *
 * <pre>
 *   LIST P=18F4520
 *   #include &lt;p18f4520.inc&gt;
 *   CONFIG OSC=INTIO67, WDT=OFF, LVP=OFF, MCLRE=OFF, PBADEN=DIG
 *   ; -- variables --------------------------------------------------------
 *   ACS_BANK  UDATA_ACS
 *   R0/R1/.../R7        scratch
 *   ARG0..ARG3          calling-convention argument slots
 *   RETVAL              return value mirror of WREG
 *   DELAY_CNT_*         delay-loop counters
 *   EVAL_STACK[0..15]   expression evaluation stack
 *   G_&lt;name&gt;            globals
 *   F_&lt;fn&gt;__LOC_&lt;v&gt;  per-function locals (statically allocated)
 *   ; -- code -------------------------------------------------------------
 *   reset  CODE  0x0000   GOTO MAIN
 *   hi-isr CODE  0x0008   RETFIE FAST    ; placeholder
 *   prog   CODE
 *           function bodies, then runtime helpers (delay, etc.)
 *   END
 * </pre>
 *
 * <h2>Calling convention (8-bit only)</h2>
 * <ul>
 *   <li>Up to 4 byte-sized arguments passed in fixed RAM slots
 *       {@code ARG0..ARG3}. The caller stores them before {@code CALL}.</li>
 *   <li>Return value left in {@code WREG} and mirrored to {@code RETVAL}.</li>
 *   <li>Calls use the PIC18 hardware return stack (limit: 31 nested
 *       {@code CALL}s).</li>
 *   <li>Locals are <em>not</em> stored on a stack – they get statically
 *       allocated bytes per function. Two activations of the same function
 *       (recursion) therefore alias each other's locals.</li>
 * </ul>
 *
 * <h2>Expression evaluation</h2>
 *
 * Expressions evaluate to a single byte left in {@code WREG}. For binary
 * operators we compile the left operand to {@code WREG}, push it to the
 * compile-time-tracked stack {@code EVAL_STACK[sp]}, then compile the right
 * operand to {@code WREG}, and finally emit the actual ALU operation against
 * {@code EVAL_STACK[sp-1]}.
 */
public class Pic18CodeGenerator extends MiniCBaseVisitor<Void> {

    /** Maximum addressable depth of the expression evaluation stack. */
    private static final int EVAL_STACK_SIZE = 16;

    /** Maximum supported byte-sized arguments. */
    private static final int MAX_ARGS = 4;

    private final StringBuilder code = new StringBuilder();
    private final Map<String, GlobalSym> globals = new LinkedHashMap<>();
    private final Map<String, FunctionSig> functions = new LinkedHashMap<>();
    private final Set<String> runtimeNeeded = new LinkedHashSet<>();

    // Per-function state.
    private FunctionSig currentFn;
    private Map<String, String> currentLocals;   // name -> label
    private Set<String> declaredLocals;          // for collision checks

    // Expression evaluation depth (compile-time).
    private int sp = 0;
    private int maxSp = 0;

    private int labelCounter = 0;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Compile a parsed program and return the assembly source text. */
    public String compile(MiniCParser.ProgramContext tree) {
        // Pass 1: collect global declarations and function signatures so we can
        // do forward references and validate calls.
        for (MiniCParser.TopLevelContext t : tree.topLevel()) {
            if (t.globalVarDecl() != null) {
                registerGlobal(t.globalVarDecl());
            } else if (t.functionDecl() != null) {
                registerFunction(t.functionDecl());
            }
        }

        if (!functions.containsKey("main")) {
            throw new CompileException("program has no main() function");
        }

        emitPrelude();
        emitDataSections();
        emitCodeStart();

        // Pass 2: emit code for each function body. Prototype-only declarations
        // (no '{' block) are skipped.
        for (MiniCParser.TopLevelContext t : tree.topLevel()) {
            if (t.functionDecl() != null && t.functionDecl().block() != null) {
                emitFunction(t.functionDecl());
            }
        }

        emitRuntimeHelpers();
        emitEnd();
        return code.toString();
    }

    // -------------------------------------------------------------------------
    // Symbol tables
    // -------------------------------------------------------------------------

    private static final class GlobalSym {
        final String name;
        final String label;
        final Integer initializer;   // null if no initializer

        GlobalSym(String name, String label, Integer initializer) {
            this.name = name;
            this.label = label;
            this.initializer = initializer;
        }
    }

    private static final class FunctionSig {
        final String name;
        final String label;
        final List<String> paramNames;
        final boolean returnsValue;
        boolean hasBody;

        FunctionSig(String name, String label, List<String> paramNames, boolean returnsValue) {
            this.name = name;
            this.label = label;
            this.paramNames = paramNames;
            this.returnsValue = returnsValue;
        }
    }

    private void registerGlobal(MiniCParser.GlobalVarDeclContext ctx) {
        String name = ctx.ID().getText();
        if (globals.containsKey(name)) {
            throw new CompileException(ctx, "duplicate global variable '" + name + "'");
        }
        Integer initVal = null;
        if (ctx.expression() != null) {
            initVal = evalConstantExpression(ctx.expression());
            if (initVal == null) {
                throw new CompileException(ctx,
                        "global initializer must be a constant integer expression");
            }
        }
        globals.put(name, new GlobalSym(name, "G_" + name, initVal));
    }

    private void registerFunction(MiniCParser.FunctionDeclContext ctx) {
        String name = ctx.ID().getText();
        boolean returnsValue = !"void".equals(typeName(ctx.type()));
        boolean isPrototype = ctx.block() == null;

        java.util.List<String> params = new java.util.ArrayList<>();
        if (ctx.paramList() != null && ctx.paramList().param() != null) {
            for (MiniCParser.ParamContext p : ctx.paramList().param()) {
                params.add(p.ID().getText());
            }
        }
        if (params.size() > MAX_ARGS) {
            throw new CompileException(ctx,
                    "function '" + name + "' has " + params.size() + " parameters; max is " + MAX_ARGS);
        }

        FunctionSig existing = functions.get(name);
        if (existing != null) {
            // Allow prototype-then-definition; allow re-prototyping. Reject
            // double definitions and signature mismatches.
            if (existing.paramNames.size() != params.size()
                    || existing.returnsValue != returnsValue) {
                throw new CompileException(ctx,
                        "redeclaration of '" + name + "' with different signature");
            }
            if (!isPrototype && existing.hasBody) {
                throw new CompileException(ctx, "duplicate definition of function '" + name + "'");
            }
            if (!isPrototype) {
                existing.hasBody = true;
            }
            return;
        }

        String label = name.equals("main") ? "MAIN" : ("F_" + name);
        FunctionSig sig = new FunctionSig(name, label, params, returnsValue);
        sig.hasBody = !isPrototype;
        functions.put(name, sig);
    }

    // -------------------------------------------------------------------------
    // Prelude / data sections
    // -------------------------------------------------------------------------

    private void emitPrelude() {
        emitLine("; ============================================================");
        emitLine("; Generated by pic18-c-compiler (teaching-quality codegen).");
        emitLine("; Target chip: PIC18F4520. Adjust LIST/include/CONFIG as needed.");
        emitLine("; ============================================================");
        emitLine("        LIST    P=18F4520");
        emitLine("        #include <p18f4520.inc>");
        emitLine("");
        emitLine("; --- Configuration bits ------------------------------------");
        emitLine("; NOTE: tweak these to match your hardware. We default to a");
        emitLine(";       safe internal-oscillator setup with WDT disabled.");
        emitLine("        CONFIG  OSC = INTIO67");
        emitLine("        CONFIG  WDT = OFF");
        emitLine("        CONFIG  LVP = OFF");
        emitLine("        CONFIG  MCLRE = OFF");
        emitLine("        CONFIG  PBADEN = DIG");
        emitLine("");
    }

    private void emitDataSections() {
        emitLine("; --- Variables (access bank) -------------------------------");
        emitLine("ACS_VARS UDATA_ACS");
        // Virtual scratch registers.
        for (int i = 0; i < 8; i++) {
            emitLine(pad("R" + i) + "RES     1");
        }
        // Calling-convention slots.
        for (int i = 0; i < MAX_ARGS; i++) {
            emitLine(pad("ARG" + i) + "RES     1");
        }
        emitLine(pad("RETVAL") + "RES     1");
        // Delay loop counters (used by the delay() runtime helper).
        emitLine(pad("DELAY_OUTER") + "RES     1");
        emitLine(pad("DELAY_INNER") + "RES     1");
        // Expression evaluation stack.
        emitLine(pad("EVAL_STACK") + "RES     " + EVAL_STACK_SIZE);

        // Globals.
        for (GlobalSym g : globals.values()) {
            emitLine(pad(g.label) + "RES     1");
        }

        // Per-function parameter mirror slots. (Declared locals are reserved in
        // a per-function UDATA_ACS section emitted just before the function
        // body in emitFunction().) Prototype-only declarations don't need
        // slots because they have no body to reference them.
        for (FunctionSig fn : functions.values()) {
            if (!fn.hasBody) continue;
            for (String p : fn.paramNames) {
                emitLine(pad(localLabel(fn.name, p)) + "RES     1");
            }
        }

        emitLine("");
    }

    private void emitCodeStart() {
        emitLine("; --- Reset vector ------------------------------------------");
        emitLine("RESETV  CODE    0x0000");
        emitLine("        GOTO    MAIN");
        emitLine("");
        emitLine("; --- High-priority interrupt vector (placeholder) ----------");
        emitLine("HIISR   CODE    0x0008");
        emitLine("        RETFIE  FAST");
        emitLine("");
        emitLine("; --- Low-priority interrupt vector (placeholder) -----------");
        emitLine("LOISR   CODE    0x0018");
        emitLine("        RETFIE  FAST");
        emitLine("");
        emitLine("; --- Program code ------------------------------------------");
        emitLine("PROG    CODE");
    }

    private void emitEnd() {
        emitLine("");
        emitLine("        END");
    }

    // -------------------------------------------------------------------------
    // Function emission
    // -------------------------------------------------------------------------

    private void emitFunction(MiniCParser.FunctionDeclContext ctx) {
        currentFn = functions.get(ctx.ID().getText());
        currentLocals = new LinkedHashMap<>();
        declaredLocals = new LinkedHashSet<>();
        sp = 0;
        maxSp = 0;

        // Mirror parameters into local slots so they're addressable as variables.
        for (String p : currentFn.paramNames) {
            currentLocals.put(p, localLabel(currentFn.name, p));
            declaredLocals.add(p);
        }

        // Pre-scan for local variable declarations and reserve them in the
        // udata section *now* (we patch the code buffer because the udata block
        // was already emitted). We use a deferred mechanism: append RES lines
        // to a side buffer that we later inject. To keep things linear, we
        // emit a fresh per-function UDATA section right before the function
        // label - that's still legal MPASM.
        Map<String, String> localsToReserve = new LinkedHashMap<>();
        collectLocalDecls(ctx.block(), localsToReserve);

        if (!localsToReserve.isEmpty()) {
            emitLine("");
            emitLine("; locals for " + currentFn.name + "()");
            emitLine("LOC_" + currentFn.name + "  UDATA_ACS");
            for (Map.Entry<String, String> e : localsToReserve.entrySet()) {
                String name = e.getKey();
                String label = e.getValue();
                if (currentLocals.containsKey(name)) {
                    throw new CompileException(
                            "local '" + name + "' shadows parameter in function '" + currentFn.name + "'");
                }
                currentLocals.put(name, label);
                declaredLocals.add(name);
                emitLine(pad(label) + "RES     1");
            }
            emitLine("PROG    CODE");
        }

        emitLine("");
        emitLine("; ===== function " + currentFn.name + "(" + String.join(", ", currentFn.paramNames) + ") =====");
        emitLine(currentFn.label + ":");

        // Copy ARG slots into locally named slots.
        for (int i = 0; i < currentFn.paramNames.size(); i++) {
            String label = currentLocals.get(currentFn.paramNames.get(i));
            emitLine("        MOVF    ARG" + i + ", W, A");
            emitLine("        MOVWF   " + label + ", A");
        }

        // Initialize all "declared" locals to 0 if they have an initializer
        // expression (handled when we visit the localDeclStmt). Plain decls
        // without init are left undefined per C semantics — to be friendlier,
        // we explicitly clear them.
        for (Map.Entry<String, String> e : localsToReserve.entrySet()) {
            emitLine("        CLRF    " + e.getValue() + ", A");
        }

        // Body.
        visit(ctx.block());

        // Fall-through return.
        emitLine("        RETURN");
        emitLine("        ; max expr-stack depth used here: " + maxSp);
    }

    /** Walk a function body and gather local-variable declarations. */
    private void collectLocalDecls(ParserRuleContext root, Map<String, String> out) {
        if (root == null) {
            return;
        }
        if (root instanceof MiniCParser.LocalDeclStmtContext lds) {
            String name = lds.ID().getText();
            String label = localLabel(currentFn.name, name);
            if (out.containsKey(name)) {
                throw new CompileException(lds, "duplicate local '" + name + "' in function '"
                        + currentFn.name + "'");
            }
            out.put(name, label);
        }
        if (root instanceof MiniCParser.ForStmtContext fs && fs.forInit() != null
                && fs.forInit().type() != null) {
            // for (int i = ...; ...; ...)
            MiniCParser.ForInitContext fi = fs.forInit();
            String name = fi.ID().getText();
            String label = localLabel(currentFn.name, name);
            if (!out.containsKey(name)) {
                out.put(name, label);
            }
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            if (root.getChild(i) instanceof ParserRuleContext child) {
                collectLocalDecls(child, out);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Statements
    // -------------------------------------------------------------------------

    @Override
    public Void visitBlock(MiniCParser.BlockContext ctx) {
        for (MiniCParser.StatementContext s : ctx.statement()) {
            visit(s);
        }
        return null;
    }

    @Override
    public Void visitBlockStmt(MiniCParser.BlockStmtContext ctx) {
        return visitBlock(ctx.block());
    }

    @Override
    public Void visitEmptyStmt(MiniCParser.EmptyStmtContext ctx) {
        return null;
    }

    @Override
    public Void visitLocalDeclStmt(MiniCParser.LocalDeclStmtContext ctx) {
        String name = ctx.ID().getText();
        String label = currentLocals.get(name);
        if (label == null) {
            // Should not happen because we pre-collected; defensive.
            throw new CompileException(ctx, "internal: local '" + name + "' not pre-allocated");
        }
        if (ctx.expression() != null) {
            emitComment("init local " + name);
            emitExpression(ctx.expression());
            emitLine("        MOVWF   " + label + ", A");
        }
        return null;
    }

    @Override
    public Void visitAssignStmt(MiniCParser.AssignStmtContext ctx) {
        String name = ctx.ID().getText();
        String label = resolveVariable(name, ctx);
        emitComment("assign " + name);
        emitExpression(ctx.expression());
        emitLine("        MOVWF   " + label + ", A");
        return null;
    }

    @Override
    public Void visitExprStmt(MiniCParser.ExprStmtContext ctx) {
        emitComment("expression-statement");
        emitExpression(ctx.expression());
        return null;
    }

    @Override
    public Void visitReturnStmt(MiniCParser.ReturnStmtContext ctx) {
        if (ctx.expression() != null) {
            emitExpression(ctx.expression());
            emitLine("        MOVWF   RETVAL, A");
        }
        emitLine("        RETURN");
        return null;
    }

    @Override
    public Void visitIfStmt(MiniCParser.IfStmtContext ctx) {
        String elseLbl = newLabel("ELSE");
        String endLbl = newLabel("ENDIF");

        emitComment("if (...)");
        emitExpression(ctx.expression());
        emitLine("        IORLW   0           ; set Z if W==0");
        emitLine("        BZ      " + elseLbl);

        visit(ctx.statement(0));
        emitLine("        BRA     " + endLbl);

        emitLine(elseLbl + ":");
        if (ctx.statement().size() > 1) {
            visit(ctx.statement(1));
        }
        emitLine(endLbl + ":");
        return null;
    }

    @Override
    public Void visitWhileStmt(MiniCParser.WhileStmtContext ctx) {
        String topLbl = newLabel("WHILE");
        String endLbl = newLabel("WEND");

        emitComment("while (...)");
        emitLine(topLbl + ":");
        emitExpression(ctx.expression());
        emitLine("        IORLW   0");
        emitLine("        BZ      " + endLbl);
        visit(ctx.statement());
        emitLine("        BRA     " + topLbl);
        emitLine(endLbl + ":");
        return null;
    }

    @Override
    public Void visitForStmt(MiniCParser.ForStmtContext ctx) {
        String topLbl = newLabel("FOR");
        String endLbl = newLabel("FEND");

        emitComment("for (...)");
        if (ctx.forInit() != null) {
            MiniCParser.ForInitContext fi = ctx.forInit();
            if (fi.type() != null) {
                String name = fi.ID().getText();
                String label = currentLocals.get(name);
                if (label == null) {
                    throw new CompileException(fi, "internal: for-init local '" + name + "' missing");
                }
                if (fi.expression() != null) {
                    emitExpression(fi.expression());
                    emitLine("        MOVWF   " + label + ", A");
                }
            } else if (fi.ID() != null) {
                String name = fi.ID().getText();
                String label = resolveVariable(name, fi);
                emitExpression(fi.expression());
                emitLine("        MOVWF   " + label + ", A");
            } else if (fi.expression() != null) {
                emitExpression(fi.expression());
            }
        }
        emitLine(topLbl + ":");
        if (ctx.expression().size() > 0) {
            // first expression is the test (if any)
            emitExpression(ctx.expression(0));
            emitLine("        IORLW   0");
            emitLine("        BZ      " + endLbl);
        }
        visit(ctx.statement());
        if (ctx.expression().size() > 1) {
            // second expression is the step
            emitExpression(ctx.expression(1));
        }
        emitLine("        BRA     " + topLbl);
        emitLine(endLbl + ":");
        return null;
    }

    // -------------------------------------------------------------------------
    // Expressions
    // -------------------------------------------------------------------------

    /**
     * Compile an expression so that its 8-bit result ends up in {@code WREG}.
     */
    private void emitExpression(MiniCParser.ExpressionContext ctx) {
        if (ctx instanceof MiniCParser.ParenExprContext p) {
            emitExpression(p.expression());
        } else if (ctx instanceof MiniCParser.IntLitContext lit) {
            emitLoadConst(parseInt(lit.INT().getText(), 10), lit);
        } else if (ctx instanceof MiniCParser.HexLitContext lit) {
            emitLoadConst(parseInt(lit.HEX().getText().substring(2), 16), lit);
        } else if (ctx instanceof MiniCParser.CharLitContext lit) {
            emitLoadConst(parseChar(lit.CHAR().getText(), lit), lit);
        } else if (ctx instanceof MiniCParser.IdExprContext ie) {
            emitLoadVar(ie.ID().getText(), ie);
        } else if (ctx instanceof MiniCParser.PostIncExprContext pe) {
            emitIncDec(pe.ID(), pe.op.getText(), /*postfix=*/true, pe);
        } else if (ctx instanceof MiniCParser.PreIncExprContext pe) {
            emitIncDec(pe.ID(), pe.op.getText(), /*postfix=*/false, pe);
        } else if (ctx instanceof MiniCParser.UnaryExprContext ue) {
            emitUnary(ue);
        } else if (ctx instanceof MiniCParser.MulExprContext me) {
            emitBinary(me.expression(0), me.expression(1), me.op.getText(), me);
        } else if (ctx instanceof MiniCParser.AddExprContext ae) {
            emitBinary(ae.expression(0), ae.expression(1), ae.op.getText(), ae);
        } else if (ctx instanceof MiniCParser.ShiftExprContext se) {
            emitShift(se);
        } else if (ctx instanceof MiniCParser.RelExprContext re) {
            emitBinary(re.expression(0), re.expression(1), re.op.getText(), re);
        } else if (ctx instanceof MiniCParser.EqExprContext ee) {
            emitBinary(ee.expression(0), ee.expression(1), ee.op.getText(), ee);
        } else if (ctx instanceof MiniCParser.BitAndExprContext be) {
            emitBinary(be.expression(0), be.expression(1), "&", be);
        } else if (ctx instanceof MiniCParser.BitXorExprContext be) {
            emitBinary(be.expression(0), be.expression(1), "^", be);
        } else if (ctx instanceof MiniCParser.BitOrExprContext be) {
            emitBinary(be.expression(0), be.expression(1), "|", be);
        } else if (ctx instanceof MiniCParser.LogAndExprContext la) {
            emitLogicalAnd(la);
        } else if (ctx instanceof MiniCParser.LogOrExprContext lo) {
            emitLogicalOr(lo);
        } else if (ctx instanceof MiniCParser.CallExprContext ce) {
            emitCall(ce);
        } else {
            throw new CompileException(ctx,
                    "internal: unhandled expression alternative " + ctx.getClass().getSimpleName());
        }
    }

    private void emitLoadConst(int value, ParserRuleContext ctx) {
        int byteVal = value & 0xFF;
        if ((value & ~0xFF) != 0 && (value & ~0xFF) != ~0xFF) {
            // Out of byte range — emit a comment but still load the truncated low byte.
            emitComment("warning: literal " + value + " truncated to 8 bits at line "
                    + ctx.getStart().getLine());
        }
        emitLine("        MOVLW   " + hex8(byteVal));
    }

    private void emitLoadVar(String name, ParserRuleContext ctx) {
        String label = resolveVariable(name, ctx);
        emitLine("        MOVF    " + label + ", W, A");
    }

    /**
     * Compile a pre-/post-fix {@code ++} or {@code --} on a bare identifier.
     *
     * <p>Semantics, for an integer variable {@code v}:
     * <ul>
     *   <li>{@code v++} — leave {@code WREG} = old {@code v}, then {@code v++}</li>
     *   <li>{@code ++v} — {@code v++}, then leave {@code WREG} = new {@code v}</li>
     *   <li>{@code v--} / {@code --v} — symmetric, with {@code DECF}.</li>
     * </ul>
     *
     * The PIC18 {@code INCF}/{@code DECF} instructions read-modify-write a file
     * register without touching {@code W} when the destination operand is
     * {@code F}, which lets us generate compact code for both cases.
     */
    private void emitIncDec(TerminalNode idNode, String op, boolean postfix,
                            ParserRuleContext ctx) {
        String name = idNode.getText();
        String label = resolveVariable(name, ctx);
        boolean inc = op.equals("++");
        String mnem = inc ? "INCF" : "DECF";
        String human = (postfix ? "post" : "pre") + (inc ? "-inc " : "-dec ") + name;
        emitComment(human);
        if (postfix) {
            // W <- old value, then update v in place.
            emitLine("        MOVF    " + label + ", W, A      ; W = old " + name);
            emitLine("        " + mnem + "    " + label + ", F, A      ; " + name
                    + (inc ? "++" : "--") + " (W keeps old value)");
        } else {
            // Update v in place, then W <- new value.
            emitLine("        " + mnem + "    " + label + ", F, A      ; "
                    + (inc ? "++" : "--") + name);
            emitLine("        MOVF    " + label + ", W, A      ; W = new " + name);
        }
    }

    private void emitUnary(MiniCParser.UnaryExprContext ctx) {
        String op = ctx.op.getText();
        emitExpression(ctx.expression());
        switch (op) {
            case "-" -> {
                // PIC18 has no NEGW; stash W in R0, negate R0 in place, reload.
                emitLine("        MOVWF   R0, A         ; unary minus: stash W");
                emitLine("        NEGF    R0, A");
                emitLine("        MOVF    R0, W, A");
            }
            case "!" -> {
                String zeroLbl = newLabel("LNOT_Z");
                String endLbl  = newLabel("LNOT_E");
                emitLine("        IORLW   0             ; logical not: set Z if W==0");
                emitLine("        BZ      " + zeroLbl);
                emitLine("        MOVLW   0x00          ; W was non-zero -> result 0");
                emitLine("        BRA     " + endLbl);
                emitLine(zeroLbl + ":");
                emitLine("        MOVLW   0x01          ; W was zero -> result 1");
                emitLine(endLbl + ":");
            }
            case "~" -> emitLine("        XORLW   0xFF          ; bitwise not");
            default  -> throw new CompileException(ctx, "unknown unary op " + op);
        }
    }

    private void emitBinary(MiniCParser.ExpressionContext lhs,
                            MiniCParser.ExpressionContext rhs,
                            String op,
                            ParserRuleContext ctx) {
        // Compile LHS into W, then push to EVAL_STACK[sp], then compile RHS into W.
        emitExpression(lhs);
        pushW();
        emitExpression(rhs);
        // Now: WREG = rhs, EVAL_STACK[sp-1] = lhs.
        int slot = sp - 1;
        String lhsRef = "EVAL_STACK+" + slot;

        switch (op) {
            case "+" ->
                emitLine("        ADDWF   " + lhsRef + ", W, A   ; W = lhs + rhs");
            case "-" -> {
                // We want lhs - rhs. SUBWF f, W -> W = f - W = lhs - rhs.
                emitLine("        SUBWF   " + lhsRef + ", W, A   ; W = lhs - rhs");
            }
            case "*" -> {
                // 8-bit unsigned MUL: W * f -> PRODH:PRODL. We keep PRODL.
                emitLine("        MULWF   " + lhsRef + ", A      ; PRODH:PRODL = lhs * rhs");
                emitLine("        MOVF    PRODL, W, A          ; keep low byte");
            }
            case "/" -> emitConstantDiv(lhs, rhs, lhsRef, ctx, false);
            case "%" -> emitConstantDiv(lhs, rhs, lhsRef, ctx, true);
            case "&" ->
                emitLine("        ANDWF   " + lhsRef + ", W, A   ; W = lhs & rhs");
            case "|" ->
                emitLine("        IORWF   " + lhsRef + ", W, A   ; W = lhs | rhs");
            case "^" ->
                emitLine("        XORWF   " + lhsRef + ", W, A   ; W = lhs ^ rhs");
            case "==" -> emitCompare(lhsRef, "==");
            case "!=" -> emitCompare(lhsRef, "!=");
            case "<"  -> emitCompare(lhsRef, "<");
            case "<=" -> emitCompare(lhsRef, "<=");
            case ">"  -> emitCompare(lhsRef, ">");
            case ">=" -> emitCompare(lhsRef, ">=");
            default -> throw new CompileException(ctx, "unsupported binary op '" + op + "'");
        }
        popW();
    }

    /**
     * Emit assembly for a comparison, given that {@code WREG} holds the rhs and
     * {@code EVAL_STACK[sp-1]} (label {@code lhsRef}) holds the lhs. The
     * result is left in {@code WREG} as 0 (false) or 1 (true).
     *
     * <p>PIC18 mnemonics:
     * <ul>
     *   <li>{@code CPFSEQ f}: skip next instruction if W == f</li>
     *   <li>{@code CPFSGT f}: skip next instruction if W &gt; f (unsigned)</li>
     *   <li>{@code CPFSLT f}: skip next instruction if W &lt; f (unsigned)</li>
     * </ul>
     * Because we want {@code lhs OP rhs}, and {@code f = lhs}, {@code W = rhs},
     * we translate:
     * <pre>
     *   lhs == rhs  =&gt;  W == f         =&gt; CPFSEQ f
     *   lhs &gt; rhs   =&gt;  f &gt; W   =&gt; W &lt; f         =&gt; CPFSLT f
     *   lhs &lt; rhs   =&gt;  f &lt; W   =&gt; W &gt; f         =&gt; CPFSGT f
     * </pre>
     */
    private void emitCompare(String lhsRef, String op) {
        // CPFS<X> f, A skips the next instruction when its predicate is true.
        // We pick a mnemonic so that "predicate true" means "the source-level
        // comparison evaluates to true" -- but for !=, <=, >= the instruction's
        // predicate is the inverse, so we set `invertResult` and swap the 0/1
        // values placed into W.
        String mnem;
        boolean invertResult = false;
        switch (op) {
            case "==" -> mnem = "CPFSEQ";
            case "!=" -> { mnem = "CPFSEQ"; invertResult = true; }
            // f > W  <=>  W < f, so CPFSLT skips when (lhs > rhs)
            case ">"  -> mnem = "CPFSLT";
            case "<=" -> { mnem = "CPFSLT"; invertResult = true; }
            // f < W  <=>  W > f, so CPFSGT skips when (lhs < rhs)
            case "<"  -> mnem = "CPFSGT";
            case ">=" -> { mnem = "CPFSGT"; invertResult = true; }
            default -> throw new CompileException("internal: bad compare op " + op);
        }
        String falseLbl = newLabel("CMP_F");
        String endLbl   = newLabel("CMP_E");
        String onSkip   = invertResult ? "0x00" : "0x01";   // when predicate true
        String onFall   = invertResult ? "0x01" : "0x00";   // when predicate false

        emitLine("        " + mnem + "  " + lhsRef + ", A   ; skip next if " + op + " true");
        emitLine("        BRA     " + falseLbl);
        emitLine("        MOVLW   " + onSkip);
        emitLine("        BRA     " + endLbl);
        emitLine(falseLbl + ":");
        emitLine("        MOVLW   " + onFall);
        emitLine(endLbl + ":");
    }

    /** Emit constant division/modulus by a power-of-two integer literal. */
    private void emitConstantDiv(MiniCParser.ExpressionContext lhs,
                                 MiniCParser.ExpressionContext rhs,
                                 String lhsRef,
                                 ParserRuleContext ctx,
                                 boolean isMod) {
        Integer rhsConst = evalConstantExpression(rhs);
        if (rhsConst == null) {
            throw new CompileException(ctx,
                    (isMod ? "%" : "/") + " requires a constant power-of-two right operand "
                            + "(division runtime not yet implemented)");
        }
        int rv = rhsConst & 0xFF;
        if (rv == 0) {
            throw new CompileException(ctx, "division by zero");
        }
        int log = Integer.numberOfTrailingZeros(rv);
        if ((rv & (rv - 1)) != 0) {
            throw new CompileException(ctx,
                    (isMod ? "%" : "/") + " by " + rv + " not supported (only powers of two for now)");
        }
        // We currently have W = rhs (which we no longer need) and lhs at lhsRef.
        // Move lhs into W and then do shifts (for /) or mask (for %).
        emitLine("        MOVF    " + lhsRef + ", W, A      ; reload lhs");
        if (isMod) {
            int mask = rv - 1;
            emitLine("        ANDLW   " + hex8(mask) + "       ; W = lhs % " + rv);
        } else {
            // Right-shift `log` times. Use RRNCF (rotate right no carry) — for
            // unsigned it's effectively a logical shift right with W input.
            // RRNCF takes f, but we can use a temp slot R0.
            emitLine("        MOVWF   R0, A");
            for (int i = 0; i < log; i++) {
                emitLine("        BCF     STATUS, C, A   ; clear carry");
                emitLine("        RRCF    R0, F, A");
            }
            emitLine("        MOVF    R0, W, A         ; W = lhs / " + rv);
        }
    }

    private void emitShift(MiniCParser.ShiftExprContext ctx) {
        String op = ctx.op.getText();
        Integer rhsConst = evalConstantExpression(ctx.expression(1));
        if (rhsConst == null) {
            throw new CompileException(ctx,
                    "shift count must be a constant integer (variable shifts not yet supported)");
        }
        int n = rhsConst & 0xFF;
        emitExpression(ctx.expression(0));
        // W has lhs.
        if (n == 0) {
            return;
        }
        emitLine("        MOVWF   R0, A");
        for (int i = 0; i < n; i++) {
            emitLine("        BCF     STATUS, C, A");
            if (op.equals("<<")) {
                emitLine("        RLCF    R0, F, A");
            } else {
                emitLine("        RRCF    R0, F, A");
            }
        }
        emitLine("        MOVF    R0, W, A");
    }

    private void emitLogicalAnd(MiniCParser.LogAndExprContext ctx) {
        String falseLbl = newLabel("LAND_F");
        String endLbl   = newLabel("LAND_E");
        emitExpression(ctx.expression(0));
        emitLine("        IORLW   0");
        emitLine("        BZ      " + falseLbl);
        emitExpression(ctx.expression(1));
        emitLine("        IORLW   0");
        emitLine("        BZ      " + falseLbl);
        emitLine("        MOVLW   0x01");
        emitLine("        BRA     " + endLbl);
        emitLine(falseLbl + ":");
        emitLine("        MOVLW   0x00");
        emitLine(endLbl + ":");
    }

    private void emitLogicalOr(MiniCParser.LogOrExprContext ctx) {
        String trueLbl = newLabel("LOR_T");
        String endLbl  = newLabel("LOR_E");
        emitExpression(ctx.expression(0));
        emitLine("        IORLW   0");
        emitLine("        BNZ     " + trueLbl);
        emitExpression(ctx.expression(1));
        emitLine("        IORLW   0");
        emitLine("        BNZ     " + trueLbl);
        emitLine("        MOVLW   0x00");
        emitLine("        BRA     " + endLbl);
        emitLine(trueLbl + ":");
        emitLine("        MOVLW   0x01");
        emitLine(endLbl + ":");
    }

    // -------------------------------------------------------------------------
    // Function calls (incl. built-ins out / in / delay)
    // -------------------------------------------------------------------------

    private void emitCall(MiniCParser.CallExprContext ctx) {
        String fname = ctx.ID().getText();
        List<MiniCParser.ExpressionContext> args = ctx.argList() != null
                ? ctx.argList().expression()
                : List.of();

        switch (fname) {
            case "out"   -> { emitBuiltinOut(ctx, args); return; }
            case "in"    -> { emitBuiltinIn(ctx, args); return; }
            case "delay" -> { emitBuiltinDelay(ctx, args); return; }
            default -> { /* fall through */ }
        }

        FunctionSig sig = functions.get(fname);
        if (sig == null) {
            throw new CompileException(ctx, "call to unknown function '" + fname + "'");
        }
        if (args.size() != sig.paramNames.size()) {
            throw new CompileException(ctx, "call to '" + fname + "': expected "
                    + sig.paramNames.size() + " args, got " + args.size());
        }

        emitComment("call " + fname);

        // Evaluate args left-to-right, push to EVAL_STACK so subsequent arg
        // evaluations don't clobber earlier ones; then move them into ARG slots
        // just before the CALL.
        int argBase = sp;
        for (MiniCParser.ExpressionContext a : args) {
            emitExpression(a);
            pushW();
        }
        for (int i = args.size() - 1; i >= 0; i--) {
            emitLine("        MOVF    EVAL_STACK+" + (argBase + i) + ", W, A");
            emitLine("        MOVWF   ARG" + i + ", A");
        }
        // Pop the arg slots.
        for (int i = 0; i < args.size(); i++) {
            popW();
        }

        emitLine("        CALL    " + sig.label + ", 0   ; FAST=0; uses HW return stack");
        emitLine("        MOVF    RETVAL, W, A");
    }

    private void emitBuiltinOut(MiniCParser.CallExprContext ctx,
                                List<MiniCParser.ExpressionContext> args) {
        if (args.size() != 2) {
            throw new CompileException(ctx, "out(port, value) takes 2 args");
        }
        Integer portConst = evalConstantExpression(args.get(0));
        if (portConst == null) {
            throw new CompileException(ctx,
                    "out(): port argument must be a constant integer 0..4 (PORTA..PORTE)");
        }
        String latReg = portToLatRegister(portConst, ctx);
        emitComment("out(" + portConst + " /* " + latReg + " */, expr)");
        emitExpression(args.get(1));
        emitLine("        MOVWF   " + latReg + ", A   ; drive port");
    }

    private void emitBuiltinIn(MiniCParser.CallExprContext ctx,
                               List<MiniCParser.ExpressionContext> args) {
        if (args.size() != 1) {
            throw new CompileException(ctx, "in(port) takes 1 arg");
        }
        Integer portConst = evalConstantExpression(args.get(0));
        if (portConst == null) {
            throw new CompileException(ctx,
                    "in(): port argument must be a constant integer 0..4 (PORTA..PORTE)");
        }
        String portReg = portToPortRegister(portConst, ctx);
        emitComment("in(" + portConst + " /* " + portReg + " */)");
        emitLine("        MOVF    " + portReg + ", W, A   ; read port");
    }

    private void emitBuiltinDelay(MiniCParser.CallExprContext ctx,
                                  List<MiniCParser.ExpressionContext> args) {
        if (args.size() != 1) {
            throw new CompileException(ctx, "delay(n) takes 1 arg");
        }
        emitComment("delay(n)");
        emitExpression(args.get(0));
        emitLine("        MOVWF   ARG0, A");
        emitLine("        CALL    F_delay, 0");
        runtimeNeeded.add("delay");
    }

    private static String portToLatRegister(int port, ParserRuleContext ctx) {
        return switch (port) {
            case 0 -> "LATA";
            case 1 -> "LATB";
            case 2 -> "LATC";
            case 3 -> "LATD";
            case 4 -> "LATE";
            default -> throw new CompileException(ctx,
                    "out(): port " + port + " out of range; expected 0..4 (PORTA..PORTE)");
        };
    }

    private static String portToPortRegister(int port, ParserRuleContext ctx) {
        return switch (port) {
            case 0 -> "PORTA";
            case 1 -> "PORTB";
            case 2 -> "PORTC";
            case 3 -> "PORTD";
            case 4 -> "PORTE";
            default -> throw new CompileException(ctx,
                    "in(): port " + port + " out of range; expected 0..4 (PORTA..PORTE)");
        };
    }

    // -------------------------------------------------------------------------
    // Runtime helpers
    // -------------------------------------------------------------------------

    private void emitRuntimeHelpers() {
        if (runtimeNeeded.contains("delay")) {
            emitLine("");
            emitLine("; ===== runtime: delay(n) — nested decrement loop ======");
            emitLine("F_delay:");
            emitLine("        MOVF    ARG0, W, A");
            emitLine("        MOVWF   DELAY_OUTER, A");
            emitLine("        BZ      F_delay_done");
            emitLine("F_delay_outer:");
            emitLine("        MOVLW   0xFF");
            emitLine("        MOVWF   DELAY_INNER, A");
            emitLine("F_delay_inner:");
            emitLine("        DECFSZ  DELAY_INNER, F, A");
            emitLine("        BRA     F_delay_inner");
            emitLine("        DECFSZ  DELAY_OUTER, F, A");
            emitLine("        BRA     F_delay_outer");
            emitLine("F_delay_done:");
            emitLine("        RETURN");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers: expression stack tracking, labels, formatting
    // -------------------------------------------------------------------------

    private void pushW() {
        emitLine("        MOVWF   EVAL_STACK+" + sp + ", A   ; push (sp=" + sp + ")");
        sp++;
        if (sp > maxSp) {
            maxSp = sp;
        }
        if (sp >= EVAL_STACK_SIZE) {
            throw new CompileException("expression too deep (eval stack overflow)");
        }
    }

    private void popW() {
        sp--;
        if (sp < 0) {
            throw new CompileException("internal: eval stack underflow");
        }
    }

    private String newLabel(String prefix) {
        return prefix + "_" + (labelCounter++);
    }

    private static String localLabel(String fn, String var) {
        return "F_" + fn + "__LOC_" + var;
    }

    private String resolveVariable(String name, ParserRuleContext ctx) {
        if (currentLocals != null && currentLocals.containsKey(name)) {
            return currentLocals.get(name);
        }
        GlobalSym g = globals.get(name);
        if (g != null) {
            return g.label;
        }
        throw new CompileException(ctx, "undefined identifier '" + name + "'");
    }

    private static String typeName(MiniCParser.TypeContext ctx) {
        // Re-stringify type. The grammar only allows `unsigned? (int|char) | void`.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ctx.getChildCount(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(ctx.getChild(i).getText());
        }
        return sb.toString().contains("void") ? "void" : "int";
    }

    /**
     * Try to fold an expression to a compile-time constant (8-bit). Returns
     * {@code null} if the expression isn't a pure constant integer.
     */
    private Integer evalConstantExpression(MiniCParser.ExpressionContext ctx) {
        if (ctx instanceof MiniCParser.ParenExprContext p) {
            return evalConstantExpression(p.expression());
        }
        if (ctx instanceof MiniCParser.IntLitContext l) {
            return parseInt(l.INT().getText(), 10);
        }
        if (ctx instanceof MiniCParser.HexLitContext l) {
            return parseInt(l.HEX().getText().substring(2), 16);
        }
        if (ctx instanceof MiniCParser.CharLitContext l) {
            return parseChar(l.CHAR().getText(), l);
        }
        if (ctx instanceof MiniCParser.UnaryExprContext u) {
            Integer v = evalConstantExpression(u.expression());
            if (v == null) return null;
            return switch (u.op.getText()) {
                case "-" -> -v;
                case "~" -> ~v;
                case "!" -> v == 0 ? 1 : 0;
                default  -> null;
            };
        }
        if (ctx instanceof MiniCParser.AddExprContext a) {
            return binConst(a.expression(0), a.expression(1), a.op.getText());
        }
        if (ctx instanceof MiniCParser.MulExprContext m) {
            return binConst(m.expression(0), m.expression(1), m.op.getText());
        }
        if (ctx instanceof MiniCParser.ShiftExprContext s) {
            return binConst(s.expression(0), s.expression(1), s.op.getText());
        }
        if (ctx instanceof MiniCParser.BitAndExprContext b) {
            return binConst(b.expression(0), b.expression(1), "&");
        }
        if (ctx instanceof MiniCParser.BitOrExprContext b) {
            return binConst(b.expression(0), b.expression(1), "|");
        }
        if (ctx instanceof MiniCParser.BitXorExprContext b) {
            return binConst(b.expression(0), b.expression(1), "^");
        }
        return null;
    }

    private Integer binConst(MiniCParser.ExpressionContext a,
                             MiniCParser.ExpressionContext b,
                             String op) {
        Integer x = evalConstantExpression(a);
        Integer y = evalConstantExpression(b);
        if (x == null || y == null) return null;
        return switch (op) {
            case "+" -> x + y;
            case "-" -> x - y;
            case "*" -> x * y;
            case "/" -> y == 0 ? null : x / y;
            case "%" -> y == 0 ? null : x % y;
            case "<<" -> x << y;
            case ">>" -> x >> y;
            case "&" -> x & y;
            case "|" -> x | y;
            case "^" -> x ^ y;
            default  -> null;
        };
    }

    private static int parseInt(String s, int radix) {
        return Integer.parseInt(s, radix);
    }

    private static int parseChar(String literal, ParserRuleContext ctx) {
        // strip surrounding quotes; handle a couple of basic escapes.
        if (literal.length() < 3 || literal.charAt(0) != '\''
                || literal.charAt(literal.length() - 1) != '\'') {
            throw new CompileException(ctx, "malformed char literal: " + literal);
        }
        String body = literal.substring(1, literal.length() - 1);
        if (body.length() == 1) {
            return body.charAt(0) & 0xFF;
        }
        if (body.length() == 2 && body.charAt(0) == '\\') {
            return switch (body.charAt(1)) {
                case 'n'  -> '\n';
                case 'r'  -> '\r';
                case 't'  -> '\t';
                case '0'  -> 0;
                case '\\' -> '\\';
                case '\'' -> '\'';
                case '"'  -> '"';
                default -> throw new CompileException(ctx, "unknown escape: " + body);
            };
        }
        throw new CompileException(ctx, "unsupported char literal: " + literal);
    }

    private static String hex8(int v) {
        return String.format("0x%02X", v & 0xFF);
    }

    private static String pad(String label) {
        StringBuilder sb = new StringBuilder(label);
        while (sb.length() < 24) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private void emitLine(String s) {
        code.append(s).append('\n');
    }

    private void emitComment(String s) {
        code.append("        ; ").append(s).append('\n');
    }
}
