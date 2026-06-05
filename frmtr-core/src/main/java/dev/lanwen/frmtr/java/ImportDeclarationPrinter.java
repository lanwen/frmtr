package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.ImportDeclaration;
import dev.lanwen.frmtr.doc.Doc;

/**
 * Prints individual import declarations after the compilation-unit import block has been selected.
 *
 * <p>This helper owns import-level leading comments, the keyword fork between {@code import} and {@code import static},
 * and the trailing asterisk marker for wildcard imports. It intentionally delegates import ordering, static-versus-
 * normal block separation, package declarations, and module declarations back to {@link JavaPrinter} because those are
 * top-level compilation-unit layout decisions rather than single-import formatting decisions.
 *
 * <p>Representative fixture pairs live under
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/package_and_imports}, especially
 * {@code classWithMixedImports/input.java} and {@code classWithMixedImports/frmtr.output.java}; the static-only and
 * non-static-only fixtures cover the block-selection cases that remain in {@link JavaPrinter}.
 */
final class ImportDeclarationPrinter {
    private final JavaFormatter.CommentTracker comments;

    ImportDeclarationPrinter(JavaFormatter.CommentTracker comments) {
        this.comments = comments;
    }

    /**
     * Prints one import declaration with its leading comments.
     *
     * <p>JavaParser stores the imported name without the wildcard marker, so asterisk imports add {@code .*} after the
     * name. Static imports use a different keyword but otherwise share the same name and wildcard shape as normal
     * imports.
     */
    Doc importDeclaration(ImportDeclaration declaration) {
        String prefix = declaration.isStatic() ? "import static " : "import ";
        String suffix = declaration.isAsterisk() ? ".*" : "";
        return Doc.concat(comments.leading(declaration), Doc.text(prefix + declaration.getNameAsString() + suffix + ";"));
    }
}
