package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.stmt.AssertStmt;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
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

    private final BiFunction<Expression, LayoutContext, Doc> returnStatementRenderer;

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

    private final BiFunction<String, NodeList<Expression>, Optional<Doc>> eligibleBlockLambdaHug;

    // The expression statement's method-call-chain shape cascade, owned by {@link MethodCallPrinter#statementChain}
    // (statement analogue of {@link ReturnExpressionPrinter}'s {@code returnChain}). The statement printer no longer
    // threads the chain shape-callbacks the cascade used to compose (source-multiline statement call, forced call with
    // terminator, and the final-trailing-comment / has-comments / is-source-multiline / root-is-object-creation /
    // root-is-field-access predicates). It hands the one composite entry the statement-flavored inputs — the {@code ;}
    // terminator, the first-line width closure, and the raw-source whole-statement width measure
    // ({@link #methodCallStatementWidth}) — and the chain printer owns the shape selection.
    private final StatementChainRenderer statementChain;

    private final Function<MethodCallExpr, Doc> brokenMethodCallRenderer;

    private final Function<Expression, Doc> ifConditionRenderer;

    private final ControlConditionPrinter controlConditions;

    private final Function<Expression, String> compactWithOwnBlockComment;

    private final Function<Node, Doc> sameLineBlockCommentBeforeNode;

    private final BiFunction<NodeList<Expression>, Doc, Doc> methodCallArgumentList;

    private final CommentedExpressionListPrinter commentedExpressionLists;

    private final ToIntFunction<String> currentIndentedWidth;

    private final ArgumentHeaviness argumentHeaviness = new ArgumentHeaviness();

    private final TryStatementLayout tryStatementLayout;

    private final IfStatementLayout ifStatementLayout;

    private final LoopStatementLayout loopStatementLayout;

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
            BiFunction<Expression, LayoutContext, Doc> returnStatementRenderer,
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
            BiFunction<String, NodeList<Expression>, Optional<Doc>> eligibleBlockLambdaHug,
            StatementChainRenderer statementChain,
            Function<MethodCallExpr, Doc> brokenMethodCallRenderer,
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
        this.eligibleBlockLambdaHug = eligibleBlockLambdaHug;
        this.statementChain = statementChain;
        this.brokenMethodCallRenderer = brokenMethodCallRenderer;
        this.ifConditionRenderer = ifConditionRenderer;
        this.controlConditions = controlConditions;
        this.compactWithOwnBlockComment = compactWithOwnBlockComment;
        this.sameLineBlockCommentBeforeNode = sameLineBlockCommentBeforeNode;
        this.methodCallArgumentList = methodCallArgumentList;
        this.commentedExpressionLists = commentedExpressionLists;
        this.currentIndentedWidth = currentIndentedWidth;
        this.tryStatementLayout = new TryStatementLayout(
            comments,
            commentPlacement,
            sourceShapePolicy,
            options,
            layoutWidth,
            blockWithLeadingRenderer,
            variableDeclarationRenderer,
            parameterText,
            compact,
            compactTypeLike,
            compactJoinTypeLike,
            modifiers,
            annotationFlatText,
            methodCallArgumentList,
            currentIndentedWidth,
            this::commentText
        );
        this.ifStatementLayout = new IfStatementLayout(
            comments,
            commentPlacement,
            controlConditions,
            options,
            layoutWidth,
            statementRenderer,
            ifConditionRenderer,
            sameLineBlockCommentBeforeNode,
            compact,
            this::nestedStatement,
            this::commentText,
            this::emptyBodyOwnBlockComment,
            this::trailingEmptyBodyBlockComment,
            this::enclosedTrailingLineComment
        );
        this.loopStatementLayout = new LoopStatementLayout(
            comments,
            commentPlacement,
            rawSource,
            sourceShapePolicy,
            options,
            layoutWidth,
            controlConditions,
            statementRenderer,
            blockRenderer,
            brokenMethodCallRenderer,
            compact,
            compactWithoutOwnComment,
            compactJoin,
            compactTypeLike,
            compactWithOwnBlockComment,
            annotationFlatText,
            modifiers,
            this::nestedStatement,
            this::commentText,
            this::emptyBodyOwnBlockComment,
            this::trailingEmptyBodyBlockComment
        );
    }

    /**
     * Renders the structured body of a statement after StatementRuleEnvelope has chosen formatted output.
     *
     * <p>Raw pragma handling stays in {@link StatementRuleEnvelope} because formatter off/on state changes which later
     * statements format. This method only decides the formatted content once the outer gate has decided the statement
     * should not be printed from raw source.
     */
    Doc statement(Statement statement) {
        return statement(statement, layoutWidth::blockStatement);
    }

    Doc statement(Statement statement, ToIntFunction<String> lineWidth) {
        return switch (statement) {
            case BlockStmt blockStmt -> blockRenderer.format(blockStmt, LayoutContext.root());
            case ReturnStmt returnStmt -> returnStatement(returnStmt);
            case ThrowStmt throwStmt -> throwStatement(throwStmt);
            case YieldStmt yieldStmt -> yieldStatement(yieldStmt);
            case ExplicitConstructorInvocationStmt constructorInvocation -> Doc.concat(
                explicitConstructorInvocation(constructorInvocation),
                Doc.text(";")
            );
            case ExpressionStmt expressionStmt -> expressionStatement(expressionStmt, lineWidth);
            case EmptyStmt ignored -> Doc.text(";");
            case AssertStmt assertStmt -> assertStatement(assertStmt);
            case BreakStmt breakStmt -> breakStatement(breakStmt);
            case ContinueStmt continueStmt -> continueStatement(continueStmt);
            case LabeledStmt labeledStmt -> labeledStatement(labeledStmt);
            case LocalClassDeclarationStmt localClassDeclaration -> bodyRenderer.format(
                localClassDeclaration.getClassDeclaration()
            , LayoutContext.root());
            case LocalRecordDeclarationStmt localRecordDeclaration -> bodyRenderer.format(
                localRecordDeclaration.getRecordDeclaration()
            , LayoutContext.root());
            case IfStmt ifStmt -> ifStatementLayout.ifStatement(ifStmt);
            case WhileStmt whileStmt -> loopStatementLayout.whileStatement(whileStmt);
            case DoStmt doStmt -> loopStatementLayout.doStatement(doStmt);
            case TryStmt tryStmt -> tryStatementLayout.tryStatement(tryStmt);
            case SynchronizedStmt synchronizedStmt -> Doc.concat(
                Doc.text("synchronized "),
                controlConditions.controlCondition(
                    synchronizedStmt.getExpression(),
                    "synchronized (",
                    ") {}",
                    layoutWidth::blockStatement
                ),
                Doc.text(" "),
                blockRenderer.format(synchronizedStmt.getBody(), LayoutContext.root())
            );
            case ForStmt forStmt -> loopStatementLayout.forStatement(forStmt);
            case ForEachStmt forEachStmt -> loopStatementLayout.forEachStatement(forEachStmt);
            case SwitchStmt switchStmt -> switchStatementRenderer.format(switchStmt, LayoutContext.root());
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

    private Doc returnStatement(ReturnStmt statement) {
        // Build the positional context for the returned value: it sits in RETURN_VALUE position and owns its own first
        // column after the "return " keyword. The return width helpers measure at that rendered column (nodeLine +
        // leftEdgePrefix "return "), so no line-budget selector is threaded onto the context.
        LayoutContext layout = new LayoutContext(EnclosingConstruct.RETURN_VALUE, "", "", false);
        return statement.getExpression()
                .map(expression -> returnStatementRenderer.apply(expression, layout))
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
            expressionWithTailRenderer.render(thrown, ExpressionTail.SEMICOLON, layoutWidth::blockStatement)
        );
    }

    /**
     * Recovers a line comment that trails {@code throw new X(...);} on the statement's end line.
     *
     * <p>When the constructor argument list breaks one-per-line, {@link CommentedExpressionListPrinter} leaves a comment
     * sitting after the completed call to the enclosing syntax (as it does for {@code ExpressionStmt} via
     * {@link #expressionStatementTrailingComment(ExpressionStmt)} and for a {@code MethodCallExpr} statement via
     * {@code MethodCallPrinter#finalTrailingLineComments}). A {@code throw new X(...)} had no such recovery, so the
     * comment vanished once the list broke.
     *
     * <p>Offered under the throw statement's {@link OwnerSlot#TRAILING} slot and rendered inline as a {@code lineSuffix}
     * after the {@code ;}, claimed once. JavaParser parks it differently by source shape, so this gathers both shapes the
     * envelope's {@link CommentTracker#trailingLineComment(Node)} (which sees only the {@code ThrowStmt}'s own trivia)
     * misses:
     *
     * <ul>
     *   <li>pre-wrap, the wide {@code throw new X(arg,} / {@code arg); // note} source attaches the comment to the last
     *       argument, so it surfaces as a contained line comment of the object creation on its end line; and</li>
     *   <li>after the list breaks, the re-emitted {@code ); // note} closing line re-parses with the comment as a free
     *       orphan of the enclosing block on the statement's end line. Recovering that orphan too (before the block
     *       interleaver would place it on its own line) keeps the inline form stable across re-format.</li>
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
        // double-print it. A comment starting after the body ends is the body's own TRAILING comment (under a collapse a
        // following statement's before-label comment re-buckets there); claiming it here would drop it, since the body
        // renderer reads it back via trailingLineComment. The startsAfterEndOf guard leaves it unclaimed.
        comments.ownComment(
            statement,
            comment -> comment instanceof LineComment && !CommentIndex.startsAfterEndOf(statement, comment)
        );
    }

    /**
     * {@code @collapsed}-only orphan-recovery fallback for a labeled statement's leading comments.
     *
     * <p>{@code @default} is covered by {@link StatementRuleEnvelope} (the {@link LabeledStmt}'s own/adjacent leading
     * comments) and {@link #labeledStatementLeadingComments(LabeledStmt)} (the raw slice between {@code :} and the nested
     * statement, preserving author blank-line groups). Under a collapse those comments re-bucket onto the
     * {@code LabeledStmt} orphans, the label's own comment, and the nested statement's comments, and the now single-line
     * raw slice no longer exposes them, so they drop.
     *
     * <p>This fallback contributes only what those miss — the source-order comments between label and body on those
     * buckets — with two dedupe seams:
     *
     * <ol>
     *   <li><strong>string guard against the raw slice.</strong> The raw-slice path returns {@code List<String>} and never
     *       claims, so it cannot be deduped by identity; any candidate whose normalized text the raw slice already
     *       produced is excluded (same normalization the comment-presence net uses). At {@code @default} the raw slice
     *       produces every leading comment, so this removes all candidates — {@code @default} stays byte-identical.
     *   <li><strong>identity claim.</strong> Each survivor is claimed by {@link CommentTracker#comment}, which renders an
     *       already-claimed comment (e.g. one the envelope printed) as empty, so the overlap is not double-printed.
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
            // This fast path renders an empty labeled for-loop body directly, bypassing the statement envelope, so it
            // must still emit the ForEachStmt's own trailing line comment (`loop: for (...) {} // note`). Claimed once,
            // so it is not double-printed when the idempotence pass re-attaches it to the rebuilt LabeledStmt. With no
            // such comment this returns the byte-identical `for (...) {}` text.
            Doc trailing = comments.trailingLineComment(forEachStmt);
            return trailing == Doc.EMPTY ? emptyFor : Doc.concat(emptyFor, Doc.text(" "), trailing);
        }
        if (statement instanceof BlockStmt blockStmt) {
            return blockRenderer.format(blockStmt, LayoutContext.root());
        }
        return statementRenderer.format(statement, LayoutContext.root());
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

    // The nested statement begins at the first non-blank, non-comment-only line after the label's `:` — whatever its
    // keyword. Matching only `for`/`{` mis-fired for a labeled `while`/`do` loop that contains a `for`: it skipped the
    // loop header and bound the loop body's leading comment to the label, hoisting (then re-duplicating) it every pass.
    private int labeledNestedStatementStart(String labelBody) {
        int cursor = 0;
        for (String line : labelBody.split("\\R", -1)) {
            String leadingStripped = line.stripLeading();
            if (!leadingStripped.isBlank() && !isCommentOnlyLine(leadingStripped.stripTrailing())) {
                return cursor + (line.length() - leadingStripped.length());
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
                layoutWidth::blockStatement
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
        // super(...)/this(...) are constructor invocations, so they opt into the wide-argument-count rule the same way
        // object creation does. A heavy list must break one-per-line, so skip the lambda hug.
        boolean heavy = argumentHeaviness.isHeavy(statement.getArguments(), true);
        Optional<Doc> eligibleHug = heavy ? Optional.empty() : eligibleBlockLambdaHug.apply(prefix, statement.getArguments());
        // super(...)/this(...) reach the plain argument list directly, so they miss the comment-aware breaking
        // method-call and object-creation printers get from CommentedExpressionListPrinter — without it an interior
        // argument's trailing line comment is dropped once the list breaks. Offer the same broken layout first so each
        // argument keeps its trailing comment, claimed once.
        Optional<Doc> commentedArguments = commentedExpressionLists.parenthesized(prefix, statement, statement.getArguments());
        Doc fallback = commentedArguments.orElseGet(() -> Doc.group(
            Doc.concat(
                heavy ? Doc.BREAK_PARENT : Doc.EMPTY,
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
        ));
        if (eligibleHug.isEmpty()) {
            return fallback;
        }
        // Ranks the hug against the same fallback the gate used to fall through to on overflow, at the renderer's true
        // column instead of the fixed block-statement width probe; the hug wins outright whenever its first line fits.
        return Doc.bestFittingFirstLine(List.of(eligibleHug.orElseThrow(), fallback), new int[] {1, 0});
    }

    private Doc expressionStatement(ExpressionStmt statement, ToIntFunction<String> lineWidth) {
        Expression expression = statement.getExpression();
        Doc trailing = expressionStatementTrailingComment(statement);
        if (expression instanceof VariableDeclarationExpr variableDeclaration) {
            return Doc.concat(
                variableDeclarationStatementRenderer.format(variableDeclaration, LayoutContext.root()),
                variableDeclarationTrailingComment(variableDeclaration),
                trailing
            );
        }
        if (expression instanceof MethodCallExpr methodCall) {
            // The chain shape-selection cascade lives in MethodCallPrinter.statementChain. The statement-specific bits
            // stay here — the ExpressionTail.SEMICOLON terminator, the first-line width closure, and the raw-source
            // whole-statement width measure (methodCallStatementWidth) — and the trailing statement comment is
            // concatenated after the chosen chain shape. An empty result means no chain shape was selected (fits within
            // the line width, not final-trailing-comment or source-multiline shaped), so fall through to the general
            // expression-with-tail rendering below.
            Optional<Doc> chain = statementChain.render(
                methodCall,
                statement,
                ExpressionTail.SEMICOLON,
                lineWidth,
                call -> methodCallStatementWidth(call, lineWidth)
            );
            if (chain.isPresent()) {
                return Doc.concat(chain.orElseThrow(), trailing);
            }
        }
        if (expression instanceof AssignExpr assignExpr) {
            return Doc.concat(assignmentStatementRenderer.apply(assignExpr), trailing);
        }
        return Doc.concat(
            expressionWithTailRenderer.render(expression, ExpressionTail.SEMICOLON, lineWidth),
            trailing
        );
    }

    private Doc expressionStatementTrailingComment(ExpressionStmt statement) {
        Doc statementTrailing = trailingLineComment(statement);
        if (statementTrailing != Doc.EMPTY) {
            return Doc.lineSuffix(Doc.concat(Doc.text(" "), statementTrailing));
        }
        Doc recoveredNextSiblingTrailing = nextStatementLeadingTrailingComment(statement);
        if (recoveredNextSiblingTrailing != Doc.EMPTY) {
            return Doc.lineSuffix(Doc.concat(Doc.text(" "), recoveredNextSiblingTrailing));
        }
        return conditionalElseStatementTrailingComment(statement)
                .map(comment -> Doc.lineSuffix(Doc.concat(Doc.text(" "), comments.comment(comment))))
                .orElse(Doc.EMPTY);
    }

    /**
     * Recovers a {@code //} that trails this statement's closing {@code );} but which JavaParser attributes to the
     * following statement's leading trivia once the call's arguments break one-per-line. Claiming it here as this
     * statement's {@link OwnerSlot#TRAILING} keeps the inline {@code ); // note} placement stable across a re-format.
     */
    private Doc nextStatementLeadingTrailingComment(ExpressionStmt statement) {
        if (!(statement.getParentNode().orElse(null) instanceof BlockStmt block)) {
            return Doc.EMPTY;
        }
        NodeList<Statement> siblings = block.getStatements();
        for (int index = 0; index + 1 < siblings.size(); index++) {
            if (siblings.get(index) != statement) {
                continue;
            }
            Statement next = siblings.get(index + 1);
            int nextBeginLine = next.getRange().map(range -> range.begin.line).orElse(Integer.MIN_VALUE);
            return commentPlacement.leadingComment(next)
                    .filter(JavaCommentTrivia::isLine)
                    .filter(trivia -> trivia.startsAfterNodeOnSameLine(statement))
                    // Require the next statement to start on a later line than the comment: when a run of statements
                    // collapses onto one line, only the last — whose sibling begins below — actually trails the comment,
                    // so this claims it exactly once instead of for every statement sharing the line.
                    .filter(trivia -> nextBeginLine > trivia.beginLine(Integer.MAX_VALUE))
                    .map(trivia -> comments.comment(trivia, statement, OwnerSlot.TRAILING))
                    .orElse(Doc.EMPTY);
        }
        return Doc.EMPTY;
    }

    /**
     * Renders the {@code //} line comment that trails a local variable declaration's {@link VariableDeclarationExpr}
     * after the closing {@code ;} (e.g. {@code int limit = 10; // cap}).
     *
     * <p>Routes through the layout-independent {@link CommentTracker#trailingComment(Node)} anchor: bound to the stable
     * {@code (declaration, TRAILING)} slot and deferred as a width-free {@link Doc#lineSuffix(Doc)} after the {@code ;}
     * (so the declaration lays out as if the comment were absent), so it survives whichever ranked layout wins without
     * being dropped or double-printed. The enclosing {@link ExpressionStmt}'s own trailing comment is a distinct
     * {@code (statement, TRAILING)} slot ({@link #expressionStatementTrailingComment(ExpressionStmt)}), so the two never
     * contend.
     */
    private Doc variableDeclarationTrailingComment(VariableDeclarationExpr declaration) {
        return comments.trailingComment(declaration);
    }

    private int methodCallStatementWidth(MethodCallExpr methodCall, ToIntFunction<String> lineWidth) {
        // Measure the SOURCE-NEUTRAL compact form, not normalizeWhitespace(rawWithoutOwnComment): the latter turns each
        // source newline into a space, so a chain the author already wrapped measures wider than the same chain on one
        // line and this gate flips the statement between the generic fan and the collapsing two-selector-fan across passes.
        String compactText = compactWithoutOwnComment.apply(methodCall);
        return lineWidth.applyAsInt(compactText + ";");
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
            return statementRenderer.format(statement, LayoutContext.root());
        }
        if (
            statement.isIfStmt()
            || statement.isForStmt()
            || statement.isForEachStmt()
            || statement.isWhileStmt()
            || statement.isDoStmt()
        ) {
            return Doc.indent(Doc.concat(Doc.HARD_LINE, statementRenderer.format(statement, LayoutContext.root())));
        }
        return leadingLineCommentBody(statement).orElseGet(() -> statementRenderer.format(statement, LayoutContext.root()));
    }

    /**
     * Breaks and indents a braceless {@code if}/{@code else}/{@code do} body that carries its leading {@code //} line
     * comment as its own trivia (the {@code @default} shape).
     *
     * <p>The body-own counterpart of {@link LoopStatementLayout#bracelessLoopBody(Node, Node, Statement)}, used where the
     * enclosing construct already recovers the perturbed attachments elsewhere (the {@code if} close-paren trailing path
     * and the {@code do-while} condition-leading path), so only the body-own slot is handled here. The whole body renders
     * through {@link #statementRenderer}, whose envelope emits the leading comment once; {@link Doc#indent} puts both at
     * the body indent. Empty when the body has no leading line comment, leaving the same-line collapse intact.
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
        return Optional.of(Doc.indent(Doc.concat(Doc.HARD_LINE, statementRenderer.format(statement, LayoutContext.root()))));
    }

    private Doc emptyControlBlock(BlockStmt block) {
        Doc inlineBlockComment = comments.ownComment(block, BlockComment.class::isInstance);
        if (inlineBlockComment != Doc.EMPTY) {
            return Doc.concat(inlineBlockComment, Doc.text(" {"), Doc.HARD_LINE, Doc.text("}"));
        }
        Doc leading = comments.leading(block);
        return Doc.concat(leading, Doc.text("{"), Doc.HARD_LINE, Doc.text("}"));
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

    /**
     * A statement's own content-render trailing line comment: its own attached trailing comment (under the distinct
     * {@code (node, CONTENT_TRAILING)} slot) or, failing that, an unattached trailing comment recovered from a parent
     * bucket (under {@code (node, UNATTACHED_TRAILING)}).
     *
     * <p>The content path an expression statement uses, kept distinct from the outer envelope's {@code (node, TRAILING)}
     * offer and an enclosing construct's {@link #enclosedTrailingLineComment(Node)} offer: when all three fire for one
     * node, the dry-run's first offerer owns the comment and the rest render empty by ownership (no build-order
     * {@code isPrinted} read). For a plain expression statement the envelope offers first, so this attached-own offer
     * renders empty; this path still owns the unattached recovery the envelope never offers.
     */
    private Doc trailingLineComment(Node node) {
        Doc own = comments.contentTrailingLineComment(node);
        if (own != Doc.EMPTY) {
            return own;
        }
        return unattachedTrailingLineComment(node, OwnerSlot.UNATTACHED_TRAILING);
    }

    /**
     * The trailing line comment of a statement whose placement an <em>enclosing</em> construct owns — the callback the
     * {@link IfStatementLayout} uses to render a then/else body's trailing comment in the spot only the {@code if} layout
     * controls (the then-body's comment on its own line before {@code else}).
     *
     * <p>Anchored to the distinct {@code (node, ENCLOSED_TRAILING)} slot (both attached-own and parent-bucket recovery).
     * The enclosing {@code if} layout offers this before the nested body renders, so the dry-run records it as owner and
     * the nested statement's own envelope and content offers render empty by ownership. One slot covers both comments
     * (a node has at most one), reproducing the first-claim-wins winner without a build-order {@code isPrinted} read.
     */
    private Doc enclosedTrailingLineComment(Node node) {
        Doc own = comments.enclosedTrailingLineComment(node);
        if (own != Doc.EMPTY) {
            return own;
        }
        return unattachedTrailingLineComment(node, OwnerSlot.ENCLOSED_TRAILING);
    }

    /**
     * Recovers block comments that appear after an empty-body semicolon.
     *
     * <p>Those comments are visually part of the empty body, but JavaParser may not attach them to the empty statement
     * node. The raw fallback keeps the same inline placement used by the empty statement fixtures.
     */
    private String trailingEmptyBodyBlockComment(Node node) {
        // unattachedTrailingBlockComment parent-walks to recover a block comment after an empty-body semicolon, so the
        // same comment can be reached from more than one anchor (e.g. the loop statement and its empty body). Anchoring
        // to this node's own (node, UNATTACHED_TRAILING_BLOCK) owner disambiguates: the dry-run's first recovering anchor
        // owns it and any other renders Doc.EMPTY (caught below) and falls to the raw fallback — first-claim-wins with no
        // build-order isPrinted skip.
        Doc unattached = commentPlacement.unattachedTrailingBlockComment(node)
                .map(trivia -> comments.comment(trivia, node, OwnerSlot.UNATTACHED_TRAILING_BLOCK))
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

    /**
     * Recovers a trailing line comment that JavaParser parked on a parent bucket rather than on the node it visually
     * trails, anchoring it to {@code node} under the given {@code slot}.
     *
     * <p>The same parent-parked comment can be reached from more than one recovering node and from more than one slot on
     * the same node (the content path uses {@link OwnerSlot#UNATTACHED_TRAILING}, an enclosing {@code if} layout uses
     * {@link OwnerSlot#ENCLOSED_TRAILING}). The {@code slot} parameter keeps those distinct so the first recovering offer
     * owns the comment and the rest render empty — first-claim-wins with no build-order {@code isPrinted} read.
     */
    private Doc unattachedTrailingLineComment(Node node, OwnerSlot slot) {
        return commentPlacement.unattachedTrailingLineComment(node)
                .map(trivia -> comments.comment(trivia, node, slot))
                .filter(doc -> doc != Doc.EMPTY)
                .orElse(Doc.EMPTY);
    }

    /**
     * The single composite entry for an expression statement's method-call-chain shape, collapsing the chain
     * shape-callbacks the statement cascade used to thread. Implemented by {@link MethodCallPrinter#statementChain} (via
     * {@link ExpressionPrinters#statementChain}); the caller supplies only the statement-flavored inputs — the
     * {@code tail} terminator, the {@code lineWidth} first-line width closure, and the {@code statementWidth} raw-source
     * whole-statement width measure — and receives the chosen chain shape, or {@link Optional#empty()} when no chain shape
     * applies and the caller should render the general expression form.
     */
    @FunctionalInterface
    interface StatementChainRenderer {
        Optional<Doc> render(
            MethodCallExpr methodCall,
            ExpressionStmt statement,
            ExpressionTail tail,
            ToIntFunction<String> lineWidth,
            ToIntFunction<MethodCallExpr> statementWidth
        );
    }
}
