package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Renders the loop-statement family: {@code for}, enhanced {@code for}, {@code while}, and {@code do}/{@code while},
 * including their header layout, empty-body shapes, braceless single-statement bodies, and the source-shape-independent
 * comment recovery those constructs need.
 *
 * <p>This helper owns everything reachable only from {@link StatementPrinter}'s loop switch arms: the {@code for} and
 * enhanced-{@code for} header reconstruction (including the iterable that owns its own method-call argument break and the
 * flat init declaration prefix), the {@code do}-body and {@code do}/{@code while} tail with the block/line comments
 * between the body, the {@code while}, and the trailing {@code ;}, the empty-body {@code header;} shapes, and the
 * braceless-loop-body handler that keeps a header-to-body comment out of the collapsed header line. The boundary exists so
 * {@link StatementPrinter}'s statement-kind dispatch can keep the other statement grammars local instead of carrying this
 * comment-heavy loop cluster inline, mirroring the injected per-kind renderers ({@code blockRenderer},
 * {@code switchStatementRenderer}) and the {@link IfStatementLayout} / {@link TryStatementLayout} StatementPrinter already
 * delegates to.
 *
 * <p>Condition, expression, type, and block formatting stay with their existing owners and are reached through the
 * callbacks injected here (for example {@code controlConditions}, {@code brokenMethodCallRenderer}, {@code compact},
 * {@code compactJoin}, {@code compactTypeLike}, {@code statementRenderer}, and {@code blockRenderer}). Width decisions
 * defer to the injected {@link LayoutWidth} and {@link FormatterOptions}. Nested statements route back through the shared
 * {@code nestedStatement} handle so a nested body gets the same raw/pragma/comment gate and switch routing as any other
 * statement, and the shared empty-body and comment-text flattening ({@code emptyBodyOwnBlockComment},
 * {@code trailingEmptyBodyBlockComment}, {@code commentText}) stays a StatementPrinter concern injected as a handle
 * because the if and simple-statement paths use it too.
 */
final class LoopStatementLayout {

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    private final RawSource rawSource;

    private final FormatterOptions options;

    private final LayoutWidth layoutWidth;

    private final ControlConditionPrinter controlConditions;

    private final JavaFormatRule<Statement> statementRenderer;

    private final JavaFormatRule<BlockStmt> blockRenderer;

    private final Function<MethodCallExpr, Doc> brokenMethodCallRenderer;

    private final Function<Node, String> compact;

    private final Function<Node, String> compactWithoutOwnComment;

    private final Function<List<? extends Node>, String> compactJoin;

    private final Function<Node, String> compactTypeLike;

    private final Function<Expression, String> compactWithOwnBlockComment;

    private final Function<AnnotationExpr, String> annotationFlatText;

    private final Function<NodeWithModifiers<?>, String> modifiers;

    private final Function<Statement, Doc> nestedStatement;

    private final Function<Doc, String> commentText;

    private final Function<Statement, Doc> emptyBodyOwnBlockComment;

    private final Function<Node, String> trailingEmptyBodyBlockComment;

    LoopStatementLayout(
            CommentTracker comments,
            JavaCommentPlacementPolicy commentPlacement,
            RawSource rawSource,
            FormatterOptions options,
            LayoutWidth layoutWidth,
            ControlConditionPrinter controlConditions,
            JavaFormatRule<Statement> statementRenderer,
            JavaFormatRule<BlockStmt> blockRenderer,
            Function<MethodCallExpr, Doc> brokenMethodCallRenderer,
            Function<Node, String> compact,
            Function<Node, String> compactWithoutOwnComment,
            Function<List<? extends Node>, String> compactJoin,
            Function<Node, String> compactTypeLike,
            Function<Expression, String> compactWithOwnBlockComment,
            Function<AnnotationExpr, String> annotationFlatText,
            Function<NodeWithModifiers<?>, String> modifiers,
            Function<Statement, Doc> nestedStatement,
            Function<Doc, String> commentText,
            Function<Statement, Doc> emptyBodyOwnBlockComment,
            Function<Node, String> trailingEmptyBodyBlockComment
    ) {
        this.comments = comments;
        this.commentPlacement = commentPlacement;
        this.rawSource = rawSource;
        this.options = options;
        this.layoutWidth = layoutWidth;
        this.controlConditions = controlConditions;
        this.statementRenderer = statementRenderer;
        this.blockRenderer = blockRenderer;
        this.brokenMethodCallRenderer = brokenMethodCallRenderer;
        this.compact = compact;
        this.compactWithoutOwnComment = compactWithoutOwnComment;
        this.compactJoin = compactJoin;
        this.compactTypeLike = compactTypeLike;
        this.compactWithOwnBlockComment = compactWithOwnBlockComment;
        this.annotationFlatText = annotationFlatText;
        this.modifiers = modifiers;
        this.nestedStatement = nestedStatement;
        this.commentText = commentText;
        this.emptyBodyOwnBlockComment = emptyBodyOwnBlockComment;
        this.trailingEmptyBodyBlockComment = trailingEmptyBodyBlockComment;
    }

    private boolean conditionCommentStartsBeforeExpression(Expression condition, Comment comment) {
        return CommentIndex.startsBefore(comment, condition);
    }

    /**
     * Renders a braceless {@code while}/{@code for}/{@code for-each} body that carries a {@code //} line comment between
     * the loop header and the body, claiming the comment exactly once and placing it the same way the {@code if}
     * close-paren path places a condition comment.
     *
     * <p>A braceless body normally collapses onto the header line ({@code while (cond) call();}), but a header-to-body
     * {@code //} comment cannot share that line (it would comment out the body). Source position decides placement: a
     * comment on the header-end line ({@code while (cond) // note}) stays inline like
     * {@link ControlConditions#closeParenTrailingLineComment}; one on its own line below the header leads the body and
     * moves above it. Either way the body breaks to an indented next line.
     *
     * <p>JavaParser attaches that gap comment to different nodes by whitespace (body's own leading trivia at
     * {@code @default}, the {@code afterNode} header expression's trailing trivia under a collapse, the
     * {@code controlStmt} orphans under an expand);
     * {@link JavaCommentPlacementPolicy#gapLineCommentsBefore(Node, Node, java.util.Collection)} recovers it from
     * whichever bucket (excluding the body's own comment) and claims it once under the body's leading slot, so exactly
     * one path prints it — neither dropped under perturbation nor duplicated at {@code @default}. Empty when no leading
     * line comment is present, leaving the caller's same-line collapse intact.
     */
    private Optional<Doc> bracelessLoopBody(Node controlStmt, Node afterNode, Statement body) {
        if (body.isBlockStmt()) {
            return Optional.empty();
        }
        List<JavaCommentTrivia> gapComments = commentPlacement.gapLineCommentsBefore(
            afterNode,
            body,
            List.of(controlStmt, afterNode)
        );
        boolean bodyOwnsLeadingLineComment = commentPlacement.leadingComment(body)
                .filter(JavaCommentTrivia::isLine)
                .filter(trivia -> !trivia.startsAfterEndOf(body))
                .isPresent();
        if (gapComments.isEmpty() && !bodyOwnsLeadingLineComment) {
            return Optional.empty();
        }
        List<Doc> headerTrailing = new ArrayList<>();
        List<Doc> aboveBody = new ArrayList<>();
        for (JavaCommentTrivia gapComment : gapComments) {
            Doc rendered = comments.comment(gapComment, body, OwnerSlot.LEADING);
            if (rendered == Doc.EMPTY) {
                continue;
            }
            if (gapComment.startsOnEndLine(afterNode)) {
                headerTrailing.add(rendered);
            } else {
                aboveBody.add(rendered);
            }
        }
        List<Doc> indented = new ArrayList<>();
        indented.add(Doc.HARD_LINE);
        for (Doc aboveComment : aboveBody) {
            indented.add(aboveComment);
            indented.add(Doc.HARD_LINE);
        }
        indented.add(statementRenderer.format(body, LayoutContext.root()));
        List<Doc> result = new ArrayList<>();
        result.add(Doc.text(" "));
        for (Doc inline : headerTrailing) {
            result.add(inline);
        }
        result.add(Doc.indent(Doc.concat(indented)));
        return Optional.of(Doc.concat(result));
    }

    Doc forEachStatement(ForEachStmt statement) {
        if (statement.getBody().isEmptyStmt()) {
            return Doc.text(
                "for ("
                    + compact.apply(statement.getVariable())
                    + " : "
                    + emptyBodyHeaderExpression(statement.getIterable(), statement.getBody())
                    + ");"
                    + trailingEmptyBodyBlockComment.apply(statement)
            );
        }
        Doc header = forEachHeader(statement);
        Optional<Doc> commentedBracelessBody = bracelessLoopBody(
            statement,
            statement.getIterable(),
            statement.getBody()
        );
        if (commentedBracelessBody.isPresent()) {
            return Doc.concat(header, commentedBracelessBody.orElseThrow());
        }
        return Doc.concat(header, Doc.text(" "), nestedStatement.apply(statement.getBody()));
    }

    /**
     * Lets the iterable own method-call argument breaks when the enhanced-for header would otherwise overflow.
     */
    private Doc forEachHeader(ForEachStmt statement) {
        String variable = forEachVariable(statement);
        Expression iterable = statement.getIterable();
        String header = "for (" + variable + " : " + compact.apply(iterable) + ")";
        if (
            // Measure the for-each header at the statement's true rendered block/type depth
            // ({@link LayoutWidth#nodeLine}) instead of the fixed BLOCK baseline.
            layoutWidth.nodeLine(statement, header + " {}") <= options.lineWidth()
            || !(iterable instanceof MethodCallExpr methodCall)
        ) {
            return Doc.text(header);
        }
        return Doc.concat(
            Doc.text("for (" + variable + " : "),
            brokenMethodCallRenderer.apply(methodCall),
            Doc.text(")")
        );
    }

    private String forEachVariable(ForEachStmt statement) {
        String raw = rawSource.raw(statement);
        int open = raw.indexOf('(');
        int colon = raw.indexOf(':', open);
        if (open < 0 || colon < open) {
            return compact.apply(statement.getVariable());
        }
        String variable = raw.substring(open + 1, colon);
        return variable.contains("/*")
            ? CommentedTokenText.tokenLine(CommentedTokenText.tokens(variable))
            : compact.apply(statement.getVariable());
    }

    private String forHeader(ForStmt statement) {
        String init = statement.getInitialization()
                .stream()
                .map(this::forHeaderExpression)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        String compare = statement.getCompare().map(this::forHeaderExpression).orElse("");
        String update = compactJoin.apply(statement.getUpdate());
        if (init.isEmpty() && compare.isEmpty() && update.isEmpty()) {
            return "for (;;)";
        }
        return "for (" + init + "; " + compare + "; " + update + ")";
    }

    Doc forStatement(ForStmt statement) {
        if (statement.getBody().isEmptyStmt()) {
            return loopWithEmptyBody(forHeader(statement), statement);
        }
        if (statement.getBody() instanceof DoStmt) {
            return Doc.concat(Doc.text(forHeader(statement) + " "), statementRenderer.format(statement.getBody(), LayoutContext.root()));
        }
        Optional<Doc> commentedBracelessBody = forHeaderEndNode(statement)
                .flatMap(afterNode -> bracelessLoopBody(statement, afterNode, statement.getBody()));
        if (commentedBracelessBody.isPresent()) {
            return Doc.concat(Doc.text(forHeader(statement)), commentedBracelessBody.orElseThrow());
        }
        return Doc.concat(Doc.text(forHeader(statement) + " "), nestedStatement.apply(statement.getBody()));
    }

    /**
     * Names the last node of a {@code for} header so the gap-comment recovery can bound "comments before the body" from
     * the last header element it follows. The update, then the comparison, then the initialization run last to first; a
     * fully-empty {@code for (;;)} header has no node, so the gap recovery is skipped and the body keeps its own-comment
     * handling.
     */
    private Optional<Node> forHeaderEndNode(ForStmt statement) {
        if (!statement.getUpdate().isEmpty()) {
            return Optional.of(statement.getUpdate().get(statement.getUpdate().size() - 1));
        }
        if (statement.getCompare().isPresent()) {
            return statement.getCompare().map(Node.class::cast);
        }
        if (!statement.getInitialization().isEmpty()) {
            return Optional.of(statement.getInitialization().get(statement.getInitialization().size() - 1));
        }
        return Optional.empty();
    }

    Doc whileStatement(WhileStmt statement) {
        if (statement.getBody().isEmptyStmt()) {
            return Doc.text(
                "while ("
                    + emptyBodyHeaderExpression(statement.getCondition(), statement.getBody())
                    + ");"
                    + trailingEmptyBodyBlockComment.apply(statement)
            );
        }
        Optional<Doc> commentedBody = commentedLoopBody(statement, statement.getBody());
        if (commentedBody.isPresent()) {
            return Doc.concat(
                Doc.text("while "),
                controlConditions.controlCondition(
                    statement.getCondition(),
                    "while (",
                    ") {}",
                    layoutWidth::blockStatement
                ),
                commentedBody.orElseThrow()
            );
        }
        Doc whileHeader = Doc.concat(
            Doc.text("while "),
            controlConditions.controlCondition(
                statement.getCondition(),
                "while (",
                ") {}",
                layoutWidth::blockStatement
            )
        );
        Optional<Doc> commentedBracelessBody = bracelessLoopBody(
            statement,
            statement.getCondition(),
            statement.getBody()
        );
        if (commentedBracelessBody.isPresent()) {
            return Doc.concat(whileHeader, commentedBracelessBody.orElseThrow());
        }
        return Doc.concat(whileHeader, Doc.text(" "), nestedStatement.apply(statement.getBody()));
    }

    /**
     * Keeps an inline block comment attached to a single-statement loop body.
     *
     * <p>When the body starts on the header line the comment remains inline; when it starts later, the comment and body
     * move to an indented next line.
     */
    private Optional<Doc> commentedLoopBody(Node loop, Statement body) {
        if (body.isBlockStmt()) {
            return Optional.empty();
        }
        Doc comment = comments.ownComment(body, BlockComment.class::isInstance);
        if (comment == Doc.EMPTY) {
            return Optional.empty();
        }
        Doc commentedStatement = Doc.concat(comment, Doc.text(" "), statementRenderer.format(body, LayoutContext.root()));
        if (CommentIndex.sameBeginLine(loop, body)) {
            return Optional.of(Doc.concat(Doc.text(" "), commentedStatement));
        }
        return Optional.of(Doc.indent(Doc.concat(Doc.HARD_LINE, commentedStatement)));
    }

    Doc doStatement(DoStmt statement) {
        if (statement.getBody().isEmptyStmt()) {
            String condition = compact.apply(statement.getCondition());
            Doc bodyComment = emptyBodyOwnBlockComment.apply(statement.getBody());
            Doc conditionComment = comments.ownComment(statement.getCondition(), BlockComment.class::isInstance);
            if (bodyComment != Doc.EMPTY || conditionComment != Doc.EMPTY) {
                String comment = bodyComment != Doc.EMPTY ? commentText.apply(bodyComment) : commentText.apply(conditionComment);
                return Doc.text("do; while (" + comment + " " + condition + ");");
            }
            return Doc.text("do; while (" + condition + ");");
        }
        boolean bracelessDoBodyBroke = bracelessBodyBrokeOnLeadingComment(statement.getBody());
        return Doc.concat(
            Doc.text("do "),
            doBody(statement.getBody()),
            doWhileTail(statement, bracelessDoBodyBroke)
        );
    }

    /**
     * Whether a braceless do-body broke onto its own line(s) because of a leading {@code //} comment. Mirrors the
     * braceless-body break predicate {@link #bracelessLoopBody} and {@code StatementPrinter} use, so the {@code while}
     * tail moves to its own line rather than cramming onto the body's last line.
     */
    private boolean bracelessBodyBrokeOnLeadingComment(Statement body) {
        if (body.isBlockStmt()) {
            return false;
        }
        return commentPlacement.leadingComment(body)
                .filter(JavaCommentTrivia::isLine)
                .filter(trivia -> !trivia.startsAfterEndOf(body))
                .isPresent();
    }

    private Doc doBody(Statement body) {
        if (!body.isBlockStmt()) {
            return nestedStatement.apply(body);
        }
        Doc leadingBlockComment = comments.ownComment(body, BlockComment.class::isInstance);
        if (leadingBlockComment == Doc.EMPTY) {
            return nestedStatement.apply(body);
        }
        return Doc.concat(leadingBlockComment, Doc.text(" "), blockRenderer.format(body.asBlockStmt(), LayoutContext.root()));
    }

    private Doc doWhileTail(DoStmt statement, boolean bracelessDoBodyBroke) {
        Doc trailing = doWhileTrailingLineComment(statement);
        Doc beforeWhileComment = doWhileBeforeWhileBlockComment(statement);
        // A braceless do-body that broke onto its own line(s) (a leading comment) pushes `while` to its own line;
        // otherwise it stays on the body's line (`} while (...)` or the collapsed `body; while (...)`).
        Doc lead = bracelessDoBodyBroke ? Doc.HARD_LINE : Doc.text(" ");
        if (beforeWhileComment != Doc.EMPTY) {
            return Doc.concat(
                lead,
                Doc.text(
                    commentText.apply(beforeWhileComment)
                        + " while ("
                        + compactWithoutOwnComment.apply(statement.getCondition())
                        + ");"
                ),
                trailing
            );
        }
        return Doc.concat(
            lead,
            Doc.text("while "),
            controlConditions.controlCondition(
                statement.getCondition(),
                "while (",
                ") {}",
                layoutWidth::blockStatement
            ),
            Doc.text(";"),
            trailing
        );
    }

    /**
     * Recovers the line comment that trails a {@code do ... while (cond);} statement after the closing {@code ;}.
     *
     * <p>At {@code @default} the comment is the {@link DoStmt}'s own trivia, rendered by {@link StatementRuleEnvelope}'s
     * shared trailing slot. When the body spans multiple source lines JavaParser attaches it to the {@code while}
     * condition instead, where the condition renderer would drop it, so this reclaims it through the layout-independent
     * {@link CommentTracker#trailingComment(Node)} anchor — bound to the stable {@code (condition, TRAILING)} slot and
     * deferred as a {@code lineSuffix} after the {@code ;} (like
     * {@link StatementPrinter#expressionStatementTrailingComment(ExpressionStmt)} and the {@code try} renderer). Emptiness
     * is decided from the recorded owner, so the comment survives whichever ranked layout wins without being dropped or
     * double-printed; in the single-line-body shape the slot is unowned and this adds nothing.
     */
    private Doc doWhileTrailingLineComment(DoStmt statement) {
        return comments.trailingComment(statement.getCondition());
    }

    /**
     * Recovers the block comment that sits between a {@code do} body and its {@code while}
     * ({@code } /* note *}{@code / while (...)}), independent of source shape.
     *
     * <p>At {@code @default} JavaParser attaches it as the condition's own comment, so the condition own path renders it.
     * A whitespace perturbation that pushes the comment onto its own line re-buckets it as a {@link DoStmt} orphan; this
     * query then recovers the {@code do} orphan block comments that begin before the condition. The rendering stays the
     * same inline {@code note while (...)} shape used for the own-comment case.
     */
    private Doc doWhileBeforeWhileBlockComment(DoStmt statement) {
        Optional<Comment> conditionComment = statement.getCondition().getComment().filter(
            BlockComment.class::isInstance
        );
        if (
            conditionComment.isPresent()
            && conditionCommentStartsBeforeExpression(statement.getCondition(), conditionComment.orElseThrow())
        ) {
            return comments.comment(conditionComment.orElseThrow());
        }
        return Doc.concat(comments.blockCommentsBefore(List.of(statement), statement.getCondition()));
    }

    /**
     * Prints a loop or if branch whose body is a semicolon.
     *
     * <p>Block comments attached to an empty body are the only visible content in that body, so they either move before
     * the header or stay after the semicolon depending on how JavaParser exposes them for the original source.
     */
    private Doc loopWithEmptyBody(String header, Node statement) {
        Doc bodyComment =
            statement instanceof ForStmt forStmt ? emptyBodyOwnBlockComment.apply(forStmt.getBody()) : Doc.EMPTY;
        if (bodyComment == Doc.EMPTY) {
            return Doc.text(header + ";" + trailingEmptyBodyBlockComment.apply(statement));
        }
        return Doc.concat(
            bodyComment,
            Doc.HARD_LINE,
            Doc.text(header + ";" + trailingEmptyBodyBlockComment.apply(statement))
        );
    }

    private String emptyBodyHeaderExpression(Expression expression, Statement body) {
        Doc bodyComment = emptyBodyOwnBlockComment.apply(body);
        if (bodyComment == Doc.EMPTY) {
            return compact.apply(expression);
        }
        return compact.apply(expression) + " " + commentText.apply(bodyComment);
    }

    private String forHeaderExpression(Expression expression) {
        if (expression instanceof BinaryExpr binaryExpr) {
            return compact.apply(binaryExpr.getLeft())
                + " "
                + binaryExpr.getOperator().asString()
                + " "
                + compactWithOwnBlockComment.apply(binaryExpr.getRight());
        }
        if (
            expression instanceof VariableDeclarationExpr variableDeclaration
            && variableDeclaration.getVariables().size() == 1
        ) {
            VariableDeclarator variable = variableDeclaration.getVariables().get(0);
            return forInitDeclarationPrefix(variableDeclaration)
                + compactTypeLike.apply(variable.getType())
                + " "
                + variable.getNameAsString()
                + variable.getInitializer().map(initializer -> " = " + compact.apply(initializer)).orElse("");
        }
        return compact.apply(expression);
    }

    /**
     * Builds the flat annotation/modifier prefix for a single-declarator {@code for}-loop init declaration.
     *
     * <p>The for-header reconstructs the declaration as flat text (it never wraps), so the declaration-level annotations
     * and modifiers that {@link #forHeaderExpression} would otherwise drop must be re-emitted inline here. Annotations
     * use the shared inline annotation text and modifiers use the shared modifier-string policy so a {@code final}
     * modifier or an annotation such as {@code @SuppressWarnings("unchecked")} on the init variable survives instead of
     * being silently discarded.
     */
    private String forInitDeclarationPrefix(VariableDeclarationExpr declaration) {
        String annotations = declaration.getAnnotations()
                .stream()
                .map(annotationFlatText)
                .reduce((left, right) -> left + " " + right)
                .map(text -> text + " ")
                .orElse("");
        return annotations + modifiers.apply(declaration);
    }
}
