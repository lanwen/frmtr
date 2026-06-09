package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.modules.ModuleDeclaration;
import com.github.javaparser.ast.modules.ModuleDirective;
import com.github.javaparser.ast.modules.ModuleExportsDirective;
import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.DocRenderer;
import org.junit.jupiter.api.Test;

final class ModuleBlockPrinterTest {
    @Test
    void formatsValidModuleDirectiveSiblingsAroundRawRecoveredDirectiveGap() {
        String source = """
                module demo {
                    requires   before;
                    exports   broken.pkg   to   raw.Target; // keep raw
                    uses   after.Service;
                }
                """;
        CompilationUnit unit = parse(source);
        directive(unit, ModuleExportsDirective.class).setParsed(Node.Parsedness.UNPARSABLE);

        String formatted = printRecovered(unit, source);

        assertThat(formatted).isEqualTo("""
                module demo {
                    requires before;
                    exports   broken.pkg   to   raw.Target; // keep raw
                    uses after.Service;
                }
                """);
    }

    @Test
    void recoversDirectiveWhenParsedDirectiveHasUnparsableDescendant() {
        String source = """
                module demo {
                    requires   before;
                    exports   broken.pkg   to   raw.Target; // keep raw
                    uses   after.Service;
                }
                """;
        CompilationUnit unit = parse(source);
        ModuleExportsDirective brokenDirective = directive(unit, ModuleExportsDirective.class);
        assertThat(brokenDirective.getParsed()).isEqualTo(Node.Parsedness.PARSED);
        brokenDirective.getName().setParsed(Node.Parsedness.UNPARSABLE);

        assertThat(ModuleBlockPrinter.hasRecoverableModuleDirectiveListProblem(module(unit))).isTrue();
        assertThat(JavaFormatter.isSupportedRecovery(brokenDirective.getName())).isTrue();

        String formatted = printRecovered(unit, source);

        assertThat(formatted).isEqualTo("""
                module demo {
                    requires before;
                    exports   broken.pkg   to   raw.Target; // keep raw
                    uses after.Service;
                }
                """);
    }

    @Test
    void usesWholeModuleFallbackWhenCommentOutsideRecoveredDirectiveRawGap() {
        String source = """
                module demo /* header */ {
                    requires   before;
                    exports   broken.pkg   to   raw.Target; // keep raw
                    uses   after.Service;
                }
                """;
        CompilationUnit unit = parse(source);
        directive(unit, ModuleExportsDirective.class).setParsed(Node.Parsedness.UNPARSABLE);

        String formatted = printRecovered(unit, source);

        assertThat(formatted).isEqualTo("""
                module demo /* header */ {
                  requires before;
                  exports broken.pkg
                    to raw.Target; // keep raw
                  uses after.Service;
                }
                """);
        assertThat(formatted)
                .containsOnlyOnce("/* header */")
                .containsOnlyOnce("// keep raw");
    }

    @Test
    void reportsRawCommentBoundaryFailuresAsRecoverableModuleDirectiveListFailures() {
        String source = """
                module demo {
                    requires before;
                    exports broken.pkg to raw.Target;
                    uses after.Service;
                }
                """;
        CompilationUnit unit = parse(source);
        ModuleDeclaration module = module(unit);
        LineComment rangeLessComment = new LineComment("range-less");
        module.addOrphanComment(rangeLessComment);
        directive(unit, ModuleExportsDirective.class).setParsed(Node.Parsedness.UNPARSABLE);

        Throwable thrown = catchThrowable(() -> printRecovered(unit, source));

        assertThat(rangeLessComment.getRange()).isEmpty();
        assertThat(thrown)
                .isInstanceOf(FormatterException.class)
                .hasMessageContaining("Unable to recover Java parse error inside module directive list:")
                .hasMessageContaining("cannot safely account LineComment at unknown range")
                .hasCauseInstanceOf(RecoveredSourceRegions.CrossingCommentBoundaryException.class);
        assertThat(((FormatterException) thrown).internal()).isFalse();
    }

    private static String printRecovered(CompilationUnit unit, String source) {
        return new DocRenderer(FormatterOptions.defaults())
                .render(new JavaPrinter(FormatterOptions.defaults(), new SourceText(source), true).print(unit));
    }

    private static ModuleDeclaration module(CompilationUnit unit) {
        return unit.getModule().orElseThrow();
    }

    private static <T extends ModuleDirective> T directive(CompilationUnit unit, Class<T> directiveType) {
        return module(unit).getDirectives().stream()
                .filter(directiveType::isInstance)
                .map(directiveType::cast)
                .findFirst()
                .orElseThrow();
    }

    private static CompilationUnit parse(String source) {
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                .setStoreTokens(true)
                .setAttributeComments(true));
        return parser.parse(ParseStart.COMPILATION_UNIT, Providers.provider(source))
                .getResult()
                .orElseThrow();
    }
}
