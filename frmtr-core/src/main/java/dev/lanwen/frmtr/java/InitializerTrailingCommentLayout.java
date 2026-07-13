package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Owns the recovery of {@code //} line comments that trail a variable declarator around its statement terminator, for
 * {@link VariableInitializerLayout#variableWithStatementTerminator}.
 *
 * <p>This helper hosts the family that answers "does a comment trail this declaration, and where does it belong relative
 * to the {@code ;}?" — the declarator-or-initializer trailing line comment recovered after the {@code ;}
 * ({@link #trailingDeclaratorLineComment}), the pre-{@code ;} initializer comments that must drop the {@code ;} onto its
 * own line ({@link #preSemicolonInitializerComment}), and the method-call tail-comment probes that route a chain
 * initializer onto the statement-terminator path ({@link #methodCallNeedsStatementTerminatorTail},
 * {@link #initializerTailLineComment}, {@link #methodCallFinalTrailingLineComments}). Each of these reads the same
 * comment-candidate sets the terminator emitter re-renders, keyed purely on source-order ownership relative to the raw
 * declaration's final {@code ;}, so a detection verdict and the later {@code comments.comment(...)} render stay in
 * lockstep and no trailing comment is dropped when an initializer collapses off its source shape.
 *
 * <p>The boundary exists so the terminator emitter can consult one comment authority instead of carrying every
 * trailing/tail comment scan inline. The helper only reports and recovers comments; it never chooses the initializer's
 * shape, glues the recovered comment onto the line, or emits the {@code ;} — those stay with the caller, which threads
 * the {@link Optional} trivia through its own {@code comments.comment(...)} render and spaces / terminates the line
 * itself. The parent keeps a thin {@code methodCallFinalTrailingLineComments} wrapper because its object-creation
 * dot-break shape path consults the same leaf.
 */
final class InitializerTrailingCommentLayout {

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    private final RawSource rawSource;

    InitializerTrailingCommentLayout(
            CommentTracker comments,
            JavaCommentPlacementPolicy commentPlacement,
            RawSource rawSource
    ) {
        this.comments = comments;
        this.commentPlacement = commentPlacement;
        this.rawSource = rawSource;
    }

    /**
     * Recovers the {@code //} line comment that trails the declarator and renders after the closing {@code ;}, falling
     * back to the initializer's own trailing line comment when the declarator slot is empty.
     *
     * <p>When a declarator's initializer collapses from a multi-line source shape onto one line, JavaParser parks a
     * {@code } // note} comment that began after the initializer's last token (and after the {@code ;}) as the
     * <em>initializer's</em> own trailing line comment rather than the declarator's. The declarator's own trailing slot
     * ({@link CommentTracker#trailingLineComment(Node)} on the variable) is then empty, so that comment is dropped even
     * though it genuinely trails the whole declaration. This fallback claims the initializer's own post-end trailing line
     * comment in exactly that case, keying purely on source-order ownership rather than the collapsed-versus-multiline
     * shape. At shapes where the declarator slot already holds the comment, the fallback is never consulted; at shapes
     * where the comment is the field declaration's own trailing comment it is claimed earlier by the body envelope and
     * this offer renders {@link Doc#EMPTY}, so unperturbed output is unchanged.
     */
    Doc trailingDeclaratorLineComment(VariableDeclarator variable) {
        Doc declaratorTrailing = comments.trailingLineComment(variable);
        if (declaratorTrailing != Doc.EMPTY) {
            return declaratorTrailing;
        }
        return variable.getInitializer()
                .map(comments::trailingLineComment)
                .orElse(Doc.EMPTY);
    }

    /**
     * Recovers the {@code //} line comments that trail this declarator's initializer after its last operand and before the
     * closing {@code ;}, emitting them on their own continuation lines so the {@code ;} can drop onto its own line below.
     *
     * <p>For a multi-line String concatenation initializer JavaParser parks such a comment as an orphan of the enclosing
     * {@link FieldDeclaration}/{@link ExpressionStmt} rather than as the initializer's contained trivia or the declarator's
     * own trailing trivia, so neither the binary-line recovery nor the post-{@code ;} trailing slot prints it (see
     * {@link CommentTracker#trailingInitializerCommentsBeforeSemicolon(Node, Node)}). The recovered comments are indented to
     * the operand-continuation column the START/between-operand lines already use so the END comment aligns with the
     * {@code +} lines; the caller drops the {@code ;} onto its own base-indent line below via {@link Doc#HARD_LINE}, because a
     * {@code //} line would otherwise swallow a trailing {@code ;} into the comment. When there is no such comment the result
     * is {@link Doc#EMPTY} (the same singleton the empty-recovery branch returns), leaving the terminator byte-identical to
     * the no-recovery {@code concat(declaration, ";", trailing)}.
     */
    Doc preSemicolonInitializerComment(VariableDeclarator variable) {
        Expression initializer = variable.getInitializer().orElse(null);
        if (initializer == null) {
            return Doc.EMPTY;
        }
        Node owner = semicolonOwner(variable).orElse(null);
        if (owner == null) {
            return Doc.EMPTY;
        }
        List<Doc> recovered = comments.trailingInitializerCommentsBeforeSemicolon(owner, initializer);
        if (recovered.isEmpty()) {
            return Doc.EMPTY;
        }
        return Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, recovered)));
    }

    boolean methodCallNeedsStatementTerminatorTail(VariableDeclarator variable, MethodCallExpr methodCall) {
        return methodCallHasPreSemicolonTailLineComment(
            variable,
            methodCall
        ) || initializerTailLineComment(variable, methodCall).isPresent();
    }

    private boolean methodCallHasPreSemicolonTailLineComment(
            VariableDeclarator variable,
            MethodCallExpr methodCall
    ) {
        return methodCallFinalTrailingLineComments(methodCall)
                .stream()
                .anyMatch(comment -> commentStartsBeforeDeclarationSemicolon(comment, variable));
    }

    List<JavaCommentTrivia> methodCallFinalTrailingLineComments(MethodCallExpr expression) {
        List<JavaCommentTrivia> sourceComments = new ArrayList<>();
        commentPlacement.trailingLineComment(expression).ifPresent(sourceComments::add);
        commentPlacement.containedComments(expression)
                .stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.startsAfterNodeOnSameLine(expression))
                .filter(
                    comment -> sourceComments.stream().noneMatch(existing -> existing.comment() == comment.comment())
                )
                .forEach(sourceComments::add);
        return sourceComments;
    }

    Optional<JavaCommentTrivia> initializerTailLineComment(
            VariableDeclarator variable,
            Expression initializer
    ) {
        return initializerTailLineCommentCandidates(variable)
                .stream()
                .filter(comment -> comment.startsAfterNodeOnSameLine(initializer))
                .filter(comment -> commentStartsBeforeDeclarationSemicolon(comment, variable))
                .findFirst();
    }

    private List<JavaCommentTrivia> initializerTailLineCommentCandidates(VariableDeclarator variable) {
        List<JavaCommentTrivia> candidates = new ArrayList<>();
        commentPlacement.trailingLineComment(variable).ifPresent(candidates::add);
        commentPlacement.containedComments(variable)
                .stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> candidates.stream().noneMatch(existing -> existing.comment() == comment.comment()))
                .forEach(candidates::add);
        return candidates;
    }

    private boolean commentStartsBeforeDeclarationSemicolon(
            JavaCommentTrivia comment,
            VariableDeclarator variable
    ) {
        return semicolonOwner(variable)
                .map(owner -> commentStartsBeforeFinalSemicolonInRawOwner(comment, owner))
                .orElse(false);
    }

    private boolean commentStartsBeforeFinalSemicolonInRawOwner(JavaCommentTrivia comment, Node owner) {
        String rawOwner = rawSource.raw(owner);
        int commentIndex = commentIndex(rawOwner, comment);
        int semicolonIndex = rawOwner.lastIndexOf(';');
        return commentIndex >= 0 && semicolonIndex >= 0 && commentIndex < semicolonIndex;
    }

    private int commentIndex(String rawOwner, JavaCommentTrivia comment) {
        List<String> spellings = List.of(
            comment.comment().toString(),
            "//" + comment.comment().getContent(),
            "// " + comment.comment().getContent()
        );
        return spellings.stream()
                .mapToInt(rawOwner::indexOf)
                .filter(index -> index >= 0)
                .findFirst()
                .orElse(-1);
    }

    private Optional<Node> semicolonOwner(VariableDeclarator variable) {
        Node current = variable;
        while (current.getParentNode().isPresent()) {
            current = current.getParentNode().orElseThrow();
            if (current instanceof FieldDeclaration || current instanceof ExpressionStmt) {
                return Optional.of(current);
            }
        }
        return Optional.empty();
    }
}
