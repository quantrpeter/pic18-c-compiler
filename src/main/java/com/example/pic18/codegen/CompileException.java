package com.example.pic18.codegen;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;

/**
 * Raised by {@link Pic18CodeGenerator} for semantic / unsupported-construct
 * errors detected during code generation (i.e. after the parser has accepted
 * the input).
 */
public class CompileException extends RuntimeException {

    public CompileException(String msg) {
        super(msg);
    }

    public CompileException(ParserRuleContext ctx, String msg) {
        super(formatLocation(ctx) + msg);
    }

    private static String formatLocation(ParserRuleContext ctx) {
        if (ctx == null) {
            return "";
        }
        Token t = ctx.getStart();
        if (t == null) {
            return "";
        }
        return "line " + t.getLine() + ":" + (t.getCharPositionInLine() + 1) + ": ";
    }
}
