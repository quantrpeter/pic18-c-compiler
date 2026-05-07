package com.example.pic18.grammar;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Lexer;

/**
 * Stub superclass required by the upstream {@code CLexer.g4} grammar
 * (which declares {@code options { superClass = CLexerBase; }}).
 *
 * <p>The official {@code CLexerBase.java} from
 * <a href="https://github.com/antlr/grammars-v4/tree/master/c/Java">antlr/grammars-v4</a>
 * shells out to a system {@code gcc}/{@code clang} to preprocess the input
 * before lexing, writing temp files like {@code stdin.c}/{@code stdin.c.p}.
 * That's overkill for a teaching compiler that doesn't support the C
 * preprocessor, and would force every {@code mvn test} to spawn gcc.
 *
 * <p>This stub instead passes the input through unchanged. The grammar's
 * {@code MultiLineMacro}/{@code Directive}/{@code LineDirective} lexer rules
 * already route stray {@code #}-prefixed lines to the HIDDEN channel, so
 * passing pre-existing {@code #include}/{@code #define} lines through is
 * harmless: they just get ignored.
 */
public abstract class CLexerBase extends Lexer {

    protected CLexerBase(CharStream input) {
        super(input);
    }
}
