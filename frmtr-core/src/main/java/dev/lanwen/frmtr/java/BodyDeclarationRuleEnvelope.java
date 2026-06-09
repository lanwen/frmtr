package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.doc.Doc;

/**
 * Applies body-declaration-level pragma, raw-source, and attached-comment gates before content dispatch.
 *
 * <p>This helper owns the outer body-declaration rule envelope: formatter off/on and ignore pragmas, raw source
 * recovery, and the leading/trailing comment slot attached to the declaration itself. The boundary keeps those
 * source-sensitive gates out of {@link BodyDeclarationDispatcher}, which only narrows already-formattable declaration
 * content, and out of declaration printers, which render the selected declaration grammar.
 *
 * <p>Callers still choose when a body-declaration context is reached and provide the already-wired content dispatcher.
 * Class, record, enum, annotation, field, method, constructor, initializer, member sequencing, and fallback content
 * layout stay with their existing owners.
 */
final class BodyDeclarationRuleEnvelope {
    private final CommentTracker comments;
    private final FormatterPragmas formatterPragmas;
    private final RawPreservedSource rawPreservedSource;
    private final JavaFormatRule<BodyDeclaration<?>> bodyContent;

    BodyDeclarationRuleEnvelope(
            CommentTracker comments,
            FormatterPragmas formatterPragmas,
            RawPreservedSource rawPreservedSource,
            JavaFormatRule<BodyDeclaration<?>> bodyContent) {
        this.comments = comments;
        this.formatterPragmas = formatterPragmas;
        this.rawPreservedSource = rawPreservedSource;
        this.bodyContent = bodyContent;
    }

    /**
     * Applies body-level formatter pragmas and leading comment attachment before declaration content rendering.
     *
     * <p>Formatter off/on pragmas update persistent state across later declarations, so this gate must run before the
     * content dispatcher. Formatted declarations route to {@link BodyDeclarationDispatcher} only after body-level raw
     * output has been ruled out and the declaration's leading comment slot has been claimed.
     */
    Doc body(BodyDeclaration<?> declaration) {
        requireParsedDeclarationNode(declaration);
        FormatterPragmas.PrintAction action = formatterPragmas.bodyAction(declaration);
        Doc doc = switch (action) {
            case RAW -> rawBody(declaration);
            case FORMAT -> formattedBody(declaration);
            case RAW_WITH_TRAILING_HARD_LINE -> throw new IllegalStateException("body declarations cannot use "
                    + FormatterPragmas.PrintAction.RAW_WITH_TRAILING_HARD_LINE);
        };
        return Doc.label("java.bodyDeclaration:" + declaration.getClass().getSimpleName(), doc);
    }

    /**
     * Prints the declaration's attached comment slot around structured or compact-fallback declaration content.
     *
     * <p>Declaration printers may still own nested comments inside the declaration grammar, but the outer attached
     * comment belongs to this envelope so all body declarations pass through one comment gate before content dispatch.
     * A same-line trailing line comment is kept after the declaration instead of being reclassified as a leading comment.
     */
    private Doc formattedBody(BodyDeclaration<?> declaration) {
        Doc trailing = comments.trailingLineComment(declaration);
        Doc leading = trailing == Doc.EMPTY ? comments.leading(declaration) : Doc.EMPTY;
        Doc body = bodyContent.format(declaration);
        return Doc.concat(leading, body, trailing == Doc.EMPTY ? Doc.EMPTY : Doc.concat(Doc.text(" "), trailing));
    }

    /**
     * Emits a raw-passed declaration after printing its leading comment separately.
     *
     * <p>The source text has the declaration's own attached comment removed so raw pragma output does not duplicate the
     * leading comment that has already been claimed by the comment tracker.
     */
    private Doc rawBody(BodyDeclaration<?> declaration) {
        return Doc.concat(comments.leading(declaration), rawPreservedSource.rawWithoutOwnComment(declaration));
    }

    private static void requireParsedDeclarationNode(BodyDeclaration<?> declaration) {
        if (declaration.getParsed() == Node.Parsedness.PARSED) {
            return;
        }
        // TODO: Expose the rejected recovered declaration through formatter diagnostics once recovery reporting exists.
        throw new FormatterException("Unsupported Java parse-error recovery reached declaration formatter: "
                + declaration.getClass().getSimpleName());
    }
}
