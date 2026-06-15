package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import dev.lanwen.frmtr.FormatterOptions;
import org.junit.jupiter.api.Test;

final class SourceTextTest {

    @Test
    void mapsLfJavaParserRangesToSourceOffsets() {
        String source = "class Demo {\n    int value;\n}\n";
        SourceText sourceText = new SourceText(source);
        FieldDeclaration field = parse(source).findFirst(FieldDeclaration.class).orElseThrow();

        SourceRegion region = sourceText.region(field.getRange().orElseThrow());

        assertThat(region.beginOffset()).isEqualTo(source.indexOf("int value;"));
        assertThat(region.endOffset()).isEqualTo(source.indexOf("int value;") + "int value;".length());
        assertThat(sourceText.slice(region)).isEqualTo("int value;");
    }

    @Test
    void mapsCrlfJavaParserRangesToSourceOffsets() {
        String source = "class Demo {\r\n\tString value;\r\n}\r\n";
        SourceText sourceText = new SourceText(source);
        FieldDeclaration field = parse(source).findFirst(FieldDeclaration.class).orElseThrow();

        SourceRegion region = sourceText.region(field.getRange().orElseThrow());

        assertThat(region.beginOffset()).isEqualTo(source.indexOf("String value;"));
        assertThat(region.endOffset()).isEqualTo(source.indexOf("String value;") + "String value;".length());
        assertThat(sourceText.slice(region)).isEqualTo("String value;");
    }

    @Test
    void mapsDirectOffsetRegionAcrossLineBreaksToRawSliceAndLabel() {
        String source = "class Demo {\n  int first;\n\n  int second;\n}\n";
        SourceText sourceText = new SourceText(source);
        int beginOffset = source.indexOf("\n\n  int second;");
        int endOffset = source.indexOf("int second;");

        SourceRegion region = sourceText.region(beginOffset, endOffset);

        assertThat(region.beginOffset()).isEqualTo(beginOffset);
        assertThat(region.endOffset()).isEqualTo(endOffset);
        assertThat(sourceText.slice(region)).isEqualTo("\n\n  ");
        assertThat(region.lineColumnLabel()).isEqualTo("line 2, column 13 to line 4, column 2");
    }

    @Test
    void mapsZeroWidthOffsetRegionAtLineBoundaryToEmptySliceAndLabel() {
        String source = "class Demo {\n  int value;\n}\n";
        SourceText sourceText = new SourceText(source);
        int boundaryOffset = source.indexOf("  int value;");

        SourceRegion region = sourceText.region(boundaryOffset, boundaryOffset);

        assertThat(region.beginOffset()).isEqualTo(boundaryOffset);
        assertThat(region.endOffset()).isEqualTo(boundaryOffset);
        assertThat(sourceText.slice(region)).isEmpty();
        assertThat(region.lineColumnLabel()).isEqualTo("line 2, column 1 to line 2, column 1");
    }

    @Test
    void slicesMultiLineRegionsWithoutChangingLineEndings() {
        String source = """
                class Demo {
                    void demo() {
                        call();
                    }
                }
                """;
        SourceText sourceText = new SourceText(source);
        MethodDeclaration method = parse(source).findFirst(MethodDeclaration.class).orElseThrow();

        String slice = sourceText.slice(sourceText.region(method.getRange().orElseThrow()));

        assertThat(slice).isEqualTo(
            String.join(
                "\n",
                "void demo() {",
                "        call();",
                "    }"
            )
        );
    }

    @Test
    void stripsTrailingHorizontalWhitespaceLineByLineWhilePreservingCrlfLineEndings() {
        String source = "class Demo {  \r\n\tint value;\t \r\n}\t\r\n";
        SourceText sourceText = new SourceText(source);
        SourceRegion wholeSource = sourceText.region(0, source.length());

        String raw = sourceText.rawSlice(wholeSource, options(false));

        assertThat(raw).isEqualTo("class Demo {\r\n\tint value;\r\n}\r\n");
    }

    @Test
    void preservesTrailingHorizontalWhitespaceWhenConfigured() {
        String source = "class Demo {  \n    int value;\t \n}\n";
        SourceText sourceText = new SourceText(source);
        SourceRegion wholeSource = sourceText.region(0, source.length());

        String raw = sourceText.rawSlice(wholeSource, options(true));

        assertThat(raw).isEqualTo(source);
    }

    @Test
    void formatsHumanReadableLineColumnLabels() {
        String source = """
                class Demo {
                    void demo() {
                        call();
                    }
                }
                """;
        SourceText sourceText = new SourceText(source);
        MethodDeclaration method = parse(source).findFirst(MethodDeclaration.class).orElseThrow();

        SourceRegion region = sourceText.region(method.getRange().orElseThrow());

        assertThat(region.lineColumnLabel()).isEqualTo("line 2, column 5 to line 4, column 5");
    }

    private static CompilationUnit parse(String source) {
        JavaParser parser = new JavaParser(
            new ParserConfiguration()
                    .setStoreTokens(true)
                    .setAttributeComments(true)
        );
        return parser.parse(ParseStart.COMPILATION_UNIT, Providers.provider(source))
                .getResult()
                .orElseThrow();
    }

    private static FormatterOptions options(boolean preserveRawTrailingWhitespace) {
        return FormatterOptions.defaults().withPreserveRawTrailingWhitespace(preserveRawTrailingWhitespace);
    }
}
