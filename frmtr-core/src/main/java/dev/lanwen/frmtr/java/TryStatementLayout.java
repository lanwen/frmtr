package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.TryStmt;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Renders the try-statement family: {@code try}/{@code catch}/{@code finally} and the try-with-resources header, together
 * with the comment recovery those clauses need.
 *
 * <p>This helper owns everything reachable only from {@link StatementPrinter}'s {@code TryStmt} switch arm: the
 * resource-section layout (flat collapse, single attached method-call resource, one-resource-per-line lines, and the
 * width probe that measures the opener at its true rendered column), the multi-catch parameter breaking, the empty-body
 * shapes for try/catch/finally blocks, and the source-shape-independent comment handoffs between adjacent clauses
 * (opener/inter-resource/trailing resource comments, the block/line comments that lead a {@code catch} or
 * {@code finally}, the trailing comment that a completed clause body hands to the next clause, and the final trailing
 * comment of the whole statement). The boundary exists so {@link StatementPrinter}'s statement-kind dispatch can keep the
 * other statement grammars local instead of carrying this large, comment-heavy try cluster inline, mirroring the injected
 * per-kind renderers ({@code blockRenderer}, {@code switchStatementRenderer}) StatementPrinter already delegates to.
 *
 * <p>Expression, type, local-variable-declaration, argument-list, and block formatting stay with their existing owners
 * and are reached through the callbacks injected here (for example {@code compact}, {@code variableDeclarationRenderer},
 * {@code methodCallArgumentList}, and {@code blockWithLeadingRenderer}). Width decisions defer to the injected
 * {@link LayoutWidth} and {@code currentIndentedWidth} probes rather than being recomputed here, and the shared
 * {@code commentText} flattening stays a StatementPrinter concern injected as a handle because many non-try statement
 * paths use it too.
 */
final class TryStatementLayout {

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    private final SourceShapePolicy sourceShapePolicy;

    private final FormatterOptions options;

    private final LayoutWidth layoutWidth;

    private final BiFunction<BlockStmt, Doc, Doc> blockWithLeadingRenderer;

    private final JavaFormatRule<VariableDeclarationExpr> variableDeclarationRenderer;

    private final Function<Parameter, String> parameterText;

    private final Function<Node, String> compact;

    private final Function<Node, String> compactTypeLike;

    private final Function<List<? extends Node>, String> compactJoinTypeLike;

    private final Function<NodeWithModifiers<?>, String> modifiers;

    private final Function<AnnotationExpr, String> annotationFlatText;

    private final BiFunction<NodeList<Expression>, Doc, Doc> methodCallArgumentList;

    private final ToIntFunction<String> currentIndentedWidth;

    private final Function<Doc, String> commentText;

    TryStatementLayout(
            CommentTracker comments,
            JavaCommentPlacementPolicy commentPlacement,
            SourceShapePolicy sourceShapePolicy,
            FormatterOptions options,
            LayoutWidth layoutWidth,
            BiFunction<BlockStmt, Doc, Doc> blockWithLeadingRenderer,
            JavaFormatRule<VariableDeclarationExpr> variableDeclarationRenderer,
            Function<Parameter, String> parameterText,
            Function<Node, String> compact,
            Function<Node, String> compactTypeLike,
            Function<List<? extends Node>, String> compactJoinTypeLike,
            Function<NodeWithModifiers<?>, String> modifiers,
            Function<AnnotationExpr, String> annotationFlatText,
            BiFunction<NodeList<Expression>, Doc, Doc> methodCallArgumentList,
            ToIntFunction<String> currentIndentedWidth,
            Function<Doc, String> commentText
    ) {
        this.comments = comments;
        this.commentPlacement = commentPlacement;
        this.sourceShapePolicy = sourceShapePolicy;
        this.options = options;
        this.layoutWidth = layoutWidth;
        this.blockWithLeadingRenderer = blockWithLeadingRenderer;
        this.variableDeclarationRenderer = variableDeclarationRenderer;
        this.parameterText = parameterText;
        this.compact = compact;
        this.compactTypeLike = compactTypeLike;
        this.compactJoinTypeLike = compactJoinTypeLike;
        this.modifiers = modifiers;
        this.annotationFlatText = annotationFlatText;
        this.methodCallArgumentList = methodCallArgumentList;
        this.currentIndentedWidth = currentIndentedWidth;
        this.commentText = commentText;
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
    Doc tryStatement(TryStmt statement) {
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
            && tryOpenerLineWidth(statement, flat + " {}") <= options.lineWidth()
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
            tryResourceOpenerCommentsDoc(statement, openerComments),
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
     * Measures a try-with-resources opener line at the column where it actually renders.
     *
     * <p>The resource-section fit gates ask whether the flat opener (either {@code try (…) {}} for the whole-section
     * collapse or {@code try (Type name = scope.call(} for the single attached method-call resource) fits on one line.
     * That opener renders at the {@code try} statement's own indentation, which is the enclosing block/type nesting
     * depth: two units for a {@code try} directly inside a method body and one unit deeper for every
     * further block ({@code if}, loop, nested {@code try}, …) around it. Measuring against the fixed
     * {@link LayoutWidth#currentIndented} baseline (one unit) under-counted that indentation for every non-top-level
     * {@code try}, so a resource list that overflowed its real column was collapsed flat anyway and rendered past the
     * width limit (#219). Counting the node's rendered nesting through {@link LayoutWidth#nodeLine} reproduces the true
     * column regardless of source layout, mirroring the return/ternary/unary rendered-column corrections in LDM-2 and the
     * {@code } catch (…)} multi-catch gate in this printer. The {@code currentIndentedWidth} term is kept as a floor so a
     * {@code try} nested directly under a member (no enclosing block) is still measured against at least one unit.
     */
    private int tryOpenerLineWidth(TryStmt statement, String openerLine) {
        return Math.max(
            layoutWidth.nodeLine(statement, openerLine),
            currentIndentedWidth.applyAsInt(openerLine)
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
        if (tryOpenerLineWidth(statement, "try (" + resourcePrefix) > options.lineWidth()) {
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
        // A try-resource variable declaration renders through the width-driven variable-declaration renderer so its
        // initializer breaks by rendered width; a non-declaration resource stays compact.
        Doc body = resource instanceof VariableDeclarationExpr declaration
            ? variableDeclarationRenderer.format(declaration, LayoutContext.root())
            : Doc.text(compact.apply(resource));
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

    private Doc tryResourceOpenerCommentsDoc(TryStmt statement, List<JavaCommentTrivia> openerComments) {
        return Doc.concat(
            openerComments.stream()
                    // A try-resource opener line comment is also offered by the neighboring first-resource render (its
                    // adjacent-leading / leading slot) under the comment's own anchorless (comment, INTERLEAVED) key.
                    // Offering it here under the enclosing try statement's own (statement, INTERLEAVED) anchor lets
                    // comment ownership disambiguate: when the resource render owns it, this slot is not the recorded
                    // owner and comment(...) returns Doc.EMPTY (caught by the != Doc.EMPTY filter below); an opener
                    // comment no resource render claimed is owned here and placed by this slot. Anchoring to the distinct
                    // (statement, INTERLEAVED) key rather than the comment's own node is what makes the ownership gate
                    // sufficient, so no build-order isPrinted skip is needed.
                    .map(comment -> comments.comment(comment, statement, OwnerSlot.INTERLEAVED))
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
                type = commentText.apply(leading) + " " + type;
            }
            String prefix = i == 0 ? "" : "| ";
            String suffix = i == parts.size() - 1 ? " " + parameter.getNameAsString() : "";
            lines.add(Doc.text(prefix + type + suffix));
        }
        return Doc.concat(Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, lines))), Doc.HARD_LINE);
    }
}
