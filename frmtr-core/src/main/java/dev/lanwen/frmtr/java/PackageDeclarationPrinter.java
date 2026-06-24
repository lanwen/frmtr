package dev.lanwen.frmtr.java;

import com.github.javaparser.GeneratedJavaParserConstants;
import com.github.javaparser.JavaToken;
import com.github.javaparser.Position;
import com.github.javaparser.TokenRange;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.comments.Comment;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.Optional;

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
     * <p>This path only emits the raw prefix when a package declaration exists, the compilation-unit token stream has
     * content before the {@code package} keyword, and that earlier content starts with a line or block comment. If any
     * of those checks fail, the caller's normal orphan-comment and JavaParser leading-comment handling keeps ownership
     * exactly as before.
     *
     * <p>The package boundary is located by the lexer {@code package} keyword token, not by a literal
     * {@code "package <name>"} substring match. A substring match is defeated whenever the source has non-canonical
     * spacing inside the package token run (for example {@code package  com . google . common . collect}, as the
     * idempotence/comment-presence whitespace perturbations produce): the literal {@code package com.google...} prefix
     * no longer occurs verbatim, so the recovery would silently return {@link Doc#EMPTY} and drop an AST-invisible file
     * header. Accumulating the raw token text up to the first {@code package} keyword reconstructs exactly the same
     * leading region the old {@code rawUnit.substring(0, packageStart)} produced, independent of intra-declaration
     * spacing.
     *
     * <p>The raw sweep stops at the comment JavaParser attached to the package declaration as its leading trivia, not at
     * the {@code package} keyword itself, whenever such a comment exists. When two or more comments precede
     * {@code package} (for example a license header followed by a {@code /** package doc *}{@code /}, the
     * {@code package-info.java} shape), JavaParser attaches the <em>last</em> one as the package declaration's leading
     * comment while leaving the earlier ones detached. {@link #packageDeclaration(PackageDeclaration)} already emits that
     * attached comment through the tracked {@link CommentTracker#leading(com.github.javaparser.ast.Node)} slot, so
     * sweeping it into the raw prefix too would emit it twice — once raw (untracked, invisible to the strict-claims and
     * comment-presence guardrails because it never claims) and once tracked — duplicating the package doc on every pass
     * without bound. Truncating the sweep at the attached comment's begin position keeps each pre-{@code package} comment
     * emitted exactly once: the detached header(s) raw, the attached comment via the leading slot. Files with a single
     * pre-{@code package} comment have no attached package leading comment, so the sweep still runs to the {@code package}
     * keyword and their output is unchanged.
     */
    Doc sourceLeadingCommentsBeforePackage(CompilationUnit unit) {
        Optional<PackageDeclaration> packageDeclaration = unit.getPackageDeclaration();
        if (packageDeclaration.isEmpty()) {
            return Doc.EMPTY;
        }
        TokenRange tokens = unit.getTokenRange().orElse(null);
        if (tokens == null) {
            return Doc.EMPTY;
        }
        Optional<Position> attachedLeadingCommentStart = packageDeclaration
                .flatMap(PackageDeclaration::getComment)
                .flatMap(PackageDeclarationPrinter::commentBeginPosition);
        StringBuilder leadingTokens = new StringBuilder();
        boolean foundBoundary = false;
        for (JavaToken token : tokens) {
            if (token.getKind() == GeneratedJavaParserConstants.PACKAGE || reachesAttachedLeadingComment(
                token,
                attachedLeadingCommentStart
            )) {
                foundBoundary = true;
                break;
            }
            leadingTokens.append(token.getText());
        }
        if (!foundBoundary) {
            return Doc.EMPTY;
        }
        String leading = leadingTokens.toString().stripTrailing();
        if (leading.isEmpty() || (!leading.startsWith("/*") && !leading.startsWith("//"))) {
            return Doc.EMPTY;
        }
        return Doc.text(
            options.preserveRawTrailingWhitespace()
                ? leading
                : rawSource.stripTrailingHorizontalWhitespace(leading)
        );
    }

    /**
     * Reports whether {@code token} begins at or after the package declaration's attached leading comment, which the
     * tracked leading-comment slot will emit separately. Tokens without a source range never reach the boundary, so the
     * sweep falls back to the {@code package} keyword stop exactly as before.
     */
    private static boolean reachesAttachedLeadingComment(JavaToken token, Optional<Position> attachedLeadingCommentStart) {
        return attachedLeadingCommentStart
                .flatMap(commentStart -> token.getRange().map(range -> !range.begin.isBefore(commentStart)))
                .orElse(false);
    }

    private static Optional<Position> commentBeginPosition(Comment comment) {
        return comment.getRange().map(range -> range.begin);
    }

    /**
     * Prints one package declaration with its JavaParser-attributed leading comments.
     */
    Doc packageDeclaration(PackageDeclaration declaration) {
        return Doc.concat(comments.leading(declaration), Doc.text("package " + declaration.getNameAsString() + ";"));
    }
}
