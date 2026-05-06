grammar MiniC;

// NOTE on packaging:
//
// The antlr4-maven-plugin auto-derives the Java package for generated sources
// from the grammar file's path relative to src/main/antlr4. Because this file
// lives at src/main/antlr4/com/example/pic18/grammar/MiniC.g4, the generated
// classes are placed in package `com.example.pic18.grammar` automatically.
//
// We therefore intentionally omit `@header { package ...; }` here -- adding
// it would emit a duplicate `package` declaration in the generated sources
// (the plugin would emit one, and the @header block would emit another), and
// the resulting Java files would not compile.

// =============================================================================
// Parser rules
// =============================================================================

program
    : topLevel* EOF
    ;

topLevel
    : functionDecl
    | globalVarDecl
    ;

// ---- Declarations -----------------------------------------------------------

globalVarDecl
    : type ID ('=' expression)? ';'
    ;

// A function declaration with a body (definition) OR a forward declaration
// (prototype) terminated by ';'. Prototypes generate no code; they exist so
// users can pre-declare the built-in helpers (out/in/delay) idiomatically.
functionDecl
    : type ID '(' paramList? ')' (block | ';')
    ;

paramList
    : param (',' param)*
    | 'void'              // explicit (void) parameter list -> no params
    ;

param
    : type ID
    ;

type
    : 'unsigned'? ('int' | 'char')
    | 'void'
    ;

// ---- Statements -------------------------------------------------------------

block
    : '{' statement* '}'
    ;

statement
    : block                                                       # blockStmt
    | type ID ('=' expression)? ';'                               # localDeclStmt
    | 'if' '(' expression ')' statement ('else' statement)?       # ifStmt
    | 'while' '(' expression ')' statement                        # whileStmt
    | 'for' '(' forInit? ';' expression? ';' expression? ')'
        statement                                                 # forStmt
    | 'return' expression? ';'                                    # returnStmt
    | ID '=' expression ';'                                       # assignStmt
    | expression ';'                                              # exprStmt
    | ';'                                                         # emptyStmt
    ;

forInit
    : type ID ('=' expression)?      // for (int i = 0; ...; ...)
    | ID '=' expression              // for (i = 0; ...; ...)
    | expression                     // for (foo(); ...; ...)
    ;

// ---- Expressions ------------------------------------------------------------
//
// ANTLR4 supports direct left recursion in a single rule. We use a single
// `expression` rule with alts ordered so that higher-precedence operators
// appear first (mul before add, etc.).
//
expression
    : '(' expression ')'                                          # parenExpr
    | ID '(' argList? ')'                                         # callExpr
    // Pre-/post-fix increment & decrement. We restrict the operand to a bare
    // identifier (the only lvalue this language has anyway). The post-fix
    // alternative must precede `ID # idExpr` so a token sequence like
    // `a ++` matches here instead of being eaten as a plain identifier.
    | ID op=('++' | '--')                                         # postIncExpr
    | op=('++' | '--') ID                                         # preIncExpr
    | op=('!' | '-' | '~') expression                             # unaryExpr
    | expression op=('*' | '/' | '%') expression                  # mulExpr
    | expression op=('+' | '-') expression                        # addExpr
    | expression op=('<<' | '>>') expression                      # shiftExpr
    | expression op=('<' | '<=' | '>' | '>=') expression          # relExpr
    | expression op=('==' | '!=') expression                      # eqExpr
    | expression '&' expression                                   # bitAndExpr
    | expression '^' expression                                   # bitXorExpr
    | expression '|' expression                                   # bitOrExpr
    | expression '&&' expression                                  # logAndExpr
    | expression '||' expression                                  # logOrExpr
    | HEX                                                         # hexLit
    | INT                                                         # intLit
    | CHAR                                                        # charLit
    | ID                                                          # idExpr
    ;

argList
    : expression (',' expression)*
    ;

// =============================================================================
// Lexer rules
// =============================================================================

// Numeric literals -- HEX must come before INT (longest match would otherwise
// pick INT for the leading "0").
HEX   : '0' [xX] [0-9a-fA-F]+ ;
INT   : [0-9]+ ;
CHAR  : '\'' ( ~['\\\r\n] | '\\' . ) '\'' ;

// All keywords used in the parser (e.g. 'int', 'while', ...) become implicit
// tokens that take precedence over ID because they are declared earlier
// (parser literal tokens are emitted before lexer rules in the token table).
ID    : [a-zA-Z_][a-zA-Z_0-9]* ;

// Skip whitespace and comments.
WS            : [ \t\r\n]+         -> skip ;
LINE_COMMENT  : '//' ~[\r\n]*      -> skip ;
BLOCK_COMMENT : '/*' .*? '*/'      -> skip ;
