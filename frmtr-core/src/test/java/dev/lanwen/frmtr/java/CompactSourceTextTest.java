package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.type.Type;
import dev.lanwen.frmtr.FormatterOptions;
import org.junit.jupiter.api.Test;

final class CompactSourceTextTest {

    @Test
    void compactTypeLikeFallsBackToTokensForUnparsedAnnotatedClassTypesBeforeAnnotationPolicy() {
        Type type = parseType("Map<@A String>");
        type.setParsed(Node.Parsedness.UNPARSABLE);
        CompactSourceText compactSource = new CompactSourceText(new RawSource(FormatterOptions.defaults()));

        String compact = compactSource.compactTypeLike(type);

        assertThat(compact).isEqualTo("Map<@A String>");
    }

    private static Type parseType(String source) {
        JavaParser parser = new JavaParser(
            new ParserConfiguration()
                    .setStoreTokens(true)
                    .setAttributeComments(true)
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.RAW)
        );
        return parser.parse(ParseStart.TYPE, Providers.provider(source))
                .getResult()
                .orElseThrow();
    }
}
