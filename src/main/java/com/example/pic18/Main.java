package com.example.pic18;

import com.example.pic18.codegen.CompileException;
import com.example.pic18.codegen.Pic18CodeGenerator;
import com.example.pic18.grammar.MiniCLexer;
import com.example.pic18.grammar.MiniCParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Command-line entry point for the PIC18 mini-C compiler.
 *
 * Usage:
 *   java -jar pic18-c-compiler.jar &lt;input.c&gt; [-o output.asm]
 *
 * If {@code -o} is omitted, output is written to {@code &lt;input&gt;.asm} alongside
 * the source file. Use {@code -} as the output path to write to stdout.
 */
public final class Main {

    private Main() {
        // utility
    }

    public static void main(String[] args) {
        try {
            int rc = run(args, System.out, System.err);
            if (rc != 0) {
                System.exit(rc);
            }
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Programmatic entry point used by both the CLI and tests.
     *
     * @return shell-style exit code (0 = success).
     */
    public static int run(String[] args, PrintStream out, PrintStream err) throws IOException {
        if (args.length == 0 || equalsAny(args[0], "-h", "--help")) {
            err.println("usage: pic18-c-compiler <input.c> [-o output.asm]");
            err.println("       use '-' as the output path to write to stdout");
            return args.length == 0 ? 1 : 0;
        }

        String inputPath = null;
        String outputPath = null;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("-o")) {
                if (i + 1 >= args.length) {
                    err.println("error: -o requires an argument");
                    return 1;
                }
                outputPath = args[++i];
            } else if (a.startsWith("-")) {
                err.println("error: unknown option " + a);
                return 1;
            } else if (inputPath == null) {
                inputPath = a;
            } else {
                err.println("error: unexpected positional argument " + a);
                return 1;
            }
        }

        if (inputPath == null) {
            err.println("error: no input file specified");
            return 1;
        }

        String source = Files.readString(Paths.get(inputPath), StandardCharsets.UTF_8);
        String asm;
        try {
            asm = compileSource(source, inputPath, err);
        } catch (CompileException e) {
            err.println("compile error: " + e.getMessage());
            return 2;
        } catch (SyntaxErrorException e) {
            // Errors already printed via the listener.
            return 2;
        }

        if (outputPath == null) {
            // default: replace .c with .asm; if no extension, append .asm
            String defaultOut;
            int dot = inputPath.lastIndexOf('.');
            int slash = Math.max(inputPath.lastIndexOf('/'), inputPath.lastIndexOf('\\'));
            if (dot > slash) {
                defaultOut = inputPath.substring(0, dot) + ".asm";
            } else {
                defaultOut = inputPath + ".asm";
            }
            outputPath = defaultOut;
        }

        if (outputPath.equals("-")) {
            out.print(asm);
        } else {
            Files.writeString(Path.of(outputPath), asm, StandardCharsets.UTF_8);
            err.println("wrote " + outputPath);
        }
        return 0;
    }

    /**
     * Compile a source string and return the produced assembly. Syntax errors
     * are reported to {@code err}; on syntax error a {@link SyntaxErrorException}
     * is thrown.
     */
    public static String compileSource(String source, String sourceName, PrintStream err) {
        CharStream cs = CharStreams.fromString(source, sourceName == null ? "<string>" : sourceName);
        MiniCLexer lexer = new MiniCLexer(cs);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        MiniCParser parser = new MiniCParser(tokens);

        DiagnosticErrorListener listener = new DiagnosticErrorListener(err);
        lexer.removeErrorListeners();
        lexer.addErrorListener(listener);
        parser.removeErrorListeners();
        parser.addErrorListener(listener);

        MiniCParser.ProgramContext tree = parser.program();

        if (listener.errorCount() > 0) {
            throw new SyntaxErrorException(listener.errorCount() + " syntax error(s)");
        }

        Pic18CodeGenerator gen = new Pic18CodeGenerator();
        return gen.compile(tree);
    }

    private static boolean equalsAny(String s, String... opts) {
        for (String o : opts) {
            if (o.equals(s)) {
                return true;
            }
        }
        return false;
    }

    /** Thrown when the parser reports one or more syntax errors. */
    public static final class SyntaxErrorException extends RuntimeException {
        public SyntaxErrorException(String msg) {
            super(msg);
        }
    }

    /** Routes ANTLR syntax errors to {@code err} with file:line:col prefix. */
    private static final class DiagnosticErrorListener extends BaseErrorListener {
        private final PrintStream err;
        private int count = 0;

        DiagnosticErrorListener(PrintStream err) {
            this.err = err;
        }

        int errorCount() {
            return count;
        }

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer,
                                Object offendingSymbol,
                                int line,
                                int charPositionInLine,
                                String msg,
                                RecognitionException e) {
            count++;
            String src = recognizer.getInputStream() == null
                    ? "<unknown>"
                    : recognizer.getInputStream().getSourceName();
            err.println(src + ":" + line + ":" + (charPositionInLine + 1) + ": error: " + msg);
        }
    }
}
