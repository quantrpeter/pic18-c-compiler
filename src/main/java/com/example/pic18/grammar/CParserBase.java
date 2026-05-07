package com.example.pic18.grammar;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;

/**
 * Stub superclass required by the upstream {@code CParser.g4} grammar
 * (which declares {@code options { superClass = CParserBase; }}).
 *
 * <p>The official {@code CParserBase.java} from
 * <a href="https://github.com/antlr/grammars-v4/tree/master/c/Java">antlr/grammars-v4</a>
 * is ~600 lines that maintain a real C symbol table (with classes
 * {@code SymbolTable}, {@code Symbol}, {@code TypeClassification},
 * {@code SourceLocation}) so the parser can disambiguate typedef-names from
 * identifiers, etc. It also reads {@code sun.java.command} for
 * {@code --no-semantics}/{@code --debug}/etc. flags.
 *
 * <p>This stub takes a much cheaper path: every semantic predicate is
 * implemented with a small token-lookahead heuristic that is sufficient for
 * the C subset this teaching compiler actually emits code for (no typedefs,
 * no compound literals, no GNU empty-struct extension). Action methods are
 * no-ops because we don't maintain a parse-time symbol table — the codegen
 * pass does its own.
 *
 * <p>Consequence: source that needs typedef-name disambiguation
 * (e.g. {@code typedef int T; T x;}) will not parse the second statement
 * as a declaration. That's fine because {@link
 * com.example.pic18.codegen.Pic18CodeGenerator} rejects {@code typedef}
 * up front anyway.
 */
public abstract class CParserBase extends Parser {

    protected CParserBase(TokenStream input) {
        super(input);
    }

    // --- Semantic predicates referenced from the .g4 grammar ----------------

    /**
     * In {@code blockItem}, true when the next tokens look like a statement
     * (label, control-flow keyword, or anything that isn't a declaration
     * specifier).
     */
    public boolean IsStatement() {
        Token t1 = peek(1);
        Token t2 = peek(2);
        if (t1 != null && t1.getType() == CLexer.Identifier
                && t2 != null && t2.getType() == CLexer.Colon) {
            return true;
        }
        return !IsDeclaration();
    }

    /**
     * In {@code blockItem}, true when the next tokens start a declaration
     * (i.e. begin with a declaration-specifier keyword such as
     * {@code int}/{@code char}/{@code static}/{@code _Static_assert}/...).
     */
    public boolean IsDeclaration() {
        Token t1 = peek(1);
        if (t1 == null) {
            return false;
        }
        if (t1.getType() == CLexer.StaticAssert
                || t1.getType() == CLexer.Static_assert) {
            return true;
        }
        // C23 attribute-declaration: '[[' ... ']]' ';'
        if (t1.getType() == CLexer.LeftBracket) {
            Token t2 = peek(2);
            if (t2 != null && t2.getType() == CLexer.LeftBracket) {
                return true;
            }
        }
        return IsDeclarationSpecifier();
    }

    /** True if the next token (or the k-th lookahead) starts a declaration specifier. */
    public boolean IsDeclarationSpecifier() {
        return isDeclSpecifierTokenAt(1);
    }

    /** True if the next k-th token is a type-specifier or qualifier. */
    public boolean IsTypeSpecifierQualifier() {
        return isTypeSpecifierOrQualifierAt(1);
    }

    /**
     * In {@code declaration}, gates the optional {@code initDeclaratorList}.
     * After the declaration-specifiers we have an init-declarator list iff
     * the next token starts a declarator: an {@code Identifier},
     * {@code *}/{@code ^} (pointer), or {@code (}.
     */
    public boolean IsInitDeclaratorList() {
        Token t1 = peek(1);
        if (t1 == null) {
            return false;
        }
        int type = t1.getType();
        return type == CLexer.Identifier
                || type == CLexer.Star
                || type == CLexer.Caret
                || type == CLexer.LeftParen
                || type == CLexer.LeftBracket   // for [[attr]] sequences
                || type == CLexer.Attribute;    // GNU __attribute__
    }

    /**
     * Used by {@code typedefName}. We don't track typedefs, so this is
     * always false — the parser will treat all bare identifiers as
     * identifiers, never typedef-names. Source that relies on typedef
     * disambiguation will not parse correctly under this stub, which is
     * fine because the codegen rejects {@code typedef} regardless.
     */
    public boolean IsTypedefName() {
        return false;
    }

    /**
     * Used inside {@code unaryExpression} to choose between
     * {@code sizeof EXPR} and {@code sizeof '(' typeName ')'}, and the
     * analogous {@code _Alignof}/{@code _Countof}/{@code _Maxof}/
     * {@code _Minof} forms. True if the source looks like
     * "( type-keyword ...)".
     */
    public boolean IsSomethingOfTypeName() {
        Token t1 = peek(1);
        Token t2 = peek(2);
        if (t1 == null || t2 == null) {
            return false;
        }
        if (t1.getType() != CLexer.LeftParen) {
            return false;
        }
        return isTypeSpecifierOrQualifierAt(2);
    }

    /**
     * Used in {@code castExpression} to disambiguate {@code (T) expr} from
     * {@code (expr)}. True if we see {@code '(' type-keyword}.
     */
    public boolean IsCast() {
        Token t1 = peek(1);
        Token t2 = peek(2);
        if (t1 == null || t2 == null) {
            return false;
        }
        if (t1.getType() != CLexer.LeftParen) {
            return false;
        }
        return isTypeSpecifierOrQualifierAt(2);
    }

    /**
     * GNU empty-struct extension ({@code struct foo { };}). We don't
     * support structs, so this is irrelevant.
     */
    public boolean IsNullStructDeclarationListExtension() {
        return false;
    }

    // --- Action method stubs referenced from the .g4 grammar ----------------

    /** Upstream emits the symbol table at end of parse. We don't have one. */
    public void OutputSymbolTable() {
    }

    /** Upstream looks the identifier up in its symbol table. No-op here. */
    public void LookupSymbol() {
    }

    /** Upstream classifies declarations to find typedef-names. No-op here. */
    public void EnterDeclaration() {
    }

    /** Upstream pushes a new scope. We have a single flat scope per fn. */
    public void EnterScope() {
    }

    /** Upstream pops the scope. No-op. */
    public void ExitScope() {
    }

    // --- Helpers -----------------------------------------------------------

    private Token peek(int k) {
        TokenStream ts = getInputStream();
        if (!(ts instanceof CommonTokenStream)) {
            return null;
        }
        return ts.LT(k);
    }

    private boolean isDeclSpecifierTokenAt(int k) {
        Token t = peek(k);
        if (t == null) {
            return false;
        }
        switch (t.getType()) {
            // storage-class
            case CLexer.Auto:
            case CLexer.Constexpr:
            case CLexer.Extern:
            case CLexer.Register:
            case CLexer.Static:
            case CLexer.ThreadLocal:
            case CLexer.Typedef:
            // type-specifier
            case CLexer.Void:
            case CLexer.Char:
            case CLexer.Short:
            case CLexer.Int:
            case CLexer.Long:
            case CLexer.Float:
            case CLexer.Double:
            case CLexer.Signed:
            case CLexer.Unsigned:
            case CLexer.Bool:
            case CLexer.Complex:
            case CLexer.Atomic:
            case CLexer.BitInt:
            case CLexer.Decimal32:
            case CLexer.Decimal64:
            case CLexer.Decimal128:
            case CLexer.Imaginary:
            case CLexer.Struct:
            case CLexer.Union:
            case CLexer.Enum:
            case CLexer.Typeof:
            case CLexer.Typeof_unqual:
            // type-qualifier
            case CLexer.Const:
            case CLexer.Restrict:
            case CLexer.Volatile_1:
            case CLexer.Volatile_2:
            // function-specifier
            case CLexer.Inline:
            case CLexer.Noreturn:
            case CLexer.KW__stdcall:
            case CLexer.KW__declspec:
            case CLexer.Attribute:
            // alignment-specifier
            case CLexer.Alignas:
                return true;
            default:
                return false;
        }
    }

    private boolean isTypeSpecifierOrQualifierAt(int k) {
        Token t = peek(k);
        if (t == null) {
            return false;
        }
        switch (t.getType()) {
            case CLexer.Void:
            case CLexer.Char:
            case CLexer.Short:
            case CLexer.Int:
            case CLexer.Long:
            case CLexer.Float:
            case CLexer.Double:
            case CLexer.Signed:
            case CLexer.Unsigned:
            case CLexer.Bool:
            case CLexer.Complex:
            case CLexer.Atomic:
            case CLexer.BitInt:
            case CLexer.Decimal32:
            case CLexer.Decimal64:
            case CLexer.Decimal128:
            case CLexer.Imaginary:
            case CLexer.Struct:
            case CLexer.Union:
            case CLexer.Enum:
            case CLexer.Typeof:
            case CLexer.Typeof_unqual:
            case CLexer.Const:
            case CLexer.Restrict:
            case CLexer.Volatile_1:
            case CLexer.Volatile_2:
            case CLexer.Alignas:
                return true;
            default:
                return false;
        }
    }
}
