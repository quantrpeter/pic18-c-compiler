package com.example.pic18.codegen;

import com.example.pic18.grammar.CParser;
import com.example.pic18.grammar.CParser.*;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Generates MPASM-style PIC18 assembly (targeted at the PIC18F4520) from a
 * C11 parse tree produced by {@link com.example.pic18.grammar.CParser}.
 *
 * <h2>Supported C subset</h2>
 *
 * Despite consuming the full C11 grammar, this codegen only handles the same
 * teaching-quality subset the project has always supported:
 *
 * <ul>
 *   <li>Primitive integer types: {@code int}, {@code char}, optionally with
 *       {@code unsigned}/{@code signed}. All are 8-bit on this target.</li>
 *   <li>{@code void} return type / parameter list.</li>
 *   <li>Global variable declarations with optional constant initializers.</li>
 *   <li>Function definitions and prototypes (up to {@value #MAX_ARGS} byte
 *       parameters).</li>
 *   <li>{@code if}/{@code else}, {@code while}, {@code for}, {@code return},
 *       expression statements, single-identifier assignment.</li>
 *   <li>Arithmetic ({@code + - *}), constant power-of-two {@code /} and
 *       {@code %}, bitwise ({@code & | ^ ~}), constant-count shifts
 *       ({@code << >>}), comparisons, short-circuit logicals, unary
 *       {@code + - ! ~}, pre/post {@code ++}/{@code --} on bare
 *       identifiers.</li>
 *   <li>Built-ins {@code out(port, value)}, {@code in(port)},
 *       {@code delay(n)}.</li>
 * </ul>
 *
 * Anything else the grammar accepts (pointers, arrays, structs, unions,
 * enums, typedefs, switch, do-while, goto, labels, casts, sizeof, string
 * literals, floating constants, multiple declarators per declaration, etc.)
 * is rejected with a {@link CompileException} explaining the limitation.
 *
 * <h2>Calling convention &amp; expression evaluation</h2>
 *
 * Identical to the previous (MiniC-based) version of this class — see the
 * project README for the full description. Briefly: every value is one
 * byte, parameters live in {@code ARG0..ARG3}, the return value is in
 * {@code WREG} mirrored to {@code RETVAL}, and binary operators evaluate
 * the LHS into {@code WREG}, push it to the compile-time-tracked
 * {@code EVAL_STACK[sp]}, evaluate the RHS into {@code WREG}, then perform
 * the ALU op against {@code EVAL_STACK[sp-1]}.
 */
public class Pic18CodeGenerator {

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

    /** Compile a parsed translation unit and return the assembly source text. */
    public String compile(CompilationUnitContext tree) {
        TranslationUnitContext tu = tree.translationUnit();
        if (tu == null) {
            throw new CompileException("empty translation unit (no declarations or function definitions)");
        }

        // Pass 1: collect global declarations and function signatures.
        for (ExternalDeclarationContext ext : tu.externalDeclaration()) {
            registerExternalDecl(ext);
        }

        if (!functions.containsKey("main")) {
            throw new CompileException("program has no main() function");
        }

        emitPrelude();
        emitDataSections();
        emitCodeStart();

        // Pass 2: emit code for each function body. Prototype-only declarations
        // are skipped because they have no body to emit.
        for (ExternalDeclarationContext ext : tu.externalDeclaration()) {
            if (ext.functionDefinition() != null) {
                emitFunction(ext.functionDefinition());
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

    /** Bag of facts extracted from a {@code declarator} parse subtree. */
    private static final class DeclaratorInfo {
        String name;
        boolean isFunction;
        List<String> paramNames;   // null if not a function
    }

    // -------------------------------------------------------------------------
    // External declaration registration (pass 1)
    // -------------------------------------------------------------------------

    private void registerExternalDecl(ExternalDeclarationContext ext) {
        if (ext.functionDefinition() != null) {
            registerFunctionDef(ext.functionDefinition());
            return;
        }
        if (ext.declaration() != null) {
            registerTopLevelDeclaration(ext.declaration());
            return;
        }
        if (ext.asmDefinition() != null) {
            throw new CompileException(ext, "top-level inline asm not supported");
        }
        // stray ';' -> nothing to do
    }

    private void registerFunctionDef(FunctionDefinitionContext fd) {
        if (fd.declarationList() != null) {
            throw new CompileException(fd, "K&R-style declaration lists not supported");
        }
        String returnType = typeFromSpecs(fd.declarationSpecifiers(), fd);
        DeclaratorInfo di = parseDeclarator(fd.declarator());
        if (!di.isFunction) {
            throw new CompileException(fd, "function definition has non-function declarator");
        }
        boolean returnsValue = !"void".equals(returnType);

        FunctionSig existing = functions.get(di.name);
        if (existing != null) {
            if (existing.paramNames.size() != di.paramNames.size()
                    || existing.returnsValue != returnsValue) {
                throw new CompileException(fd,
                        "redeclaration of '" + di.name + "' with different signature");
            }
            if (existing.hasBody) {
                throw new CompileException(fd, "duplicate definition of function '" + di.name + "'");
            }
            existing.hasBody = true;
            return;
        }
        String label = di.name.equals("main") ? "MAIN" : ("F_" + di.name);
        FunctionSig sig = new FunctionSig(di.name, label, di.paramNames, returnsValue);
        sig.hasBody = true;
        functions.put(di.name, sig);
    }

    private void registerTopLevelDeclaration(DeclarationContext dctx) {
        if (dctx.staticAssertDeclaration() != null) {
            throw new CompileException(dctx, "_Static_assert declarations not supported");
        }
        if (dctx.attributeDeclaration() != null) {
            throw new CompileException(dctx, "attribute-only declarations not supported");
        }
        DeclarationSpecifiersContext ds = dctx.declarationSpecifiers();
        String type = typeFromSpecs(ds, dctx);

        InitDeclaratorListContext idl = dctx.initDeclaratorList();
        if (idl == null) {
            // Bare specifier-only declaration like `int;` -- meaningless; reject.
            throw new CompileException(dctx, "declaration without declarator not supported");
        }
        List<InitDeclaratorContext> ids = idl.initDeclarator();
        if (ids.size() > 1) {
            throw new CompileException(dctx,
                    "multiple declarators in one declaration not supported (write each on its own line)");
        }
        InitDeclaratorContext id = ids.get(0);
        DeclaratorInfo di = parseDeclarator(id.declarator());

        if (di.isFunction) {
            // Function prototype.
            if (id.initializer() != null) {
                throw new CompileException(dctx, "function declaration cannot have initializer");
            }
            boolean returnsValue = !"void".equals(type);
            FunctionSig existing = functions.get(di.name);
            if (existing != null) {
                if (existing.paramNames.size() != di.paramNames.size()
                        || existing.returnsValue != returnsValue) {
                    throw new CompileException(dctx,
                            "redeclaration of '" + di.name + "' with different signature");
                }
                return; // re-prototyping is fine
            }
            String label = di.name.equals("main") ? "MAIN" : ("F_" + di.name);
            FunctionSig sig = new FunctionSig(di.name, label, di.paramNames, returnsValue);
            sig.hasBody = false;
            functions.put(di.name, sig);
            return;
        }

        // Global variable declaration.
        if ("void".equals(type)) {
            throw new CompileException(dctx, "variable cannot have type 'void'");
        }
        if (globals.containsKey(di.name)) {
            throw new CompileException(dctx, "duplicate global variable '" + di.name + "'");
        }
        Integer initVal = null;
        if (id.initializer() != null) {
            InitializerContext init = id.initializer();
            if (init.assignmentExpression() == null) {
                throw new CompileException(dctx,
                        "global initializer must be a constant integer (brace initializers not supported)");
            }
            initVal = evalConstantAssign(init.assignmentExpression());
            if (initVal == null) {
                throw new CompileException(dctx,
                        "global initializer must be a constant integer expression");
            }
        }
        globals.put(di.name, new GlobalSym(di.name, "G_" + di.name, initVal));
    }

    // -------------------------------------------------------------------------
    // Declaration-specifier / declarator parsing
    // -------------------------------------------------------------------------

    /**
     * Reduce a {@code declarationSpecifiers} subtree to one of the supported
     * primitive types: {@code "int"} or {@code "void"}. Throws {@link
     * CompileException} on anything outside the supported subset (typedef,
     * static, struct, etc.).
     */
    private String typeFromSpecs(DeclarationSpecifiersContext ds, ParserRuleContext ctx) {
        if (ds == null) {
            throw new CompileException(ctx, "missing type specifier");
        }
        int hasVoid = 0, hasInt = 0, hasChar = 0;
        int hasShort = 0, hasLong = 0, hasSigned = 0, hasUnsigned = 0;

        for (DeclarationSpecifierContext d : ds.declarationSpecifier()) {
            if (d.storageClassSpecifier() != null) {
                String s = d.storageClassSpecifier().getText();
                throw new CompileException(ctx,
                        "storage class '" + s + "' not supported in this teaching compiler");
            }
            if (d.alignmentSpecifier() != null) {
                throw new CompileException(ctx, "alignment specifiers not supported");
            }
            if (d.typeQualifier() != null) {
                continue; // const / volatile / restrict / _Atomic -> silently ignore
            }
            if (d.functionSpecifier() != null) {
                continue; // inline / _Noreturn / __stdcall / attributes -> ignore
            }
            TypeSpecifierContext ts = d.typeSpecifier();
            if (ts == null) {
                continue;
            }
            if (ts.structOrUnionSpecifier() != null) {
                throw new CompileException(ctx, "struct/union types not supported");
            }
            if (ts.enumSpecifier() != null) {
                throw new CompileException(ctx, "enum types not supported");
            }
            if (ts.atomicTypeSpecifier() != null) {
                throw new CompileException(ctx, "_Atomic types not supported");
            }
            if (ts.typedefName() != null) {
                throw new CompileException(ctx,
                        "typedef-name '" + ts.typedefName().getText() + "' not supported");
            }
            if (ts.typeofSpecifier() != null) {
                throw new CompileException(ctx, "typeof not supported");
            }
            // Otherwise it's a single keyword (int, char, void, short, ...) or
            // a vector / SIMD specifier. The first child is the keyword token.
            String kw = ts.getChild(0).getText();
            switch (kw) {
                case "void"     -> hasVoid++;
                case "char"     -> hasChar++;
                case "int"      -> hasInt++;
                case "short"    -> hasShort++;
                case "long"     -> hasLong++;
                case "signed"   -> hasSigned++;
                case "unsigned" -> hasUnsigned++;
                case "float", "double" ->
                        throw new CompileException(ctx, "floating-point types not supported");
                case "bool", "_Bool" ->
                        throw new CompileException(ctx, "_Bool/bool not supported");
                case "_Complex", "_Imaginary" ->
                        throw new CompileException(ctx, "complex/imaginary types not supported");
                default ->
                        throw new CompileException(ctx, "type specifier '" + kw + "' not supported");
            }
        }

        if (hasVoid > 0) {
            if (hasInt + hasChar + hasShort + hasLong + hasSigned + hasUnsigned > 0) {
                throw new CompileException(ctx, "void cannot be combined with other type specifiers");
            }
            return "void";
        }
        if (hasShort > 0 || hasLong > 0) {
            throw new CompileException(ctx,
                    "short/long types not supported (all integers are 8-bit on PIC18 here)");
        }
        if (hasInt + hasChar + hasSigned + hasUnsigned == 0) {
            throw new CompileException(ctx, "missing or unsupported type specifier");
        }
        return "int";
    }

    /**
     * Reduce a {@code declarator} subtree to the bare identifier name and a
     * flag for whether it's a function declarator. Rejects pointers, arrays,
     * bit-fields, varargs, and unnamed parameters.
     */
    private DeclaratorInfo parseDeclarator(DeclaratorContext dctx) {
        if (!dctx.pointer().isEmpty()) {
            throw new CompileException(dctx, "pointer types not supported");
        }
        return parseDirectDeclarator(dctx.directDeclarator());
    }

    private DeclaratorInfo parseDirectDeclarator(DirectDeclaratorContext dd) {
        DeclaratorInfo info = new DeclaratorInfo();

        // Find the base: either an Identifier or '(' declarator ')' wrapping.
        if (dd.Identifier() != null) {
            info.name = dd.Identifier().getText();
        } else if (dd.declarator() != null) {
            DeclaratorInfo nested = parseDeclarator(dd.declarator());
            info.name = nested.name;
            info.isFunction = nested.isFunction;
            info.paramNames = nested.paramNames;
        } else {
            throw new CompileException(dd, "unsupported declarator form");
        }

        // Reject array suffixes (`[ ... ]`).
        for (int i = 0; i < dd.getChildCount(); i++) {
            ParseTree c = dd.getChild(i);
            if (c instanceof TerminalNode tn && "[".equals(tn.getText())) {
                throw new CompileException(dd, "array types not supported");
            }
            if (c instanceof TerminalNode tn && ":".equals(tn.getText())) {
                throw new CompileException(dd, "bit-field declarators not supported");
            }
        }

        // Detect function suffix `( parameterTypeList )`.
        if (!dd.parameterTypeList().isEmpty()) {
            if (info.isFunction) {
                throw new CompileException(dd, "function-returning-function not supported");
            }
            info.isFunction = true;
            info.paramNames = parseParamList(dd.parameterTypeList(0));
        }
        return info;
    }

    private List<String> parseParamList(ParameterTypeListContext ptl) {
        List<String> params = new ArrayList();
        // Reject varargs.
        for (int i = 0; i < ptl.getChildCount(); i++) {
            ParseTree c = ptl.getChild(i);
            if (c instanceof TerminalNode tn && "...".equals(tn.getText())) {
                throw new CompileException(ptl, "varargs not supported");
            }
        }
        if (ptl.parameterList() == null) {
            return params;
        }
        List<ParameterDeclarationContext> pdecls = ptl.parameterList().parameterDeclaration();

        // Special case: `(void)` -> empty parameter list.
        if (pdecls.size() == 1) {
            ParameterDeclarationContext only = pdecls.get(0);
            if (only.declarationSpecifiers() != null
                    && only.declarator() == null
                    && only.abstractDeclarator() == null) {
                String t = typeFromSpecs(only.declarationSpecifiers(), only);
                if ("void".equals(t)) {
                    return params;
                }
            }
        }

        for (ParameterDeclarationContext pd : pdecls) {
            if (pd.declarationSpecifiers() == null) {
                throw new CompileException(pd, "parameter missing type specifier");
            }
            String t = typeFromSpecs(pd.declarationSpecifiers(), pd);
            if ("void".equals(t)) {
                throw new CompileException(pd, "parameter cannot have type 'void'");
            }
            if (pd.declarator() == null) {
                throw new CompileException(pd, "unnamed parameters not supported");
            }
            DeclaratorInfo di = parseDeclarator(pd.declarator());
            if (di.isFunction) {
                throw new CompileException(pd, "function-typed parameters not supported");
            }
            params.add(di.name);
        }
        if (params.size() > MAX_ARGS) {
            throw new CompileException(ptl,
                    "function has " + params.size() + " parameters; max is " + MAX_ARGS);
        }
        return params;
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
        for (int i = 0; i < 8; i++) {
            emitLine(pad("R" + i) + "RES     1");
        }
        for (int i = 0; i < MAX_ARGS; i++) {
            emitLine(pad("ARG" + i) + "RES     1");
        }
        emitLine(pad("RETVAL") + "RES     1");
        emitLine(pad("DELAY_OUTER") + "RES     1");
        emitLine(pad("DELAY_INNER") + "RES     1");
        emitLine(pad("EVAL_STACK") + "RES     " + EVAL_STACK_SIZE);

        for (GlobalSym g : globals.values()) {
            emitLine(pad(g.label) + "RES     1");
        }

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
    // Function emission (pass 2)
    // -------------------------------------------------------------------------

    private void emitFunction(FunctionDefinitionContext fd) {
        DeclaratorInfo di = parseDeclarator(fd.declarator());
        currentFn = functions.get(di.name);
        currentLocals = new LinkedHashMap<>();
        declaredLocals = new LinkedHashSet<>();
        sp = 0;
        maxSp = 0;

        for (String p : currentFn.paramNames) {
            currentLocals.put(p, localLabel(currentFn.name, p));
            declaredLocals.add(p);
        }

        // Pre-collect all local variables declared anywhere in the body so we
        // can reserve their RAM up front. We use a per-function UDATA_ACS
        // section emitted just before the function label.
        Map<String, String> localsToReserve = new LinkedHashMap<>();
        collectLocalDecls(fd.functionBody().compoundStatement(), localsToReserve);

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

        // Friendlier-than-C semantics: zero out declared locals.
        for (Map.Entry<String, String> e : localsToReserve.entrySet()) {
            emitLine("        CLRF    " + e.getValue() + ", A");
        }

        emitCompound(fd.functionBody().compoundStatement());

        // Fall-through return.
        emitLine("        RETURN");
        emitLine("        ; max expr-stack depth used here: " + maxSp);
    }

    /** Walk a compound statement and gather every local-variable declaration. */
    private void collectLocalDecls(ParserRuleContext root, Map<String, String> out) {
        if (root == null) {
            return;
        }
        if (root instanceof DeclarationContext dc) {
            if (dc.initDeclaratorList() != null) {
                if (dc.initDeclaratorList().initDeclarator().size() > 1) {
                    throw new CompileException(dc,
                            "multiple declarators in one declaration not supported");
                }
                InitDeclaratorContext id = dc.initDeclaratorList().initDeclarator(0);
                DeclaratorInfo di = parseDeclarator(id.declarator());
                if (di.isFunction) {
                    throw new CompileException(dc, "nested function declarations not supported");
                }
                if (out.containsKey(di.name)) {
                    throw new CompileException(dc,
                            "duplicate local '" + di.name + "' in function '" + currentFn.name + "'");
                }
                out.put(di.name, localLabel(currentFn.name, di.name));
            }
        }
        if (root instanceof ForDeclarationContext fdc) {
            if (fdc.initDeclaratorList() != null) {
                for (InitDeclaratorContext id : fdc.initDeclaratorList().initDeclarator()) {
                    DeclaratorInfo di = parseDeclarator(id.declarator());
                    if (!out.containsKey(di.name)) {
                        out.put(di.name, localLabel(currentFn.name, di.name));
                    }
                }
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

    private void emitCompound(CompoundStatementContext cs) {
        BlockItemListContext bil = cs.blockItemList();
        if (bil == null) {
            return;
        }
        for (BlockItemContext bi : bil.blockItem()) {
            if (bi.statement() != null) {
                emitStatement(bi.statement());
            } else if (bi.declaration() != null) {
                emitLocalDeclaration(bi.declaration());
            }
        }
    }

    private void emitStatement(StatementContext s) {
        if (s.compoundStatement() != null) {
            emitCompound(s.compoundStatement());
        } else if (s.expressionStatement() != null) {
            emitExpressionStatement(s.expressionStatement());
        } else if (s.selectionStatement() != null) {
            emitSelection(s.selectionStatement());
        } else if (s.iterationStatement() != null) {
            emitIteration(s.iterationStatement());
        } else if (s.jumpStatement() != null) {
            emitJump(s.jumpStatement());
        } else if (s.labeledStatement() != null) {
            throw new CompileException(s, "labels (case/default/goto-target) not supported");
        } else if (s.asmStatement() != null) {
            throw new CompileException(s, "inline asm statements not supported");
        }
    }

    private void emitLocalDeclaration(DeclarationContext dc) {
        if (dc.staticAssertDeclaration() != null) {
            throw new CompileException(dc, "_Static_assert not supported");
        }
        if (dc.attributeDeclaration() != null) {
            throw new CompileException(dc, "attribute-only declarations not supported");
        }
        DeclarationSpecifiersContext ds = dc.declarationSpecifiers();
        String t = typeFromSpecs(ds, dc);
        if ("void".equals(t)) {
            throw new CompileException(dc, "void variable not allowed");
        }
        InitDeclaratorListContext idl = dc.initDeclaratorList();
        if (idl == null) {
            return;
        }
        InitDeclaratorContext id = idl.initDeclarator(0);
        DeclaratorInfo di = parseDeclarator(id.declarator());
        if (di.isFunction) {
            throw new CompileException(dc, "nested function declarations not supported");
        }
        String label = currentLocals.get(di.name);
        if (label == null) {
            throw new CompileException(dc,
                    "internal: local '" + di.name + "' not pre-allocated");
        }
        if (id.initializer() != null) {
            InitializerContext init = id.initializer();
            if (init.assignmentExpression() == null) {
                throw new CompileException(dc, "brace initializers not supported");
            }
            emitComment("init local " + di.name);
            emitAssignmentExpression(init.assignmentExpression());
            emitLine("        MOVWF   " + label + ", A");
        }
    }

    private void emitExpressionStatement(ExpressionStatementContext es) {
        if (es.expression() == null) {
            return; // empty `;`
        }
        emitComment("expression-statement");
        emitExpression(es.expression());
    }

    private void emitSelection(SelectionStatementContext ss) {
        // First token tells us if/switch.
        if ("switch".equals(ss.getStart().getText())) {
            throw new CompileException(ss, "switch statements not supported");
        }
        String elseLbl = newLabel("ELSE");
        String endLbl = newLabel("ENDIF");

        emitComment("if (...)");
        emitExpression(ss.expression());
        emitLine("        IORLW   0           ; set Z if W==0");
        emitLine("        BZ      " + elseLbl);

        emitStatement(ss.statement(0));
        emitLine("        BRA     " + endLbl);

        emitLine(elseLbl + ":");
        if (ss.statement().size() > 1) {
            emitStatement(ss.statement(1));
        }
        emitLine(endLbl + ":");
    }

    private void emitIteration(IterationStatementContext is) {
        if (is.Do() != null) {
            throw new CompileException(is, "do-while not supported");
        }
        if (is.For() != null) {
            emitFor(is);
            return;
        }
        if (is.While() != null) {
            String topLbl = newLabel("WHILE");
            String endLbl = newLabel("WEND");
            emitComment("while (...)");
            emitLine(topLbl + ":");
            emitExpression(is.expression());
            emitLine("        IORLW   0");
            emitLine("        BZ      " + endLbl);
            emitStatement(is.statement());
            emitLine("        BRA     " + topLbl);
            emitLine(endLbl + ":");
            return;
        }
        throw new CompileException(is, "internal: unknown iteration form");
    }

    private void emitFor(IterationStatementContext is) {
        String topLbl = newLabel("FOR");
        String endLbl = newLabel("FEND");
        emitComment("for (...)");
        ForConditionContext fc = is.forCondition();

        // -- Init clause ----------------------------------------------------
        if (fc.forDeclaration() != null) {
            ForDeclarationContext fd = fc.forDeclaration();
            String t = typeFromSpecs(fd.declarationSpecifiers(), fd);
            if ("void".equals(t)) {
                throw new CompileException(fd, "void variable in for-init");
            }
            if (fd.initDeclaratorList() != null) {
                for (InitDeclaratorContext id : fd.initDeclaratorList().initDeclarator()) {
                    DeclaratorInfo di = parseDeclarator(id.declarator());
                    String label = currentLocals.get(di.name);
                    if (label == null) {
                        throw new CompileException(fd,
                                "internal: for-init local '" + di.name + "' missing");
                    }
                    if (id.initializer() != null) {
                        InitializerContext init = id.initializer();
                        if (init.assignmentExpression() == null) {
                            throw new CompileException(fd, "brace initializers not supported");
                        }
                        emitAssignmentExpression(init.assignmentExpression());
                        emitLine("        MOVWF   " + label + ", A");
                    }
                }
            }
        } else if (fc.expression() != null) {
            emitExpression(fc.expression());
        }

        // -- Determine which forExpression slot is condition vs step --------
        // The grammar is roughly:  init? ';' cond? ';' step?
        // ANTLR doesn't tell us which optional was present, so walk children
        // and remember the index of each forExpression relative to the
        // surrounding semicolons.
        int condIdx = -1;
        int stepIdx = -1;
        int feSeen = 0;
        int semis = 0;
        for (int i = 0; i < fc.getChildCount(); i++) {
            ParseTree c = fc.getChild(i);
            if (c instanceof TerminalNode tn && ";".equals(tn.getText())) {
                semis++;
            } else if (c instanceof ForExpressionContext) {
                if (semis == 1) {
                    condIdx = feSeen;
                } else if (semis == 2) {
                    stepIdx = feSeen;
                }
                feSeen++;
            }
        }

        emitLine(topLbl + ":");
        List<ForExpressionContext> fexprs = fc.forExpression();
        if (condIdx >= 0) {
            emitForExpression(fexprs.get(condIdx));
            emitLine("        IORLW   0");
            emitLine("        BZ      " + endLbl);
        }
        emitStatement(is.statement());
        if (stepIdx >= 0) {
            emitForExpression(fexprs.get(stepIdx));
        }
        emitLine("        BRA     " + topLbl);
        emitLine(endLbl + ":");
    }

    private void emitJump(JumpStatementContext js) {
        String first = js.getStart().getText();
        switch (first) {
            case "goto"     -> throw new CompileException(js, "goto not supported");
            case "continue" -> throw new CompileException(js, "continue not supported");
            case "break"    -> throw new CompileException(js, "break not supported");
            case "return" -> {
                if (js.expression() != null) {
                    emitExpression(js.expression());
                    emitLine("        MOVWF   RETVAL, A");
                }
                emitLine("        RETURN");
            }
            default -> throw new CompileException(js, "internal: unknown jump '" + first + "'");
        }
    }

    // -------------------------------------------------------------------------
    // Expressions
    // -------------------------------------------------------------------------

    /** Emit each comma-separated assignment-expression in turn; result of last in W. */
    private void emitExpression(ExpressionContext ctx) {
        for (AssignmentExpressionContext ae : ctx.assignmentExpression()) {
            emitAssignmentExpression(ae);
        }
    }

    private void emitForExpression(ForExpressionContext ctx) {
        for (AssignmentExpressionContext ae : ctx.assignmentExpression()) {
            emitAssignmentExpression(ae);
        }
    }

    private void emitAssignmentExpression(AssignmentExpressionContext ctx) {
        if (ctx.DigitSequence() != null) {
            // The grammar permits a bare DigitSequence here as a `for` corner-case.
            emitLoadConst(parseInt(ctx.DigitSequence().getText(), 10), ctx);
            return;
        }
        if (ctx.assignementOperator != null) {
            String op = ctx.assignementOperator.getText();
            if (!"=".equals(op)) {
                throw new CompileException(ctx, "compound assignment '" + op + "' not supported");
            }
            String name = extractBareIdentifier(ctx.unaryExpression());
            if (name == null) {
                throw new CompileException(ctx,
                        "left side of '=' must be a bare identifier in this teaching compiler");
            }
            String label = resolveVariable(name, ctx);
            emitComment("assign " + name);
            emitAssignmentExpression(ctx.assignmentExpression());
            emitLine("        MOVWF   " + label + ", A");
            return;
        }
        emitConditional(ctx.conditionalExpression());
    }

    private void emitConditional(ConditionalExpressionContext ctx) {
        if (ctx.expression() != null) {
            throw new CompileException(ctx, "ternary '?:' not supported");
        }
        emitLogicalOr(ctx.logicalOrExpression());
    }

    private void emitLogicalOr(LogicalOrExpressionContext ctx) {
        List<LogicalAndExpressionContext> opers = ctx.logicalAndExpression();
        if (opers.size() == 1) {
            emitLogicalAnd(opers.get(0));
            return;
        }
        String trueLbl = newLabel("LOR_T");
        String endLbl = newLabel("LOR_E");
        for (LogicalAndExpressionContext op : opers) {
            emitLogicalAnd(op);
            emitLine("        IORLW   0");
            emitLine("        BNZ     " + trueLbl);
        }
        emitLine("        MOVLW   0x00");
        emitLine("        BRA     " + endLbl);
        emitLine(trueLbl + ":");
        emitLine("        MOVLW   0x01");
        emitLine(endLbl + ":");
    }

    private void emitLogicalAnd(LogicalAndExpressionContext ctx) {
        List<InclusiveOrExpressionContext> opers = ctx.inclusiveOrExpression();
        if (opers.size() == 1) {
            emitInclusiveOr(opers.get(0));
            return;
        }
        String falseLbl = newLabel("LAND_F");
        String endLbl = newLabel("LAND_E");
        for (InclusiveOrExpressionContext op : opers) {
            emitInclusiveOr(op);
            emitLine("        IORLW   0");
            emitLine("        BZ      " + falseLbl);
        }
        emitLine("        MOVLW   0x01");
        emitLine("        BRA     " + endLbl);
        emitLine(falseLbl + ":");
        emitLine("        MOVLW   0x00");
        emitLine(endLbl + ":");
    }

    private void emitInclusiveOr(InclusiveOrExpressionContext ctx) {
        emitChain(ctx.exclusiveOrExpression(), extractOps(ctx, "|"), this::emitExclusiveOr, ctx);
    }

    private void emitExclusiveOr(ExclusiveOrExpressionContext ctx) {
        emitChain(ctx.andExpression(), extractOps(ctx, "^"), this::emitAnd, ctx);
    }

    private void emitAnd(AndExpressionContext ctx) {
        emitChain(ctx.equalityExpression(), extractOps(ctx, "&"), this::emitEquality, ctx);
    }

    private void emitEquality(EqualityExpressionContext ctx) {
        emitChain(ctx.relationalExpression(), extractOps(ctx, "==", "!="), this::emitRelational, ctx);
    }

    private void emitRelational(RelationalExpressionContext ctx) {
        emitChain(ctx.shiftExpression(), extractOps(ctx, "<", ">", "<=", ">="), this::emitShift, ctx);
    }

    private void emitShift(ShiftExpressionContext ctx) {
        List<AdditiveExpressionContext> opers = ctx.additiveExpression();
        if (opers.size() == 1) {
            emitAdditive(opers.get(0));
            return;
        }
        List<String> ops = extractOps(ctx, "<<", ">>");
        emitAdditive(opers.get(0));
        for (int i = 1; i < opers.size(); i++) {
            Integer rhsConst = evalConstantAdditive(opers.get(i));
            if (rhsConst == null) {
                throw new CompileException(ctx,
                        "shift count must be a constant integer (variable shifts not yet supported)");
            }
            int n = rhsConst & 0xFF;
            if (n == 0) {
                continue;
            }
            String op = ops.get(i - 1);
            emitLine("        MOVWF   R0, A");
            for (int k = 0; k < n; k++) {
                emitLine("        BCF     STATUS, C, A");
                if (op.equals("<<")) {
                    emitLine("        RLCF    R0, F, A");
                } else {
                    emitLine("        RRCF    R0, F, A");
                }
            }
            emitLine("        MOVF    R0, W, A");
        }
    }

    private void emitAdditive(AdditiveExpressionContext ctx) {
        emitChain(ctx.multiplicativeExpression(), extractOps(ctx, "+", "-"),
                this::emitMultiplicative, ctx);
    }

    private void emitMultiplicative(MultiplicativeExpressionContext ctx) {
        List<CastExpressionContext> opers = ctx.castExpression();
        if (opers.size() == 1) {
            emitCast(opers.get(0));
            return;
        }
        List<String> ops = extractOps(ctx, "*", "/", "%");
        emitCast(opers.get(0));
        for (int i = 1; i < opers.size(); i++) {
            String op = ops.get(i - 1);
            pushW();
            int slot = sp - 1;
            String lhsRef = "EVAL_STACK+" + slot;
            if (op.equals("*")) {
                emitCast(opers.get(i));
                emitLine("        MULWF   " + lhsRef + ", A      ; PRODH:PRODL = lhs * rhs");
                emitLine("        MOVF    PRODL, W, A          ; keep low byte");
            } else {
                Integer rhsConst = evalConstantCast(opers.get(i));
                if (rhsConst == null) {
                    throw new CompileException(ctx,
                            op + " requires a constant power-of-two right operand "
                                    + "(division runtime not yet implemented)");
                }
                emitConstantDivLowering(rhsConst, lhsRef, op.equals("%"), ctx);
            }
            popW();
        }
    }

    private void emitConstantDivLowering(int rv, String lhsRef, boolean isMod,
                                         ParserRuleContext ctx) {
        rv = rv & 0xFF;
        if (rv == 0) {
            throw new CompileException(ctx, "division by zero");
        }
        if ((rv & (rv - 1)) != 0) {
            throw new CompileException(ctx,
                    (isMod ? "%" : "/") + " by " + rv + " not supported (only powers of two for now)");
        }
        int log = Integer.numberOfTrailingZeros(rv);
        emitLine("        MOVF    " + lhsRef + ", W, A      ; reload lhs");
        if (isMod) {
            int mask = rv - 1;
            emitLine("        ANDLW   " + hex8(mask) + "       ; W = lhs % " + rv);
        } else {
            emitLine("        MOVWF   R0, A");
            for (int k = 0; k < log; k++) {
                emitLine("        BCF     STATUS, C, A   ; clear carry");
                emitLine("        RRCF    R0, F, A");
            }
            emitLine("        MOVF    R0, W, A         ; W = lhs / " + rv);
        }
    }

    private void emitCast(CastExpressionContext ctx) {
        if (ctx.typeName() != null) {
            throw new CompileException(ctx, "casts not supported");
        }
        if (ctx.DigitSequence() != null) {
            emitLoadConst(parseInt(ctx.DigitSequence().getText(), 10), ctx);
            return;
        }
        emitUnary(ctx.unaryExpression());
    }

    private void emitUnary(UnaryExpressionContext ctx) {
        if (ctx.postfixExpression() != null) {
            emitPostfix(ctx.postfixExpression());
            return;
        }
        // Pre-increment / pre-decrement.
        String first = ctx.getStart().getText();
        if (("++".equals(first) || "--".equals(first)) && ctx.unaryExpression() != null) {
            String name = extractBareIdentifier(ctx.unaryExpression());
            if (name == null) {
                throw new CompileException(ctx,
                        "pre-" + ("++".equals(first) ? "increment" : "decrement")
                                + " operand must be a bare identifier");
            }
            emitIncDec(name, first, /*postfix=*/false, ctx);
            return;
        }
        // Unary operator: '&', '*', '+', '-', '~', '!', '__extension__', etc.
        if (ctx.unaryOperator != null && ctx.castExpression() != null) {
            String op = ctx.unaryOperator.getText();
            switch (op) {
                case "+" -> emitCast(ctx.castExpression());
                case "-" -> {
                    emitCast(ctx.castExpression());
                    emitLine("        MOVWF   R0, A         ; unary minus: stash W");
                    emitLine("        NEGF    R0, A");
                    emitLine("        MOVF    R0, W, A");
                }
                case "!" -> {
                    emitCast(ctx.castExpression());
                    String zeroLbl = newLabel("LNOT_Z");
                    String endLbl = newLabel("LNOT_E");
                    emitLine("        IORLW   0             ; logical not: set Z if W==0");
                    emitLine("        BZ      " + zeroLbl);
                    emitLine("        MOVLW   0x00          ; W was non-zero -> result 0");
                    emitLine("        BRA     " + endLbl);
                    emitLine(zeroLbl + ":");
                    emitLine("        MOVLW   0x01          ; W was zero -> result 1");
                    emitLine(endLbl + ":");
                }
                case "~" -> {
                    emitCast(ctx.castExpression());
                    emitLine("        XORLW   0xFF          ; bitwise not");
                }
                case "&" -> throw new CompileException(ctx, "address-of '&' not supported");
                case "*" -> throw new CompileException(ctx, "pointer dereference '*' not supported");
                default -> throw new CompileException(ctx, "unsupported unary operator '" + op + "'");
            }
            return;
        }
        // sizeof / Alignof / Countof / && Identifier
        throw new CompileException(ctx,
                "sizeof / alignof / countof / address-of-label not supported");
    }

    private void emitPostfix(PostfixExpressionContext ctx) {
        PrimaryExpressionContext pe = ctx.primaryExpression();
        if (pe == null) {
            throw new CompileException(ctx, "compound literals not supported");
        }
        // Collect children after the primary; those are the suffixes.
        if (ctx.getChildCount() == 1) {
            emitPrimary(pe);
            return;
        }
        // We support exactly one suffix, and it's either a function call or
        // a postfix '++' / '--'.
        ParseTree suffix = ctx.getChild(1);

        if (suffix instanceof TerminalNode tn) {
            String t = tn.getText();
            if ("++".equals(t) || "--".equals(t)) {
                if (ctx.getChildCount() != 2) {
                    throw new CompileException(ctx, "chained postfix expressions not supported");
                }
                String name = extractIdFromPrimary(pe);
                if (name == null) {
                    throw new CompileException(ctx,
                            "post-" + ("++".equals(t) ? "increment" : "decrement")
                                    + " operand must be a bare identifier");
                }
                emitIncDec(name, t, /*postfix=*/true, ctx);
                return;
            }
            if ("(".equals(t)) {
                // primary '(' argList? ')' (no further suffixes)
                ArgumentExpressionListContext args = ctx.argumentExpressionList().isEmpty()
                        ? null : ctx.argumentExpressionList(0);
                int closingIdx = (args != null) ? 3 : 2;
                if (ctx.getChildCount() != closingIdx + 1) {
                    throw new CompileException(ctx,
                            "chained postfix expressions (e.g. f()()) not supported");
                }
                String name = extractIdFromPrimary(pe);
                if (name == null) {
                    throw new CompileException(ctx, "indirect function calls not supported");
                }
                emitCall(name, args, ctx);
                return;
            }
            if ("[".equals(t)) {
                throw new CompileException(ctx, "array indexing not supported");
            }
            if (".".equals(t) || "->".equals(t)) {
                throw new CompileException(ctx, "struct/union member access not supported");
            }
        }
        throw new CompileException(ctx, "unsupported postfix expression");
    }

    private void emitPrimary(PrimaryExpressionContext ctx) {
        if (ctx.Identifier() != null) {
            emitLoadVar(ctx.Identifier().getText(), ctx);
            return;
        }
        if (ctx.constant() != null) {
            emitConstant(ctx.constant());
            return;
        }
        if (ctx.expression() != null) {
            emitExpression(ctx.expression());
            return;
        }
        if (!ctx.StringLiteral().isEmpty()) {
            throw new CompileException(ctx, "string literals not supported");
        }
        if (ctx.genericSelection() != null) {
            throw new CompileException(ctx, "_Generic not supported");
        }
        String text = ctx.getText();
        if (text.startsWith("__func__")
                || text.startsWith("__FUNCTION__")
                || text.startsWith("__PRETTY_FUNCTION__")) {
            throw new CompileException(ctx, "function-name predefined identifier not supported");
        }
        if (text.startsWith("__builtin_")) {
            throw new CompileException(ctx, "GCC __builtin_* primitives not supported");
        }
        throw new CompileException(ctx, "unsupported primary expression: " + text);
    }

    private void emitConstant(ConstantContext ctx) {
        if (ctx.IntegerConstant() != null) {
            emitLoadConst(parseIntegerLiteral(ctx.IntegerConstant().getText(), ctx), ctx);
            return;
        }
        if (ctx.CharacterConstant() != null) {
            emitLoadConst(parseCharLiteral(ctx.CharacterConstant().getText(), ctx), ctx);
            return;
        }
        if (ctx.FloatingConstant() != null) {
            throw new CompileException(ctx, "floating-point constants not supported");
        }
        if (ctx.predefinedConstant() != null) {
            String t = ctx.predefinedConstant().getText();
            if ("true".equals(t)) {
                emitLoadConst(1, ctx);
                return;
            }
            if ("false".equals(t)) {
                emitLoadConst(0, ctx);
                return;
            }
            throw new CompileException(ctx, "predefined constant '" + t + "' not supported");
        }
        throw new CompileException(ctx, "unknown constant kind");
    }

    private void emitLoadConst(int value, ParserRuleContext ctx) {
        int byteVal = value & 0xFF;
        if ((value & ~0xFF) != 0 && (value & ~0xFF) != ~0xFF) {
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
     * See the original codegen comment for the semantic rationale.
     */
    private void emitIncDec(String name, String op, boolean postfix, ParserRuleContext ctx) {
        String label = resolveVariable(name, ctx);
        boolean inc = op.equals("++");
        String mnem = inc ? "INCF" : "DECF";
        String human = (postfix ? "post" : "pre") + (inc ? "-inc " : "-dec ") + name;
        emitComment(human);
        if (postfix) {
            emitLine("        MOVF    " + label + ", W, A      ; W = old " + name);
            emitLine("        " + mnem + "    " + label + ", F, A      ; " + name
                    + (inc ? "++" : "--") + " (W keeps old value)");
        } else {
            emitLine("        " + mnem + "    " + label + ", F, A      ; "
                    + (inc ? "++" : "--") + name);
            emitLine("        MOVF    " + label + ", W, A      ; W = new " + name);
        }
    }

    /** Generic chain compiler for left-associative binary expressions. */
    private <T extends ParserRuleContext> void emitChain(List<T> opers, List<String> ops,
                                                         java.util.function.Consumer<T> emit,
                                                         ParserRuleContext ctx) {
        if (opers.size() == 1) {
            emit.accept(opers.get(0));
            return;
        }
        emit.accept(opers.get(0));
        for (int i = 1; i < opers.size(); i++) {
            pushW();
            emit.accept(opers.get(i));
            emitBinaryOp(ops.get(i - 1), ctx);
            popW();
        }
    }

    /**
     * Emit assembly for a binary operator with LHS at {@code EVAL_STACK[sp-1]}
     * and RHS already in {@code WREG}.
     */
    private void emitBinaryOp(String op, ParserRuleContext ctx) {
        int slot = sp - 1;
        String lhsRef = "EVAL_STACK+" + slot;
        switch (op) {
            case "+" ->
                emitLine("        ADDWF   " + lhsRef + ", W, A   ; W = lhs + rhs");
            case "-" ->
                emitLine("        SUBWF   " + lhsRef + ", W, A   ; W = lhs - rhs");
            case "&" ->
                emitLine("        ANDWF   " + lhsRef + ", W, A   ; W = lhs & rhs");
            case "|" ->
                emitLine("        IORWF   " + lhsRef + ", W, A   ; W = lhs | rhs");
            case "^" ->
                emitLine("        XORWF   " + lhsRef + ", W, A   ; W = lhs ^ rhs");
            case "==", "!=", "<", "<=", ">", ">=" -> emitCompare(lhsRef, op);
            default -> throw new CompileException(ctx, "unsupported binary op '" + op + "'");
        }
    }

    /**
     * Emit a comparison whose LHS is at {@code lhsRef} and RHS is already in
     * {@code WREG}. Result is left in {@code WREG} as 0/1. See the original
     * MiniC version of this method for the full mnemonic-mapping rationale.
     */
    private void emitCompare(String lhsRef, String op) {
        String mnem;
        boolean invertResult = false;
        switch (op) {
            case "==" -> mnem = "CPFSEQ";
            case "!=" -> { mnem = "CPFSEQ"; invertResult = true; }
            case ">"  -> mnem = "CPFSLT";
            case "<=" -> { mnem = "CPFSLT"; invertResult = true; }
            case "<"  -> mnem = "CPFSGT";
            case ">=" -> { mnem = "CPFSGT"; invertResult = true; }
            default -> throw new CompileException("internal: bad compare op " + op);
        }
        String falseLbl = newLabel("CMP_F");
        String endLbl   = newLabel("CMP_E");
        String onSkip   = invertResult ? "0x00" : "0x01";
        String onFall   = invertResult ? "0x01" : "0x00";

        emitLine("        " + mnem + "  " + lhsRef + ", A   ; skip next if " + op + " true");
        emitLine("        BRA     " + falseLbl);
        emitLine("        MOVLW   " + onSkip);
        emitLine("        BRA     " + endLbl);
        emitLine(falseLbl + ":");
        emitLine("        MOVLW   " + onFall);
        emitLine(endLbl + ":");
    }

    // -------------------------------------------------------------------------
    // Function calls (incl. built-ins out / in / delay)
    // -------------------------------------------------------------------------

    private void emitCall(String fname, ArgumentExpressionListContext args, ParserRuleContext ctx) {
        List<AssignmentExpressionContext> argList = (args == null)
                ? List.of()
                : args.assignmentExpression();

        switch (fname) {
            case "out"   -> { emitBuiltinOut(ctx, argList); return; }
            case "in"    -> { emitBuiltinIn(ctx, argList); return; }
            case "delay" -> { emitBuiltinDelay(ctx, argList); return; }
            default -> { /* fall through */ }
        }

        FunctionSig sig = functions.get(fname);
        if (sig == null) {
            throw new CompileException(ctx, "call to unknown function '" + fname + "'");
        }
        if (argList.size() != sig.paramNames.size()) {
            throw new CompileException(ctx, "call to '" + fname + "': expected "
                    + sig.paramNames.size() + " args, got " + argList.size());
        }

        emitComment("call " + fname);
        int argBase = sp;
        for (AssignmentExpressionContext a : argList) {
            emitAssignmentExpression(a);
            pushW();
        }
        for (int i = argList.size() - 1; i >= 0; i--) {
            emitLine("        MOVF    EVAL_STACK+" + (argBase + i) + ", W, A");
            emitLine("        MOVWF   ARG" + i + ", A");
        }
        for (int i = 0; i < argList.size(); i++) {
            popW();
        }
        emitLine("        CALL    " + sig.label + ", 0   ; FAST=0; uses HW return stack");
        emitLine("        MOVF    RETVAL, W, A");
    }

    private void emitBuiltinOut(ParserRuleContext ctx, List<AssignmentExpressionContext> args) {
        if (args.size() != 2) {
            throw new CompileException(ctx, "out(port, value) takes 2 args");
        }
        Integer portConst = evalConstantAssign(args.get(0));
        if (portConst == null) {
            throw new CompileException(ctx,
                    "out(): port argument must be a constant integer 0..4 (PORTA..PORTE)");
        }
        String latReg = portToLatRegister(portConst, ctx);
        emitComment("out(" + portConst + " /* " + latReg + " */, expr)");
        emitAssignmentExpression(args.get(1));
        emitLine("        MOVWF   " + latReg + ", A   ; drive port");
    }

    private void emitBuiltinIn(ParserRuleContext ctx, List<AssignmentExpressionContext> args) {
        if (args.size() != 1) {
            throw new CompileException(ctx, "in(port) takes 1 arg");
        }
        Integer portConst = evalConstantAssign(args.get(0));
        if (portConst == null) {
            throw new CompileException(ctx,
                    "in(): port argument must be a constant integer 0..4 (PORTA..PORTE)");
        }
        String portReg = portToPortRegister(portConst, ctx);
        emitComment("in(" + portConst + " /* " + portReg + " */)");
        emitLine("        MOVF    " + portReg + ", W, A   ; read port");
    }

    private void emitBuiltinDelay(ParserRuleContext ctx, List<AssignmentExpressionContext> args) {
        if (args.size() != 1) {
            throw new CompileException(ctx, "delay(n) takes 1 arg");
        }
        emitComment("delay(n)");
        emitAssignmentExpression(args.get(0));
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
    // Constant folding (used for global initializers, shift counts, /, %, ports)
    // -------------------------------------------------------------------------

    private Integer evalConstantAssign(AssignmentExpressionContext ctx) {
        if (ctx.assignementOperator != null) {
            return null; // assignments aren't constant
        }
        if (ctx.DigitSequence() != null) {
            return parseInt(ctx.DigitSequence().getText(), 10);
        }
        return evalConstantConditional(ctx.conditionalExpression());
    }

    private Integer evalConstantConditional(ConditionalExpressionContext ctx) {
        if (ctx.expression() != null) {
            return null; // ternary not folded
        }
        return evalConstantLOr(ctx.logicalOrExpression());
    }

    private Integer evalConstantLOr(LogicalOrExpressionContext ctx) {
        if (ctx.logicalAndExpression().size() > 1) {
            return null;
        }
        return evalConstantLAnd(ctx.logicalAndExpression(0));
    }

    private Integer evalConstantLAnd(LogicalAndExpressionContext ctx) {
        if (ctx.inclusiveOrExpression().size() > 1) {
            return null;
        }
        return evalConstantInclusiveOr(ctx.inclusiveOrExpression(0));
    }

    private Integer evalConstantInclusiveOr(InclusiveOrExpressionContext ctx) {
        return foldChain(ctx.exclusiveOrExpression(), extractOps(ctx, "|"),
                this::evalConstantExclusiveOr);
    }

    private Integer evalConstantExclusiveOr(ExclusiveOrExpressionContext ctx) {
        return foldChain(ctx.andExpression(), extractOps(ctx, "^"),
                this::evalConstantAnd);
    }

    private Integer evalConstantAnd(AndExpressionContext ctx) {
        return foldChain(ctx.equalityExpression(), extractOps(ctx, "&"),
                this::evalConstantEquality);
    }

    private Integer evalConstantEquality(EqualityExpressionContext ctx) {
        if (ctx.relationalExpression().size() > 1) {
            return null;
        }
        return evalConstantRelational(ctx.relationalExpression(0));
    }

    private Integer evalConstantRelational(RelationalExpressionContext ctx) {
        if (ctx.shiftExpression().size() > 1) {
            return null;
        }
        return evalConstantShift(ctx.shiftExpression(0));
    }

    private Integer evalConstantShift(ShiftExpressionContext ctx) {
        return foldChain(ctx.additiveExpression(), extractOps(ctx, "<<", ">>"),
                this::evalConstantAdditive);
    }

    private Integer evalConstantAdditive(AdditiveExpressionContext ctx) {
        return foldChain(ctx.multiplicativeExpression(), extractOps(ctx, "+", "-"),
                this::evalConstantMultiplicative);
    }

    private Integer evalConstantMultiplicative(MultiplicativeExpressionContext ctx) {
        return foldChain(ctx.castExpression(), extractOps(ctx, "*", "/", "%"),
                this::evalConstantCast);
    }

    private Integer evalConstantCast(CastExpressionContext ctx) {
        if (ctx.typeName() != null) {
            return null;
        }
        if (ctx.DigitSequence() != null) {
            return parseInt(ctx.DigitSequence().getText(), 10);
        }
        return evalConstantUnary(ctx.unaryExpression());
    }

    private Integer evalConstantUnary(UnaryExpressionContext ctx) {
        if (ctx.postfixExpression() != null) {
            return evalConstantPostfix(ctx.postfixExpression());
        }
        if (ctx.unaryOperator != null && ctx.castExpression() != null) {
            Integer v = evalConstantCast(ctx.castExpression());
            if (v == null) return null;
            return switch (ctx.unaryOperator.getText()) {
                case "+" -> v;
                case "-" -> -v;
                case "~" -> ~v;
                case "!" -> v == 0 ? 1 : 0;
                default  -> null;
            };
        }
        return null;
    }

    private Integer evalConstantPostfix(PostfixExpressionContext ctx) {
        if (ctx.getChildCount() != 1) {
            return null;
        }
        if (ctx.primaryExpression() == null) {
            return null;
        }
        return evalConstantPrimary(ctx.primaryExpression());
    }

    private Integer evalConstantPrimary(PrimaryExpressionContext ctx) {
        if (ctx.constant() != null) {
            return evalConstantConst(ctx.constant());
        }
        if (ctx.expression() != null) {
            ExpressionContext e = ctx.expression();
            if (e.assignmentExpression().size() != 1) {
                return null;
            }
            return evalConstantAssign(e.assignmentExpression(0));
        }
        return null;
    }

    private Integer evalConstantConst(ConstantContext ctx) {
        if (ctx.IntegerConstant() != null) {
            return parseIntegerLiteral(ctx.IntegerConstant().getText(), ctx);
        }
        if (ctx.CharacterConstant() != null) {
            return parseCharLiteral(ctx.CharacterConstant().getText(), ctx);
        }
        if (ctx.predefinedConstant() != null) {
            String t = ctx.predefinedConstant().getText();
            if ("true".equals(t)) return 1;
            if ("false".equals(t)) return 0;
        }
        return null;
    }

    private <T extends ParserRuleContext> Integer foldChain(List<T> opers, List<String> ops,
                                                            Function<T, Integer> fold) {
        Integer acc = fold.apply(opers.get(0));
        if (acc == null) {
            return null;
        }
        for (int i = 1; i < opers.size(); i++) {
            Integer rhs = fold.apply(opers.get(i));
            if (rhs == null) {
                return null;
            }
            switch (ops.get(i - 1)) {
                case "+"  -> acc = acc + rhs;
                case "-"  -> acc = acc - rhs;
                case "*"  -> acc = acc * rhs;
                case "/"  -> { if (rhs == 0) return null; acc = acc / rhs; }
                case "%"  -> { if (rhs == 0) return null; acc = acc % rhs; }
                case "<<" -> acc = acc << rhs;
                case ">>" -> acc = acc >> rhs;
                case "&"  -> acc = acc & rhs;
                case "|"  -> acc = acc | rhs;
                case "^"  -> acc = acc ^ rhs;
                default -> { return null; }
            }
        }
        return acc;
    }

    // -------------------------------------------------------------------------
    // Helpers: identifier extraction, expression-stack tracking, formatting
    // -------------------------------------------------------------------------

    /**
     * Return the bare identifier name if {@code u} is just a single
     * identifier with no operators or suffixes (the only kind of lvalue this
     * compiler accepts). Returns {@code null} otherwise.
     */
    private String extractBareIdentifier(UnaryExpressionContext u) {
        if (u == null || u.postfixExpression() == null) {
            return null;
        }
        return extractIdFromPostfix(u.postfixExpression());
    }

    private String extractIdFromPostfix(PostfixExpressionContext pe) {
        if (pe.primaryExpression() == null || pe.getChildCount() != 1) {
            return null;
        }
        return extractIdFromPrimary(pe.primaryExpression());
    }

    private String extractIdFromPrimary(PrimaryExpressionContext pr) {
        if (pr.Identifier() != null && pr.getChildCount() == 1) {
            return pr.Identifier().getText();
        }
        // Allow `(x)` to count as a bare identifier.
        if (pr.expression() != null) {
            ExpressionContext e = pr.expression();
            if (e.assignmentExpression().size() == 1) {
                AssignmentExpressionContext ae = e.assignmentExpression(0);
                if (ae.assignementOperator == null && ae.conditionalExpression() != null) {
                    ConditionalExpressionContext ce = ae.conditionalExpression();
                    if (ce.expression() == null) {
                        // Walk down the chain looking for a single bare identifier.
                        return idFromLOr(ce.logicalOrExpression());
                    }
                }
            }
        }
        return null;
    }

    private String idFromLOr(LogicalOrExpressionContext c) {
        if (c.logicalAndExpression().size() != 1) return null;
        LogicalAndExpressionContext la = c.logicalAndExpression(0);
        if (la.inclusiveOrExpression().size() != 1) return null;
        InclusiveOrExpressionContext io = la.inclusiveOrExpression(0);
        if (io.exclusiveOrExpression().size() != 1) return null;
        ExclusiveOrExpressionContext xo = io.exclusiveOrExpression(0);
        if (xo.andExpression().size() != 1) return null;
        AndExpressionContext an = xo.andExpression(0);
        if (an.equalityExpression().size() != 1) return null;
        EqualityExpressionContext eq = an.equalityExpression(0);
        if (eq.relationalExpression().size() != 1) return null;
        RelationalExpressionContext rl = eq.relationalExpression(0);
        if (rl.shiftExpression().size() != 1) return null;
        ShiftExpressionContext sh = rl.shiftExpression(0);
        if (sh.additiveExpression().size() != 1) return null;
        AdditiveExpressionContext ad = sh.additiveExpression(0);
        if (ad.multiplicativeExpression().size() != 1) return null;
        MultiplicativeExpressionContext mu = ad.multiplicativeExpression(0);
        if (mu.castExpression().size() != 1) return null;
        CastExpressionContext ca = mu.castExpression(0);
        if (ca.typeName() != null || ca.DigitSequence() != null) return null;
        return extractBareIdentifier(ca.unaryExpression());
    }

    private List<String> extractOps(ParserRuleContext ctx, String... allowed) {
        Set<String> ok = new LinkedHashSet<>(Arrays.asList(allowed));
        List<String> result = new ArrayList();
        for (int i = 0; i < ctx.getChildCount(); i++) {
            ParseTree c = ctx.getChild(i);
            if (c instanceof TerminalNode tn && ok.contains(tn.getText())) {
                result.add(tn.getText());
            }
        }
        return result;
    }

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

    private static int parseInt(String s, int radix) {
        return Integer.parseInt(s, radix);
    }

    /** Parse a C integer literal (supports decimal, hex, octal, binary, with U/L suffixes). */
    private static int parseIntegerLiteral(String raw, ParserRuleContext ctx) {
        String s = raw;
        // Strip integer suffixes (u, U, l, L, ll, LL).
        while (!s.isEmpty()) {
            char c = s.charAt(s.length() - 1);
            if (c == 'u' || c == 'U' || c == 'l' || c == 'L') {
                s = s.substring(0, s.length() - 1);
            } else {
                break;
            }
        }
        if (s.isEmpty()) {
            throw new CompileException(ctx, "malformed integer constant: " + raw);
        }
        try {
            if (s.length() > 1 && s.charAt(0) == '0'
                    && (s.charAt(1) == 'x' || s.charAt(1) == 'X')) {
                return Integer.parseInt(s.substring(2), 16);
            }
            if (s.length() > 1 && s.charAt(0) == '0'
                    && (s.charAt(1) == 'b' || s.charAt(1) == 'B')) {
                return Integer.parseInt(s.substring(2), 2);
            }
            if (s.length() > 1 && s.charAt(0) == '0') {
                return Integer.parseInt(s.substring(1), 8);
            }
            return Integer.parseInt(s, 10);
        } catch (NumberFormatException e) {
            throw new CompileException(ctx, "malformed integer constant: " + raw);
        }
    }

    private static int parseCharLiteral(String literal, ParserRuleContext ctx) {
        String s = literal;
        // Strip optional encoding prefix (L', u', U').
        if (s.length() >= 3 && (s.charAt(0) == 'L' || s.charAt(0) == 'u' || s.charAt(0) == 'U')
                && s.charAt(1) == '\'') {
            s = s.substring(1);
        }
        if (s.length() < 3 || s.charAt(0) != '\''
                || s.charAt(s.length() - 1) != '\'') {
            throw new CompileException(ctx, "malformed char literal: " + literal);
        }
        String body = s.substring(1, s.length() - 1);
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
                case 'a'  -> 0x07;
                case 'b'  -> '\b';
                case 'f'  -> '\f';
                case 'v'  -> 0x0B;
                case '?'  -> '?';
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

    /** Reference to the {@link CParser} class for unused-import suppression. */
    @SuppressWarnings("unused")
    private static final Class<?> KEEP_CPARSER_REF = CParser.class;
}
