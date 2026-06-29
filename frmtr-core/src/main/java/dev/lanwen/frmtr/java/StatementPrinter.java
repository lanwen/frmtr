package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.stmt.AssertStmt;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.LabeledStmt;
import com.github.javaparser.ast.stmt.LocalClassDeclarationStmt;
import com.github.javaparser.ast.stmt.LocalRecordDeclarationStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.stmt.YieldStmt;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Renders structured Java statements.
 *
 * <p>This helper owns statement dispatch and the source-sensitive statement details around if/else chains, loops, switch
 * statements, try/catch/finally, labels, empty bodies, and simple semicolon statements. The boundary exists so
 * {@link StatementRuleEnvelope} can keep formatter pragma raw-passes and leading/trailing statement comment attachment
 * in the outer rule envelope. Switch statements are part of the normal statement-kind decision here, while their labels,
 * guards, rule bodies, and statement groups stay delegated to {@link SwitchPrinter}'s switch-entry renderer.
 *
 * <p>Expression, type, local-variable, declaration-body, and block formatting stay with their existing owners and are
 * called through callbacks. Representative coverage pairs for this boundary include
 * {@code frmtr-core/src/test/resources/format/if-else-chain/input.java} with
 * {@code frmtr-core/src/test/resources/format/if-else-chain/frmtr-default.output.java},
 * {@code frmtr-core/src/test/resources/format/for-loop/input.java} with
 * {@code frmtr-core/src/test/resources/format/for-loop/frmtr-default.output.java},
 * {@code frmtr-core/src/test/resources/format/while-do/input.java} with
 * {@code frmtr-core/src/test/resources/format/while-do/frmtr-default.output.java},
 * {@code frmtr-core/src/test/resources/format/empty-statement/input.java} with
 * {@code frmtr-core/src/test/resources/format/empty-statement/frmtr-default.output.java}, and
 * {@code frmtr-core/src/test/resources/format/comment-preservation-if-statement/input.java}
 * with
 * {@code frmtr-core/src/test/resources/format/comment-preservation-if-statement/frmtr-default.output.java}.
 */
final class StatementPrinter {

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    private final RawSource rawSource;

    private final SourceShapePolicy sourceShapePolicy;

    private final FormatterOptions options;

    private final LayoutWidth layoutWidth;

    private final JavaFormatRule<Statement> statementRenderer;

    private final JavaFormatRule<SwitchStmt> switchStatementRenderer;

    private final JavaFormatRule<BlockStmt> blockRenderer;

    private final BiFunction<BlockStmt, Doc, Doc> blockWithLeadingRenderer;

    private final JavaFormatRule<BodyDeclaration<?>> bodyRenderer;

    private final JavaFormatRule<Expression> expressionRenderer;

    private final ExpressionTailRenderer expressionWithTailRenderer;

    private final Function<AssignExpr, Doc> assignmentStatementRenderer;

    private final BiFunction<Expression, LayoutWidth.LineBudget, Doc> returnStatementRenderer;

    private final BiFunction<ObjectCreationExpr, String, Doc> objectCreationWithSuffix;

    private final JavaFormatRule<VariableDeclarationExpr> variableDeclarationRenderer;

    private final JavaFormatRule<VariableDeclarationExpr> variableDeclarationStatementRenderer;

    private final Function<Parameter, String> parameterText;

    private final Function<Node, String> compact;

    private final Function<Node, String> compactWithoutOwnComment;

    private final Function<List<? extends Node>, String> compactJoin;

    private final Function<Node, String> compactTypeLike;

    private final Function<List<? extends Node>, String> compactJoinTypeLike;

    private final Function<NodeWithModifiers<?>, String> modifiers;

    private final Function<AnnotationExpr, String> annotationFlatText;

    private final HuggableArgumentsRenderer huggableBlockLambdaArguments;

    private final BiFunction<MethodCallExpr, ExpressionStmt, Optional<Doc>> sourceMultilineMethodCallStatementRenderer;

    private final BiFunction<MethodCallExpr, LayoutWidth.LineBudget, Optional<Doc>> forcedMethodCallChainRenderer;

    private final BiFunction<MethodCallExpr, LayoutWidth.LineBudget, Doc> forcedMethodCallWithSemicolonRenderer;

    private final Function<MethodCallExpr, Doc> brokenMethodCallRenderer;

    private final Predicate<MethodCallExpr> methodCallChainHasComments;

    private final Predicate<MethodCallExpr> methodCallChainHasFinalTrailingLineComment;

    private final Predicate<MethodCallExpr> methodCallChainIsSourceMultiline;

    private final Predicate<MethodCallExpr> methodCallChainRootIsObjectCreation;

    private final Predicate<MethodCallExpr> methodCallChainRootIsFieldAccess;

    private final Function<Expression, Doc> ifConditionRenderer;

    private final ControlConditionPrinter controlConditions;

    private final Function<Expression, String> compactWithOwnBlockComment;

    private final Function<Node, Doc> sameLineBlockCommentBeforeNode;

    private final BiFunction<NodeList<Expression>, Doc, Doc> methodCallArgumentList;

    private final CommentedExpressionListPrinter commentedExpressionLists;

    private final ToIntFunction<String> currentIndentedWidth;

    StatementPrinter(
            CommentTracker comments,
            JavaCommentPlacementPolicy commentPlacement,
            RawSource rawSource,
            SourceShapePolicy sourceShapePolicy,
            FormatterOptions options,
            LayoutWidth layoutWidth,
            JavaFormatRule<Statement> statementRenderer,
            JavaFormatRule<SwitchStmt> switchStatementRenderer,
            JavaFormatRule<BlockStmt> blockRenderer,
            BiFunction<BlockStmt, Doc, Doc> blockWithLeadingRenderer,
            JavaFormatRule<BodyDeclaration<?>> bodyRenderer,
            JavaFormatRule<Expression> expressionRenderer,
            ExpressionTailRenderer expressionWithTailRenderer,
            Function<AssignExpr, Doc> assignmentStatementRenderer,
            BiFunction<Expression, LayoutWidth.LineBudget, Doc> returnStatementRenderer,
            BiFunction<ObjectCreationExpr, String, Doc> objectCreationWithSuffix,
            JavaFormatRule<VariableDeclarationExpr> variableDeclarationRenderer,
            JavaFormatRule<VariableDeclarationExpr> variableDeclarationStatementRenderer,
            Function<Parameter, String> parameterText,
            Function<Node, String> compact,
            Function<Node, String> compactWithoutOwnComment,
            Function<List<? extends Node>, String> compactJoin,
            Function<Node, String> compactTypeLike,
            Function<List<? extends Node>, String> compactJoinTypeLike,
            Function<NodeWithModifiers<?>, String> modifiers,
            Function<AnnotationExpr, String> annotationFlatText,
            HuggableArgumentsRenderer huggableBlockLambdaArguments,
            BiFunction<MethodCallExpr, ExpressionStmt, Optional<Doc>> sourceMultilineMethodCallStatementRenderer,
            BiFunction<MethodCallExpr, LayoutWidth.LineBudget, Optional<Doc>> forcedMethodCallChainRenderer,
            BiFunction<MethodCallExpr, LayoutWidth.LineBudget, Doc> forcedMethodCallWithSemicolonRenderer,
            Function<MethodCallExpr, Doc> brokenMethodCallRenderer,
            Predicate<MethodCallExpr> methodCallChainHasComments,
            Predicate<MethodCallExpr> methodCallChainHasFinalTrailingLineComment,
            Predicate<MethodCallExpr> methodCallChainIsSourceMultiline,
            Predicate<MethodCallExpr> methodCallChainRootIsObjectCreation,
            Predicate<MethodCallExpr> methodCallChainRootIsFieldAccess,
            Function<Expression, Doc> ifConditionRenderer,
            ControlConditionPrinter controlConditions,
            Function<Expression, String> compactWithOwnBlockComment,
            Function<Node, Doc> sameLineBlockCommentBeforeNode,
            BiFunction<NodeList<Expression>, Doc, Doc> methodCallArgumentList,
            CommentedExpressionListPrinter commentedExpressionLists,
            ToIntFunction<String> currentIndentedWidth
    ) {
        this.comments = comments;
        this.commentPlacement = commentPlacement;
        this.rawSource = rawSource;
        this.sourceShapePolicy = sourceShapePolicy;
        this.options = options;
        this.layoutWidth = layoutWidth;
        this.statementRenderer = statementRenderer;
        this.switchStatementRenderer = switchStatementRenderer;
        this.blockRenderer = blockRenderer;
        this.blockWithLeadingRenderer = blockWithLeadingRenderer;
        this.bodyRenderer = bodyRenderer;
        this.expressionRenderer = expressionRenderer;
        this.expressionWithTailRenderer = expressionWithTailRenderer;
        this.assignmentStatementRenderer = assignmentStatementRenderer;
        this.returnStatementRenderer = returnStatementRenderer;
        this.objectCreationWithSuffix = objectCreationWithSuffix;
        this.variableDeclarationRenderer = variableDeclarationRenderer;
        this.variableDeclarationStatementRenderer = variableDeclarationStatementRenderer;
        this.parameterText = parameterText;
        this.compact = compact;
        this.compactWithoutOwnComment = compactWithoutOwnComment;
        this.compactJoin = compactJoin;
        this.compactTypeLike = compactTypeLike;
        this.compactJoinTypeLike = compactJoinTypeLike;
        this.modifiers = modifiers;
        this.annotationFlatText = annotationFlatText;
        this.huggableBlockLambdaArguments = huggableBlockLambdaArguments;
        this.sourceMultilineMethodCallStatementRenderer = sourceMultilineMethodCallStatementRenderer;
        this.forcedMethodCallChainRenderer = forcedMethodCallChainRenderer;
        this.forcedMethodCallWithSemicolonRenderer = forcedMethodCallWithSemicolonRenderer;
        this.brokenMethodCallRenderer = brokenMethodCallRenderer;
        this.methodCallChainHasComments = methodCallChainHasComments;
        this.methodCallChainHasFinalTrailingLineComment = methodCallChainHasFinalTrailingLineComment;
        this.methodCallChainIsSourceMultiline = methodCallChainIsSourceMultiline;
        this.methodCallChainRootIsObjectCreation = methodCallChainRootIsObjectCreation;
        this.methodCallChainRootIsFieldAccess = methodCallChainRootIsFieldAccess;
        this.ifConditionRenderer = ifConditionRenderer;
        this.controlConditions = controlConditions;
        this.compactWithOwnBlockComment = compactWithOwnBlockComment;
        this.sameLineBlockCommentBeforeNode = sameLineBlockCommentBeforeNode;
        this.methodCallArgumentList = methodCallArgumentList;
        this.commentedExpressionLists = commentedExpressionLists;
        this.currentIndentedWidth = currentIndentedWidth;
    }

    /**
     * Renders the structured body of a statement after StatementRuleEnvelope has chosen formatted output.
     *
     * <p>Raw pragma handling stays in {@link StatementRuleEnvelope} because formatter off/on state changes which later
     * statements format. This method only decides the formatted content once the outer gate has decided the statement
     * should not be printed from raw source.
     */
    Doc statement(Statement statement) {
        return statement(statement, LayoutWidth.LineBudget.BLOCK);
    }

    Doc statement(Statement statement, LayoutWidth.LineBudget lineBudget) {
        return switch (statement) {
            case BlockStmt blockStmt -> blockRenderer.format(blockStmt);
            case ReturnStmt returnStmt -> returnStatement(returnStmt, lineBudget);
            case ThrowStmt throwStmt -> throwStatement(throwStmt);
            case YieldStmt yieldStmt -> yieldStatement(yieldStmt);
            case ExplicitConstructorInvocationStmt constructorInvocation -> Doc.concat(
                explicitConstructorInvocation(constructorInvocation),
                Doc.text(";")
            );
            case ExpressionStmt expressionStmt -> expressionStatement(expressionStmt, lineBudget);
            case EmptyStmt ignored -> Doc.text(";");
            case AssertStmt assertStmt -> assertStatement(assertStmt);
            case BreakStmt breakStmt -> breakStatement(breakStmt);
            case ContinueStmt continueStmt -> continueStatement(continueStmt);
            case LabeledStmt labeledStmt -> labeledStatement(labeledStmt);
            case LocalClassDeclarationStmt localClassDeclaration -> bodyRenderer.format(
                localClassDeclaration.getClassDeclaration()
            );
            case LocalRecordDeclarationStmt localRecordDeclaration -> bodyRenderer.format(
                localRecordDeclaration.getRecordDeclaration()
            );
            case IfStmt ifStmt -> ifStatement(ifStmt);
            case WhileStmt whileStmt -> whileStatement(whileStmt);
            case DoStmt doStmt -> doStatement(doStmt);
            case TryStmt tryStmt -> tryStatement(tryStmt);
            case SynchronizedStmt synchronizedStmt -> Doc.concat(
                Doc.text("synchronized "),
                controlConditions.controlCondition(
                    synchronizedStmt.getExpression(),
                    "synchronized (",
                    ") {}",
                    layoutWidth::blockStatement
                ),
                Doc.text(" "),
                blockRenderer.format(synchronizedStmt.getBody())
            );
            case ForStmt forStmt -> forStatement(forStmt);
            case ForEachStmt forEachStmt -> forEachStatement(forEachStmt);
            case SwitchStmt switchStmt -> switchStatementRenderer.format(switchStmt);
            default -> Doc.text(compact.apply(statement));
        };
    }

    private Doc breakStatement(BreakStmt statement) {
        Doc leadingBlockComment = comments.ownComment(statement, BlockComment.class::isInstance);
        Doc body = Doc.text(
            "break"
                + statement.getLabel().map(label -> " " + label.asString()).orElse("")
                + ";"
                + trailingStatementBlockComment(statement)
        );
        if (leadingBlockComment == Doc.EMPTY) {
            return body;
        }
        // A single-line block comment renders inline as `/* note */ break;`; commentText flattens that Doc.Text to a
        // string. A multi-line block comment renders as a Doc.Concat that commentText cannot flatten, so place it on its
        // own line(s) above the statement (the ordinary leading-comment shape) instead of dropping it and leaving a
        // stray space.
        if (leadingBlockComment instanceof Doc.Text) {
            return Doc.concat(Doc.text(commentText(leadingBlockComment) + " "), body);
        }
        return Doc.concat(leadingBlockComment, Doc.HARD_LINE, body);
    }

    private Doc continueStatement(ContinueStmt statement) {
        return Doc.text("continue" + statement.getLabel().map(this::continueLabel).orElse("") + ";");
    }

    private Doc assertStatement(AssertStmt statement) {
        String message = statement.getMessage().map(expression -> " : " + compact.apply(expression)).orElse("");
        return Doc.text("assert " + compactWithOwnBlockComment.apply(statement.getCheck()) + message + ";");
    }

    private String trailingStatementBlockComment(Statement statement) {
        String raw = rawSource.raw(statement);
        int commentStart = raw.indexOf("/*");
        int semicolon = raw.lastIndexOf(';');
        if (commentStart < 0 || semicolon < commentStart) {
            return "";
        }
        int commentEnd = raw.indexOf("*/", commentStart);
        if (commentEnd < 0 || commentEnd > semicolon) {
            return "";
        }
        return " " + raw.substring(commentStart, commentEnd + 2);
    }

    private String continueLabel(com.github.javaparser.ast.expr.SimpleName label) {
        Doc labelComment = comments.ownComment(label, BlockComment.class::isInstance);
        return labelComment == Doc.EMPTY
            ? " " + label.asString()
            : " " + commentText(labelComment) + " " + label.asString();
    }

    private Doc returnStatement(ReturnStmt statement, LayoutWidth.LineBudget lineBudget) {
        return statement.getExpression()
                .map(expression -> returnStatementRenderer.apply(expression, lineBudget))
                .orElse(Doc.text("return;" + trailingStatementBlockComment(statement)));
    }

    private Doc throwStatement(ThrowStmt statement) {
        Expression thrown = statement.getExpression();
        if (thrown instanceof ObjectCreationExpr objectCreation) {
            return Doc.concat(
                Doc.text("throw "),
                objectCreationWithSuffix.apply(objectCreation, ";"),
                throwObjectCreationTrailingComment(statement, objectCreation)
            );
        }
        return Doc.concat(
            Doc.text("throw "),
            expressionWithTailRenderer.render(thrown, ExpressionTail.SEMICOLON, LayoutWidth.LineBudget.BLOCK)
        );
    }

    /**
     * Recovers a line comment that trails {@code throw new X(...);} on the statement's end line.
     *
     * <p>When the constructor argument list re-wraps one-argument-per-line (because the call exceeds the line width, or
     * because the source was already multi-line), {@link CommentedExpressionListPrinter} renders the broken list but
     * deliberately leaves a comment that sits after the completed call to the enclosing syntax — its last-argument gap
     * keeps {@code call(arg) // note} comments out so chain and statement printers own them. For an
     * {@code ExpressionStmt} that owner is {@link #expressionStatementTrailingComment(ExpressionStmt)}; the analogous
     * {@code MethodCallExpr} statement form recovers the same comment through
     * {@code MethodCallPrinter#finalTrailingLineComments}. A {@code throw new X(...)} had no such recovery, so the
     * trailing {@code //} comment vanished once the list broke.
     *
     * <p>The comment is offered under the throw statement's {@link OwnerSlot#TRAILING} slot and rendered inline as a
     * {@code lineSuffix} after the {@code ;}, claimed once. JavaParser parks it on a different node depending on the
     * source shape, so this gathers both shapes the {@link StatementRuleEnvelope}'s own
     * {@link CommentTracker#trailingLineComment(Node)} (which sees only the {@code ThrowStmt}'s own trivia) misses:
     *
     * <ul>
     *   <li>the original wide {@code throw new X(arg,} / {@code arg); // note} pre-wrap source attaches the comment to
     *       the last constructor argument's interior node, so it surfaces as a contained line comment of the object
     *       creation that begins after the whole creation on its end line; and</li>
     *   <li>after this fix breaks the list, the re-emitted {@code ); // note} closing line re-parses with the comment
     *       parked as a free orphan of the enclosing block on the statement's end line. Recovering that orphan here too
     *       (claimed before the block interleaver would place it on its own line) keeps the inline form stable across a
     *       re-format instead of drifting the comment below {@code );}.</li>
     * </ul>
     */
    private Doc throwObjectCreationTrailingComment(ThrowStmt statement, ObjectCreationExpr objectCreation) {
        Doc recovered = commentPlacement.containedComments(objectCreation)
                .stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.startsAfterNodeOnSameLine(objectCreation))
                .map(comment -> comments.comment(comment, statement, OwnerSlot.TRAILING))
                .filter(comment -> comment != Doc.EMPTY)
                .findFirst()
                .or(() -> throwStatementOrphanTrailingComment(statement))
                .orElse(Doc.EMPTY);
        return recovered == Doc.EMPTY ? Doc.EMPTY : Doc.lineSuffix(Doc.concat(Doc.text(" "), recovered));
    }

    /**
     * Recovers the throw-statement trailing comment in the re-parse shape where JavaParser leaves it as a free orphan of
     * the enclosing block, attached to nothing, sitting on the throw statement's end line just after the closing
     * {@code );}. Claiming it under the throw statement here, before the block's source-order interleaver would emit it
     * on its own line, is what keeps the inline {@code lineSuffix} placement idempotent.
     */
    private Optional<Doc> throwStatementOrphanTrailingComment(ThrowStmt statement) {
        return statement.getParentNode()
                .map(commentPlacement::orphanComments)
                .stream()
                .flatMap(List::stream)
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.startsAfterNodeOnSameLine(statement))
                .map(comment -> comments.comment(
                    comment,
                    statement,
                    OwnerSlot.TRAILING
                ))
                .filter(comment -> comment != Doc.EMPTY)
                .findFirst();
    }

    private Doc labeledStatement(LabeledStmt statement) {
        Doc label = Doc.text(statement.getLabel().asString() + ": ");
        List<String> leadingComments = labeledStatementLeadingComments(statement);
        if (!leadingComments.isEmpty()) {
            consumeLabeledBodyLeadingLineComment(statement.getStatement());
        }
        List<Doc> recovered = recoveredLabeledLeadingComments(statement, leadingComments);
        Doc labeledBody = labeledStatementBody(statement.getStatement());
        Doc body = Doc.concat(label, labeledBody);
        List<Doc> leading = new ArrayList<>(leadingComments.stream().map(Doc::text).toList());
        leading.addAll(recovered);
        if (leading.isEmpty()) {
            return body;
        }
        return Doc.concat(Doc.join(Doc.HARD_LINE, leading), Doc.HARD_LINE, body);
    }

    private void consumeLabeledBodyLeadingLineComment(Statement statement) {
        // Suppress only a genuinely-LEADING own line comment on the labeled body so the raw-slice leading path does not
        // double-print it. A line comment that starts after the body ends is the body's own TRAILING comment, not a
        // leading one (under a whitespace collapse a following statement's before-label line comment re-buckets onto the
        // previous sibling's body as exactly such a trailing comment); claiming it here would drop it, since the body
        // renderer reads it back via trailingLineComment. The startsAfterEndOf guard leaves the trailing comment unclaimed.
        comments.ownComment(
            statement,
            comment -> comment instanceof LineComment && !CommentIndex.startsAfterEndOf(statement, comment)
        );
    }

    /**
     * {@code @collapsed}-only orphan-recovery fallback for a labeled statement's leading comments.
     *
     * <p>The two existing paths cover {@code @default}: {@link StatementRuleEnvelope} renders the {@link LabeledStmt}'s
     * own/adjacent leading comments, and {@link #labeledStatementLeadingComments(LabeledStmt)} reproduces — verbatim from
     * the raw source slice between the {@code :} and the nested statement — the leading comment lines (preserving author
     * blank-line groups the AST cannot reconstruct). Under a whitespace collapse those leading comments re-bucket onto the
     * {@code LabeledStmt} orphan pool, the label {@code SimpleName}'s own comment, and the nested
     * {@link com.github.javaparser.ast.stmt.ForEachStmt ForEachStmt}'s own/orphan comments, and the now single-line raw
     * slice no longer exposes them as comment-only lines, so they drop.
     *
     * <p>This fallback contributes only the comments the other two paths miss. It selects, source-order between the label
     * and the body, the comments JavaParser parked on those buckets and then applies two dedupe seams:
     *
     * <ol>
     *   <li><strong>string guard against the raw slice.</strong> The raw-slice path returns {@code List<String>} and never
     *       claims through {@link CommentTracker}, so it cannot be deduped by comment identity. Any candidate whose
     *       normalized text the raw slice already produced for this statement is excluded, comparing with the same
     *       normalization the comment-presence net uses. At {@code @default} the raw slice produces every leading comment,
     *       so this guard removes all candidates and the leading block is unchanged — {@code @default} stays
     *       byte-identical by construction.
     *   <li><strong>identity claim.</strong> Each surviving candidate is claimed by {@link CommentTracker#comment}, which
     *       renders an already-claimed comment (e.g. one the envelope path printed as the {@code LabeledStmt}'s own
     *       leading comment) as empty, so the envelope-path overlap is not double-printed.
     * </ol>
     */
    private List<Doc> recoveredLabeledLeadingComments(LabeledStmt statement, List<String> rawSliceComments) {
        Set<String> alreadyRendered = rawSliceComments.stream()
                .map(StatementPrinter::normalizeCommentText)
                .collect(java.util.stream.Collectors.toSet());
        List<Doc> recovered = new ArrayList<>();
        for (JavaCommentTrivia trivia : commentPlacement.gapCommentsBetween(
            statement.getLabel(),
            statement.getStatement(),
            List.of(statement, statement.getLabel(), statement.getStatement())
        )) {
            if (alreadyRendered.contains(normalizeCommentText(trivia.comment()))) {
                continue;
            }
            Doc rendered = comments.comment(trivia);
            if (rendered != Doc.EMPTY) {
                recovered.add(rendered);
            }
        }
        return recovered;
    }

    /**
     * Normalizes a comment's text to the same key the comment-presence net compares with, so the raw-slice string dedupe
     * and the recovered-trivia dedupe agree. Mirrors {@code CommentPresenceDiagnosticTest.normalizeRawComment}: a line
     * comment becomes its text after {@code //}, stripped; a block comment becomes its inner non-blank lines, each with a
     * leading {@code *} removed and stripped, joined by newlines.
     */
    private static String normalizeCommentText(Comment comment) {
        if (comment instanceof LineComment) {
            return normalizeCommentText("//" + comment.getContent());
        }
        return normalizeCommentText("/*" + comment.getContent() + "*/");
    }

    private static String normalizeCommentText(String raw) {
        String text = raw.strip();
        if (text.startsWith("//")) {
            return text.substring(2).strip();
        }
        String inner = text;
        if (inner.startsWith("/*")) {
            inner = inner.substring(2);
        }
        if (inner.endsWith("*/")) {
            inner = inner.substring(0, inner.length() - 2);
        }
        List<String> lines = new ArrayList<>();
        for (String rawLine : inner.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.startsWith("*")) {
                line = line.substring(1).strip();
            }
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return String.join("\n", lines);
    }

    private Doc labeledStatementBody(Statement statement) {
        if (
            statement instanceof ForEachStmt forEachStmt
            && forEachStmt.getBody().isBlockStmt()
            && forEachStmt.getBody().asBlockStmt().getStatements().isEmpty()
            && forEachStmt.getBody().asBlockStmt().getOrphanComments().isEmpty()
        ) {
            Doc emptyFor = Doc.text(
                "for ("
                    + compact.apply(forEachStmt.getVariable())
                    + " : "
                    + compact.apply(forEachStmt.getIterable())
                    + ") {}"
            );
            // This fast path renders an empty labeled for-loop body directly instead of routing through the statement
            // envelope, so it must still emit the ForEachStmt's own trailing line comment (e.g. `loop: for (...) {} //
            // note`) that the bypassed envelope would otherwise render. trailingLineComment claims the comment once, so
            // it is not double-printed when the idempotence pass re-attaches it to the rebuilt LabeledStmt and renders
            // it through the envelope. With no such comment this returns the byte-identical `for (...) {}` text.
            Doc trailing = comments.trailingLineComment(forEachStmt);
            return trailing == Doc.EMPTY ? emptyFor : Doc.concat(emptyFor, Doc.text(" "), trailing);
        }
        if (statement instanceof BlockStmt blockStmt) {
            return blockRenderer.format(blockStmt);
        }
        return statementRenderer.format(statement);
    }

    private List<String> labeledStatementLeadingComments(LabeledStmt statement) {
        String raw = rawSource.raw(statement);
        int colon = raw.indexOf(':');
        if (colon < 0) {
            return List.of();
        }
        String labelBody = raw.substring(colon + 1);
        int statementStart = labeledNestedStatementStart(labelBody);
        if (statementStart < 0) {
            return List.of();
        }
        String comments = labelBody.substring(0, statementStart);
        List<String> lines = new ArrayList<>();
        for (String line : comments.split("\\R", -1)) {
            String stripped = line.strip();
            if (stripped.isEmpty()) {
                if (!lines.isEmpty() && !lines.getLast().isEmpty()) {
                    lines.add("");
                }
                continue;
            }
            if (isCommentOnlyLine(stripped)) {
                lines.add(stripped);
            }
        }
        while (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
        return lines;
    }

    private int labeledNestedStatementStart(String labelBody) {
        int cursor = 0;
        for (String line : labelBody.split("\\R", -1)) {
            String stripped = line.stripLeading();
            if (stripped.startsWith("for") || stripped.startsWith("{")) {
                return cursor + line.indexOf(stripped);
            }
            cursor += line.length() + 1;
        }
        return -1;
    }

    private boolean isCommentOnlyLine(String line) {
        return line.startsWith("//") || line.startsWith("/*") && line.endsWith("*/");
    }

    private Doc yieldStatement(YieldStmt statement) {
        if (compact.apply(statement.getExpression()).equals("()")) {
            return Doc.text("yield();");
        }
        return Doc.concat(
            Doc.text("yield "),
            expressionWithTailRenderer.render(
                statement.getExpression(),
                ExpressionTail.SEMICOLON,
                LayoutWidth.LineBudget.BLOCK
            )
        );
    }

    private Doc explicitConstructorInvocation(ExplicitConstructorInvocationStmt statement) {
        String prefix = statement.getExpression().map(expression -> compact.apply(expression) + ".").orElse("")
            + statement
                    .getTypeArguments()
                    .map(typeArguments -> "<" + compactJoinTypeLike.apply(typeArguments) + ">")
                    .orElse("")
            + (statement.isThis() ? "this" : "super");
        if (statement.getArguments().isEmpty()) {
            return Doc.text(prefix + "()");
        }
        Optional<Doc> huggableLambda = huggableBlockLambdaArguments.render(prefix, statement.getArguments());
        if (huggableLambda.isPresent()) {
            return huggableLambda.orElseThrow();
        }
        // super(...)/this(...) reach the plain argument list directly, so they otherwise miss the comment-aware
        // breaking that method-call and object-creation printers get from CommentedExpressionListPrinter. Without it an
        // interior argument's trailing line comment is dropped once the list breaks, because the compact join the plain
        // path falls back to renders arguments comment-free. Offer the same broken layout first so each argument keeps
        // its trailing comment, claimed once.
        Optional<Doc> commentedArguments = comments.speculatively(
            () -> commentedExpressionLists.parenthesized(prefix, statement, statement.getArguments())
        );
        if (commentedArguments.isPresent()) {
            return commentedArguments.orElseThrow();
        }
        return Doc.group(
            Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(
                    Doc.concat(
                        Doc.SOFT_LINE,
                        methodCallArgumentList.apply(statement.getArguments(), Doc.LINE)
                    )
                ),
                Doc.SOFT_LINE,
                Doc.text(")")
            )
        );
    }

    private Doc expressionStatement(ExpressionStmt statement, LayoutWidth.LineBudget lineBudget) {
        Expression expression = statement.getExpression();
        Doc trailing = expressionStatementTrailingComment(statement);
        if (expression instanceof VariableDeclarationExpr variableDeclaration) {
            return Doc.concat(
                variableDeclarationStatementRenderer.format(variableDeclaration),
                variableDeclarationTrailingComment(variableDeclaration),
                trailing
            );
        }
        if (expression instanceof MethodCallExpr methodCall) {
            if (methodCallChainHasFinalTrailingLineComment.test(methodCall)) {
                return Doc.concat(forcedMethodCallWithSemicolonRenderer.apply(methodCall, lineBudget), trailing);
            }
            if (!methodCallChainIsSourceMultiline.test(methodCall)) {
                Optional<Doc> sourceMultilineCall = sourceMultilineMethodCallStatementRenderer.apply(
                    methodCall,
                    statement
                );
                if (sourceMultilineCall.isPresent()) {
                    return Doc.concat(sourceMultilineCall.orElseThrow(), ExpressionTail.SEMICOLON.doc(), trailing);
                }
            }
            if (methodCallStatementWidth(methodCall, lineBudget) > options.lineWidth()) {
                boolean chainBreak = methodCallChainHasComments.test(methodCall)
                    || methodCallChainIsSourceMultiline.test(methodCall)
                    || methodCallChainRootIsObjectCreation.test(methodCall)
                    || !methodCallChainRootIsFieldAccess.test(methodCall);
                if (chainBreak) {
                    return Doc.concat(forcedMethodCallWithSemicolonRenderer.apply(methodCall, lineBudget), trailing);
                }
                return Doc.concat(
                    forcedMethodCallWithSemicolonRenderer.apply(methodCall, lineBudget),
                    trailing
                );
            }
        }
        if (expression instanceof AssignExpr assignExpr) {
            return Doc.concat(assignmentStatementRenderer.apply(assignExpr), trailing);
        }
        return Doc.concat(
            expressionWithTailRenderer.render(expression, ExpressionTail.SEMICOLON, lineBudget),
            trailing
        );
    }

    private Doc expressionStatementTrailingComment(ExpressionStmt statement) {
        Doc statementTrailing = trailingLineComment(statement);
        if (statementTrailing != Doc.EMPTY) {
            return Doc.lineSuffix(Doc.concat(Doc.text(" "), statementTrailing));
        }
        return conditionalElseStatementTrailingComment(statement)
                .map(comment -> Doc.lineSuffix(Doc.concat(Doc.text(" "), comments.comment(comment))))
                .orElse(Doc.EMPTY);
    }

    private Doc variableDeclarationTrailingComment(VariableDeclarationExpr declaration) {
        Doc declarationTrailing = comments.trailingLineComment(declaration);
        return declarationTrailing == Doc.EMPTY
            ? Doc.EMPTY
            : Doc.lineSuffix(Doc.concat(Doc.text(" "), declarationTrailing));
    }

    private int methodCallStatementWidth(MethodCallExpr methodCall, LayoutWidth.LineBudget lineBudget) {
        String raw = rawSource.normalizeWhitespace(rawSource.rawWithoutOwnComment(methodCall));
        return layoutWidth.line(lineBudget, raw + ";");
    }

    private Optional<Comment> conditionalElseStatementTrailingComment(ExpressionStmt statement) {
        return statement.getExpression()
                .findAll(ConditionalExpr.class)
                .stream()
                .flatMap(conditionalExpr -> conditionalExpr.getElseExpr()
                            .getComment()
                            .filter(LineComment.class::isInstance)
                            .filter(comment -> CommentIndex.startsAfterNodeOnSameLine(statement, comment))
                            .stream()
                )
                .findFirst();
    }

    /**
     * Prints try/catch/finally while handing trailing comments from one completed block into the next clause.
     *
     * <p>JavaParser can attach a line comment after a try block to the block that just ended even when users visually
     * read it as the first line inside the following catch or finally. The handoff keeps those adjacent block comments
     * in source order for fixtures such as
     * {@code frmtr-core/src/test/resources/format/comment-complex-block-statements/input.java}
     * and its {@code frmtr-default.output.java} pair.
     */
    private Doc tryStatement(TryStmt statement) {
        List<Doc> docs = new ArrayList<>();
        docs.add(Doc.text("try"));
        docs.add(tryResources(statement));
        docs.add(Doc.text(" "));
        docs.add(tryBlock(statement.getTryBlock()));
        Doc previousBlockTrailingComment = clauseTrailingComment(
            statement,
            statement.getTryBlock(),
            firstFollowingClause(statement, -1)
        );
        for (int i = 0; i < statement.getCatchClauses().size(); i++) {
            CatchClause clause = statement.getCatchClauses().get(i);
            Doc catchPrefixComment = ownBlockCommentBeforeNode(clause);
            docs.add(Doc.text(" "));
            if (catchPrefixComment != Doc.EMPTY) {
                docs.add(catchPrefixComment);
                docs.add(Doc.text(" "));
            }
            docs.add(
                catchClause(
                    clause,
                    statement.getCatchClauses().size(),
                    statement.getFinallyBlock().isPresent(),
                    clauseLeadingComment(previousBlockTrailingComment, ownLineCommentBeforeNode(clause))
                )
            );
            previousBlockTrailingComment = trailingCommentAfterClause(statement, clause, firstFollowingClause(statement, i));
        }
        if (statement.getFinallyBlock().isPresent()) {
            BlockStmt finallyBlock = statement.getFinallyBlock().orElseThrow();
            Doc finallyPrefixComment = finallyPrefixBlockComment(statement, finallyBlock);
            docs.add(Doc.text(" "));
            if (finallyPrefixComment != Doc.EMPTY) {
                docs.add(finallyPrefixComment);
                docs.add(Doc.text(" "));
            }
            docs.add(Doc.text("finally "));
            docs.add(
                tryBlock(
                    finallyBlock,
                    clauseLeadingComment(previousBlockTrailingComment, ownLineCommentBeforeNode(finallyBlock))
                )
            );
        }
        Doc finalTrailingComment = statement.getFinallyBlock()
                .map(comments::trailingLineComment)
                .orElse(previousBlockTrailingComment);
        if (finalTrailingComment == Doc.EMPTY) {
            finalTrailingComment = rawTrailingLineComment(statement);
        }
        if (finalTrailingComment == Doc.EMPTY) {
            finalTrailingComment = parentOrphanCommentOnEndLine(statement);
        }
        Doc tryStatementTrailingComment = comments.trailingLineComment(statement);
        if (finalTrailingComment != Doc.EMPTY) {
            docs.add(Doc.text(" "));
            docs.add(finalTrailingComment);
        }
        if (tryStatementTrailingComment != Doc.EMPTY) {
            docs.add(Doc.text(" "));
            docs.add(tryStatementTrailingComment);
        }
        return Doc.concat(docs);
    }

    private Doc tryResources(TryStmt statement) {
        if (statement.getResources().isEmpty()) {
            return Doc.EMPTY;
        }
        SourceShapePolicy.TryResourcesShape resourceShape = sourceShapePolicy.tryResources(statement);
        boolean trailingSemicolon = resourceShape.trailingSemicolon();
        String flatResources = statement.getResources()
                .stream()
                .map(this::flatResourceText)
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
        if (trailingSemicolon) {
            flatResources += ";";
        }
        List<JavaCommentTrivia> openerComments = tryResourceOpenerComments(statement);
        List<JavaCommentTrivia> trailingResourceComments = tryResourceTrailingComments(statement);
        String flat = "try (" + flatResources + ")";
        // The flat-collapse decision must be width-driven, not source-shape-driven, or the section never converges.
        //
        // A single resource whose own initializer call was broken across lines makes the section "span multiple lines",
        // but that is incidental to the initializer's argument layout, not a deliberate one-resource-per-line shape the
        // author asked us to keep. Honoring spansMultipleLines for a single resource lets each pass re-observe the prior
        // pass's break and refuse the flat form forever, so the section flip-flops between the opener-break and the
        // attached-argument-break (see issue #98). For two or more resources, spansMultipleLines does capture a
        // deliberate one-per-line layout, so it still gates the collapse there.
        boolean preserveAuthorMultiline = statement.getResources().size() > 1 && resourceShape.spansMultipleLines();
        if (
            !preserveAuthorMultiline
            && openerComments.isEmpty()
            && !tryResourcesHaveLeadingComments(statement)
            && trailingResourceComments.isEmpty()
            && currentIndentedWidth.applyAsInt(flat + " {}") <= options.lineWidth()
        ) {
            return Doc.text(" (" + flatResources + ")");
        }
        Optional<Doc> attachedMethodCallResource = trailingResourceComments.isEmpty()
            ? attachedSingleMethodCallResource(statement, resourceShape)
            : Optional.empty();
        if (attachedMethodCallResource.isPresent()) {
            return Doc.concat(
                Doc.text(" ("),
                attachedMethodCallResource.orElseThrow(),
                Doc.text(")")
            );
        }
        return Doc.concat(
            Doc.text(" ("),
            tryResourceOpenerCommentsDoc(openerComments),
            Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    tryResourceLines(statement),
                    trailingSemicolon ? Doc.text(";") : Doc.EMPTY,
                    tryResourceTrailingCommentsDoc(trailingResourceComments)
                )
            ),
            Doc.HARD_LINE,
            Doc.text(")")
        );
    }

    /**
     * Builds the one-line text for a resource used when the whole resource section collapses flat.
     *
     * <p>The generic {@code compact} path reconstructs initializer expressions (method calls, object creations, …) with
     * normalized spacing, but a {@link VariableDeclarationExpr} itself falls through to token-text normalization, which
     * only collapses whitespace runs. When the author had broken the initializer's arguments across lines, that left
     * single interior spaces after {@code (}, before {@code )}, and around commas (for example
     * {@code new Resource( a, b )}) in the collapsed form. This helper rebuilds a comment-free declaration from its
     * modifiers, type, and per-variable {@code name = compact(init)} pieces so the collapsed resource matches the spacing
     * the formatter would have produced for a source-flat declaration, keeping the collapse idempotent and clean.
     *
     * <p>Resources carrying contained comments, or anything that is not a plain variable declaration, stay on the generic
     * {@code compact} path: those either never reach the flat collapse (comments keep the section broken) or are already
     * normalized by {@code compact}.
     */
    private String flatResourceText(Expression resource) {
        if (
            !(resource instanceof VariableDeclarationExpr declaration)
            || !declaration.getAllContainedComments().isEmpty()
            || declaration.getVariables().isEmpty()
        ) {
            return compact.apply(resource);
        }
        String prefix = declaration.getAnnotations()
                .stream()
                .map(annotationFlatText)
                .map(text -> text + " ")
                .reduce("", String::concat)
            + modifiers.apply(declaration)
            + compactTypeLike.apply(CStyleArrayDeclarators.sharedPrefixType(declaration.getVariables()))
            + " ";
        return prefix + declaration.getVariables()
                .stream()
                .map(this::flatVariableText)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private String flatVariableText(VariableDeclarator variable) {
        String name = variable.getNameAsString() + CStyleArrayDeclarators.declaratorBracketsAfterName(variable);
        return variable.getInitializer()
                .map(initializer -> name + " = " + compact.apply(initializer))
                .orElse(name);
    }

    /**
     * Keeps one method-call resource attached to {@code try (} so the resource initializer owns the break.
     */
    private Optional<Doc> attachedSingleMethodCallResource(
            TryStmt statement,
            SourceShapePolicy.TryResourcesShape resourceShape
    ) {
        if (
            resourceShape.trailingSemicolon()
            || tryResourcesHaveLeadingComments(statement)
            || statement.getResources().size() != 1
            || !(statement.getResources().get(0) instanceof VariableDeclarationExpr declaration)
            || !declaration.getModifiers().isEmpty()
            || !declaration.getAnnotations().isEmpty()
            || !declaration.getAllContainedComments().isEmpty()
            || declaration.getVariables().size() != 1
        ) {
            return Optional.empty();
        }
        VariableDeclarator variable = declaration.getVariables().get(0);
        Optional<MethodCallExpr> initializer = variable.getInitializer()
                .filter(MethodCallExpr.class::isInstance)
                .map(MethodCallExpr.class::cast)
                .filter(methodCall -> !methodCall.getArguments().isEmpty())
                .filter(methodCall -> methodCall.getAllContainedComments().isEmpty());
        if (initializer.isEmpty()) {
            return Optional.empty();
        }
        MethodCallExpr methodCall = initializer.orElseThrow();
        String resourcePrefix = compactTypeLike.apply(variable.getType())
            + " "
            + variable.getNameAsString()
            + " = "
            + tryResourceMethodCallPrefix(methodCall)
            + "(";
        if (currentIndentedWidth.applyAsInt("try (" + resourcePrefix) > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(resourcePrefix),
                Doc.indent(
                    Doc.indent(
                        Doc.concat(
                            Doc.HARD_LINE,
                            methodCallArgumentList.apply(methodCall.getArguments(), Doc.HARD_LINE)
                        )
                    )
                ),
                Doc.HARD_LINE,
                Doc.text(")")
            )
        );
    }

    private String tryResourceMethodCallPrefix(MethodCallExpr methodCall) {
        return methodCall.getScope().map(scope -> compact.apply(scope) + ".").orElse("")
            + methodCall
                    .getTypeArguments()
                    .map(typeArguments -> "<" + compactJoinTypeLike.apply(typeArguments) + ">")
                    .orElse("")
            + methodCall.getNameAsString();
    }

    private Doc tryResource(Expression resource) {
        Doc leading = Doc.concat(comments.adjacentLeadingLineComments(resource), comments.leading(resource));
        Doc body;
        if (sourceShapePolicy.wasMultiline(resource) && resource instanceof VariableDeclarationExpr declaration) {
            body = variableDeclarationRenderer.format(declaration);
        } else {
            body = Doc.text(compact.apply(resource));
        }
        return Doc.concat(leading, body);
    }

    private Doc tryResourceLines(TryStmt statement) {
        NodeList<Expression> resources = statement.getResources();
        List<Doc> lines = new ArrayList<>();
        for (int index = 0; index < resources.size(); index++) {
            Expression resource = resources.get(index);
            Doc resourceLine = tryResource(resource);
            if (index + 1 < resources.size()) {
                lines.add(Doc.concat(resourceLine, Doc.text(";")));
                lines.addAll(tryResourceGapComments(statement, resource, resources.get(index + 1)));
            } else {
                lines.add(resourceLine);
            }
        }
        return Doc.join(Doc.HARD_LINE, lines);
    }

    private List<Doc> tryResourceGapComments(TryStmt statement, Expression previous, Expression next) {
        return commentPlacement.lineCommentsBetween(statement, previous, next)
                .stream()
                .map(comments::comment)
                .filter(doc -> doc != Doc.EMPTY)
                .toList();
    }

    /**
     * Recovers the opener comment that trails the resource list's {@code (} (e.g. {@code try ( // resource scope {}),
     * independent of source shape.
     *
     * <p>Ownership is source-order, not line-based: the opener is a line comment that begins before the first resource.
     * {@link JavaCommentPlacementPolicy#lineCommentsBeforeFirst(Node, Node)} already bounds the candidates to the region
     * after the {@code try (} opening and at or before the first resource's line, so any candidate that begins before the
     * first resource in source order sits in the {@code (}-to-first-resource gap — that is the opener. The previous
     * line-equality filter ({@code startsOnBeginLine(statement)}) required the comment to share the {@code try (} line,
     * which a whitespace perturbation defeats by pushing the opener onto its own line below the {@code (} even though the
     * AST is unchanged. The source-order test is a strict superset at the {@code @default} shape: an opener inline on the
     * {@code try (} line begins on the statement's begin line and before the first resource, so both tests agree; the
     * source-order test additionally keeps the same owner when expansion moves the opener off the {@code (} line.
     */
    private List<JavaCommentTrivia> tryResourceOpenerComments(TryStmt statement) {
        if (statement.getResources().isEmpty()) {
            return List.of();
        }
        Expression firstResource = statement.getResources().getFirst().orElseThrow();
        return commentPlacement.lineCommentsBeforeFirst(statement, firstResource)
                .stream()
                .filter(comment -> comment.startsBefore(firstResource))
                .toList();
    }

    private Doc tryResourceOpenerCommentsDoc(List<JavaCommentTrivia> openerComments) {
        return Doc.concat(
            openerComments.stream()
                    // A try-resource opener comment can also be reached from a neighboring resource render; skip
                    // already-printed comments so this slot does not duplicate-claim them. Output is unchanged because the
                    // first claimant placed the comment and a re-offer only ever rendered empty.
                    .filter(trivia -> !comments.isPrinted(trivia))
                    .map(comments::comment)
                    .filter(doc -> doc != Doc.EMPTY)
                    .map(doc -> Doc.concat(Doc.text(" "), doc))
                    .toList()
        );
    }

    private List<JavaCommentTrivia> tryResourceTrailingComments(TryStmt statement) {
        if (statement.getResources().isEmpty()) {
            return List.of();
        }
        return commentPlacement.lineCommentsBetween(
            statement,
            statement.getResources().getLast().orElseThrow(),
            statement.getTryBlock()
        );
    }

    private Doc tryResourceTrailingCommentsDoc(List<JavaCommentTrivia> trailingResourceComments) {
        return Doc.concat(
            trailingResourceComments.stream()
                    .map(comments::comment)
                    .filter(doc -> doc != Doc.EMPTY)
                    .map(doc -> Doc.concat(Doc.HARD_LINE, doc))
                    .toList()
        );
    }

    /**
     * Reports whether any resource carries a line comment that lives inside the {@code try ( ... )} section.
     *
     * <p>A line comment on the line directly above {@code try (} is the try statement's own leading comment: JavaParser
     * attaches it to the {@link TryStmt} and the enclosing block already renders it before {@code try}. It only looks
     * "adjacent" to the first resource because the resource sits on the next source line. Counting it here forced the
     * resource section to stay broken even when the flat {@code try (...) {} } form fit the width, which (together with the
     * single-resource {@code spansMultipleLines} gate) made the section never converge across passes (issue #98).
     * Filtering to comments that begin at or after the {@code try} keyword keeps genuine in-section comments — openers,
     * inter-resource notes, and comments on a non-first resource — while ignoring the statement's own leading comment.
     */
    private boolean tryResourcesHaveLeadingComments(TryStmt statement) {
        return statement.getResources()
                .stream()
                .anyMatch(resource -> commentPlacement.adjacentLeadingLineComments(resource)
                                .stream()
                                .anyMatch(comment -> !comment.startsBefore(statement))
                        || commentPlacement.leadingComment(resource)
                                .filter(JavaCommentTrivia::isLine)
                                .filter(comment -> comment.startsBeforeBeginLine(resource))
                                .filter(comment -> !comment.startsBefore(statement))
                                .isPresent()
                );
    }

    private Doc trailingCommentAfterClause(TryStmt statement, CatchClause clause, Optional<? extends Node> next) {
        Doc bodyTrailing = clauseTrailingComment(statement, clause.getBody(), next);
        if (bodyTrailing != Doc.EMPTY) {
            return bodyTrailing;
        }
        return comments.trailingLineComment(clause);
    }

    /**
     * Recovers the line comment trailing a completed try/catch/finally clause body, independent of source shape.
     *
     * <p>The handoff prefers {@code body}'s own trailing line comment ({@link CommentTracker#trailingLineComment(Node)}).
     * When the comment lands on the brace line that path recovers it. When a whitespace perturbation pushes the comment
     * onto the line below the brace, JavaParser re-buckets it as an orphan of the enclosing {@code try}, so the orphan
     * recovery ({@link CommentTracker#trailingLineCommentBlockAfter(Node, Node, Optional)}) keeps the same ownership by
     * selecting the {@code try} orphans that source-order between this body's end and the next clause's begin.
     *
     * <p>The recovered slice can be a multi-line {@code //} block written between two {@code catch} clauses, so it is
     * rendered as a {@link Doc#HARD_LINE}-separated block: handing it into the following clause body as a single fused
     * line corrupts the comment (issue #128). A single recovered line renders identically either way.
     */
    private Doc clauseTrailingComment(TryStmt statement, Node body, Optional<? extends Node> next) {
        Doc own = comments.trailingLineComment(body);
        if (own != Doc.EMPTY) {
            return own;
        }
        return comments.trailingLineCommentBlockAfter(statement, body, next);
    }

    /**
     * Combines the comment block handed off from the previous try/catch clause ({@code previousTrailing}) with the
     * following clause's own leading line comment ({@code ownLeading}) into the single {@code leadingInside} doc that
     * opens that clause's block body.
     *
     * <p>Both halves can be present at once: a multi-line {@code //} block written between two {@code catch} clauses is
     * split by JavaParser into {@code try} orphans (recovered into {@code previousTrailing}) and a final line attached to
     * the next clause (recovered into {@code ownLeading}). Concatenating them directly would fuse the orphan block's last
     * line onto the clause's own line, so a {@link Doc#HARD_LINE} separates the two halves when both are non-empty
     * (issue #128). When only one half is present the result is that half unchanged.
     */
    private Doc clauseLeadingComment(Doc previousTrailing, Doc ownLeading) {
        if (previousTrailing == Doc.EMPTY) {
            return ownLeading;
        }
        if (ownLeading == Doc.EMPTY) {
            return previousTrailing;
        }
        return Doc.concat(previousTrailing, Doc.HARD_LINE, ownLeading);
    }

    /**
     * Returns the structural node that begins the clause following the one at {@code clauseIndex} (a {@code -1} index
     * means "after the try block"). Used to bound which {@code try}-orphan trailing comments belong to each handoff.
     */
    private Optional<? extends Node> firstFollowingClause(TryStmt statement, int clauseIndex) {
        if (clauseIndex + 1 < statement.getCatchClauses().size()) {
            return Optional.of(statement.getCatchClauses().get(clauseIndex + 1));
        }
        return statement.getFinallyBlock();
    }

    /**
     * Recovers the block comment that sits before the {@code finally} block ({@code } /* note *}{@code / finally {}),
     * independent of source shape.
     *
     * <p>At {@code @default} JavaParser attaches it as the finally {@link BlockStmt}'s own comment, so
     * {@link #ownBlockCommentBeforeNode(Node)} renders it and the orphan fallback never runs. A whitespace perturbation
     * that pushes the comment onto its own line re-buckets it as a {@link TryStmt} orphan; the own path then loses it, so
     * we recover the {@code try} orphan block comments that begin before the finally block. Earlier-clause comments
     * (a {@code catch} prefix) are already claimed by the time the finally prefix renders, so they cannot leak in here.
     */
    private Doc finallyPrefixBlockComment(TryStmt statement, BlockStmt finallyBlock) {
        Doc own = ownBlockCommentBeforeNode(finallyBlock);
        if (own != Doc.EMPTY) {
            return own;
        }
        return Doc.concat(comments.blockCommentsBefore(List.of(statement), finallyBlock));
    }

    private Doc ownBlockCommentBeforeNode(Node node) {
        return comments.ownComment(node, comment -> comment instanceof BlockComment
                && comment.getRange()
                        .flatMap(commentRange -> node.getRange().map(
                                nodeRange -> CommentIndex.startsBefore(commentRange, nodeRange)
                        ))
                        .orElse(false)
        );
    }

    private Doc ownLineCommentBeforeNode(Node node) {
        return comments.ownComment(node, comment -> comment instanceof LineComment
                && CommentIndex.startsBeforeBeginLine(comment, node)
        );
    }

    private Doc rawTrailingLineComment(Node node) {
        String raw = node.getTokenRange().map(Object::toString).orElse("");
        int lastBrace = raw.lastIndexOf('}');
        if (lastBrace < 0) {
            return Doc.EMPTY;
        }
        int commentStart = raw.indexOf("//", lastBrace);
        if (commentStart < 0 || raw.substring(lastBrace, commentStart).contains("\n")) {
            return Doc.EMPTY;
        }
        int commentEnd = raw.indexOf('\n', commentStart);
        String comment = commentEnd < 0 ? raw.substring(commentStart) : raw.substring(commentStart, commentEnd);
        return Doc.text(comment.stripTrailing());
    }

    private Doc parentOrphanCommentOnEndLine(Node node) {
        return node.getParentNode()
                .filter(BlockStmt.class::isInstance)
                .map(BlockStmt.class::cast)
                .map(parent -> Doc.concat(
                        comments.orphanCommentStatements(parent, comment -> CommentIndex.startsOnEndLine(node, comment))
                ))
                .orElse(Doc.EMPTY);
    }

    private Doc tryBlock(BlockStmt block) {
        return tryBlock(block, Doc.EMPTY);
    }

    /**
     * Prints a try-related block after optional comment docs have been handed off from the previous clause.
     *
     * <p>Empty try blocks keep the historic multi-line {@code {\n}} shape, but comments handed in from an adjacent
     * catch or finally force the normal block-with-leading path so the comment has an inside-the-block home.
     */
    private Doc tryBlock(BlockStmt block, Doc leadingInside) {
        if (block.getStatements().isEmpty() && block.getOrphanComments().isEmpty() && leadingInside == Doc.EMPTY) {
            return Doc.concat(Doc.text("{"), Doc.HARD_LINE, Doc.text("}"));
        }
        return blockWithLeadingRenderer.apply(block, leadingInside);
    }

    private Doc catchClause(CatchClause clause, int catchCount, boolean hasFinally, Doc leadingInside) {
        boolean compactEmptyBody = catchCount == 1
            && !hasFinally
            && clause.getBody().getStatements().isEmpty()
            && clause.getBody().getOrphanComments().isEmpty()
            && leadingInside == Doc.EMPTY;
        return Doc.concat(
            Doc.text("catch ("),
            catchParameter(clause),
            Doc.text(") "),
            compactEmptyBody ? Doc.text("{}") : tryBlock(clause.getBody(), leadingInside)
        );
    }

    private Doc catchParameter(CatchClause clause) {
        Parameter parameter = clause.getParameter();
        String flat = parameterText.apply(parameter);
        if (parameterHasComments(parameter) && flat.contains("|")) {
            return commentedCatchParameter(parameter, flat);
        }
        if (
            !flat.contains("|")
            || layoutWidth.nodeLine(clause, "} catch (" + flat + ") {}") <= options.lineWidth()
        ) {
            return Doc.text(flat);
        }
        return brokenCatchParameter(parameter, flat);
    }

    private Doc brokenCatchParameter(Parameter parameter, String flat) {
        List<String> parts = List.of(catchParameterTypeText(parameter, flat).split("\\s*\\|\\s*"));
        List<Doc> lines = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            String prefix = i == 0 ? "" : "| ";
            String suffix = i == parts.size() - 1 ? " " + parameter.getNameAsString() : "";
            lines.add(Doc.text(prefix + parts.get(i) + suffix));
        }
        return Doc.concat(Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, lines))), Doc.HARD_LINE);
    }

    private String catchParameterTypeText(Parameter parameter, String flat) {
        String name = parameter.getNameAsString();
        if (flat.endsWith(" " + name)) {
            return flat.substring(0, flat.length() - name.length()).stripTrailing();
        }
        return compactTypeLike.apply(parameter.getType());
    }

    private boolean parameterHasComments(Parameter parameter) {
        return parameter.getComment().filter(BlockComment.class::isInstance).isPresent()
            || !parameter.getAllContainedComments().isEmpty();
    }

    private Doc commentedCatchParameter(Parameter parameter, String flat) {
        String rawType = catchParameterTypeText(parameter, flat);
        List<String> parts = List.of(rawType.split("\\s*\\|\\s*"));
        Doc leading = comments.ownComment(parameter, BlockComment.class::isInstance);
        List<Doc> lines = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            String type = CommentedTokenText.tokenLine(CommentedTokenText.tokens(parts.get(i).strip()));
            if (i == 0 && leading != Doc.EMPTY) {
                type = commentText(leading) + " " + type;
            }
            String prefix = i == 0 ? "" : "| ";
            String suffix = i == parts.size() - 1 ? " " + parameter.getNameAsString() : "";
            lines.add(Doc.text(prefix + type + suffix));
        }
        return Doc.concat(Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, lines))), Doc.HARD_LINE);
    }

    /**
     * Prints if/else chains while preserving the comment slots between the condition, then branch, and else branch.
     *
     * <p>The forks here are driven by source layout rather than Java syntax alone: empty bodies can keep comments inside
     * the header line, comments between {@code then} and {@code else} stay between those tokens, and nested {@code else
     * if} routes back through the outer statement callback so it gets the same raw/pragma/comment gate and switch
     * routing as any other nested statement.
     */
    private Doc ifStatement(IfStmt statement) {
        if (statement.getThenStmt().isEmptyStmt()) {
            return ifWithEmptyThenStatement(statement);
        }
        List<Doc> docs = new ArrayList<>();
        // A line comment on the then branch's `}` line that is followed by `else`/`else if` belongs to the else-leading
        // gap block, not to a separate then-trailing slot: a collapse can re-attach such a gap line as the then block's
        // own trailing comment, and claiming it here would let elseChainSeparator's then-trailing branch render it alone
        // and drop the rest of the block. When the then is a block and an else follows, let the gap block (computed in
        // the else branch below) own every gap line; only claim the standalone then-trailing slot otherwise.
        boolean elseLeadingGapOwnsThenTrailing = statement.getThenStmt().isBlockStmt()
            && statement.getElseStmt().filter(elseStatement -> !elseStatement.isEmptyStmt()).isPresent();
        Doc thenTrailingLineComment = elseLeadingGapOwnsThenTrailing
            ? Doc.EMPTY
            : trailingLineComment(statement.getThenStmt());
        // Claim the else-leading gap block before rendering the then branch so the dry-run records the whole block as the
        // gap's own (in Doc-construction order, which is what the record-only pre-pass follows), even though it renders
        // after the then branch's `}`. Otherwise a gap line that a collapse re-attached as the then block's own trailing
        // comment would be claimed by the block printer first and render on the `}` line, splitting the block.
        Doc elseLeadingLineComment = statement.getElseStmt()
                .filter(elseStatement -> !elseStatement.isEmptyStmt())
                .map(elseStatement -> elseLeadingLineComment(statement, elseStatement))
                .orElse(Doc.EMPTY);
        Doc conditionTrailingLineComment = controlConditions.closeParenTrailingLineComment(statement.getCondition());
        Doc betweenThenAndElseBlockComment = blockCommentBetweenThenAndElse(statement);
        docs.add(ifCondition(statement));
        if (conditionTrailingLineComment != Doc.EMPTY) {
            docs.add(Doc.text(" "));
            docs.add(conditionTrailingLineComment);
            docs.add(ifThenStatementAfterConditionTrailingComment(statement));
        } else {
            docs.add(Doc.text(" "));
            docs.add(ifThenStatement(statement));
        }
        statement
                .getElseStmt()
                .ifPresent(elseStatement -> {
                    if (elseStatement.isEmptyStmt()) {
                        docs.add(emptyElseStatement(statement, elseStatement));
                        return;
                    }
                    // A block comment between the then-block close and else is recovered by blockCommentBetweenThenAndElse
                    // above and takes priority in elseChainSeparator; for `} /* c */ else {` that same comment is also a
                    // same-line block comment before the else statement. Only offer the else-leading block comment when
                    // the between-then-and-else slot did not already claim it, so the comment is claimed once. Output is
                    // unchanged because elseChainSeparator returns on the between slot before it ever reads the else
                    // leading block comment.
                    Doc elseLeadingBlockComment = betweenThenAndElseBlockComment == Doc.EMPTY
                        ? sameLineBlockCommentBeforeNode.apply(elseStatement)
                        : Doc.EMPTY;
                    Doc elseTrailingLineComment = elseTrailingLineComment(statement, elseStatement);
                    docs.add(
                        elseChainSeparator(
                            statement,
                            elseStatement,
                            conditionTrailingLineComment,
                            thenTrailingLineComment,
                            betweenThenAndElseBlockComment,
                            elseLeadingLineComment,
                            elseLeadingBlockComment
                        )
                    );
                    docs.add(
                        elseStatement.isIfStmt()
                            ? statementRenderer.format(elseStatement)
                            : nestedStatement(elseStatement)
                    );
                    if (elseTrailingLineComment != Doc.EMPTY) {
                        docs.add(Doc.text(" "));
                        docs.add(elseTrailingLineComment);
                    }
                });
        if (statement.getElseStmt().isEmpty() && thenTrailingLineComment != Doc.EMPTY) {
            docs.add(Doc.text(" "));
            docs.add(thenTrailingLineComment);
        }
        return Doc.concat(docs);
    }

    /**
     * Recovers the {@code //} comment block that leads the {@code else} keyword ({@code } // note\nelse}), independent of
     * source shape, as a single together-rendered cluster.
     *
     * <p>A multi-line block written between the then branch's {@code }} and {@code else}/{@code else if} is not held by a
     * single node: JavaParser keeps the trailing lines as the enclosing {@code if}'s orphan trivia while the line
     * directly above the {@code else}/{@code else if} node becomes that node's own leading trivia. Reading only one of
     * those two slots (own first, orphan fallback) split the block — one line rendered above {@code else}, the rest
     * folded into the nested {@code else if}'s leading cluster, which mangled {@code else if} into {@code else //\n if}
     * and rotated the lines every pass because re-parsing re-split the block onto different nodes. We instead claim the
     * whole gap block in one slot (see
     * {@link JavaCommentPlacementPolicy#gapLeadingLineCommentBlock(Node, Node, java.util.Collection)}) so it renders once,
     * together, above {@code else}, and the nested {@code else if} can no longer reclaim a leading line. At
     * {@code @default} the block is a single contiguous run, so this renders the same lines in the same order.
     */
    private Doc elseLeadingLineComment(IfStmt statement, Statement elseStatement) {
        return comments.gapLeadingLineCommentBlock(
            statement,
            statement.getThenStmt(),
            elseStatement,
            List.of(statement)
        );
    }

    /**
     * Recovers the line comment that trails the {@code else} body ({@code } else {} // note}), independent of source
     * shape.
     *
     * <p>This mirrors the try-clause {@link #clauseTrailingComment(TryStmt, Node, java.util.Optional)} recovery. At
     * {@code @default} the comment sits on the else body's end line, so {@link CommentTracker#trailingLineComment(Node)}
     * (via {@link #trailingLineComment(Node)}) owns it. When a collapse perturbation places it on the shared else
     * {@code }} position so {@code startsAfterEndOf(elseStatement)} fails, JavaParser re-buckets it onto the
     * {@link IfStmt} orphan pool; we then recover the {@code if} orphan line comment that source-orders after the else
     * body ends (open to the statement's end, since the else is the last clause).
     */
    private Doc elseTrailingLineComment(IfStmt statement, Statement elseStatement) {
        Doc own = trailingLineComment(elseStatement);
        if (own != Doc.EMPTY) {
            return own;
        }
        return comments.trailingLineCommentsAfter(statement, elseStatement, Optional.empty());
    }

    private Doc ifWithEmptyThenStatement(IfStmt statement) {
        List<Doc> docs = new ArrayList<>();
        docs.add(Doc.text("if (" + ifEmptyThenCondition(statement) + ");"));
        statement
                .getElseStmt()
                .ifPresent(elseStatement -> {
                    docs.add(Doc.HARD_LINE);
                    docs.add(
                        elseStatement.isEmptyStmt()
                            ? Doc.text("else;" + trailingEmptyBodyBlockComment(elseStatement))
                            : Doc.concat(Doc.text("else "), nestedStatement(elseStatement))
                    );
                });
        return Doc.concat(docs);
    }

    private String ifEmptyThenCondition(IfStmt statement) {
        List<String> parts = new ArrayList<>();
        parts.add(compact.apply(statement.getCondition()));
        String thenComment = commentText(emptyBodyOwnBlockComment(statement.getThenStmt()));
        if (!thenComment.isEmpty()) {
            parts.add(thenComment);
        }
        String betweenThenAndElse = commentText(blockCommentBetweenThenAndElse(statement));
        if (!betweenThenAndElse.isEmpty()) {
            parts.add(betweenThenAndElse);
        }
        statement.getElseStmt()
                .filter(Statement::isEmptyStmt)
                .map(this::emptyBodyOwnBlockComment)
                .map(this::commentText)
                .filter(comment -> !comment.isEmpty())
                .ifPresent(parts::add);
        return String.join(" ", parts);
    }

    /**
     * Recovers the block comment that sits between the then branch's {@code }} and the {@code else} keyword
     * ({@code } /* note *}{@code / else}), independent of source shape.
     *
     * <p>The own path keeps the original column arithmetic: at {@code @default} JavaParser attaches the comment to the
     * else node as its own trivia, and the comment shares the then-end line immediately after {@code }} (within two
     * columns) and lies before the {@code else} node on that line. That column window is what distinguishes a
     * {@code } /* note *}{@code / else} comment from an {@code else /* note *}{@code / {} comment — both are the else
     * node's own block comment on the same line, but only the former sits immediately after {@code }}. Keeping that
     * window means {@code @default} renders byte-identically and the {@code else}-leading comment still falls through to
     * {@link #ifStatement(IfStmt)}'s {@code elseLeadingBlockComment} slot.
     *
     * <p>A whitespace perturbation that pushes the {@code } /* note *}{@code / else} comment onto its own line below the
     * {@code }} re-buckets it as a {@link IfStmt} orphan even though the AST is otherwise identical, so the own path's
     * line/column predicates lose it. The orphan fallback then recovers the {@code if} orphan block comment that
     * source-orders strictly between the then branch end and the else node begin. The fallback only sees orphans, so an
     * {@code else}-leading comment (which stays the else node's own trivia under perturbation) is never claimed here and
     * still renders through the {@code elseLeadingBlockComment} slot.
     */
    private Doc blockCommentBetweenThenAndElse(IfStmt statement) {
        if (statement.getElseStmt().isEmpty()) {
            return Doc.EMPTY;
        }
        Statement thenStatement = statement.getThenStmt();
        Statement elseStatement = statement.getElseStmt().orElseThrow();
        Doc own = statement.getAllContainedComments()
                .stream()
                .filter(BlockComment.class::isInstance)
                .filter(comment -> comment.getRange()
                            .flatMap(commentRange -> thenStatement.getRange().flatMap(
                                    thenRange -> elseStatement.getRange().map(
                                        elseRange -> commentRange.begin.line == thenRange.end.line
                                                && commentRange.begin.column > thenRange.end.column
                                                && commentRange.begin.column <= thenRange.end.column + 2
                                                && commentRange.begin.line == elseRange.begin.line
                                                && commentRange.begin.column < elseRange.begin.column
                                    )
                            ))
                            .orElse(false)
                )
                .findFirst()
                .map(comments::comment)
                .orElse(Doc.EMPTY);
        if (own != Doc.EMPTY) {
            return own;
        }
        return commentPlacement.orphanComments(statement)
                .stream()
                .filter(JavaCommentTrivia::isBlock)
                .filter(comment -> comment.liesBetween(thenStatement, elseStatement))
                .sorted(Comparator.comparing(JavaCommentTrivia::comment, CommentIndex.sourceOrderComparator()))
                .findFirst()
                .map(comments::comment)
                .filter(doc -> doc != Doc.EMPTY)
                .orElse(Doc.EMPTY);
    }

    private Doc emptyElseStatement(IfStmt statement, Statement elseStatement) {
        String elseComment = commentText(emptyBodyOwnBlockComment(elseStatement));
        String prefix = elseComment.isEmpty() ? " else;" : " " + elseComment + " else;";
        return Doc.text(prefix + trailingEmptyBodyBlockComment(elseStatement));
    }

    private Doc ifCondition(IfStmt statement) {
        return Doc.concat(Doc.text("if "), ifConditionRenderer.apply(statement.getCondition()));
    }

    private boolean conditionCommentStartsBeforeExpression(Expression condition, Comment comment) {
        return CommentIndex.startsBefore(comment, condition);
    }

    private Doc ifThenStatement(IfStmt statement) {
        if (
            statement.getElseStmt().isEmpty()
            && statement.getThenStmt().isBlockStmt()
            && statement.getThenStmt().asBlockStmt().getStatements().isEmpty()
            && statement.getThenStmt().asBlockStmt().getOrphanComments().isEmpty()
            && compact.apply(statement.getCondition()).contains("instanceof")
        ) {
            return Doc.concat(Doc.text("{"), Doc.HARD_LINE, Doc.text("}"));
        }
        return nestedStatement(statement.getThenStmt());
    }

    private Doc ifThenStatementAfterConditionTrailingComment(IfStmt statement) {
        if (statement.getThenStmt().isBlockStmt()) {
            return Doc.concat(Doc.HARD_LINE, ifThenStatement(statement));
        }
        return Doc.indent(Doc.concat(Doc.HARD_LINE, statementRenderer.format(statement.getThenStmt())));
    }

    private Doc elseChainSeparator(
            IfStmt statement,
            Statement elseStatement,
            Doc conditionTrailingLineComment,
            Doc thenTrailingLineComment,
            Doc betweenThenAndElseBlockComment,
            Doc elseLeadingLineComment,
            Doc elseLeadingBlockComment
    ) {
        if (conditionTrailingLineComment != Doc.EMPTY && !statement.getThenStmt().isBlockStmt()) {
            // The condition trailing comment is rendered on the `if` line by ifStatement; this slot only emits the
            // separator. A braceless then whose first leading line comment bubbled up as the condition trailing comment
            // does not silence the then-trailing and else-leading slots: the remaining then-leading lines can surface as
            // the then statement's own trailing comment, and the else body's leading `//` block has already been claimed
            // by the elseLeadingLineComment gap slot. Returning the bare separator would leave both already-claimed
            // clusters unrendered and silently drop them, so emit whichever of them is present before `else`.
            return separatorWithThenTrailingAndElseLeading(thenTrailingLineComment, elseLeadingLineComment);
        }
        if (thenTrailingLineComment != Doc.EMPTY) {
            return Doc.concat(Doc.HARD_LINE, thenTrailingLineComment, Doc.HARD_LINE, Doc.text("else "));
        }
        if (betweenThenAndElseBlockComment != Doc.EMPTY) {
            return Doc.concat(Doc.text(" "), betweenThenAndElseBlockComment, Doc.text(" else "));
        }
        if (elseLeadingLineComment != Doc.EMPTY) {
            return Doc.concat(Doc.HARD_LINE, elseLeadingLineComment, Doc.HARD_LINE, Doc.text("else "));
        }
        if (elseLeadingBlockComment != Doc.EMPTY) {
            return Doc.concat(Doc.text(" else "), elseLeadingBlockComment, Doc.text(" "));
        }
        if (elseStatement.isIfStmt() && !statement.getThenStmt().isBlockStmt()) {
            return Doc.concat(Doc.HARD_LINE, Doc.text("else "));
        }
        return Doc.text(" else ");
    }

    /**
     * Builds the {@code else} separator for the condition-trailing-comment branch, carrying through both the then
     * statement's own trailing line comment and the else body's already-claimed leading {@code //} block.
     *
     * <p>The two slots are distinct comment clusters that both survive elsewhere when no condition trailing comment is
     * present (the then-trailing and elseLeadingLineComment branches below render them in turn). Once a braceless then's
     * leading comment has bubbled onto the {@code if} line as the condition trailing comment, this branch is the only one
     * reached, so it must render whichever of the two remaining clusters exist rather than dropping a cluster the dry-run
     * already recorded as owned here. Each present cluster gets its own line above {@code else}, in source order
     * (then-trailing first, else-leading second), matching the layout the no-condition-comment branches produce.
     */
    private Doc separatorWithThenTrailingAndElseLeading(Doc thenTrailingLineComment, Doc elseLeadingLineComment) {
        List<Doc> docs = new ArrayList<>();
        if (thenTrailingLineComment != Doc.EMPTY) {
            docs.add(Doc.HARD_LINE);
            docs.add(thenTrailingLineComment);
        }
        if (elseLeadingLineComment != Doc.EMPTY) {
            docs.add(Doc.HARD_LINE);
            docs.add(elseLeadingLineComment);
        }
        docs.add(Doc.HARD_LINE);
        docs.add(Doc.text("else "));
        return Doc.concat(docs);
    }

    /**
     * Chooses how a control-flow body attaches to its header.
     *
     * <p>Empty if-blocks keep the two-line block shape used by existing fixtures, block bodies stay on the same line as
     * their header, and nested control statements break and indent so constructs such as single-line loops do not
     * collapse into ambiguous header/body text. A simple (non-control) body that carries a leading {@code //} line
     * comment also breaks and indents, because a line comment cannot share the header line without swallowing the body
     * text after it. Switch bodies go back through the outer statement callback, where the rule envelope preserves
     * statement gates before this printer selects the switch-statement branch again.
     */
    private Doc nestedStatement(Statement statement) {
        if (
            statement.isBlockStmt()
            && statement.asBlockStmt().getStatements().isEmpty()
            && statement.asBlockStmt().getOrphanComments().isEmpty()
            && statement.getParentNode().filter(IfStmt.class::isInstance).isPresent()
        ) {
            return emptyControlBlock(statement.asBlockStmt());
        }
        if (statement.isBlockStmt()) {
            return statementRenderer.format(statement);
        }
        if (
            statement.isIfStmt()
            || statement.isForStmt()
            || statement.isForEachStmt()
            || statement.isWhileStmt()
            || statement.isDoStmt()
        ) {
            return Doc.indent(Doc.concat(Doc.HARD_LINE, statementRenderer.format(statement)));
        }
        return leadingLineCommentBody(statement).orElseGet(() -> statementRenderer.format(statement));
    }

    /**
     * Renders a braceless {@code while}/{@code for}/{@code for-each} body that carries a {@code //} line comment between
     * the loop header and the body, claiming the comment exactly once and placing it the same way the {@code if}
     * close-paren path places a condition comment.
     *
     * <p>A braceless loop body normally collapses onto the header line ({@code while (cond) call();}). A line comment in
     * the header-to-body gap cannot share that line with the body statement: the {@code //} would comment out everything
     * after it. The comment's intended position is read from where it sits in source: a comment that begins on the same
     * line as the header end ({@code while (cond) // note}) is a header-trailing comment and stays inline on the header
     * line, exactly as {@link ControlConditions#closeParenTrailingLineComment} keeps an {@code if (cond) // note} inline;
     * a comment on its own line below the header ({@code while (cond)\n // note\n body}) leads the body and moves above
     * the indented body statement. Either way the body breaks to an indented next line.
     *
     * <p>The comment lives in a single grammar slot — the header-to-body gap — but JavaParser attaches it to different
     * nodes depending on source whitespace: at the {@code @default} shape an own-line comment is the body's own leading
     * trivia (the {@link #statementRenderer} envelope prints it); a collapse re-buckets it onto the header expression
     * named by {@code afterNode} as that node's trailing trivia, and an expand re-buckets it onto the {@code controlStmt}
     * as an orphan. {@link JavaCommentPlacementPolicy#gapLineCommentsBefore(Node, Node, java.util.Collection)} recovers
     * the comment from whichever bucket holds it while deliberately excluding the body's own comment, and every recovered
     * comment is claimed once under the body's leading slot — the same slot {@link CommentTracker#gapLineCommentsBefore}
     * would claim it in — so exactly one of the two paths (gap recovery here, or the body renderer) prints it. It is
     * therefore neither dropped under perturbation nor duplicated at {@code @default}. Returns {@link Optional#empty()}
     * when no leading line comment is present in any bucket, leaving the caller's existing same-line collapse intact.
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
        indented.add(statementRenderer.format(body));
        List<Doc> result = new ArrayList<>();
        result.add(Doc.text(" "));
        for (Doc inline : headerTrailing) {
            result.add(inline);
        }
        result.add(Doc.indent(Doc.concat(indented)));
        return Optional.of(Doc.concat(result));
    }

    /**
     * Breaks and indents a braceless {@code if}/{@code else}/{@code do} body that carries its leading {@code //} line
     * comment as its own trivia (the {@code @default} shape).
     *
     * <p>This is the body-own counterpart of {@link #bracelessLoopBody(Node, Node, Statement)}, used where the enclosing
     * construct already recovers the perturbed attachments through another slot: the {@code if} close-paren trailing path
     * ({@link ControlConditions#closeParenTrailingLineComment}) catches a comment a collapse moves onto the condition,
     * and the {@code do-while} condition-leading path catches one an expand moves onto the condition. So those constructs
     * only need the body-own slot handled here. The whole body — comment and statement — is rendered through
     * {@link #statementRenderer}, whose envelope emits the body's leading comment exactly once before the statement;
     * wrapping that in {@link Doc#indent} puts both at the body indent. Returns {@link Optional#empty()} when the body has
     * no leading line comment, leaving the caller's existing same-line collapse intact.
     */
    private Optional<Doc> leadingLineCommentBody(Statement statement) {
        if (statement.isBlockStmt()) {
            return Optional.empty();
        }
        boolean hasLeadingLineComment = commentPlacement.leadingComment(statement)
                .filter(JavaCommentTrivia::isLine)
                .filter(trivia -> !trivia.startsAfterEndOf(statement))
                .isPresent();
        if (!hasLeadingLineComment) {
            return Optional.empty();
        }
        return Optional.of(Doc.indent(Doc.concat(Doc.HARD_LINE, statementRenderer.format(statement))));
    }

    private Doc emptyControlBlock(BlockStmt block) {
        Doc inlineBlockComment = comments.ownComment(block, BlockComment.class::isInstance);
        if (inlineBlockComment != Doc.EMPTY) {
            return Doc.concat(inlineBlockComment, Doc.text(" {"), Doc.HARD_LINE, Doc.text("}"));
        }
        Doc leading = comments.leading(block);
        return Doc.concat(leading, Doc.text("{"), Doc.HARD_LINE, Doc.text("}"));
    }

    private Doc forEachStatement(ForEachStmt statement) {
        if (statement.getBody().isEmptyStmt()) {
            return Doc.text(
                "for ("
                    + compact.apply(statement.getVariable())
                    + " : "
                    + emptyBodyHeaderExpression(statement.getIterable(), statement.getBody())
                    + ");"
                    + trailingEmptyBodyBlockComment(statement)
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
        return Doc.concat(header, Doc.text(" "), nestedStatement(statement.getBody()));
    }

    /**
     * Lets the iterable own method-call argument breaks when the enhanced-for header would otherwise overflow.
     */
    private Doc forEachHeader(ForEachStmt statement) {
        String variable = forEachVariable(statement);
        Expression iterable = statement.getIterable();
        String header = "for (" + variable + " : " + compact.apply(iterable) + ")";
        if (
            layoutWidth.blockStatement(header + " {}") <= options.lineWidth()
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

    private Doc forStatement(ForStmt statement) {
        if (statement.getBody().isEmptyStmt()) {
            return loopWithEmptyBody(forHeader(statement), statement);
        }
        if (statement.getBody() instanceof DoStmt) {
            return Doc.concat(Doc.text(forHeader(statement) + " "), statementRenderer.format(statement.getBody()));
        }
        Optional<Doc> commentedBracelessBody = forHeaderEndNode(statement)
                .flatMap(afterNode -> bracelessLoopBody(statement, afterNode, statement.getBody()));
        if (commentedBracelessBody.isPresent()) {
            return Doc.concat(Doc.text(forHeader(statement)), commentedBracelessBody.orElseThrow());
        }
        return Doc.concat(Doc.text(forHeader(statement) + " "), nestedStatement(statement.getBody()));
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

    private Doc whileStatement(WhileStmt statement) {
        if (statement.getBody().isEmptyStmt()) {
            return Doc.text(
                "while ("
                    + emptyBodyHeaderExpression(statement.getCondition(), statement.getBody())
                    + ");"
                    + trailingEmptyBodyBlockComment(statement)
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
        return Doc.concat(whileHeader, Doc.text(" "), nestedStatement(statement.getBody()));
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
        Doc commentedStatement = Doc.concat(comment, Doc.text(" "), statementRenderer.format(body));
        if (CommentIndex.sameBeginLine(loop, body)) {
            return Optional.of(Doc.concat(Doc.text(" "), commentedStatement));
        }
        return Optional.of(Doc.indent(Doc.concat(Doc.HARD_LINE, commentedStatement)));
    }

    private Doc doStatement(DoStmt statement) {
        if (statement.getBody().isEmptyStmt()) {
            String condition = compact.apply(statement.getCondition());
            Doc bodyComment = emptyBodyOwnBlockComment(statement.getBody());
            Doc conditionComment = comments.ownComment(statement.getCondition(), BlockComment.class::isInstance);
            if (bodyComment != Doc.EMPTY || conditionComment != Doc.EMPTY) {
                String comment = bodyComment != Doc.EMPTY ? commentText(bodyComment) : commentText(conditionComment);
                return Doc.text("do; while (" + comment + " " + condition + ");");
            }
            return Doc.text("do; while (" + condition + ");");
        }
        return Doc.concat(Doc.text("do "), doBody(statement.getBody()), doWhileTail(statement));
    }

    private Doc doBody(Statement body) {
        if (!body.isBlockStmt()) {
            return nestedStatement(body);
        }
        Doc leadingBlockComment = comments.ownComment(body, BlockComment.class::isInstance);
        if (leadingBlockComment == Doc.EMPTY) {
            return nestedStatement(body);
        }
        return Doc.concat(leadingBlockComment, Doc.text(" "), blockRenderer.format(body.asBlockStmt()));
    }

    private Doc doWhileTail(DoStmt statement) {
        Doc trailing = doWhileTrailingLineComment(statement);
        Doc beforeWhileComment = doWhileBeforeWhileBlockComment(statement);
        if (beforeWhileComment != Doc.EMPTY) {
            return Doc.concat(
                Doc.text(
                    " "
                        + commentText(beforeWhileComment)
                        + " while ("
                        + compactWithoutOwnComment.apply(statement.getCondition())
                        + ");"
                ),
                trailing
            );
        }
        return Doc.concat(
            Doc.text(" while "),
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
     * <p>At {@code @default} JavaParser attaches that comment to the {@link DoStmt}, so {@link StatementRuleEnvelope}
     * claims and renders it through the shared statement trailing-comment slot. When the body is written across multiple
     * source lines, JavaParser instead attaches the comment to the {@code while} condition expression, where the
     * condition renderer (which prints the condition without its own comment) drops it. This query reclaims the comment
     * from the condition's own trailing slot and re-emits it as a {@code lineSuffix} after the {@code ;}, matching how
     * {@link #expressionStatementTrailingComment(ExpressionStmt)} and the {@code try} renderer place statement trailing
     * comments. Claiming it here keeps the comment printed exactly once: when the envelope already owns the {@link DoStmt}
     * comment the condition slot is empty, so this path adds nothing.
     */
    private Doc doWhileTrailingLineComment(DoStmt statement) {
        Doc conditionTrailing = comments.trailingLineComment(statement.getCondition());
        if (conditionTrailing == Doc.EMPTY) {
            return Doc.EMPTY;
        }
        return Doc.lineSuffix(Doc.concat(Doc.text(" "), conditionTrailing));
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
            statement instanceof ForStmt forStmt ? emptyBodyOwnBlockComment(forStmt.getBody()) : Doc.EMPTY;
        if (bodyComment == Doc.EMPTY) {
            return Doc.text(header + ";" + trailingEmptyBodyBlockComment(statement));
        }
        return Doc.concat(
            bodyComment,
            Doc.HARD_LINE,
            Doc.text(header + ";" + trailingEmptyBodyBlockComment(statement))
        );
    }

    private String emptyBodyHeaderExpression(Expression expression, Statement body) {
        Doc bodyComment = emptyBodyOwnBlockComment(body);
        if (bodyComment == Doc.EMPTY) {
            return compact.apply(expression);
        }
        return compact.apply(expression) + " " + commentText(bodyComment);
    }

    private Doc emptyBodyOwnBlockComment(Statement body) {
        return comments.ownComment(body, BlockComment.class::isInstance);
    }

    private String commentText(Doc comment) {
        if (comment instanceof Doc.Text text) {
            return text.value();
        }
        return "";
    }

    private Doc trailingLineComment(Node node) {
        // An attached trailing line comment is claimed and rendered once by StatementRuleEnvelope.statement, which runs
        // before this content renderer. When that node's trailing comment is already printed, re-offering it here would be
        // a duplicate claim that only ever rendered empty. Skip the attached re-offer in that case and fall through to the
        // unattached recovery below, which is this path's own responsibility (the envelope never offers unattached
        // trailing comments). Output is unchanged: the attached comment is the envelope's, the unattached one is recovered
        // here exactly as before.
        boolean attachedAlreadyPrinted = commentPlacement.trailingLineComment(node)
                .map(comments::isPrinted)
                .orElse(false);
        Doc own = attachedAlreadyPrinted ? Doc.EMPTY : comments.trailingLineComment(node);
        if (own != Doc.EMPTY) {
            return own;
        }
        return unattachedTrailingLineComment(node);
    }

    /**
     * Recovers block comments that appear after an empty-body semicolon.
     *
     * <p>Those comments are visually part of the empty body, but JavaParser may not attach them to the empty statement
     * node. The raw fallback keeps the same inline placement used by the empty statement fixtures.
     */
    private String trailingEmptyBodyBlockComment(Node node) {
        // unattachedTrailingBlockComment parent-walks to recover a block comment after an empty-body semicolon, so the
        // same comment can be reached from more than one anchor (e.g. the loop statement and its empty body). Skip an
        // already-printed comment so the second anchor does not duplicate-claim it; output is unchanged because the first
        // anchor placed it and the second only ever rendered empty.
        Doc unattached = commentPlacement.unattachedTrailingBlockComment(node)
                .filter(trivia -> !comments.isPrinted(trivia))
                .map(comments::comment)
                .orElse(Doc.EMPTY);
        if (unattached != Doc.EMPTY) {
            return " " + commentText(unattached);
        }
        String raw = rawSource.raw(node);
        int semicolon = raw.lastIndexOf(';');
        if (semicolon < 0 || semicolon + 1 >= raw.length()) {
            return "";
        }
        String trailing = raw.substring(semicolon + 1).strip();
        return trailing.startsWith("/*") ? " " + trailing : "";
    }

    private Doc unattachedTrailingLineComment(Node node) {
        // The same unattached trailing line comment can be reached from more than one anchor node (the placement policy
        // walks parents to recover it), so a sibling or enclosing statement may already have claimed and rendered it.
        // Re-offering an already-printed comment only ever rendered empty, so skip it to avoid a duplicate claim; output
        // is unchanged because the first claimant placed it.
        return commentPlacement.unattachedTrailingLineComment(node)
                .filter(trivia -> !comments.isPrinted(trivia))
                .map(comments::comment)
                .orElse(Doc.EMPTY);
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

    @FunctionalInterface
    interface HuggableArgumentsRenderer {
        Optional<Doc> render(String prefix, NodeList<Expression> arguments);
    }
}
