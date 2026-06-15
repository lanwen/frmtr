package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;

/**
 * Prints package declarations after the compilation-unit ordering rules have selected the package position.
 *
 * <p>This helper owns the raw source-leading comment prefix that can appear before {@code package ...} and the package
 * declaration line with JavaParser-attributed leading comments. It intentionally delegates orphan-comment sequencing,
 * import block selection, module declarations, and top-level declaration dispatch back to {@link JavaPrinter} because
 * those are whole-compilation-unit layout decisions rather than package declaration formatting.
 *
 * <p>Representative fixture pairs live under
 * {@code frmtr-core/src/test/resources/format/package-imports-mixed-imports}. Source-leading package comments are
 * covered near {@code frmtr-core/src/test/resources/format/comment-preservation-class-members}.
 */
final class PackageDeclarationPrinter {

    private final CommentTracker comments;

    private final RawSource rawSource;

    private final FormatterOptions options;

    PackageDeclarationPrinter(CommentTracker comments, RawSource rawSource, FormatterOptions options) {
        this.comments = comments;
        this.rawSource = rawSource;
        this.options = options;
    }

    /**
     * Recovers raw comments that appear in the source before the package declaration token sequence.
     *
     * <p>This path only emits the raw prefix when a package declaration exists, the original source contains the
     * {@code package <name>} text after earlier content, and that earlier content starts with a line or block comment.
     * If any of those checks fail, the caller's normal orphan-comment and JavaParser leading-comment handling keeps
     * ownership exactly as before.
     */
    Doc sourceLeadingCommentsBeforePackage(CompilationUnit unit) {
        if (unit.getPackageDeclaration().isEmpty()) {
            return Doc.EMPTY;
        }
        String packagePrefix = "package " + unit.getPackageDeclaration().orElseThrow().getNameAsString();
        String rawUnit = unit.getTokenRange().map(Object::toString).orElse("");
        int packageStart = rawUnit.indexOf(packagePrefix);
        if (packageStart <= 0) {
            return Doc.EMPTY;
        }
        String leading = rawUnit.substring(0, packageStart).stripTrailing();
        if (!leading.startsWith("/*") && !leading.startsWith("//")) {
            return Doc.EMPTY;
        }
        return Doc.text(
            options.preserveRawTrailingWhitespace()
                ? leading
                : rawSource.stripTrailingHorizontalWhitespace(leading)
        );
    }

    /**
     * Prints one package declaration with its JavaParser-attributed leading comments.
     */
    Doc packageDeclaration(PackageDeclaration declaration) {
        return Doc.concat(comments.leading(declaration), Doc.text("package " + declaration.getNameAsString() + ";"));
    }
}
