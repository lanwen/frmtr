package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.IntersectionType;
import com.github.javaparser.ast.type.Type;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Chooses variable initializer layout after a declaration printer has selected a variable declarator.
 *
 * <p>This helper owns source comments around {@code =}, source-leading initializer comments, and the width-driven
 * fallback order for arrays, object creations, method-call chains, casts, conditionals, lambdas, strings, and generic
 * expression breaks. The boundary keeps field and local declaration printers focused on declaration prefixes and
 * variable sequencing while initializer-specific line-width probes and construct fallbacks stay in one place.
 */
final class VariableInitializerLayout {

    /**
     * A forced-chain callback that carries the initializer's {@link LayoutContext} alongside its first-line-width probe.
     *
     * <p>LDM-2f (#190). The initializer keyword shares the chain's first line with the assignment prefix
     * ({@code NAME = }), so the caller threads that fixed prefix through {@link LayoutContext#leftEdgePrefix()}. The chain
     * width gate that reads it ({@code MethodCallChainPrinter.compactRootLineWidth}) then attributes the prefix at the
     * rendered column instead of inferring it from the initializer value's stale source column, and the object-creation
     * dot-split tail ({@code MethodCallChainPrinter.refuseOpeningSingleSimpleObjectRootChainTail}) becomes reachable. This is
     * the initializer analogue of {@link ReturnExpressionPrinter.ChainWithLayout}; there is no three-argument
     * {@link BiFunction}, so it is its own interface. The {@code firstLineWidth} probe is retained because the greedy
     * packer and stay-flat gates still measure through it; the {@link LayoutContext} only adds the same-line-prefix fact
     * the fixed-column gate needs.
     */
    @FunctionalInterface
    interface ForcedChainWithLayout {
        Optional<Doc> apply(MethodCallExpr expression, ToIntFunction<String> firstLineWidth, LayoutContext layout);
    }

    /**
     * SPIKE (fan-root-true-column, #190). The source-neutral canonical-fan callback: emits {@code chainFanOut} for a
     * fan-threshold, comment/lambda-free chain independent of the author's source shape, or empty when the chain is
     * withheld (comment / block-lambda / expression-lambda chains — the deferred lambda-arrow seam). This is the same
     * delegate the return-value path uses; the initializer's break-after-{@code =} decider ranks the fan it produces
     * against the break-after-{@code =} shape at the true column, so a fan-threshold chain (even one with source-multiline
     * selector arguments, which the imperative {@code forcedMethodCallChain} would render source-sensitively via
     * {@code canAttachFirstSegmentToSimpleRoot}) is idempotent.
     */
    @FunctionalInterface
    interface CanonicalFanChain {
        Optional<Doc> apply(MethodCallExpr expression, String suffix, LayoutContext layout);
    }

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    private final SourceShapePolicy sourceShapePolicy;

    private final RawSource rawSource;

    private final FormatterOptions options;

    private final LayoutWidth layoutWidth;

    private final Function<Node, String> compactTypeLike;

    private final Function<Node, String> compact;

    private final ConditionalExpressionLineProjection conditionalProjection;

    private final Function<Node, String> compactWithoutOwnComment;

    private final Function<List<? extends Node>, String> compactJoin;

    private final Function<Expression, Doc> expression;

    private final Function<Expression, Doc> expressionWithoutOwnComment;

    private final Predicate<BinaryExpr> binaryExpressionHasLineComments;

    private final Function<BinaryExpr, Doc> binaryExpressionLinesWithComments;

    private final BiFunction<Expression, LayoutContext, Optional<Doc>> suffixedEnclosedExpression;

    private final Function<ArrayAccessExpr, Doc> arrayAccessWithBrokenEnclosedName;

    private final Predicate<BinaryExpr> shouldKeepCastDivisionContinuationFlat;

    private final BiFunction<Expression, Boolean, Doc> binaryExpressionLines;

    private final BiFunction<Expression, Boolean, Doc> parenthesizedBreak;

    private final Function<MethodCallExpr, Doc> methodCall;

    private final Function<MethodCallExpr, Doc> brokenMethodCall;

    private final Function<MethodCallExpr, Optional<Doc>> mixedFieldMethodCallChain;

    // Output-seam slice #2: the initializer's single forced method-call-chain shape entry, owned by
    // {@link MethodCallPrinter#initializerChain} (initializer analogue of {@link ReturnExpressionPrinter}'s
    // {@code returnChain}). The initializer's chain-shape SELECTION stays here — it interleaves each shape with
    // break-after-{@code =} and {@code NAME = } prefix gating — but the forced-chain shape itself is delegated through this
    // one entry from all three forced-chain positions (direct chain, expression-lambda body, broken-after-{@code =}
    // fallback). The caller threads the {@code NAME = } left-edge prefix (or {@link LayoutContext#root()}) and the
    // first-line width probe, exactly as the former raw forced-chain callback did.
    private final ForcedChainWithLayout initializerChain;

    private final CanonicalFanChain canonicalFanChain;

    private final BiFunction<MethodCallExpr, ToIntFunction<String>, Optional<Doc>> packedMethodCallChain;

    private final Function<MethodCallExpr, Doc> methodCallWithSemicolon;

    private final Function<MethodCallExpr, Optional<Expression>> mixedFieldMethodCallRoot;

    private final Function<MethodCallExpr, String> methodCallChainFirstLine;

    private final Predicate<MethodCallExpr> methodCallChainRootIsObjectCreation;

    private final Predicate<MethodCallExpr> methodCallChainIsSourceMultiline;

    private final Function<
        MethodCallExpr,
        MethodCallChainSourcePlanner.InitializerChainShape
    > methodCallChainInitializerShape;

    private final Function<Type, Doc> castType;

    private final Function<ConditionalExpr, Doc> brokenConditionalExpression;

    private final Predicate<ConditionalExpr> shouldBreakBeforeConditionalInitializer;

    private final BiPredicate<ArrayCreationExpr, ToIntFunction<String>> arrayCreationTypeBreaks;

    private final Function<ArrayCreationExpr, String> arrayCreationPrefix;

    private final BiFunction<ArrayInitializerExpr, Boolean, Doc> arrayInitializer;

    private final BiFunction<ArrayInitializerExpr, String, String> compactArrayInitializerWithSourceSpacing;

    private final Function<ObjectCreationExpr, String> objectCreationPrefix;

    private final Function<ClassOrInterfaceType, String> typeNameWithoutArguments;

    private final Function<ClassOrInterfaceType, Doc> brokenClassOrInterfaceType;

    private final Predicate<Expression> shouldPrintScopeAsDoc;

    private final Predicate<Expression> binaryFansChainOperand;

    private final Function<MethodCallExpr, String> methodCallPrefix;

    private final BiFunction<NodeList<Expression>, Doc, Doc> methodCallArgumentList;

    private final FieldDeclarationPrinter.HuggableArgumentsRenderer huggableBlockLambdaArguments;

    private final Function<LambdaExpr, String> lambdaParameters;

    private final BiPredicate<LambdaExpr, String> lambdaParametersShouldBreak;

    private final Function<LambdaExpr, Doc> lambdaExpression;

    VariableInitializerLayout(
            JavaFormatContext context,
            Function<Expression, Doc> expression,
            Function<Expression, Doc> expressionWithoutOwnComment,
            Predicate<BinaryExpr> binaryExpressionHasLineComments,
            Function<BinaryExpr, Doc> binaryExpressionLinesWithComments,
            BiFunction<Expression, LayoutContext, Optional<Doc>> suffixedEnclosedExpression,
            Function<ArrayAccessExpr, Doc> arrayAccessWithBrokenEnclosedName,
            Predicate<BinaryExpr> shouldKeepCastDivisionContinuationFlat,
            BiFunction<Expression, Boolean, Doc> binaryExpressionLines,
            BiFunction<Expression, Boolean, Doc> parenthesizedBreak,
            Function<MethodCallExpr, Doc> methodCall,
            Function<MethodCallExpr, Doc> brokenMethodCall,
            Function<MethodCallExpr, Optional<Doc>> mixedFieldMethodCallChain,
            ForcedChainWithLayout initializerChain,
            CanonicalFanChain canonicalFanChain,
            BiFunction<MethodCallExpr, ToIntFunction<String>, Optional<Doc>> packedMethodCallChain,
            Function<MethodCallExpr, Doc> methodCallWithSemicolon,
            Function<MethodCallExpr, Optional<Expression>> mixedFieldMethodCallRoot,
            Function<MethodCallExpr, String> methodCallChainFirstLine,
            Predicate<MethodCallExpr> methodCallChainRootIsObjectCreation,
            Predicate<MethodCallExpr> methodCallChainIsSourceMultiline,
            Function<MethodCallExpr, MethodCallChainSourcePlanner.InitializerChainShape> methodCallChainInitializerShape,
            Function<Type, Doc> castType,
            Function<ConditionalExpr, Doc> brokenConditionalExpression,
            Predicate<ConditionalExpr> shouldBreakBeforeConditionalInitializer,
            BiPredicate<ArrayCreationExpr, ToIntFunction<String>> arrayCreationTypeBreaks,
            Function<ArrayCreationExpr, String> arrayCreationPrefix,
            BiFunction<ArrayInitializerExpr, Boolean, Doc> arrayInitializer,
            BiFunction<ArrayInitializerExpr, String, String> compactArrayInitializerWithSourceSpacing,
            Function<ObjectCreationExpr, String> objectCreationPrefix,
            Function<ClassOrInterfaceType, String> typeNameWithoutArguments,
            Function<ClassOrInterfaceType, Doc> brokenClassOrInterfaceType,
            Predicate<Expression> shouldPrintScopeAsDoc,
            Predicate<Expression> binaryFansChainOperand,
            Function<MethodCallExpr, String> methodCallPrefix,
            BiFunction<NodeList<Expression>, Doc, Doc> methodCallArgumentList,
            FieldDeclarationPrinter.HuggableArgumentsRenderer huggableBlockLambdaArguments,
            Function<LambdaExpr, String> lambdaParameters,
            BiPredicate<LambdaExpr, String> lambdaParametersShouldBreak,
            Function<LambdaExpr, Doc> lambdaExpression
    ) {
        this.comments = context.comments;
        this.commentPlacement = context.commentPlacementPolicy;
        this.sourceShapePolicy = context.sourceShapePolicy;
        this.rawSource = context.rawSource;
        this.options = context.options;
        this.layoutWidth = context.layoutWidth;
        this.compactTypeLike = context.compactSource::compactTypeLike;
        this.compact = context.compactSource::compact;
        this.conditionalProjection = new ConditionalExpressionLineProjection(context.compactSource::compact);
        this.compactWithoutOwnComment = context.compactSource::compactWithoutOwnComment;
        this.compactJoin = context.compactSource::compactJoin;
        this.expression = expression;
        this.expressionWithoutOwnComment = expressionWithoutOwnComment;
        this.binaryExpressionHasLineComments = binaryExpressionHasLineComments;
        this.binaryExpressionLinesWithComments = binaryExpressionLinesWithComments;
        this.suffixedEnclosedExpression = suffixedEnclosedExpression;
        this.arrayAccessWithBrokenEnclosedName = arrayAccessWithBrokenEnclosedName;
        this.shouldKeepCastDivisionContinuationFlat = shouldKeepCastDivisionContinuationFlat;
        this.binaryExpressionLines = binaryExpressionLines;
        this.parenthesizedBreak = parenthesizedBreak;
        this.methodCall = methodCall;
        this.brokenMethodCall = brokenMethodCall;
        this.mixedFieldMethodCallChain = mixedFieldMethodCallChain;
        this.initializerChain = initializerChain;
        this.canonicalFanChain = canonicalFanChain;
        this.packedMethodCallChain = packedMethodCallChain;
        this.methodCallWithSemicolon = methodCallWithSemicolon;
        this.mixedFieldMethodCallRoot = mixedFieldMethodCallRoot;
        this.methodCallChainFirstLine = methodCallChainFirstLine;
        this.methodCallChainRootIsObjectCreation = methodCallChainRootIsObjectCreation;
        this.methodCallChainIsSourceMultiline = methodCallChainIsSourceMultiline;
        this.methodCallChainInitializerShape = methodCallChainInitializerShape;
        this.castType = castType;
        this.brokenConditionalExpression = brokenConditionalExpression;
        this.shouldBreakBeforeConditionalInitializer = shouldBreakBeforeConditionalInitializer;
        this.arrayCreationTypeBreaks = arrayCreationTypeBreaks;
        this.arrayCreationPrefix = arrayCreationPrefix;
        this.arrayInitializer = arrayInitializer;
        this.compactArrayInitializerWithSourceSpacing = compactArrayInitializerWithSourceSpacing;
        this.objectCreationPrefix = objectCreationPrefix;
        this.typeNameWithoutArguments = typeNameWithoutArguments;
        this.brokenClassOrInterfaceType = brokenClassOrInterfaceType;
        this.shouldPrintScopeAsDoc = shouldPrintScopeAsDoc;
        this.binaryFansChainOperand = binaryFansChainOperand;
        this.methodCallPrefix = methodCallPrefix;
        this.methodCallArgumentList = methodCallArgumentList;
        this.huggableBlockLambdaArguments = huggableBlockLambdaArguments;
        this.lambdaParameters = lambdaParameters;
        this.lambdaParametersShouldBreak = lambdaParametersShouldBreak;
        this.lambdaExpression = lambdaExpression;
    }

    Doc variableWithStatementTerminator(VariableDeclarator variable, String declarationPrefix) {
        if (
            variable.getInitializer().orElse(null) instanceof MethodCallExpr methodCall
            && methodCallNeedsStatementTerminatorTail(variable, methodCall)
        ) {
            Doc variableInitializerTailComment = initializerTailLineComment(variable, methodCall)
                    .map(comments::comment)
                    .orElse(Doc.EMPTY);
            Doc declaration = variableWithMethodCallChain(
                variable,
                variableName(variable),
                declarationPrefix + variable.getNameAsString(),
                methodCall,
                methodCallChainFirstLine.apply(methodCall),
                methodCallWithSemicolon.apply(methodCall)
            );
            return Doc.concat(declaration, trailingLineComment(variableInitializerTailComment));
        }
        if (
            variable.getInitializer().orElse(null) instanceof MethodCallExpr methodCall
            && !methodCallFinalTrailingLineComments(methodCall).isEmpty()
        ) {
            // Whether the chain's final segment carries a trailing line comment
            // ({@code .thenMany(Flux.empty()); // note}) is a structural correctness property, not a source-shape one, so
            // this route claims it whenever such a comment is present, independent of source shape. The chain renderer
            // attaches the comment as a {@code lineSuffix} after the {@code ;} — without this route the comment falls to
            // the generic declarator-trailing path, which drops it onto its own line above a dangling {@code ;}.
            String flatName = declarationPrefix + variable.getNameAsString();
            // PR #279 review (#11): a comment-carrying, single-empty-tail object-creation chain whose constructor root
            // fits on the assignment line dot-breaks (constructor on the {@code = } line, tail selector on its own dotted
            // continuation line) rather than breaking after {@code =}. This must be decided BEFORE the chain doc is built:
            // the fan claims the trailing comment itself, and {@code methodCallWithSemicolon} would otherwise claim it first
            // (comment claims fire at doc-build time), leaving the fan's re-render empty and dropping the comment.
            Optional<Doc> dotBrokenTail = dotBrokenObjectRootTailChain(
                variable,
                variableName(variable),
                flatName,
                methodCall
            );
            if (dotBrokenTail.isPresent()) {
                return dotBrokenTail.orElseThrow();
            }
            return variableWithMethodCallChain(
                variable,
                variableName(variable),
                flatName,
                methodCall,
                methodCallChainFirstLine.apply(methodCall),
                methodCallWithSemicolon.apply(methodCall)
            );
        }
        Doc trailingLineComment = trailingDeclaratorLineComment(variable);
        Doc preSemicolonInitializerComment = preSemicolonInitializerComment(variable);
        // When the terminating `;` stays on the declaration line (no pre-`;` comment forcing it down), thread it into the
        // initializer so the (A) conditional group measures the flat form's fit *with* its `;` at the true column — the
        // same one column the old `compact + ";"` gate counted. When a pre-`;` comment drops the `;` onto its own line the
        // group terminator is empty and the initializer stays on the imperative cascade, with the HARD_LINE + `;` appended
        // after the shape exactly as before.
        boolean semicolonOnDeclarationLine = preSemicolonInitializerComment == Doc.EMPTY;
        Doc groupTerminator = semicolonOnDeclarationLine ? Doc.text(";") : Doc.EMPTY;
        Doc declaration = variable.getInitializer()
                .map(initializer -> variableWithInitializer(variable, initializer, declarationPrefix, groupTerminator))
                .orElseGet(() -> Doc.text(variableName(variable)));
        if (semicolonOnDeclarationLine && variable.getInitializer().isPresent()) {
            // The initializer shape already carries the `;` (threaded as the group terminator above).
            return Doc.concat(declaration, trailingLineComment(trailingLineComment));
        }
        Doc semicolon = semicolonOnDeclarationLine
            ? Doc.text(";")
            : Doc.concat(Doc.HARD_LINE, Doc.text(";"));
        return Doc.concat(
            declaration,
            preSemicolonInitializerComment,
            semicolon,
            trailingLineComment(trailingLineComment)
        );
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
    private Doc trailingDeclaratorLineComment(VariableDeclarator variable) {
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
    private Doc preSemicolonInitializerComment(VariableDeclarator variable) {
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

    private boolean methodCallNeedsStatementTerminatorTail(VariableDeclarator variable, MethodCallExpr methodCall) {
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

    private List<JavaCommentTrivia> methodCallFinalTrailingLineComments(MethodCallExpr expression) {
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

    private Optional<JavaCommentTrivia> initializerTailLineComment(
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

    private Doc trailingLineComment(Doc comment) {
        return comment == Doc.EMPTY ? Doc.EMPTY : Doc.concat(Doc.text(" "), comment);
    }

    /**
     * Chooses the initializer shape for a declarator whose terminator is emitted separately (a non-last multi-declarator
     * variable, whose {@code ,} the joining printer inserts). Passing {@link Doc#EMPTY} as the group terminator keeps this
     * path on the historical imperative cascade, byte-identical to before the {@code (A)} renderer-measured gate.
     */
    Doc variableWithInitializer(
            VariableDeclarator variable,
            Expression initializer,
            String declarationPrefix
    ) {
        return variableWithInitializer(variable, initializer, declarationPrefix, Doc.EMPTY);
    }

    /**
     * Chooses the initializer shape while preserving comments around {@code =}, source-leading initializer comments,
     * and construct-specific break rules before falling back to the shared expression renderer.
     *
     * <p>{@code groupTerminator} is the same-line terminator ({@code ;}) that the caller would otherwise append after the
     * whole declaration. When it is non-{@link Doc#EMPTY} and the initializer is eligible (see
     * {@link #canMeasureInitializerAtRenderedColumn}), it is folded into both arms of the {@code (A)} conditional group so
     * the renderer measures the flat form's fit <em>with</em> its terminator at the true column — reproducing the old
     * {@code compact + ";"} gate width. Otherwise it is appended after the imperative shape, exactly as before.
     */
    Doc variableWithInitializer(
            VariableDeclarator variable,
            Expression initializer,
            String declarationPrefix,
            Doc groupTerminator
    ) {
        String flat = declarationPrefix + variable.getNameAsString() + " = " + compact.apply(initializer) + ";";
        String name = variableName(variable);
        // SPIKE (fan-root-true-column, #190 foundation). Checked FIRST — ahead of BOTH the source-shape preempt tier and
        // the (A) renderer-measured gate — because the oscillation it closes is exactly those source-shape-gated routes
        // disagreeing across passes for a fan-carrying initializer. The source-shape tier's chain branches (the
        // object-creation source-multiline branches) fire on a source-multiline pass and produce a shape the (A) gate's
        // flat-source pass does not, so a fan-threshold chain routed through them oscillates. Claiming the fan-carrying
        // initializer here — with a Doc.bestFitting whose two
        // AST-derived arms (attach after `NAME = ` versus break after `=`) are ranked at the true column — makes the
        // break-after-`=` verdict a fixpoint by construction and pre-empts the source-shape routes for exactly these
        // chains. It self-gates to comment-free fan carriers (returns empty otherwise), so every comment-bearing or
        // non-fan initializer still reaches the preempt tier and (A) gate below byte-identically.
        if (groupTerminator != Doc.EMPTY) {
            Optional<Doc> fanBestFitting = variableInitializerFanBestFitting(
                variable,
                initializer,
                name,
                declarationPrefix,
                flat,
                groupTerminator
            );
            if (fanBestFitting.isPresent()) {
                return fanBestFitting.orElseThrow();
            }
        }
        // Comment-around-`=`, source-leading comments, and the source-shape/self-breaking preempts run first and, when one
        // fires, own the whole shape. They are terminator-agnostic, so the caller's same-line terminator is appended after
        // whichever shape they return (byte-identical to the historical `concat(declaration, ";")`). Only when none fires
        // does control reach the (A) renderer-measured gate below.
        Optional<Doc> commentOrPreempt = variableInitializerCommentAndSourceShapeTier(
            variable,
            initializer,
            name,
            declarationPrefix,
            flat
        );
        if (commentOrPreempt.isPresent()) {
            return Doc.concat(commentOrPreempt.orElseThrow(), groupTerminator);
        }
        // (A) Master over-width gate at the real rendered column. The ~10 repeated
        // `variableInitializer(variable, flat) > lineWidth` tests below (all comparing the same reconstructed
        // AST-nesting-depth baseline) collapse into a single Doc.conditionalGroup: the renderer decides flat-versus-broken
        // at the true running column. The flat arm is ordinary expression dispatch; the broken arm is the existing
        // construct-kind broken-shape dispatch (the same cascade bodies, only reached now when the renderer judges the
        // flat form too wide). The earlier reconstructed baseline could disagree with the column write reaches (the
        // #137/#155 width-at-wrong-column family) and print a genuinely over-width initializer flat, then break it on a
        // later pass; measuring at the real column makes the decision a fixpoint by construction rather than by tuning.
        if (canMeasureInitializerAtRenderedColumn(initializer, groupTerminator)) {
            Doc flatInitializer = Doc.concat(
                Doc.text(name + " = "),
                expression.apply(initializer),
                groupTerminator
            );
            Doc brokenInitializer = Doc.concat(
                variableInitializerBrokenOrFlat(variable, initializer, name, declarationPrefix, flat, true),
                groupTerminator
            );
            return Doc.conditionalGroup(List.of(flatInitializer, brokenInitializer));
        }
        return Doc.concat(
            variableInitializerBrokenOrFlat(variable, initializer, name, declarationPrefix, flat, false),
            groupTerminator
        );
    }

    /**
     * Runs the comment-around-{@code =}, source-leading-comment, and source-shape/self-breaking preempt tier, returning the
     * chosen shape when one branch owns the initializer or {@link Optional#empty()} to fall through to the {@code (A)}
     * renderer-measured gate.
     *
     * <p>This is the initializer's analogue of {@code ReturnExpressionPrinter}'s {@code preemptedReturnValue}: everything
     * whose broken shape is chosen by width-ranking or by a preserved source shape (a source-multiline object creation or
     * chain, a conditional whose broken ternary shape is ranked, a block-lambda-argument receiver break, an over-width
     * {@code blockStatement}-gated array/object/binary/complement break) is decided here, imperatively, exactly as before.
     * Keeping this tier untouched means the {@code (A)} conditional group only ever moves the flat-versus-broken verdict
     * for the residual, single-line-flat, comment-free initializers — never a ranked or source-preserved shape. The
     * returned shape carries no trailing terminator; the caller appends it.
     */
    private Optional<Doc> variableInitializerCommentAndSourceShapeTier(
            VariableDeclarator variable,
            Expression initializer,
            String name,
            String declarationPrefix,
            String flat
    ) {
        Optional<Doc> preEqualsBlockComment = preEqualsBlockComment(variable, initializer);
        if (preEqualsBlockComment.isPresent()) {
            String commentedName = name + " " + commentText(preEqualsBlockComment.orElseThrow());
            String commentedFlat = declarationPrefix
                + commentedName
                + " = "
                + compactWithoutOwnComment.apply(initializer)
                + ";";
            if (layoutWidth.blockStatement(commentedFlat) > options.lineWidth()) {
                return Optional.of(Doc.concat(
                    Doc.text(commentedName + " ="),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, expressionWithoutOwnComment.apply(initializer)))
                ));
            }
            return Optional.of(
                Doc.concat(Doc.text(commentedName + " = "), expressionWithoutOwnComment.apply(initializer))
            );
        }
        Optional<Doc> postEqualsBlockComment = postEqualsBlockComment(variable, initializer);
        if (postEqualsBlockComment.isPresent()) {
            String commentText = commentText(postEqualsBlockComment.orElseThrow());
            String commentedFlat = declarationPrefix
                + name
                + " = "
                + commentText
                + " "
                + compactWithoutOwnComment.apply(initializer)
                + ";";
            if (layoutWidth.blockStatement(commentedFlat) > options.lineWidth()) {
                return Optional.of(Doc.concat(
                    Doc.text(name + " = " + commentText),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, expressionWithoutOwnComment.apply(initializer)))
                ));
            }
            return Optional.of(Doc.concat(
                Doc.text(name + " = " + commentText + " "),
                expressionWithoutOwnComment.apply(initializer)
            ));
        }
        Optional<Doc> leadingInitializerComments = leadingInitializerComments(variable, initializer);
        if (leadingInitializerComments.isPresent()) {
            return Optional.of(Doc.concat(
                Doc.text(name + " ="),
                Doc.indent(
                    Doc.concat(
                        Doc.HARD_LINE,
                        leadingInitializerComments.orElseThrow(),
                        Doc.HARD_LINE,
                        expression.apply(initializer)
                    )
                )
            ));
        }
        if (initializer instanceof BinaryExpr binaryExpr && binaryExpressionHasLineComments.test(binaryExpr)) {
            if (binaryInitializerCanKeepFirstOperandWithEquals(variable, declarationPrefix, binaryExpr)) {
                return Optional.of(Doc.concat(
                    Doc.text(name + " = "),
                    Doc.indent(binaryExpressionLinesWithComments.apply(binaryExpr))
                ));
            }
            return Optional.of(Doc.concat(
                Doc.text(name + " ="),
                Doc.indent(Doc.concat(Doc.HARD_LINE, binaryExpressionLinesWithComments.apply(binaryExpr)))
            ));
        }
        if (
            initializer instanceof ConditionalExpr conditionalExpr
            && conditionalInitializerLineOverflows(variable, declarationPrefix, conditionalExpr)
            && !initializerHasOwnBreak(initializer)
        ) {
            return Optional.of(
                conditionalInitializer(name, declarationPrefix + variable.getNameAsString(), conditionalExpr)
            );
        }
        if (
            initializer instanceof MethodCallExpr methodCall
            && methodCallHasBlockLambdaArgument(methodCall)
            && !methodCallHasLeadingCommentedBlockLambdaArgument(methodCall)
            && !methodCallHasContainedCommentObjectCreationBlockLambdaArgument(methodCall)
        ) {
            Optional<Doc> receiverBreakCall = variableWithReceiverBreakBeforeOverWidthHuggableBlockLambdaArguments(
                variable,
                name,
                declarationPrefix + variable.getNameAsString(),
                methodCall
            );
            if (receiverBreakCall.isPresent()) {
                return Optional.of(receiverBreakCall.orElseThrow());
            }
        }
        if (
            initializer instanceof MethodCallExpr methodCall
            && (methodCallHasLeadingCommentedBlockLambdaArgument(methodCall)
                || methodCallHasContainedCommentObjectCreationBlockLambdaArgument(methodCall))
        ) {
            Optional<Doc> brokenCall = variableWithLeadingCommentedBlockLambdaMethodCall(
                variable,
                name,
                declarationPrefix + variable.getNameAsString(),
                methodCall
            );
            if (brokenCall.isPresent()) {
                return Optional.of(brokenCall.orElseThrow());
            }
        }
        if (layoutWidth.blockStatement(flat) > options.lineWidth()) {
            // The initializer line is already over width, so the enclosed suffix receiver must lead with a break; that
            // positional fact rides on the LayoutContext (#189) rather than a loose boolean argument.
            Optional<Doc> suffixedEnclosedInitializer =
                suffixedEnclosedExpression.apply(initializer, LayoutContext.root().withLeadingBreak(true));
            if (suffixedEnclosedInitializer.isPresent()) {
                return Optional.of(Doc.concat(Doc.text(name + " = "), suffixedEnclosedInitializer.orElseThrow()));
            }
            if (initializer instanceof ConditionalExpr conditionalExpr && !initializerHasOwnBreak(initializer)) {
                return Optional.of(
                    conditionalInitializer(name, declarationPrefix + variable.getNameAsString(), conditionalExpr)
                );
            }
            if (
                initializer instanceof ArrayAccessExpr arrayAccessExpr
                && arrayAccessExpr.getName().isEnclosedExpr()
            ) {
                return Optional.of(
                    Doc.concat(Doc.text(name + " = "), arrayAccessWithBrokenEnclosedName.apply(arrayAccessExpr))
                );
            }
            if (initializer instanceof ArrayCreationExpr arrayCreationExpr) {
                Optional<Doc> arrayCreation = variableWithBrokenArrayCreation(
                    name,
                    declarationPrefix + variable.getNameAsString(),
                    arrayCreationExpr
                );
                if (arrayCreation.isPresent()) {
                    return Optional.of(arrayCreation.orElseThrow());
                }
            }
            if (initializer instanceof ObjectCreationExpr objectCreationExpr) {
                Optional<Doc> objectCreation = variableWithBrokenObjectCreation(
                    variable,
                    name,
                    declarationPrefix + variable.getNameAsString(),
                    objectCreationExpr
                );
                if (objectCreation.isPresent()) {
                    return Optional.of(objectCreation.orElseThrow());
                }
            }
            if (logicalComplementOfParenthesizedBinary(initializer) instanceof Expression inner) {
                return Optional.of(Doc.concat(
                    Doc.text(name + " = !"),
                    parenthesizedBreak.apply(inner, true)
                ));
            }
            if (initializer instanceof BinaryExpr binaryExpr) {
                if (binaryInitializerCanKeepFirstOperandWithEquals(variable, declarationPrefix, binaryExpr)) {
                    return Optional.of(Doc.concat(
                        Doc.text(name + " = "),
                        Doc.indent(binaryExpressionLines.apply(initializer, true))
                    ));
                }
                if (shouldKeepCastDivisionContinuationFlat.test(binaryExpr)) {
                    return Optional.of(Doc.concat(
                        Doc.text(name + " ="),
                        Doc.indent(Doc.concat(Doc.HARD_LINE, expression.apply(binaryExpr)))
                    ));
                }
                return Optional.of(Doc.concat(
                    Doc.text(name + " ="),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, binaryExpressionLines.apply(initializer, true)))
                ));
            }
        }
        if (
            initializer instanceof MethodCallExpr methodCall
            && methodCall.getScope().filter(TextBlockLiteralExpr.class::isInstance).isPresent()
        ) {
            return Optional.of(Doc.concat(Doc.text(name + " = "), this.methodCall.apply(methodCall)));
        }

        return Optional.empty();
    }

    /**
     * Decides whether a comment-free initializer's flat-versus-broken verdict may be handed to the renderer-measured
     * {@link Doc#conditionalGroup} (A), or must stay on the historical imperative cascade to remain byte-identical.
     *
     * <p>The conditional group measures its flat arm at the real running column and picks the broken arm when the flat
     * arm does not fit. That only reproduces the old {@code variableInitializer(variable, compact + ";")} gate when two
     * conditions hold, so both are required here:
     *
     * <ul>
     *   <li><b>The flat arm is a single line.</b> The old gate measured the <em>compact</em> (single-line) projection,
     *       whereas the group measures the actual flat {@link Doc}. For a construct that renders its own internal breaks
     *       even when narrow — an own-break initializer (array/switch/anonymous-class), a source-multiline shape the
     *       policy preserves, or a cast wrapping either — the flat {@code Doc} carries a forced break, so the group would
     *       read it as never-fitting and always pick the broken arm, diverging from the old compact-measured verdict. A
     *       comment-bearing initializer is excluded for a different reason (both arms would claim its comments), so the
     *       comment guard is folded in here too. These stay on the imperative cascade, which renders the initializer
     *       exactly once via {@code expression.apply} (its own break intact) and reproduces the old per-branch gate.</li>
     *   <li><b>The trailing terminator is measurable inline.</b> The {@code ;} (or {@code ,}) that follows the initializer
     *       on the same line is one column the old gate counted (its {@code flat} ended with {@code ;}); the group only
     *       counts it when it is part of the measured arm. The statement-terminator caller threads {@code Doc.text(";")}
     *       here so both arms carry it; the multi-declarator comma path and the pre-{@code ;}-comment path pass
     *       {@link Doc#EMPTY} and stay imperative, where the terminator is appended after the shape exactly as before.</li>
     * </ul>
     */
    private boolean canMeasureInitializerAtRenderedColumn(Expression initializer, Doc groupTerminator) {
        return groupTerminator != Doc.EMPTY
            && initializer.getComment().isEmpty()
            && initializer.getAllContainedComments().isEmpty()
            && !initializerHasOwnBreak(initializer)
            && !(initializer instanceof CastExpr);
    }

    /**
     * SPIKE (fan-root-true-column, #190 foundation). Makes the break-after-{@code =} verdict of a fan-carrying initializer
     * SOURCE-NEUTRAL by ranking two AST-derived shapes with {@link Doc#bestFitting} at the true rendered column.
     *
     * <p>A fan-carrying initializer's rendered value <em>force-fans</em> an internal chain, so the value's {@link Doc}
     * carries a hard break the {@code conditionalGroup} flat arm can never absorb; a plain flat-versus-broken gate would
     * therefore diverge across passes. Both {@link Doc#bestFitting} arms are pure AST functions, so both passes rank
     * the same two candidates and pick the same one; the verdict is a fixpoint by construction.
     *
     * <p>Two carriers, each rendered so BOTH arms are source-neutral:
     * <ul>
     *   <li><b>Direct fan-threshold method-call chain</b> ({@code = adminClient.listConsumerGroupOffsets(...).…}). Both
     *       arms render the chain through {@link #forcedMethodCallChain} → {@code chainFanOut} (root then one dotted
     *       selector per line, a pure AST function — never the source-gated {@code canAttachFirstSegmentToSimpleRoot} that
     *       {@code expression.apply} would reach). The attached arm threads the {@code NAME = } leftEdgePrefix; the broken
     *       arm renders the chain at its own indented column with an empty prefix. Object-creation roots are excluded here
     *       (their packed/broken-constructor branches own their shape).</li>
     *   <li><b>Object creation whose constructor argument is a fan-threshold chain</b>
     *       ({@code = new ArrayList<>(entry.entity().entries().size())}). The constructor body already renders
     *       source-neutrally (its argument chain fans by the AST rule regardless of source), so both arms use ordinary
     *       {@code expression.apply}; only the enclosing attach verdict needs stabilizing.</li>
     * </ul>
     * Comment-bearing and block-lambda-bearing values are excluded (they carry their own comment/hug shape and stay on the
     * imperative cascade, where first-claim-wins comment safety is preserved).
     */
    private Optional<Doc> variableInitializerFanBestFitting(
            VariableDeclarator variable,
            Expression initializer,
            String name,
            String declarationPrefix,
            String flat,
            Doc groupTerminator
    ) {
        if (!initializer.getAllContainedComments().isEmpty() || initializer.getComment().isPresent()) {
            return Optional.empty();
        }
        String flatName = declarationPrefix + variable.getNameAsString();
        if (initializer instanceof MethodCallExpr methodCall
                && !methodCallChainRootIsObjectCreation.test(methodCall)) {
            // Direct fan-threshold chain: render the fan through the SOURCE-NEUTRAL canonicalFanChain (chainFanOut) — not
            // the imperative forcedMethodCallChain, whose canAttachFirstSegmentToSimpleRoot reads the author's
            // source-multiline shape and flips the first-selector attach across passes (bucket D). chainFanOut renders the
            // root at LayoutContext.root() and each selector on its own dotted line, a pure AST function; canonicalFanChain
            // emits it regardless of sourceMultilineArguments (the same delegate the return path uses, why return has zero
            // oscillations). Both arms wrap that ONE fan, so the only remaining choice is attach-versus-break-after-`=`,
            // ranked by Doc.bestFitting at the true column — a fixpoint by construction. Object-creation roots keep their
            // dedicated branches; comment / block-lambda / expression-lambda chains are withheld inside canonicalFanChain
            // (it returns empty), so this falls through to the imperative cascade for them unchanged.
            //
            // Both arms wrap the SAME fan Doc, rendered ONCE with an empty leftEdgePrefix (LayoutContext.root()). This is
            // load-bearing: a promoted FACTORY root (`CacheFactory.newBuilder()`) and a method-call root render their
            // opener through a column-sensitive Doc.group; threading the `NAME = ` prefix into only the attached arm would
            // make that group break differently between the two arms (the field-chain-initializer regression:
            // `= CacheFactory`⏎`.newBuilder()` in the attach arm versus `CacheFactory.newBuilder()` together in the broken
            // arm), so bestFitting would flip. Rendering one prefix-agnostic fan and letting bestFitting measure the
            // attach-versus-break line count of the whole arm (its `NAME = ` text included) at the true column keeps the
            // fan byte-identical across arms and passes; the promoted opener's own fit is then judged by the renderer at
            // the arm's real column, not by a threaded static prefix.
            Optional<Doc> fan = canonicalFanChain.apply(methodCall, "", LayoutContext.root());
            if (fan.isPresent()) {
                Doc fanDoc = fan.orElseThrow();
                Doc attached = Doc.concat(Doc.text(name + " = "), fanDoc, groupTerminator);
                Doc brokenAfterEquals = Doc.concat(
                    Doc.text(name + " ="),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, fanDoc)),
                    groupTerminator
                );
                return Optional.of(Doc.bestFitting(List.of(attached, brokenAfterEquals)));
            }
        }
        // Object-creation-ROOT fan-threshold chain ({@code = new X.Builder(a, b).withRaftProtocol(...)....build()}). This
        // is the object-creation analogue of the direct-chain arm above, guarded to constructor roots whose argument list
        // is ALWAYS width-driven (never source-preserved — flat arg count, no anonymous body, no contained comments, not a
        // try resource), so {@code chainFanOut}'s {@code expressionRenderer.format(root, root())} root doc is
        // column-invariant. Routing through the SAME source-neutral {@code canonicalFanChain}
        // fan and ranking attach-versus-break-after-{@code =} with {@code Doc.bestFitting} at the true column makes the
        // constructor-arg break the renderer's width verdict on both passes — a fixpoint by construction. Object-creation
        // roots that could preserve source-multiline arguments (four-plus args, try resources, anonymous bodies) are
        // withheld here (their {@code chainFanOut} root doc would carry a source-gated hard break, so the fan would still
        // flip) and keep their existing packed / broken-constructor branches, which are already source-shape-stable for
        // them; comment / block-lambda / expression-lambda chains are withheld inside {@code canonicalFanChain}.
        if (initializer instanceof MethodCallExpr objectRootChain
                && methodCallChainRootIsObjectCreation.test(objectRootChain)
                && methodCallChainInitializerShape.apply(objectRootChain).objectCreationRootFansSourceNeutrally()) {
            Optional<Doc> fan = canonicalFanChain.apply(objectRootChain, "", LayoutContext.root());
            if (fan.isPresent()) {
                Doc fanDoc = fan.orElseThrow();
                Doc attached = Doc.concat(Doc.text(name + " = "), fanDoc, groupTerminator);
                Doc brokenAfterEquals = Doc.concat(
                    Doc.text(name + " ="),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, fanDoc)),
                    groupTerminator
                );
                return Optional.of(Doc.bestFitting(List.of(attached, brokenAfterEquals)));
            }
        }
        // Field-access-TAIL fan-threshold chain ({@code = StreamsConfig.configDef().configKeys().get(configKey).validator}).
        // The initializer is a {@link FieldAccessExpr} whose scope is a fan-threshold method-call chain and whose {@code .field}
        // trailer stays glued to the chain's final selector on both passes; only the enclosing attach-versus-break-after-{@code =}
        // verdict needs stabilizing. Render the scope chain through the SAME
        // source-neutral {@code canonicalFanChain} with the {@code .field} appended as the final-segment suffix, then rank
        // attach-versus-break-after-{@code =} with {@code Doc.bestFitting} at the true column. Gated to a direct
        // {@code MethodCallExpr} scope that fans by the canonical rule (the suffix is a single trailing field access on the
        // chain's last selector); comment / block-lambda / expression-lambda chains are withheld inside
        // {@code canonicalFanChain}. Object-creation-scoped field-access tails ({@code = new X(...).a().b().field}) inherit
        // the same {@code chainFanOut} object-root render, so the {@code objectCreationRootFansSourceNeutrally} guard is
        // reused to keep that root doc column-invariant when the scope root is a constructor.
        if (initializer instanceof FieldAccessExpr fieldAccess
                && fieldAccess.getScope() instanceof MethodCallExpr fieldScopeChain
                && (!methodCallChainRootIsObjectCreation.test(fieldScopeChain)
                    || methodCallChainInitializerShape.apply(fieldScopeChain).objectCreationRootFansSourceNeutrally())) {
            Optional<Doc> fan = canonicalFanChain.apply(
                fieldScopeChain,
                "." + fieldAccess.getNameAsString(),
                LayoutContext.root()
            );
            if (fan.isPresent()) {
                Doc fanDoc = fan.orElseThrow();
                Doc attached = Doc.concat(Doc.text(name + " = "), fanDoc, groupTerminator);
                Doc brokenAfterEquals = Doc.concat(
                    Doc.text(name + " ="),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, fanDoc)),
                    groupTerminator
                );
                return Optional.of(Doc.bestFitting(List.of(attached, brokenAfterEquals)));
            }
        }
        if (initializer instanceof ObjectCreationExpr objectCreation
                && objectCreation.getAnonymousClassBody().isEmpty()
                && objectCreation.getArguments().stream()
                        .anyMatch(this::argumentCarriesFanThresholdChain)) {
            Doc attached = Doc.concat(Doc.text(name + " = "), expression.apply(initializer), groupTerminator);
            Doc brokenAfterEquals = Doc.concat(
                variableInitializerBrokenOrFlat(variable, initializer, name, declarationPrefix, flat, true),
                groupTerminator
            );
            return Optional.of(Doc.bestFitting(List.of(attached, brokenAfterEquals)));
        }
        // Binary/logical/string-concat initializer whose operand is a fan-threshold chain (the "G bucket":
        // {@code long newOffset = log.segments().activeSegment().baseOffset() + 1},
        // {@code String appId = getClass().getSimpleName().toLowerCase(...) + testId}). The dispatched flat rendering
        // ({@code expression.apply}) already fans that operand by the End-state A rule (its inner {@code chainFanOut} is a
        // pure AST function, operator kept inline), so — exactly like the object-creation-argument arm above — both arms
        // are AST-pure and the only remaining choice is attach-after-{@code NAME = } versus break-after-{@code =}, ranked by
        // {@code Doc.bestFitting} at the true column. Without this arm the binary initializer falls to the {@code (A)}
        // conditionalGroup gate, whose flat arm carries the operand's hard break and can never be chosen, so it can only
        // break after {@code =}; ranking both AST-pure shapes here lets the attach shape win when it fits. Comment-bearing
        // binaries are excluded above; non-fan binaries return empty here and keep the {@code (A)} gate.
        if (initializer instanceof BinaryExpr binaryExpr && binaryFansChainOperand.test(binaryExpr)) {
            Doc attached = Doc.concat(Doc.text(name + " = "), expression.apply(initializer), groupTerminator);
            Doc brokenAfterEquals = Doc.concat(
                variableInitializerBrokenOrFlat(variable, initializer, name, declarationPrefix, flat, true),
                groupTerminator
            );
            return Optional.of(Doc.bestFitting(List.of(attached, brokenAfterEquals)));
        }
        // Cast-ROOT fan-threshold chain ({@code = ((AccessControlEntryRecord) createResult.records().get(0).message()).id()}).
        // The initializer is a method call ({@code .id()}) whose receiver descends through a parenthesized cast to a
        // fan-threshold chain ({@code createResult.records().get(0).message()}). That inner chain fans by the canonical rule
        // on both passes (its {@code chainFanOut} is a pure AST function), so the value's flat rendering
        // ({@code expression.apply}) carries a hard break the {@code (A)} conditionalGroup flat arm can never absorb.
        // Ranking the same {@code expression.apply} flat shape against the
        // break-after-{@code =} shape with {@code Doc.bestFitting} at the true column makes the verdict a fixpoint. Guarded to
        // a cast whose inner chain fans source-neutrally ({@code canonicalFanChain} non-empty — comment / block-lambda /
        // expression-lambda chains are withheld there), so this only claims initializers whose flat arm genuinely fans.
        if (initializer instanceof MethodCallExpr castRootedCall
                && castRootedCallInnerChainFansSourceNeutrally(castRootedCall)) {
            Doc attached = Doc.concat(Doc.text(name + " = "), expression.apply(initializer), groupTerminator);
            Doc brokenAfterEquals = Doc.concat(
                variableInitializerBrokenOrFlat(variable, initializer, name, declarationPrefix, flat, true),
                groupTerminator
            );
            return Optional.of(Doc.bestFitting(List.of(attached, brokenAfterEquals)));
        }
        return Optional.empty();
    }

    /**
     * SPIKE (fan-root-true-column, #190). Reports whether {@code call} is a method call whose receiver descends through a
     * parenthesized cast to a fan-threshold method-call chain that {@link CanonicalFanChain} would fan source-neutrally —
     * the {@code ((Cast) a.b().c()).selector()} initializer shape. Only such a value has a flat rendering that hard-breaks
     * (from the inner fan), so ranking its flat and
     * break-after-{@code =} shapes with {@code Doc.bestFitting} stabilizes it. The walk stops at the FIRST enclosing cast in
     * the receiver spine: a chain whose root is a plain receiver (no cast) is already handled by the direct-chain arm, and
     * an object-creation or method-call root is handled by its own arm, so this keys strictly on the cast-rooted shape.
     */
    private boolean castRootedCallInnerChainFansSourceNeutrally(MethodCallExpr call) {
        Expression receiver = call.getScope().orElse(null);
        while (receiver instanceof MethodCallExpr receiverCall) {
            receiver = receiverCall.getScope().orElse(null);
        }
        if (!(receiver instanceof EnclosedExpr enclosed) || !(enclosed.getInner() instanceof CastExpr cast)) {
            return false;
        }
        return cast.getExpression() instanceof MethodCallExpr innerChain
            && canonicalFanChain.apply(innerChain, "", LayoutContext.root()).isPresent();
    }

    /**
     * SPIKE (fan-root-true-column, #190). Reports whether an object-creation constructor argument carries a fan-threshold
     * method-call chain — either the argument IS such a chain ({@code new ArrayList<>(entry.entity().entries().size())}) or a
     * fanning chain is NESTED inside it ({@code new BrokerDirs(admin.describeLogDirs(IntStream.range(0, 4).boxed().toList()),
     * 0)}, whose {@code describeLogDirs(...)} argument is itself a fanning chain). Either way the whole {@code new X(...)}
     * value force-fans (its flat {@code Doc} carries the inner fan's hard break), so the enclosing break-after-{@code =}
     * verdict must be ranked source-neutrally with {@link Doc#bestFitting}.
     *
     * <p>The direct-argument case reuses the {@code chainBreaksByRule} shape verdict (the one source of truth for the fan
     * rule). The nested case walks the argument subtree for any {@code MethodCallExpr} whose chain fans source-neutrally
     * ({@code canonicalFanChain} non-empty, which withholds comment / block-lambda / expression-lambda chains); a nested fan
     * is what gives the value its hard break. Descending through the whole argument subtree is safe here because this only
     * decides the enclosing attach verdict — the inner fan renders identically ({@code chainFanOut}, a pure AST function) in
     * both {@code bestFitting} arms, so widening the trigger cannot change the inner shape, only whether the value attaches
     * to {@code NAME = } or breaks after it.
     */
    private boolean argumentCarriesFanThresholdChain(Expression argument) {
        if (argument instanceof MethodCallExpr argumentChain
                && methodCallChainInitializerShape.apply(argumentChain).chainBreaksByRule()) {
            return true;
        }
        return argument.findAll(MethodCallExpr.class).stream()
                .anyMatch(nested -> methodCallChainInitializerShape.apply(nested).chainBreaksByRule());
    }

    /**
     * Dispatches the construct-specific broken initializer shape, reached from the {@code (A)} master gate's broken arm
     * and from the comment-bearing imperative path.
     *
     * <p>{@code forceBroken} carries the renderer's flat-versus-broken verdict into the per-construct broken-shape branches
     * below. When the caller is the {@link Doc#conditionalGroup} broken arm it passes {@code true} — the renderer already
     * judged the flat form too wide at the real column, so each branch skips its own width test. When the caller is the
     * comment-bearing path it passes {@code false}, so each branch applies its own per-branch width gate. The master gate
     * only moves the flat-versus-broken decision, not which broken shape a construct takes when it is broken. A construct
     * that owns its own break (arrays, switch, anonymous object creation) falls through to the final flat dispatch in both
     * modes, because {@code expression.apply} already renders its internal break.
     */
    private Doc variableInitializerBrokenOrFlat(
            VariableDeclarator variable,
            Expression initializer,
            String name,
            String declarationPrefix,
            String flat,
            boolean forceBroken
    ) {
        boolean overWidth = forceBroken || layoutWidth.variableInitializer(variable, flat) > options.lineWidth();
        if (
            overWidth
            && initializer instanceof MethodCallExpr methodCall
            && initializerHasOwnBreak(initializer)
        ) {
            Optional<Doc> forcedChain = variableWithForcedMethodCallChain(
                variable,
                name,
                declarationPrefix + variable.getNameAsString(),
                methodCall
            );
            if (forcedChain.isPresent()) {
                return forcedChain.orElseThrow();
            }
        }
        if (
            overWidth
            && initializer instanceof MethodCallExpr methodCall
            && !initializerHasOwnBreak(initializer)
        ) {
            // C10 (B) — #191. The single-selector, simple-attachable-root fan-out-versus-argument-break convergence
            // (NAME = Collections.newSetFromMap(...)) runs through Doc.bestFitting([argumentBreak@1, collapse@0]), which is
            // idempotent for two reasons: (1) the collapse arm is built source-neutrally (whole call flat on
            // the continuation line, a pure AST function present on every input, so both passes rank the same two
            // candidates — no source-multiline-versus-flat oscillation); and (2) opener-attachment is expressed by the
            // per-alternative priority (convergence-redesign Mechanism 2, slice 1), placed after the fit gate and before
            // line count, so the opener-attached argument-break is preferred whenever it fits even though the collapse uses
            // fewer lines, and the collapse wins only when the opener overflows the fit gate. Comment-bearing single calls
            // stay on the imperative cascade below (the ranked node is emitted only when the call is comment-free), so no
            // comment is double-claimed. Object-creation-rooted single calls (the #48 case) keep their existing imperative
            // branches below — their collapse is a broken-constructor/dot-split shape, not this whole-call collapse, so they
            // are out of this arm's scope (as is the single-simple-arg tail dot-split, #221 Case B / slice 4).
            Optional<Doc> rankedConvergence = rankedSimpleRootSingleCallConvergence(
                variable,
                name,
                declarationPrefix + variable.getNameAsString(),
                methodCall
            );
            if (rankedConvergence.isPresent()) {
                return rankedConvergence.orElseThrow();
            }
            // Canonical-fan cutover seam (End-state A): a multi-link fluent chain that reaches its link-count/root-kind
            // threshold fans one selector per line, and it must do so through the SAME source-neutral fan on every pass.
            // Placed here, ahead of the source-shape-sensitive object-creation, source-multiline, and attachable-scope
            // branches below, so a fan-threshold plain-receiver / type-like chain is claimed by the fan before those
            // branches can pick a source-dependent shape. That source-dependence is exactly the oscillation this seam
            // closes: a flat-source `NAME = a.b().c().find(x)` reaches methodCallHasAttachableScope (the outer selector's
            // scope ends on the name line) and renders the argument-break `find(⏎ x ⏎)`, while its already-fanned re-format
            // fails that same source-line test, falls through to variableWithForcedMethodCallChain, and renders the +8 fan
            // — so the two passes disagree forever. Routing through variableWithForcedMethodCallChain (which threads the
            // `NAME = ` leftEdgePrefix and reaches MethodCallChainPrinter.chainFanOut, a pure function of the AST) makes
            // both passes rebuild the identical fan. This extends the #191 pattern (rankedSimpleRootSingleCallConvergence,
            // above): #191 withheld the source-sensitive conditionalGroup arms for a SINGLE-call initializer and emitted one
            // deterministic ranked shape; this withholds them for the MULTI-LINK fan-threshold case and emits the one
            // deterministic fan shape. Object-creation-rooted chains are intentionally excluded — their dedicated
            // packed / compact / broken-constructor branches below (and the #48 / #221 Case B convergence) own their shape,
            // and chainFanOut renders an object-creation root differently than those branches; widening the fan to them is
            // a later cutover seam. Comment- and block-lambda-bearing chains stay on the imperative cascade (re-rendering a
            // comment-bearing root through the fan would double-claim its comments — the same guard the landed rankers use).
            Optional<Doc> canonicalFan = variableInitializerCanonicalFan(
                variable,
                name,
                declarationPrefix + variable.getNameAsString(),
                methodCall
            );
            if (canonicalFan.isPresent()) {
                return canonicalFan.orElseThrow();
            }
            if (
                initializerSingleSimpleArgTailDotSplits(
                    variable,
                    methodCall,
                    declarationPrefix + variable.getNameAsString()
                )
            ) {
                // #221 Case B / slice 4. An over-width object-creation-rooted single call whose selector's argument list is
                // exactly one simple argument (a name, field access, this/super, or literal) and whose opener still fits on
                // the assignment line ({@code NAME = new X(...).selector(} within budget) would otherwise reach the
                // object-creation argument-break branch below, which keeps the whole opener on the assignment line and breaks
                // that single argument onto its own line ({@code new X(...).selector(}⏎{@code arg}⏎{@code )}) — opening one
                // simple argument across three lines when {@code .selector(arg)} routinely fits on its own dotted
                // continuation line. Route it through the
                // initializer's existing chain-continuation (+8) fan-out ({@link #variableWithPackedMethodCallChain}) instead —
                // the same path a long-constructor single-selector tail already takes when its opener overflows (the
                // {@code buildLongConstructorStrategy}/{@code buildShortConstructorStrategy} goldens). That path keeps the
                // constructor root on the assignment line and fans the lone selector compact onto its own continuation line at
                // the chain-continuation indent, so Case 1 is byte-for-byte consistent with its opener-overflow siblings rather
                // than taking the argument-open shape or the shallower {@code objectRootSingleSegmentChain} indent. Emitting it
                // here — before the source-shape-sensitive {@code variableWithCompactObjectCreationChain} collapse below — keys
                // the shape on AST + the opener's fit at the rendered column only, so it wins on every pass and is idempotent:
                // {@code packedMethodCallChain} is a pure width function of the AST, so a re-format of the already-split source
                // re-derives the same packed fan-out rather than collapsing the (now-fitting) whole chain onto the
                // continuation line.
                Optional<Doc> dotSplitTail = variableWithPackedMethodCallChain(
                    variable,
                    name,
                    declarationPrefix + variable.getNameAsString(),
                    methodCall
                );
                if (dotSplitTail.isPresent()) {
                    return dotSplitTail.orElseThrow();
                }
            }
            if (
                methodCallChainRootIsObjectCreation.test(methodCall)
                && methodCallChainInitializerShape.apply(methodCall).singleCall()
            ) {
                // Only single-segment object-creation roots (new X(args).onlyCall(...)) keep the call on the
                // assignment line and break its argument list. Multi-segment constructor chains fall through to the
                // one-per-line chain below so the root sits alone and every .call() gets its own line, instead of
                // greedy-packing the root plus the leading calls onto the assignment line. The single-simple-argument
                // tail is handled above by the dot-split (#221 Case B), so only multi-argument and lambda tails (and
                // single-simple-arg tails whose opener overflows, which the dot-split gate declines) reach this
                // argument-break branch.
                Optional<Doc> directObjectCreationCall = variableWithBrokenMethodCallArguments(
                    variable,
                    name,
                    declarationPrefix + variable.getNameAsString(),
                    methodCall,
                    false
                );
                if (directObjectCreationCall.isPresent()) {
                    return directObjectCreationCall.orElseThrow();
                }
            }
            Optional<Doc> packedObjectCreationChain = variableWithPackedMethodCallChain(
                variable,
                name,
                declarationPrefix + variable.getNameAsString(),
                methodCall
            );
            if (packedObjectCreationChain.isPresent()) {
                return packedObjectCreationChain.orElseThrow();
            }
            Optional<Doc> compactObjectCreationChain = variableWithCompactObjectCreationChain(
                variable,
                name,
                methodCall
            );
            if (compactObjectCreationChain.isPresent()) {
                return compactObjectCreationChain.orElseThrow();
            }
            MethodCallChainSourcePlanner.InitializerChainShape initializerChainShape =
                methodCallChainInitializerShape.apply(methodCall);
            // The single-selector simple-attachable-root fan-out-versus-argument-break convergence (#191) is resolved
            // above by rankedSimpleRootSingleCallConvergence, so this force-wide gate now only reaches MULTI-SEGMENT
            // type-like chains (NAME = a.b.C.first(...).second(...)), whose one-per-line forced chain the ranked
            // single-call arm does not build. singleCallConvergesOnArgumentBreak still guards the object-creation single
            // call (the #48 case, whose collapse is a broken-constructor shape rendered by its own branches, not this
            // whole-call collapse); for that shape the predicate keeps the deterministic argument-break decision. It keys
            // purely on AST shape + measured width, never source line breaks, so this remaining path stays idempotent.
            if (
                initializerChainShape.shouldForceWideInitializerChain()
                && !singleCallConvergesOnArgumentBreak(
                    methodCall,
                    argumentBreakOpenerFits(variable, methodCall, declarationPrefix + variable.getNameAsString())
                )
            ) {
                Optional<Doc> forcedChain = variableWithForcedMethodCallChain(
                    variable,
                    name,
                    declarationPrefix + variable.getNameAsString(),
                    methodCall
                );
                if (forcedChain.isPresent()) {
                    return forcedChain.orElseThrow();
                }
            }
            if (methodCallHasAttachableScope(methodCall)) {
                Optional<Doc> directCall = variableWithBrokenMethodCallArguments(
                    variable,
                    name,
                    declarationPrefix + variable.getNameAsString(),
                    methodCall,
                    true
                );
                if (directCall.isPresent()) {
                    return directCall.orElseThrow();
                }
            }
            Optional<Doc> sourceMultilineBlockLambdaCall = variableWithSourceMultilineBlockLambdaInitializer(
                variable,
                name,
                declarationPrefix + variable.getNameAsString(),
                methodCall
            );
            if (sourceMultilineBlockLambdaCall.isPresent()) {
                return sourceMultilineBlockLambdaCall.orElseThrow();
            }
            Optional<Doc> forcedChain = variableWithForcedMethodCallChain(
                variable,
                name,
                declarationPrefix + variable.getNameAsString(),
                methodCall
            );
            if (forcedChain.isPresent()) {
                return forcedChain.orElseThrow();
            }
            Optional<Doc> mixedChain = mixedFieldMethodCallChain.apply(methodCall);
            if (mixedChain.isPresent()) {
                return variableWithMethodCallChain(
                    variable,
                    name,
                    declarationPrefix + variable.getNameAsString(),
                    methodCall,
                    mixedFieldMethodCallFirstLine(methodCall),
                    mixedChain.orElseThrow()
                );
            }
            Optional<Doc> directCall = variableWithBrokenMethodCallArguments(
                variable,
                name,
                declarationPrefix + variable.getNameAsString(),
                methodCall,
                false
            );
            if (directCall.isPresent()) {
                return directCall.orElseThrow();
            }
        }
        if (
            overWidth
            && initializer instanceof CastExpr castExpr
            && castExpr.getExpression() instanceof MethodCallExpr methodCall
            && !initializerHasOwnBreak(initializer)
        ) {
            return Doc.concat(
                Doc.text(name + " = "),
                castType.apply(castExpr.getType()),
                Doc.text(" "),
                brokenMethodCall.apply(methodCall)
            );
        }
        if (
            overWidth
            && initializer instanceof CastExpr castExpr
            && castTypeNeedsBreak(declarationPrefix + variable.getNameAsString(), castExpr.getType())
            && !initializerHasOwnBreak(initializer)
        ) {
            return variableWithCastTypeBreak(name, declarationPrefix + variable.getNameAsString(), castExpr);
        }
        if (
            overWidth
            && initializer instanceof ConditionalExpr conditionalExpr
            && !initializerHasOwnBreak(initializer)
        ) {
            return conditionalInitializer(name, declarationPrefix + variable.getNameAsString(), conditionalExpr);
        }
        if (
            overWidth
            && initializer instanceof LambdaExpr lambdaExpr
            && !initializerHasOwnBreak(initializer)
        ) {
            Optional<Doc> expressionLambdaInitializer = variableWithExpressionLambdaInitializer(
                variable,
                name,
                declarationPrefix + variable.getNameAsString(),
                lambdaExpr
            );
            if (expressionLambdaInitializer.isPresent()) {
                return expressionLambdaInitializer.orElseThrow();
            }
            Optional<Doc> blockLambdaInitializer = variableWithBlockLambdaInitializer(
                name,
                declarationPrefix + variable.getNameAsString(),
                lambdaExpr
            );
            if (blockLambdaInitializer.isPresent()) {
                return blockLambdaInitializer.orElseThrow();
            }
            Optional<Doc> lambdaInitializer = variableWithBrokenLambdaParameters(
                name,
                declarationPrefix + variable.getNameAsString(),
                lambdaExpr
            );
            if (lambdaInitializer.isPresent()) {
                return lambdaInitializer.orElseThrow();
            }
        }
        if (
            overWidth
            && initializer instanceof StringLiteralExpr
        ) {
            return Doc.concat(
                Doc.text(name + " ="),
                Doc.indent(Doc.concat(Doc.HARD_LINE, expression.apply(initializer)))
            );
        }
        if (overWidth && initializer instanceof ArrayInitializerExpr arrayInitializerExpr) {
            // Reprint-by-default: an overflowing array initializer always keeps `= {` on the assignment line and breaks
            // its elements one per line, regardless of whether the author wrote the braces on one source line or many.
            return Doc.concat(Doc.text(name + " = "), arrayInitializer.apply(arrayInitializerExpr, true));
        }
        if (
            overWidth
            && !(initializer instanceof StringLiteralExpr)
            && !(initializer instanceof TextBlockLiteralExpr)
            && !initializerHasOwnBreak(initializer)
        ) {
            return Doc.concat(
                Doc.text(name + " ="),
                Doc.indent(Doc.concat(Doc.HARD_LINE, brokenInitializer(variable, initializer)))
            );
        }
        return Doc.concat(Doc.text(name + " = "), expression.apply(initializer));
    }

    private boolean conditionalInitializerLineOverflows(
            VariableDeclarator variable,
            String declarationPrefix,
            ConditionalExpr initializer
    ) {
        String line = declarationPrefix
            + variable.getNameAsString()
            + " = "
            + conditionalProjection.line(initializer)
            + ";";
        return layoutWidth.variableInitializer(variable, line) > options.lineWidth();
    }

    /**
     * Keeps a binary initializer from stranding {@code =} when the first operand still fits on the declaration line.
     */
    private boolean binaryInitializerCanKeepFirstOperandWithEquals(
            VariableDeclarator variable,
            String declarationPrefix,
            BinaryExpr binaryExpr
    ) {
        String firstOperand = binaryInitializerFirstOperandLine(binaryExpr);
        return layoutWidth.variableInitializer(
            variable,
            declarationPrefix + variable.getNameAsString() + " = " + firstOperand
        ) <= options.lineWidth();
    }

    private String binaryInitializerFirstOperandLine(BinaryExpr binaryExpr) {
        Expression firstOperand = firstBinaryOperand(binaryExpr);
        if (firstOperand instanceof TextBlockLiteralExpr) {
            return "\"\"\"";
        }
        return compact.apply(firstOperand);
    }

    private Expression firstBinaryOperand(BinaryExpr binaryExpr) {
        Expression left = binaryExpr.getLeft();
        while (left instanceof BinaryExpr leftBinary && leftBinary.getOperator() == binaryExpr.getOperator()) {
            left = leftBinary.getLeft();
        }
        return left;
    }

    /**
     * Keeps a compact object-creation method chain on the continuation line when the opener cannot stay with
     * {@code =}, but the whole chain fits after the break.
     */
    private Optional<Doc> variableWithCompactObjectCreationChain(
            VariableDeclarator variable,
            String name,
            MethodCallExpr methodCall
    ) {
        boolean chainSpansMultipleSourceLines = methodCallChainIsSourceMultiline.test(methodCall);
        MethodCallChainSourcePlanner.InitializerChainShape chainShape = methodCallChainInitializerShape.apply(
            methodCall
        );
        if (
            !chainShape.canUseCompactObjectCreationInitializer(chainSpansMultipleSourceLines)
            || !methodCall.getAllContainedComments().isEmpty()
            || commentPlacement.trailingLineComment(variable).isPresent()
            || layoutWidth.continuationStatement(compact.apply(methodCall) + ";") > options.lineWidth()
        ) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(name + " ="),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(compact.apply(methodCall))))
            )
        );
    }

    private Optional<Doc> variableWithPackedMethodCallChain(
            VariableDeclarator variable,
            String name,
            String flatName,
            MethodCallExpr methodCall
    ) {
        boolean chainSpansMultipleSourceLines = methodCallChainIsSourceMultiline.test(methodCall);
        MethodCallChainSourcePlanner.InitializerChainShape chainShape = methodCallChainInitializerShape.apply(
            methodCall
        );
        if (
            !methodCallChainRootIsObjectCreation.test(methodCall)
            || !(
                chainShape.canUseCompactObjectCreationInitializer(chainSpansMultipleSourceLines)
                // #221 Case B / slice 4. A single-selector object-creation root with a single simple-argument tail whose
                // opener fits on the assignment line is admitted to this +8 fan-out too, so it fans the constructor root on
                // the assignment line and {@code .selector(simpleArg)} compact on its own continuation line — the same shape a
                // long-constructor tail produces when its opener overflows. Without this the shape gate below rejects it
                // (a single-line-source single call is not a compact-object-creation shape), which is what forced the old
                // argument-open. The width gate that follows also stops rejecting a single-simple-arg tail as an
                // argument-break candidate. The {@code initializerSingleSimpleArgTailDotSplits} branch of
                // {@code variableInitializerBrokenOrFlat} routes exactly this shape here ahead of the argument-break branch;
                // multi-argument and lambda tails never match {@link #tailHasSingleSimpleArgument} and keep their existing
                // argument-break / opener-fits behavior.
                || tailHasSingleSimpleArgument(methodCall)
            )
            || (!methodCall.getArguments().isEmpty()
                && !tailHasSingleSimpleArgument(methodCall)
                && layoutWidth.variableInitializer(
                    variable,
                    flatName + " = " + methodCallPrefix.apply(methodCall) + "("
                ) <= options.lineWidth())
        ) {
            return Optional.empty();
        }
        return packedMethodCallChain
                .apply(methodCall, text -> layoutWidth.variableInitializer(variable, flatName + " = " + text))
                .map(chain -> Doc.concat(Doc.text(name + " = "), chain));
    }


    /**
     * Finds a block comment attached before {@code =}, so the variable name and comment stay together before the
     * initializer branch decides whether to break.
     */
    private Optional<Doc> preEqualsBlockComment(VariableDeclarator variable, Expression initializer) {
        String raw = rawSource.raw(variable);
        int equals = raw.indexOf('=');
        int blockComment = raw.indexOf("/*");
        if (blockComment < 0 || equals < 0 || blockComment > equals) {
            return Optional.empty();
        }
        Doc comment = comments.ownComment(initializer, BlockComment.class::isInstance);
        return comment == Doc.EMPTY ? Optional.empty() : Optional.of(comment);
    }

    /**
     * Finds and claims a block comment attached after {@code =}, returning it as a rendered {@link Doc} so the caller can
     * emit the comment text itself and render the initializer without its own comment.
     *
     * <p>Claiming here (via {@link CommentTracker#ownComment}) rather than reading {@code initializer.getComment()}
     * directly mirrors {@link #preEqualsBlockComment}: the comment is the initializer's own block comment sitting in the
     * {@code =}-to-initializer gap, and the shared expression renderer drops that own comment for some value kinds (for
     * example method calls), so the caller must own the placement. For value kinds whose renderer already emits the
     * comment the explicit text is byte-identical, and claiming it once keeps the comment accounted for either way.
     */
    private Optional<Doc> postEqualsBlockComment(VariableDeclarator variable, Expression initializer) {
        String raw = rawSource.raw(variable);
        int equals = raw.indexOf('=');
        int blockComment = raw.indexOf("/*");
        if (blockComment < 0 || equals < 0 || blockComment < equals) {
            return Optional.empty();
        }
        Doc comment = comments.ownComment(initializer, BlockComment.class::isInstance);
        return comment == Doc.EMPTY ? Optional.empty() : Optional.of(comment);
    }

    /**
     * Breaks array-creation initializers via the clean {@code =}/brace-break ladder.
     *
     * <p>The element type is consulted only as a continuation-line last resort: this ladder keeps the compact
     * {@code new Type<...>[]} prefix and breaks at the braces or {@code =} whenever that compact prefix fits the
     * continuation line where option-3 below would place it, so short generics are not shattered into
     * {@code new T<\narg\n>[]}. The bail is deliberately measured at the continuation baseline rather than the assignment
     * line: when only the assignment line overflows this ladder can still break cleanly at {@code =} and keep every line
     * within width, so it must not hand off to the type-argument shatter. Only when the compact prefix itself overflows
     * the continuation line does this method bail, letting the shared array printer own the genuinely-too-long
     * {@code new Type<...>[]} type-argument break.
     */
    private Optional<Doc> variableWithBrokenArrayCreation(
            String name,
            String flatName,
            ArrayCreationExpr arrayCreation
    ) {
        ToIntFunction<String> continuationPrefixWidth = layoutWidth::continuationStatement;
        if (
            arrayCreation.getInitializer().isEmpty()
            || arrayCreationTypeBreaks.test(arrayCreation, continuationPrefixWidth)
            || !arrayCreation.getAllContainedComments().isEmpty()
        ) {
            return Optional.empty();
        }
        String prefix = arrayCreationPrefix.apply(arrayCreation);
        ArrayInitializerExpr initializer = arrayCreation.getInitializer().orElseThrow();
        // C10-c: measure the {@code NAME = new T[] {} opener on the assignment line at the initializer's true rendered
        // block/type depth ({@link LayoutWidth#nodeLine}) instead of the fixed CURRENT baseline.
        if (layoutWidth.nodeLine(arrayCreation, flatName + " = " + prefix + " {") <= options.lineWidth()) {
            return Optional.of(
                Doc.concat(Doc.text(name + " = " + prefix + " "), arrayInitializer.apply(initializer, true))
            );
        }
        Optional<String> compactContinuation = compactObjectCreationArrayInitializer(initializer);
        if (
            compactContinuation.isPresent()
            && layoutWidth.currentIndented(prefix + " " + compactContinuation.orElseThrow()) <= options.lineWidth()
        ) {
            return Optional.of(
                Doc.concat(
                    Doc.text(name + " ="),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(prefix + " " + compactContinuation.orElseThrow())))
                )
            );
        }
        return Optional.of(
            Doc.concat(
                Doc.text(name + " ="),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(prefix + " "), arrayInitializer.apply(initializer, true)))
            )
        );
    }

    /**
     * Keeps an array initializer compact only for the narrow object-creation list that reads better as one continuation.
     */
    private Optional<String> compactObjectCreationArrayInitializer(ArrayInitializerExpr initializer) {
        if (
            !initializer.getAllContainedComments().isEmpty()
            || initializer.getValues().isEmpty()
            || initializer.getValues().stream().anyMatch(value -> !compactObjectCreationArrayValue(value))
        ) {
            return Optional.empty();
        }
        String values = compactJoin.apply(initializer.getValues());
        return Optional.of(compactArrayInitializerWithSourceSpacing.apply(initializer, values));
    }

    /**
     * Allows the compact array continuation only for empty constructor calls, where each value stays readable without
     * its own argument or anonymous-body layout.
     */
    private boolean compactObjectCreationArrayValue(Expression value) {
        return value instanceof ObjectCreationExpr objectCreation
            && objectCreation.getScope().isEmpty()
            && objectCreation.getTypeArguments().isEmpty()
            && objectCreation.getArguments().isEmpty()
            && objectCreation.getAnonymousClassBody().isEmpty();
    }

    /**
     * Branches object creation between broken type arguments and broken constructor arguments, leaving anonymous-class
     * and commented creations to the shared object-creation formatter.
     */
    private Optional<Doc> variableWithBrokenObjectCreation(
            VariableDeclarator variable,
            String name,
            String flatName,
            ObjectCreationExpr objectCreation
    ) {
        if (objectCreation.getAnonymousClassBody().isPresent()) {
            return Optional.empty();
        }
        if (!objectCreation.getAllContainedComments().isEmpty()) {
            return variableWithCommentedObjectCreation(variable, name, flatName, objectCreation);
        }
        Optional<Doc> typeArguments = variableWithBrokenObjectCreationTypeArguments(
            variable,
            name,
            flatName,
            objectCreation
        );
        if (typeArguments.isPresent()) {
            return typeArguments;
        }
        return variableWithBrokenObjectCreationArguments(variable, name, flatName, objectCreation);
    }

    /**
     * Keeps {@code name = new Type(} together for commented constructor calls when that first line still fits, while
     * leaving the nested comment placement to the normal object-creation renderer.
     */
    private Optional<Doc> variableWithCommentedObjectCreation(
            VariableDeclarator variable,
            String name,
            String flatName,
            ObjectCreationExpr objectCreation
    ) {
        if (
            objectCreation.getArguments().isEmpty()
            || objectCreation.getComment().filter(BlockComment.class::isInstance).isPresent()
            || objectCreation.getType().getComment().filter(BlockComment.class::isInstance).isPresent()
        ) {
            return Optional.empty();
        }
        String prefix = objectCreationPrefix.apply(objectCreation);
        if (openerLineWidth(variable, flatName + " = " + prefix + "(") > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(Doc.text(name + " = "), expression.apply(objectCreation)));
    }

    /**
     * Breaks constructor arguments when the assignment and constructor prefix still fit, so only the argument list moves
     * to hard lines.
     */
    private Optional<Doc> variableWithBrokenObjectCreationArguments(
            VariableDeclarator variable,
            String name,
            String flatName,
            ObjectCreationExpr objectCreation
    ) {
        if (objectCreation.getArguments().isEmpty()) {
            return Optional.empty();
        }
        String prefix = objectCreationPrefix.apply(objectCreation);
        if (openerLineWidth(variable, flatName + " = " + prefix + "(") > options.lineWidth()) {
            return Optional.empty();
        }
        if (smallConstructorCanStayFlat(variable, flatName, objectCreation)) {
            return Optional.of(Doc.concat(Doc.text(name + " = "), expression.apply(objectCreation)));
        }
        return Optional.of(
            Doc.concat(
                Doc.text(name + " = " + prefix + "("),
                Doc.indent(
                    Doc.concat(
                        Doc.HARD_LINE,
                        Doc.join(
                            Doc.concat(Doc.text(","), Doc.HARD_LINE),
                            objectCreation.getArguments()
                                    .stream()
                                    .map(this::brokenObjectCreationArgument)
                                    .toList()
                        )
                    )
                ),
                Doc.HARD_LINE,
                Doc.text(")")
            )
        );
    }

    private Doc brokenObjectCreationArgument(Expression argument) {
        // Canonical-fan cutover seam (End-state A), the binary/logical/string-concat OPERAND carrier at the broken
        // object-creation argument position (the "G bucket"). When this constructor argument is a binary/ternary whose
        // dispatched flat rendering ({@code expression.apply}) already fans a fluent chain operand by the End-state A rule
        // ({@code new StatusData(summary.percentiles().get(0).value() * step + min, …)}), commit that flat shape and do not
        // take the operand-per-line break below. The flat shape renders the chain fanned with the operator kept on its line
        // and is a pure function of the AST (the chain fans by the width-independent link-count rule on every pass), so
        // committing it is the fixpoint; the width-gated {@code binaryExpressionLines} break below would instead lay the
        // operator on its own line. Chains the rule does not fan and comment / lambda chains are withheld by
        // {@code binaryFansChainOperand}, so those arguments take the width-driven break below.
        if (argument instanceof BinaryExpr binaryExpr && binaryFansChainOperand.test(binaryExpr)) {
            return expression.apply(argument);
        }
        if (
            argument instanceof BinaryExpr binaryExpr
            && layoutWidth.continuationStatement(compact.apply(binaryExpr)) > options.lineWidth()
        ) {
            if (binaryExpressionHasLineComments.test(binaryExpr)) {
                return binaryExpressionLinesWithComments.apply(binaryExpr);
            }
            return binaryExpressionLines.apply(binaryExpr, true);
        }
        return expression.apply(argument);
    }

    private boolean smallConstructorCanStayFlat(
            VariableDeclarator variable,
            String flatName,
            ObjectCreationExpr objectCreation
    ) {
        return objectCreation.getArguments().size() <= 3
            && openerLineWidth(variable, flatName + " = " + compact.apply(objectCreation) + ";")
                <= options.lineWidth();
    }

    /**
     * Breaks constructor type arguments for {@code new SomeVeryLongType<...>()} only when there are no constructor
     * arguments or scopes that need a different object-creation layout.
     */
    private Optional<Doc> variableWithBrokenObjectCreationTypeArguments(
            VariableDeclarator variable,
            String name,
            String flatName,
            ObjectCreationExpr objectCreation
    ) {
        if (
            !objectCreation.getArguments().isEmpty()
            || objectCreation.getScope().isPresent()
            || objectCreation.getTypeArguments().isPresent()
            || !objectCreation.getType().isClassOrInterfaceType()
        ) {
            return Optional.empty();
        }
        ClassOrInterfaceType type = objectCreation.getType().asClassOrInterfaceType();
        if (
            !hasNonEmptyTypeArguments(type)
            || openerLineWidth(
                variable,
                flatName + " = new " + typeNameWithoutArguments.apply(type) + "<"
            ) > options.lineWidth()
        ) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(name + " = new "),
                brokenClassOrInterfaceType.apply(type),
                Doc.text("()")
            )
        );
    }

    /**
     * Breaks a method-call initializer at its arguments when the call prefix still fits on the assignment line.
     */
    private Optional<Doc> variableWithBrokenMethodCallArguments(
            VariableDeclarator variable,
            String name,
            String flatName,
            MethodCallExpr methodCall,
            boolean allowNestedComments
    ) {
        if (
            methodCall.getArguments().isEmpty()
            || methodCallHasOwnComment(methodCall)
            || (!allowNestedComments && !methodCall.getAllContainedComments().isEmpty())
            || methodCall.getScope().filter(shouldPrintScopeAsDoc).isPresent()
        ) {
            return Optional.empty();
        }
        String callPrefix = methodCallPrefix.apply(methodCall);
        Optional<Doc> blockLambdaCall = variableWithHuggableBlockLambdaArguments(
            variable,
            name,
            flatName,
            methodCall,
            callPrefix
        );
        if (blockLambdaCall.isEmpty() && methodCallHasBlockLambdaArgument(methodCall)) {
            Optional<Doc> brokenReceiverCall = variableWithReceiverBreakBeforeHuggableBlockLambdaArguments(
                variable,
                name,
                flatName,
                methodCall
            );
            if (brokenReceiverCall.isPresent()) {
                return brokenReceiverCall;
            }
        }
        String firstLine = flatName + " = " + callPrefix + "(";
        boolean openerFits = openerLineWidth(variable, firstLine) <= options.lineWidth();
        if (
            methodCallChainIsSourceMultiline.test(methodCall)
            && blockLambdaCall.isEmpty()
            && !methodCallHasBlockLambdaArgument(methodCall)
            && !singleCallConvergesOnArgumentBreak(methodCall, openerFits)
        ) {
            return Optional.empty();
        }
        if (!openerFits) {
            return Optional.empty();
        }
        // The hugged block-lambda layout already renders the lambda body through the comment-preserving block printer,
        // which claims every comment inside the body. Returning it before the contained-comment fallback below keeps
        // those claims as the winner; falling through to a fresh whole-call render here would re-offer comments the
        // discarded hug build already claimed, and first-claim-wins would then drop them.
        if (blockLambdaCall.isPresent()) {
            return blockLambdaCall;
        }
        if (!methodCall.getAllContainedComments().isEmpty()) {
            return Optional.of(Doc.concat(Doc.text(name + " = "), this.methodCall.apply(methodCall)));
        }
        return Optional.of(brokenMethodCallArgumentList(name, methodCall, callPrefix));
    }

    /**
     * Routes the over-width, single-selector, simple-attachable-root initializer through the ranked
     * {@link Doc#bestFitting(java.util.List, int[]) bestFitting} engine, replacing the imperative fan-out-versus-argument-
     * break convergence that #191 (LDM-3) deferred as "non-idempotent to route" (see the {@code // C10 (B)} note in
     * {@link #variableInitializerBrokenOrFlat}). Present only for the exact shape the imperative
     * {@link #singleCallConvergesOnArgumentBreak} predicate governed for a name/type-like/field-access root
     * ({@code NAME = Collections.newSetFromMap(new WeakHashMap<>(4))}): a single selector segment, a simple attachable
     * root, breakable non-empty non-lambda arguments, no own or contained comment, and a scope the chain renders inline.
     *
     * <p><strong>The two ranked alternatives.</strong>
     * <ul>
     *   <li><b>argument-break, priority 1 (opener-attached).</b> {@code NAME = ROOT.method(}⏎{@code args}⏎{@code )} —
     *       the {@link #brokenMethodCallArgumentList} shape. Built unconditionally (the {@code openerFits} check is the
     *       renderer's fit gate, upstream of priority): its first line is the opener {@code NAME = ROOT.method(},
     *       so it fits iff the opener fits at the real rendered column.</li>
     *   <li><b>collapse, priority 0 (fewer lines).</b> {@code NAME =}⏎{@code ROOT.method(whole)} — the whole call flat on
     *       the continuation line, the same shape the imperative fall-through built via {@code brokenInitializer} for a
     *       single simple call (whose {@code forcedMethodCallChain} is empty, so it renders the call flat). This is the
     *       "single-selector fan-out / root on the continuation line, no dot-split" the convergence-redesign names; it is
     *       built directly here rather than through {@code MethodCallChainPrinter.chainFanOut}, because {@code chainFanOut}
     *       fans a single selector onto its own dotted continuation line ({@code ROOT}⏎{@code .method(...)}), which is a
     *       different (dot-split) shape than this initializer's whole-call collapse and would move the
     *       {@code field-init-typelike-root-idempotence} {@code qualifiedRootProviders} golden. (The single-simple-arg tail
     *       dot-split is deliberately out of scope here — that is #221 Case B / slice 4.)</li>
     * </ul>
     *
     * <p><strong>Why this reproduces the golden by mechanism.</strong> When the opener fits, both arms fit and priority
     * keeps the opener-attached argument-break (the maintainer's decided house style — {@code seenProviders},
     * {@code collapsedProviders}, {@code attachedProviders}). When the opener overflows, the argument-break's first line
     * overflows so the fit gate drops it and the collapse wins ({@code qualifiedRootProviders}, {@code qualifiedRootBroken}).
     * The decision keys only on AST shape and the opener's fit at the rendered column — never on source line breaks — so it
     * is a fixpoint: pass 2 re-measures the same two candidates the renderer builds fresh from the AST and picks the same
     * arm, which is what makes the {@code seenProviders} entry idempotent by construction.
     *
     * <p><strong>Comment safety.</strong> Emitted only when the call is comment-free (no own comment, no contained
     * comments). Both arms render the call once, and this returns a single {@link Doc}, so it never double-claims a comment;
     * comment-bearing single calls stay on the imperative cascade below, exactly as the landed rankers require.
     */
    private Optional<Doc> rankedSimpleRootSingleCallConvergence(
            VariableDeclarator variable,
            String name,
            String flatName,
            MethodCallExpr methodCall
    ) {
        if (
            !methodCallChainInitializerShape.apply(methodCall).singleCall()
            || !singleCallHasSimpleAttachableRoot(methodCall)
            || methodCall.getArguments().isEmpty()
            || methodCall.getArguments().stream().anyMatch(LambdaExpr.class::isInstance)
            || methodCallHasBlockLambdaArgument(methodCall)
            || methodCallHasOwnComment(methodCall)
            || !methodCall.getAllContainedComments().isEmpty()
            || methodCall.getScope().filter(shouldPrintScopeAsDoc).isPresent()
        ) {
            return Optional.empty();
        }
        String callPrefix = methodCallPrefix.apply(methodCall);
        Doc argumentBreak = brokenMethodCallArgumentList(name, methodCall, callPrefix);
        Doc collapse = Doc.concat(
            Doc.text(name + " ="),
            Doc.indent(Doc.concat(Doc.HARD_LINE, expression.apply(methodCall)))
        );
        return Optional.of(Doc.bestFitting(List.of(argumentBreak, collapse), new int[] {1, 0}));
    }

    /**
     * Routes a multi-link fan-threshold initializer chain onto the source-neutral canonical fan
     * ({@code MethodCallChainPrinter.chainFanOut}, reached through {@link #variableWithForcedMethodCallChain}), the
     * multi-link sibling of {@link #rankedSimpleRootSingleCallConvergence}'s single-call convergence. Present only for the
     * exact shape the canonical-fan cutover claims: the chain reaches the End-state A link-count/root-kind threshold
     * ({@link MethodCallChainSourcePlanner.InitializerChainShape#chainBreaksByRule()} — the one source of truth for the
     * rule), the root is not an object creation (those keep their dedicated packed / broken-constructor branches, whose
     * collapse shapes {@code chainFanOut} would not reproduce), and the chain carries no own or contained comment and no
     * block-lambda argument (a fan re-renders the root once, so a comment- or block-lambda-bearing root would be
     * double-claimed — the guard the landed {@code chainFanOut} rankers share).
     *
     * <p><strong>Why it is a fixpoint.</strong> The forced-chain path threads the initializer's {@code NAME = }
     * {@link LayoutContext#leftEdgePrefix() leftEdgePrefix} and lands in {@code MethodCallChainPrinter.methodCallChain},
     * whose canonical-fan route emits {@code chainFanOut} for a fan-threshold, comment-free chain. {@code chainFanOut}
     * builds the root plus one dotted selector per line purely from the AST, so a flat-source initializer and its
     * already-fanned re-format both rebuild the identical fan. Emitting it here — ahead of the object-creation,
     * source-multiline, and {@code methodCallHasAttachableScope} argument-break branches — is what removes the source
     * dependence those branches introduce (the attachable-scope branch reads whether the outer selector's scope ends on
     * the name line, a source-shape fact that flips once the chain is fanned), the direct cause of the flat↔fan
     * oscillation this seam closes.
     */
    private Optional<Doc> variableInitializerCanonicalFan(
            VariableDeclarator variable,
            String name,
            String flatName,
            MethodCallExpr methodCall
    ) {
        MethodCallChainSourcePlanner.InitializerChainShape chainShape = methodCallChainInitializerShape.apply(methodCall);
        if (
            !chainShape.chainBreaksByRule()
            || chainShape.rootIsObjectCreation()
            || methodCallHasOwnComment(methodCall)
            || !methodCall.getAllContainedComments().isEmpty()
            || methodCallHasBlockLambdaArgument(methodCall)
        ) {
            return Optional.empty();
        }
        return variableWithForcedMethodCallChain(variable, name, flatName, methodCall);
    }

    /**
     * Decides whether a single-selector method-call initializer should converge on the argument-break layout instead of
     * deferring to the source-shape gates.
     *
     * <p>For an over-width single-selector chain ({@code NAME = ROOT.method(args)}) with exactly one selector segment, two
     * sub-width layouts compete: keeping {@code ROOT.method(} on the assignment line and breaking the argument list (the
     * argument-break shape), or stranding {@code =} and collapsing the whole chain onto the continuation line. The
     * source-shape gates select between them by reading whether the source broke before the selector, but the collapsing
     * fallback erases that source feature, so a selector-broken input and its already collapsed re-format disagree and the
     * formatter never reaches a fixed point.
     *
     * <p>This predicate keys the decision on AST shape (single selector segment, an attachable root) and width (the
     * argument-break opener fits) only, never on the source line breaks. Two root kinds converge here:
     * <ul>
     *   <li>An object-creation root ({@code new X(ctorArgs).method(...)}) — the original #48 case. This root kind is still
     *       argument-broken imperatively (its collapse is a broken-constructor / dot-split shape, not a whole-call
     *       collapse), so this predicate remains its convergence signal.</li>
     *   <li>A simple attachable name/type-like or field-access root ({@code Collections.newSetFromMap(...)},
     *       {@code this.foo(...)}) — the #191 case, whose argument-break-versus-collapse choice now runs through the ranked
     *       engine ({@link #rankedSimpleRootSingleCallConvergence}, {@code Doc.bestFitting([argument-break@1, collapse@0])}).
     *       That ranked arm pre-empts this shape in {@link #variableInitializerBrokenOrFlat}, so here the predicate does not
     *       <em>choose</em> the layout for it; it serves as the AST+width eligibility signal the source-shape gates
     *       ({@code shouldForceWideInitializerChain} below the ranked arm, and the source-multiline guard inside
     *       {@link #variableWithBrokenMethodCallArguments}) read to <em>defer</em> a converging single call to that ranked
     *       arm rather than force a dot-split chain the re-format would then re-attach.</li>
     * </ul>
     * When it holds, the argument-break shape is chosen on every pass (imperatively for the object-creation root, via the
     * ranked engine for the simple root), so the layout is idempotent. Multi-segment chains, method-call roots (which carry
     * their own attach logic), and openers that do not fit are intentionally left to the source-shape gates and the
     * forced-chain fallbacks.
     */
    private boolean singleCallConvergesOnArgumentBreak(MethodCallExpr methodCall, boolean openerFits) {
        if (!openerFits || !methodCallChainInitializerShape.apply(methodCall).singleCall()) {
            return false;
        }
        return methodCallChainRootIsObjectCreation.test(methodCall)
            || singleCallHasSimpleAttachableRoot(methodCall);
    }

    /**
     * Mirrors the {@code openerFits} check inside {@link #variableWithBrokenMethodCallArguments}: whether
     * {@code NAME = ROOT.method(} still fits on the assignment line, so the argument-break shape is reachable. Computed
     * here so the force-chain gates can ask the convergence predicate without first descending into that method.
     */
    private boolean argumentBreakOpenerFits(VariableDeclarator variable, MethodCallExpr methodCall, String flatName) {
        return openerLineWidth(variable, flatName + " = " + methodCallPrefix.apply(methodCall) + "(")
            <= options.lineWidth();
    }

    /**
     * Measures the {@code NAME = ROOT.method(} argument-break opener at the declaration's real rendered column (the
     * C10 "measure at the rendered column" pattern), floored by the historical {@link LayoutWidth#currentIndented}
     * baseline so it never measures narrower than before.
     *
     * <p>The opener stays on the assignment line only when it fits; the fixed {@code currentIndented} budget counted one
     * indentation unit (plus, for a local, the extra unit folded into {@code flatName}), which matches a top-level field
     * or a method-body local but under-counts a field in a nested type or a local nested inside further blocks. At those
     * deeper positions the fixed baseline kept an opener that renders past the line-width limit, then a re-format from the
     * now-deeper rendered column would break it (the #137/#155 width-at-wrong-column family). {@link
     * LayoutWidth#variableInitializer} counts the declarator's real block/type nesting depth, so the keep-opener decision
     * matches the column the opener is actually written at; flooring by {@code currentIndented} keeps every already-correct
     * shallow position byte-identical.
     */
    private int openerLineWidth(VariableDeclarator variable, String openerLine) {
        return Math.max(
            layoutWidth.variableInitializer(variable, openerLine),
            layoutWidth.currentIndented(openerLine)
        );
    }

    /**
     * Identifies a single-selector call whose root is a simple attachable scope that renders inline before the selector
     * ({@code Collections.x(...)}, {@code this.x(...)}, {@code a.b.C.x(...)}). Method-call and object-creation roots are
     * excluded: object creation is handled by {@link #singleCallConvergesOnArgumentBreak} directly, and a method-call root
     * is itself a chain segment with its own attach handling, so collapsing it here would change unrelated layouts.
     */
    private boolean singleCallHasSimpleAttachableRoot(MethodCallExpr methodCall) {
        return methodCall.getScope()
                .filter(scope -> scope.isNameExpr()
                        || scope.isThisExpr()
                        || scope.isSuperExpr()
                        || scope.isFieldAccessExpr())
                .filter(scope -> !shouldPrintScopeAsDoc.test(scope))
                .isPresent();
    }

    /**
     * Decides whether an over-width object-creation-rooted single-call initializer should fan its tail compact onto its own
     * dotted continuation line instead of opening the tail's single argument (#221 Case B / slice 4). It holds only for the
     * exact shape that would otherwise arg-open:
     * <ul>
     *   <li>an object-creation root ({@code new X(...)}) with exactly one selector segment — the same
     *       {@code rootIsObjectCreation && singleCall} shape the object-creation argument-break branch owns;</li>
     *   <li>a tail whose argument list is a single <em>simple</em> argument ({@link #tailHasSingleSimpleArgument}); a
     *       multi-argument or lambda tail is out of #221's scope and keeps opening; and</li>
     *   <li>an opener that still fits on the assignment line ({@link #argumentBreakOpenerFits}, {@code NAME = new X(...).selector(}
     *       within budget). This is the precise boundary that scopes the flip to the cases that <em>currently</em> arg-open.
     *       When the opener-with-selector overflows (a long constructor whose {@code new X(...).selector(} does not fit) the
     *       call already fans onto its own continuation line through {@link #variableWithPackedMethodCallChain} — declining
     *       here leaves that (identical-looking) shape to the unchanged overflow path.</li>
     * </ul>
     *
     * <p>The chosen shape is the same chain-continuation (+8) fan-out {@link #variableWithPackedMethodCallChain} already
     * produces for an opener-overflow single-selector tail (the {@code buildLongConstructorStrategy}/
     * {@code buildShortConstructorStrategy} goldens), so Case 1 fans at the same indent as its opener-overflow siblings
     * rather than at the shallower {@code MethodCallChainPrinter.objectRootSingleSegmentChain} indent the {@code return}
     * chain's #236 dot-split uses. Every input is an AST-shape or rendered-column-width fact, never a source line break, so
     * the decision is a fixpoint: re-formatting the produced fan-out re-derives the same facts and re-emits it (see the call
     * site for why emitting here — ahead of the source-shape-sensitive collapse branches — is what makes it idempotent).
     */
    private boolean initializerSingleSimpleArgTailDotSplits(
            VariableDeclarator variable,
            MethodCallExpr methodCall,
            String flatName
    ) {
        return methodCallChainRootIsObjectCreation.test(methodCall)
            && methodCallChainInitializerShape.apply(methodCall).singleCall()
            && tailHasSingleSimpleArgument(methodCall)
            && argumentBreakOpenerFits(variable, methodCall, flatName);
    }

    /**
     * Identifies a call whose argument list is exactly one <em>simple</em> argument — a bare name, field access,
     * {@code this}/{@code super}, or literal. This mirrors {@code MethodCallChainPrinter.singleSimpleMethodCallSegmentArgument}
     * (the classification the return chain's #236 dot-split and {@code objectRootSingleSegmentChain}'s compact-tail branch
     * use) so the initializer's single-simple-arg tail gate keeps the same notion of "simple" as the chain segment renderer
     * it ultimately routes through; a lambda, nested call, or multi-argument tail is not simple and keeps opening its
     * argument list. It is the inverse of {@code ControlConditionMethodCallLayout.hasComplexArgument} for the single-argument
     * case.
     */
    private boolean tailHasSingleSimpleArgument(MethodCallExpr methodCall) {
        if (methodCall.getArguments().size() != 1) {
            return false;
        }
        Expression argument = methodCall.getArgument(0);
        return argument.isNameExpr()
            || argument.isFieldAccessExpr()
            || argument.isThisExpr()
            || argument.isSuperExpr()
            || argument.isLiteralExpr();
    }

    /**
     * Keeps a commented block-lambda method-call argument on a direct broken-call layout.
     *
     * <p>The ordinary broken-call fallback rejects nested comments so it does not steal comment ownership from method
     * call rendering. This narrower path covers two source shapes where that fallback would otherwise drop the comment
     * and oscillate: the comment is the leading cluster before the first statement inside a block lambda argument, or it
     * is a contained comment anywhere inside the block lambda of a single object-creation-rooted call (see
     * {@link #methodCallHasContainedCommentObjectCreationBlockLambdaArgument}). In both cases the call opener must still
     * fit with the assignment. Routing through {@link #brokenMethodCallArgumentList} renders the lambda body with the
     * normal block renderer, which keys the layout on the AST and opener width rather than on source line breaks and
     * preserves the contained comment.
     */
    private Optional<Doc> variableWithLeadingCommentedBlockLambdaMethodCall(
            VariableDeclarator variable,
            String name,
            String flatName,
            MethodCallExpr methodCall
    ) {
        if (
            (!methodCallHasLeadingCommentedBlockLambdaArgument(methodCall)
                && !methodCallHasContainedCommentObjectCreationBlockLambdaArgument(methodCall))
            || methodCall.getArguments().isEmpty()
            || methodCallHasOwnComment(methodCall)
            || methodCall.getScope().filter(shouldPrintScopeAsDoc).isPresent()
        ) {
            return Optional.empty();
        }
        String callPrefix = methodCallPrefix.apply(methodCall);
        String firstLine = flatName + " = " + callPrefix + "(";
        if (openerLineWidth(variable, firstLine) > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(brokenMethodCallArgumentList(name, methodCall, callPrefix));
    }

    private Doc brokenMethodCallArgumentList(
            String name,
            MethodCallExpr methodCall,
            String callPrefix
    ) {
        return Doc.concat(
            Doc.text(name + " = " + callPrefix + "("),
            Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    methodCallArgumentList.apply(methodCall.getArguments(), Doc.HARD_LINE)
                )
            ),
            Doc.HARD_LINE,
            Doc.text(")")
        );
    }

    private Optional<Doc> variableWithForcedMethodCallChain(
            VariableDeclarator variable,
            String name,
            String flatName,
            MethodCallExpr methodCall
    ) {
        return forcedMethodCallChain(variable, methodCall, flatName).map(chain -> variableWithMethodCallChain(
                variable,
                name,
                flatName,
                methodCall,
                methodCallChainFirstLine.apply(methodCall),
                chain
        ));
    }

    /**
     * Identifies receiver-call initializers where the assignment opener should be tried before chain fallback.
     */
    private boolean methodCallHasAttachableScope(MethodCallExpr methodCall) {
        return methodCall.getScope()
                .filter(scope -> scope.isNameExpr()
                        || scope.isThisExpr()
                        || scope.isSuperExpr()
                        || (scope instanceof MethodCallExpr scopedCall
                            && scopedCall.getAllContainedComments().isEmpty()
                            && methodCallScopeEndsOnNameLine(scopedCall, methodCall))
                )
                .isPresent();
    }

    private boolean methodCallScopeEndsOnNameLine(MethodCallExpr scope, MethodCallExpr methodCall) {
        return scope.getRange()
                .flatMap(scopeRange -> methodCall.getName()
                            .getRange()
                            .map(nameRange -> scopeRange.end.line == nameRange.begin.line)
                )
                .orElse(false);
    }

    private boolean methodCallHasOwnComment(MethodCallExpr methodCall) {
        return methodCall.getComment().isPresent()
            || methodCall.getName().getComment().isPresent()
            || methodCall.getScope().flatMap(Expression::getComment).isPresent();
    }

    private boolean methodCallHasBlockLambdaArgument(MethodCallExpr methodCall) {
        return methodCall.getArguments()
                .stream()
                .anyMatch(argument -> argument instanceof LambdaExpr lambdaExpr
                        && lambdaExpr.getBody().isBlockStmt()
                );
    }

    private boolean methodCallHasLeadingCommentedBlockLambdaArgument(MethodCallExpr methodCall) {
        return methodCall.getArguments()
                .stream()
                .filter(LambdaExpr.class::isInstance)
                .map(LambdaExpr.class::cast)
                .filter(lambdaExpr -> lambdaExpr.getBody().isBlockStmt())
                .map(lambdaExpr -> lambdaExpr.getBody().asBlockStmt())
                .filter(block -> !block.getStatements().isEmpty())
                .anyMatch(
                    block ->
                        !commentPlacement.lineCommentsBeforeFirst( block, block.getStatements().getFirst().orElseThrow() ) .isEmpty()
                );
    }

    /**
     * Identifies the one initializer shape that the leading-comment block-lambda handler must also rescue: a single
     * object-creation-rooted call (for example {@code new Runner(arg).query(arg, lambda -> { ... })}) whose block lambda
     * carries any contained comment that is not the call's own comment.
     *
     * <p>The receiver-break and hug paths reject this shape on a contained comment, so it would otherwise fall through to
     * the source-shape-keyed forced chain. That chain explodes when the source was already multiline and hugs when it was
     * flat, which is non-idempotent, and it drops the comment. Routing this case through the argument-break renderer keys
     * the decision on the AST and the opener width instead of the source line breaks, and preserves the comment because
     * the argument list is rendered through the normal block renderer.
     *
     * <p>This deliberately excludes method-call-rooted and name-rooted block-lambda chains so their existing attached-hug
     * layout is left untouched. It also excludes the leading-comment case, which the original predicate already covers,
     * and the no-comment case, which must keep hugging. The wider, layout-independent contained-comment drop in those
     * other initializer shapes is a separate concern this predicate intentionally does not widen into.
     */
    private boolean methodCallHasContainedCommentObjectCreationBlockLambdaArgument(MethodCallExpr methodCall) {
        return methodCallChainRootIsObjectCreation.test(methodCall)
            && methodCallChainInitializerShape.apply(methodCall).singleCall()
            && methodCallHasBlockLambdaArgument(methodCall)
            && !methodCallHasOwnComment(methodCall)
            && !methodCall.getAllContainedComments().isEmpty();
    }

    /**
     * Keeps block-lambda method-call initializers on the assignment line until the lambda opener no longer fits.
     *
     * <p>The ordinary argument-break fallback remains available for long call prefixes or lambda parameter lists. This
     * branch only wins when the assignment line through the lambda opener fits after the declaration prefix.
     */
    private Optional<Doc> variableWithHuggableBlockLambdaArguments(
            VariableDeclarator variable,
            String name,
            String flatName,
            MethodCallExpr methodCall,
            String callPrefix
    ) {
        return huggableBlockLambdaArguments
                .render(
                    callPrefix,
                    methodCall.getArguments(),
                    firstLine -> layoutWidth.variableInitializer(variable, flatName + " = " + firstLine)
                )
                .map(call -> Doc.concat(Doc.text(name + " = "), call));
    }

    private Optional<Doc> variableWithReceiverBreakBeforeOverWidthHuggableBlockLambdaArguments(
            VariableDeclarator variable,
            String name,
            String flatName,
            MethodCallExpr methodCall
    ) {
        String callPrefix = methodCallPrefix.apply(methodCall);
        if (huggableBlockLambdaArgumentsFit(variable, name, flatName, methodCall, callPrefix)) {
            return Optional.empty();
        }
        return variableWithReceiverBreakBeforeHuggableBlockLambdaArguments(variable, name, flatName, methodCall);
    }

    /**
     * Reports whether the plain (no receiver break) huggable block-lambda layout fits, used only to decide that the
     * receiver-break shape is unnecessary.
     *
     * <p>Building the candidate {@link Doc} renders the lambda block, which claims the comments inside it. This probe
     * never becomes the emitted layout — when it fits the caller returns {@link Optional#empty()} so the ordinary hug
     * renders elsewhere, and when it does not the receiver-break path renders the call fresh. Both winners re-render the
     * same lambda block, so the probe's claims must not stick or the winner would re-offer an already-claimed comment and
     * drop it (the strict-claims invariant rejects the duplicate; first-claim-wins renders it {@link Doc#EMPTY}). Wrapping
     * the render in {@link CommentTracker#speculatively} and always returning {@link Optional#empty()} from the scope rolls
     * back every claim the probe made, leaving the eventual winner as the sole claimant. The presence flag is read out
     * through a holder because the speculative result is intentionally discarded.
     */
    private boolean huggableBlockLambdaArgumentsFit(
            VariableDeclarator variable,
            String name,
            String flatName,
            MethodCallExpr methodCall,
            String callPrefix
    ) {
        boolean[] fits = {false};
        comments.speculatively(() -> {
            fits[0] = variableWithHuggableBlockLambdaArguments(variable, name, flatName, methodCall, callPrefix)
                    .isPresent();
            return Optional.<Doc>empty();
        });
        return fits[0];
    }

    private Optional<Doc> variableWithReceiverBreakBeforeHuggableBlockLambdaArguments(
            VariableDeclarator variable,
            String name,
            String flatName,
            MethodCallExpr methodCall
    ) {
        Optional<Expression> scope = methodCall.getScope();
        if (
            scope.isEmpty()
            || scope.filter(Expression::isMethodCallExpr).isPresent()
            || scope.filter(expression -> !expression.getAllContainedComments().isEmpty()).isPresent()
            || scope.filter(shouldPrintScopeAsDoc).isPresent()
        ) {
            return Optional.empty();
        }
        Expression receiver = scope.orElseThrow();
        String receiverText = compact.apply(receiver);
        if (
            receiverText.length() <= flatName.length()
            || layoutWidth.variableInitializer(variable, flatName + " = " + receiverText) > options.lineWidth()
        ) {
            return Optional.empty();
        }
        return huggableBlockLambdaArguments
                .render(
                    methodCallSegmentPrefix(methodCall),
                    methodCall.getArguments(),
                    layoutWidth::continuationStatement
                )
                .map(call -> Doc.concat(
                        Doc.text(name + " = "),
                        expression.apply(receiver),
                        Doc.indent(Doc.concat(Doc.HARD_LINE, call))
                ));
    }

    private String methodCallSegmentPrefix(MethodCallExpr methodCall) {
        return "."
            + methodCall.getTypeArguments()
                    .map(typeArguments -> "<" + compactJoin.apply(typeArguments) + ">")
                    .orElse("")
            + methodCall.getNameAsString();
    }

    /**
     * Lets a source-multiline receiver chain collapse back to the direct block-lambda call shape when the assignment
     * line through the call opener still fits.
     */
    private Optional<Doc> variableWithSourceMultilineBlockLambdaInitializer(
            VariableDeclarator variable,
            String name,
            String flatName,
            MethodCallExpr methodCall
    ) {
        if (
            !methodCallChainIsSourceMultiline.test(methodCall)
            || methodCall.getArguments().isEmpty()
            || !methodCallHasBlockLambdaArgument(methodCall)
            || methodCallHasOwnComment(methodCall)
            || methodCall.getScope().filter(shouldPrintScopeAsDoc).isPresent()
        ) {
            return Optional.empty();
        }
        return variableWithBrokenMethodCallArguments(variable, name, flatName, methodCall, false);
    }

    /**
     * PR #279 review (#17): reports a comment-carrying, single-selector object-creation-rooted chain whose tail call
     * takes no arguments ({@code new X(...).build()} / {@code new RelaySubject<>(...).withoutAuthentication()}) that has
     * no interior break point — the constructor already fits on its own line and the empty tail cannot open an argument
     * list — yet overruns when attached after {@code NAME = } at a deep column (a wide declaration prefix such as a
     * broken generic type). The width-driven no-comment sibling fans the selector onto its own dotted continuation line,
     * but that fan cannot be reproduced on this comment-carrying path without dropping the trailing comment (the packed
     * fan refuses comment-bearing chains, {@code packedCompactMethodCallChain}/{@code packedBrokenObjectRootChain}) and is
     * not a one-pass fixed point here (fanning parks the comment on the selector line, which re-routes to this attach on
     * the next pass and collapses back to flat). Breaking after {@code =} — the initializer's declared last resort — puts
     * the whole chain on its own indented continuation line where it fits, preserves the trailing comment verbatim, and is
     * a pure width+AST fixpoint.
     *
     * <p>Gated so it fires ONLY when the attached flat chain overruns the line AND the whole flat chain fits on its own
     * continuation line: a chain that still overflows on its own line (a genuinely over-wide constructor), or a tail with
     * arguments that could break to fit, is left to the existing attach / argument-break logic below unchanged. Because it
     * only fires on a chain that is currently attached over-width, it can only remove an over-width line, never reshape a
     * fitting one.
     */
    private boolean attachedSingleSegmentChainMustBreakAfterEquals(
            VariableDeclarator variable,
            String flatName,
            MethodCallExpr methodCall
    ) {
        if (
            !methodCallChainRootIsObjectCreation.test(methodCall)
            || !methodCallChainInitializerShape.apply(methodCall).singleCall()
            || !methodCall.getArguments().isEmpty()
            || !(methodCall.getScope().orElse(null) instanceof ObjectCreationExpr constructor)
        ) {
            return false;
        }
        // Reconstruct the one-line chain from the AST rather than {@code compact.apply(methodCall)}: the whole-chain
        // compaction leaks a source-shaped space before the {@code .} for a chain the author wrote broken, which would make
        // this width probe (and therefore the shape) depend on the source layout. The constructor scope compacts cleanly
        // (no chain to leak) and the tail is a zero-argument selector, so this string is source-neutral.
        String fullChain = compact.apply(constructor) + "." + methodCall.getNameAsString() + "()";
        return layoutWidth.variableInitializer(variable, flatName + " = " + fullChain + ";") > options.lineWidth()
            && layoutWidth.continuationStatement(fullChain + ";") <= options.lineWidth();
    }

    /**
     * PR #279 review (#11): renders the {@link #attachedSingleSegmentChainMustBreakAfterEquals} shape as a DOT-BREAK — the
     * constructor root on the assignment line and the zero-argument tail selector fanned onto its own dotted continuation
     * line ({@code = new RelaySubject<>(...)}⏎{@code .withoutAuthentication(); // note}) — whenever the constructor opener
     * fits on the assignment line. This supersedes break-after-{@code =} for exactly this comment-bearing, empty-tail
     * object-creation chain: the width-driven no-comment sibling already dot-breaks (its {@code (A)} conditional group fans
     * the tail at the true column), so reproducing that shape byte-for-byte on the comment sink makes the two converge — a
     * flat-source pass fans through {@code (A)}, and the re-parsed broken-source pass, which parks the trailing comment on
     * the selector line, reaches here and lands the identical fan (a one-pass fixpoint).
     *
     * <p>Built HERE, ahead of {@code variableWithMethodCallChain}, rather than by attaching the pre-built chain doc: the fan
     * must claim the trailing comment itself. The chain doc ({@code methodCallWithSemicolon}) renders the comment-bearing
     * tail flat (the packed fan refuses comment-bearing chains) and, being built first, would claim the comment at doc-build
     * time and leave this re-render empty — dropping it. Rendering the constructor root from its source-neutral first line
     * ({@link #methodCallChainFirstLine}) and the tail as {@code .selector()} text keeps the shape a pure width + AST
     * function; the trailing comment rides as a {@link Doc#lineSuffix} after the {@code ;}, exactly as
     * {@code MethodCallChainPrinter} emits a chain's final trailing comment.
     *
     * <p>Returns empty — falling back to the break-after-{@code =} shape — unless the constructor opener fits on the
     * assignment line AND the chain carries no comment other than the tail trailing line comments (a constructor-argument or
     * selector-name comment would be dropped by the text root render), so no other chain shape is disturbed and no comment
     * is lost.
     */
    private Optional<Doc> dotBrokenObjectRootTailChain(
            VariableDeclarator variable,
            String name,
            String flatName,
            MethodCallExpr methodCall
    ) {
        if (!attachedSingleSegmentChainMustBreakAfterEquals(variable, flatName, methodCall)) {
            return Optional.empty();
        }
        // The constructor root and the selector are rendered as source-neutral TEXT (the constructor from its
        // {@code methodCallChainFirstLine}, the zero-argument tail as {@code .selector()}), so those renders carry NO
        // comment; only the tail trailing line comment is re-emitted. Bail unless every comment reachable in the chain is
        // one of those tail comments (a constructor-argument, selector-name, or chain-own comment would be dropped). This is
        // source-shape-independent: JavaParser parks the same trailing comment on the SELECTOR NAME when the chain is broken
        // across source lines and on the whole expression when the tail shares the assignment line, but both surface it
        // through {@code methodCallFinalTrailingLineComments}, so both shapes take this route and converge on the same fan.
        List<JavaCommentTrivia> tailComments = methodCallFinalTrailingLineComments(methodCall);
        List<Comment> renderedComments = tailComments.stream().map(JavaCommentTrivia::comment).toList();
        List<Comment> allChainComments = new ArrayList<>(methodCall.getAllContainedComments());
        methodCall.getComment().ifPresent(allChainComments::add);
        if (!allChainComments.stream().allMatch(renderedComments::contains)) {
            return Optional.empty();
        }
        String firstLine = methodCallChainFirstLine.apply(methodCall);
        if (openerLineWidth(variable, flatName + " = " + firstLine) > options.lineWidth()) {
            return Optional.empty();
        }
        Doc commentSuffix = tailComments.isEmpty()
            ? Doc.EMPTY
            : Doc.lineSuffix(Doc.concat(
                Doc.text(" "),
                Doc.join(Doc.text(" "), tailComments.stream().map(comments::comment).toList())
            ));
        Doc tailSegment = Doc.concat(Doc.text(methodCallSegmentPrefix(methodCall) + "();"), commentSuffix);
        return Optional.of(Doc.concat(
            Doc.text(name + " = " + firstLine),
            Doc.indent(Doc.indent(Doc.concat(Doc.HARD_LINE, tailSegment)))
        ));
    }

    /**
     * Decides whether a method-call chain can start after {@code =} or must move entirely to an indented continuation.
     */
    private Doc variableWithMethodCallChain(
            VariableDeclarator variable,
            String name,
            String flatName,
            MethodCallExpr methodCall,
            String firstLine,
            Doc chain
    ) {
        if (attachedSingleSegmentChainMustBreakAfterEquals(variable, flatName, methodCall)) {
            return Doc.concat(Doc.text(name + " ="), Doc.indent(Doc.concat(Doc.HARD_LINE, chain)));
        }
        if (
            methodCallChainRootIsObjectCreation.test(methodCall)
            && openerLineWidth(variable, flatName + " = " + firstLine + ";") > options.lineWidth()
        ) {
            // PR #279: prefer breaking the constructor's argument list over breaking after `=`. The chain root's
            // constructor renders through a column-aware Doc.group (ObjectCreationPrinter.widthDrivenObjectCreation),
            // so when the whole flat constructor cannot start after `NAME = ` we still attach the chain — provided the
            // constructor OPENER (`new X(`) itself fits on the assignment line — and let the renderer break the argument
            // list at the true rendered column (the same column the emitted `NAME = ` already advanced to). The attach
            // probe's `firstLine` is the full compact constructor, measured by the planner at the base indent (a
            // wrong-column read, the #137/#155 family), so it over-reports the fit and forces break-after-`=`; measuring
            // the opener here at the real `NAME = ` column recovers the constructor-arg break. Only when even the opener
            // cannot start after `NAME = ` do we break after `=`.
            Optional<String> constructorOpener = objectCreationChainRootOpener(methodCall);
            if (
                constructorOpener.isPresent()
                && openerLineWidth(variable, flatName + " = " + constructorOpener.orElseThrow())
                    <= options.lineWidth()
            ) {
                return Doc.concat(Doc.text(name + " = "), chain);
            }
            return Doc.concat(Doc.text(name + " ="), Doc.indent(Doc.concat(Doc.HARD_LINE, chain)));
        }
        if (openerLineWidth(variable, flatName + " = " + firstLine) > options.lineWidth()) {
            return Doc.concat(Doc.text(name + " ="), Doc.indent(Doc.concat(Doc.HARD_LINE, chain)));
        }
        return Doc.concat(Doc.text(name + " = "), chain);
    }

    /**
     * Returns the constructor opener ({@code new X(}) of an object-creation-rooted chain, so the initializer can probe
     * whether the constructor can start on the assignment line and its argument list break below it (PR #279). The opener
     * is only offered when the root constructor actually carries an argument list to break; a no-argument constructor has
     * nothing to reflow, so it stays on the break-after-{@code =} path. The root is located by walking the receiver spine
     * (through method-call and field-access selectors) to the {@link ObjectCreationExpr} that
     * {@code methodCallChainRootIsObjectCreation} already confirmed is present.
     */
    private Optional<String> objectCreationChainRootOpener(MethodCallExpr methodCall) {
        Expression current = methodCall.getScope().orElse(null);
        while (current != null) {
            if (current instanceof ObjectCreationExpr objectCreation) {
                if (objectCreation.getArguments().isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(objectCreationPrefix.apply(objectCreation) + "(");
            }
            if (current instanceof MethodCallExpr scopedCall) {
                current = scopedCall.getScope().orElse(null);
            } else if (current instanceof FieldAccessExpr fieldAccess) {
                current = fieldAccess.getScope();
            } else {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private String mixedFieldMethodCallFirstLine(MethodCallExpr methodCall) {
        return mixedFieldMethodCallRoot.apply(methodCall)
                .filter(root -> !(root instanceof ObjectCreationExpr))
                .map(compact)
                .orElseGet(() -> methodCallChainFirstLine.apply(methodCall));
    }

    /**
     * Chooses the conditional initializer shape, preferring to break the ternary itself over breaking after {@code =}.
     *
     * <p>Break-after-{@code =} is a last resort (PR #279 review): when {@code NAME = <whole ternary>} overflows we would
     * rather keep the condition on the {@code NAME = <condition>} line and let the ternary own its {@code ?}/{@code :}
     * break than strand {@code =} at end of line. So the condition-stays-on-the-{@code =}-line shapes (a condition that
     * fits after {@code =}, or a parenthesized condition whose opener fits) are chosen ahead of the break-after-{@code =}
     * shapes. Only when the condition genuinely cannot start after {@code =} do we break there — preferring the whole
     * ternary flat on the continuation line when it fits, otherwise the fully-broken ternary under {@code =}. The
     * structural {@link #shouldBreakBeforeConditionalInitializer} rule (a binary condition combined with a binary branch,
     * which reads better wholly under the assignment) is honored first and is independent of this width policy.
     */
    private Doc conditionalInitializer(String name, String flatName, ConditionalExpr initializer) {
        String conditionLine = flatName + " = " + compact.apply(initializer.getCondition());
        String compactInitializer = compact.apply(initializer);
        if (shouldBreakBeforeConditionalInitializer.test(initializer)) {
            return Doc.concat(
                Doc.text(name + " ="),
                Doc.indent(Doc.concat(Doc.HARD_LINE, brokenConditionalExpression.apply(initializer)))
            );
        }
        if (layoutWidth.blockStatement(conditionLine + ";") <= options.lineWidth()) {
            return Doc.concat(Doc.text(name + " = "), brokenConditionalExpression.apply(initializer));
        }
        if (parenthesizedConditionalConditionOpenerFits(flatName, initializer)) {
            return Doc.concat(Doc.text(name + " = "), brokenConditionalExpression.apply(initializer));
        }
        // The condition itself will not start after `=`; break there. Keep the whole ternary flat on the continuation
        // line when it fits, otherwise fall back to the fully-broken ternary under `=`.
        if (layoutWidth.continuationStatement(compactInitializer + ";") <= options.lineWidth()) {
            return Doc.concat(
                Doc.text(name + " ="),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(compactInitializer)))
            );
        }
        return Doc.concat(
            Doc.text(name + " ="),
            Doc.indent(Doc.concat(Doc.HARD_LINE, brokenConditionalExpression.apply(initializer)))
        );
    }

    private boolean parenthesizedConditionalConditionOpenerFits(String flatName, ConditionalExpr initializer) {
        return initializer.getCondition() instanceof EnclosedExpr
            // C10-c: measure the {@code NAME = (} opener at the initializer's true rendered block/type depth instead of
            // the fixed CURRENT baseline.
            && layoutWidth.nodeLine(initializer, flatName + " = (") <= options.lineWidth();
    }

    /**
     * Keeps expression-lambda initializers attached to {@code =} and {@code ->} while the opener fits.
     */
    private Optional<Doc> variableWithExpressionLambdaInitializer(
            VariableDeclarator variable,
            String name,
            String flatName,
            LambdaExpr lambdaExpr
    ) {
        if (lambdaExpr.getBody().isBlockStmt() || lambdaExpr.getExpressionBody().isEmpty()) {
            return Optional.empty();
        }
        String parameters = lambdaParameters.apply(lambdaExpr);
        if (
            lambdaParametersShouldBreak.test(lambdaExpr, parameters)
            || !(lambdaExpr.getExpressionBody().orElseThrow() instanceof MethodCallExpr methodCall)
        ) {
            return Optional.empty();
        }
        String bodyFirstLine = methodCallChainFirstLine.apply(methodCall);
        String lambdaPrefix = parameters + " ->";
        // The chain here is an expression-lambda body (NAME = params -> chain), a distinct position from the direct
        // initializer chain: its same-line prefix is NAME = params -> , not NAME = . Threading a non-empty leftEdgePrefix
        // here would newly activate the object-creation dot-split for lambda-body chains too, which is out of the
        // initializer-chain slice's scope (LDM-2f #190, mirroring #236 keeping return scoped to the direct return chain).
        // Pass root(); the firstLineWidth probe still folds the lambda prefix in, so this stays byte-identical.
        Doc body = initializerChain
                .apply(
                    methodCall,
                    firstLineWidth(variable, flatName + " = " + lambdaPrefix + " "),
                    LayoutContext.root()
                )
                .orElseGet(() -> expression.apply(methodCall));
        // C10-c: measure the {@code NAME = params -> body} first line at the declarator's true rendered block/type depth
        // ({@link LayoutWidth#variableInitializer}) instead of the fixed CURRENT baseline.
        if (
            layoutWidth.variableInitializer(variable, flatName + " = " + lambdaPrefix + " " + bodyFirstLine)
                <= options.lineWidth()
        ) {
            return Optional.of(Doc.concat(Doc.text(name + " = " + lambdaPrefix + " "), body));
        }
        if (layoutWidth.variableInitializer(variable, flatName + " = " + lambdaPrefix) <= options.lineWidth()) {
            return Optional.of(
                Doc.concat(
                    Doc.text(name + " = " + lambdaPrefix),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, body))
                )
            );
        }
        return Optional.empty();
    }

    private Optional<Doc> forcedMethodCallChain(
            VariableDeclarator variable,
            MethodCallExpr methodCall,
            String flatName
    ) {
        // LDM-2f (#190): the initializer's assignment prefix (NAME = ) shares the chain's first line, so hand the chain
        // gates that fixed prefix through the LayoutContext, mirroring how ReturnExpressionPrinter threads "return ".
        // compactRootLineWidth measures the compact chain root at nodeIndentWidth(root) + "NAME = ".length() + text, so the
        // fit decision depends on the rendered column rather than where the value sits in source (a reindented value is
        // measured at its true rendered column). It also makes the
        // object-creation dot-split (MethodCallChainPrinter.refuseOpeningSingleSimpleObjectRootChainTail) reachable for the
        // object-creation-rooted chain shapes this forced path renders. The firstLineWidth probe folds the same NAME =
        // prefix in for the greedy packer and stay-flat gates.
        //
        // NOTE (scope): the single-call object-root case whose opener fits (NAME = new X(a).sel(simpleArg) kept on the
        // assignment line) does NOT reach here — variableInitializerBrokenOrFlat pre-empts it with the argument-break
        // shape under singleCallConvergesOnArgumentBreak, a deliberate idempotence-preserving convergence choice. Rerouting
        // it to the dot-split fan-out is non-idempotent for initializers (unlike return, the initializer layout space has a
        // break-after-= collapse the fan-out oscillates with), so it is left as-is and deferred.
        return initializerChain.apply(
            methodCall,
            firstLineWidth(variable, flatName + " = "),
            LayoutContext.root().withLeftEdgePrefix(flatName + " = ")
        );
    }

    private ToIntFunction<String> firstLineWidth(VariableDeclarator variable, String prefix) {
        return text -> layoutWidth.variableInitializer(variable, prefix + text);
    }

    /**
     * Keeps lambda initializer parameters and body with the shared lambda formatter when only the parameter list needs a
     * declaration-width-driven break.
     */
    private Optional<Doc> variableWithBrokenLambdaParameters(
            String name,
            String flatName,
            LambdaExpr lambdaExpr
    ) {
        String parameters = lambdaParameters.apply(lambdaExpr);
        if (
            !lambdaExpr.getBody().isBlockStmt()
            || !lambdaParametersShouldBreak.test(lambdaExpr, parameters)
            // C10-c: measure the {@code NAME = (} opener at the lambda's true rendered block/type depth instead of CURRENT.
            || layoutWidth.nodeLine(lambdaExpr, flatName + " = (") > options.lineWidth()
        ) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(Doc.text(name + " = "), lambdaExpression.apply(lambdaExpr)));
    }

    /**
     * Keeps direct block-lambda initializers on the assignment line while the lambda opener still fits.
     */
    private Optional<Doc> variableWithBlockLambdaInitializer(
            String name,
            String flatName,
            LambdaExpr lambdaExpr
    ) {
        if (!lambdaExpr.getBody().isBlockStmt()) {
            return Optional.empty();
        }
        String parameters = lambdaParameters.apply(lambdaExpr);
        if (
            lambdaParametersShouldBreak.test(lambdaExpr, parameters)
            // C10-c: measure the {@code NAME = params -> {} opener at the lambda's true rendered block/type depth instead
            // of the fixed CURRENT baseline.
            || layoutWidth.nodeLine(lambdaExpr, flatName + " = " + parameters + " -> {") > options.lineWidth()
        ) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(Doc.text(name + " = "), lambdaExpression.apply(lambdaExpr)));
    }

    /**
     * Collects source-leading line comments that sit in the gap between the declarator name and the initializer
     * expression, recovered from wherever JavaParser bucketed them rather than from a single fixed association.
     *
     * <p>JavaParser spreads {@code =}-leading comments across three buckets depending on the source layout: a comment on
     * its own line lands in the declarator's orphan comments, the comment immediately preceding the initializer becomes
     * the initializer's own comment, and a comment that collapse pushes onto the declarator name's line attaches to the
     * name as <em>its</em> trailing comment. Keying recovery on any one bucket loses comments when a whitespace
     * perturbation moves them between buckets, so this gathers from all three and selects purely by source order — a
     * comment belongs here when it begins after the declarator name and before the initializer. At the {@code @default}
     * shape the name-trailing bucket is empty, so adding it is a superset that leaves unperturbed output unchanged.
     */
    private Optional<Doc> leadingInitializerComments(VariableDeclarator variable, Expression initializer) {
        List<Comment> leadingComments = new ArrayList<>();
        variable.getOrphanComments()
                .stream()
                .filter(LineComment.class::isInstance)
                .filter(comment -> CommentIndex.startsBefore(comment, initializer))
                .forEach(leadingComments::add);
        initializer.getComment()
                .filter(LineComment.class::isInstance)
                .filter(comment -> CommentIndex.startsBefore(comment, initializer))
                .ifPresent(leadingComments::add);
        // A comment that collapse slid onto the declarator name's line attaches to the name; recover it here when it
        // sits in the name-to-initializer gap (after the name, before the initializer) rather than leading the name.
        variable.getName()
                .getComment()
                .filter(LineComment.class::isInstance)
                .filter(comment -> CommentIndex.startsAfterEndOf(variable.getName(), comment))
                .filter(comment -> CommentIndex.startsBefore(comment, initializer))
                .filter(comment -> leadingComments.stream().noneMatch(existing -> existing == comment))
                .ifPresent(leadingComments::add);
        List<Doc> docs = leadingComments.stream()
                .sorted(CommentIndex.sourceOrderComparator())
                .map(comments::comment)
                .filter(doc -> doc != Doc.EMPTY)
                .toList();
        return docs.isEmpty() ? Optional.empty() : Optional.of(Doc.join(Doc.HARD_LINE, docs));
    }

    /**
     * Keeps a block comment attached to the variable name rather than treating it as initializer trivia.
     */
    String variableName(VariableDeclarator variable) {
        // A C-style declarator (brackets written after the name, e.g. String filters[]) keeps its brackets in that
        // position; the shared type prefix carries only the element type. CStyleArrayDeclarators returns an empty
        // suffix for canonical Type[] declarators and non-array declarators, so they are unaffected.
        String name = variable.getNameAsString() + CStyleArrayDeclarators.declaratorBracketsAfterName(variable);
        Doc leadingBlockComment = comments.ownComment(variable, BlockComment.class::isInstance);
        if (leadingBlockComment == Doc.EMPTY) {
            return name;
        }
        return commentText(leadingBlockComment) + " " + name;
    }

    /**
     * Keeps assignment and cast opener together when the cast type itself owns the first useful break.
     *
     * <p>Simple casts still use the ordinary wide-initializer fallback because they do not provide an internal type break
     * that can absorb the overflow after {@code =}.
     */
    private Doc variableWithCastTypeBreak(String name, String flatName, CastExpr castExpr) {
        Doc initializer = expression.apply(castExpr);
        if (castTypeOpenerFitsOnEqualsLine(flatName, castExpr.getType())) {
            return Doc.group(Doc.concat(Doc.text(name + " = "), initializer));
        }
        return Doc.group(Doc.concat(Doc.text(name + " ="), Doc.indent(Doc.concat(Doc.LINE, initializer))));
    }

    private boolean castTypeNeedsBreak(String flatName, Type type) {
        // C10-c: measure the cast opener at the type's true rendered block/type depth instead of the fixed CURRENT
        // baseline. The cast type sits directly under the declarator (no intervening block/type), so it shares the
        // declarator's rendered depth.
        return castTypeCanBreak(type)
            && layoutWidth.nodeLine(type, flatName + " = (" + compactTypeLike.apply(type) + ")") > options.lineWidth();
    }

    private boolean castTypeOpenerFitsOnEqualsLine(String flatName, Type type) {
        // C10-c: measure the {@code NAME = (Type)} opener at the type's true rendered block/type depth instead of CURRENT.
        return layoutWidth.nodeLine(type, flatName + " = " + castTypeOpener(type)) <= options.lineWidth();
    }

    private String castTypeOpener(Type type) {
        if (
            type instanceof ClassOrInterfaceType classOrInterfaceType
            && classOrInterfaceType.getTypeArguments().isPresent()
        ) {
            return "(" + typeNameWithoutArguments.apply(classOrInterfaceType) + "<";
        }
        return "(";
    }

    private boolean castTypeCanBreak(Type type) {
        return (
            type instanceof IntersectionType
            || (type instanceof ClassOrInterfaceType classOrInterfaceType
                && classOrInterfaceType.getTypeArguments().isPresent())
        );
    }

    private boolean hasNonEmptyTypeArguments(ClassOrInterfaceType type) {
        return type.getTypeArguments().map(arguments -> !arguments.isEmpty()).orElse(false);
    }

    /**
     * Uses the shared method-chain break as the last initializer fallback before normal expression rendering.
     */
    private Doc brokenInitializer(VariableDeclarator variable, Expression initializer) {
        if (initializer instanceof MethodCallExpr methodCall) {
            // This fallback renders the chain on its own continuation line under a broken NAME = (the caller wraps it in
            // NAME =\n<indented>), so the chain owns its first column with no same-line prefix. Pass root() (empty prefix):
            // there is no NAME = to attribute at the rendered column here, so the leftEdgePrefix arm must stay off.
            return initializerChain
                    .apply(methodCall, text -> layoutWidth.variableInitializer(variable, text), LayoutContext.root())
                    .orElseGet(() -> expression.apply(initializer));
        }
        return expression.apply(initializer);
    }

    /**
     * Recognizes a {@code !(<binary>)} initializer value and hands back the parenthesized binary so the assignment line
     * can keep {@code = !(} attached and break the binary by its operands inside the parentheses.
     *
     * <p>The shared expression renderer would otherwise break this complement after {@code =} and keep {@code !(...)}
     * flat on the continuation line. When the inline assignment line overflows, the more readable shape keeps the
     * negation opener on the assignment line ({@code name = !(}), breaks the inner binary one operator per line, and drops
     * the closing {@code )} on its own line — the same parenthesized-binary break the {@code if (...)} condition and
     * complement-{@code return} paths already use. Only the parenthesized-binary complement is recognized; any other unary
     * value falls through to the shared expression renderer, which keeps the existing flat or break-after-{@code =} shapes.
     */
    private Expression logicalComplementOfParenthesizedBinary(Expression initializer) {
        if (
            initializer instanceof UnaryExpr unaryExpr
            && unaryExpr.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT
            && unaryExpr.getExpression() instanceof EnclosedExpr enclosedExpr
            && enclosedExpr.getInner() instanceof BinaryExpr binaryExpr
        ) {
            return binaryExpr;
        }
        return null;
    }

    /**
     * Detects initializer forms that already choose their own internal line breaks, so the field assignment does not add
     * a second outer break around the same expression.
     */
    private boolean initializerHasOwnBreak(Expression initializer) {
        if (initializer instanceof ArrayCreationExpr arrayCreationExpr) {
            return arrayCreationHasOwnBreak(arrayCreationExpr);
        }
        if (initializer instanceof ArrayAccessExpr) {
            return true;
        }
        if (
            initializer instanceof ObjectCreationExpr objectCreationExpr
            && objectCreationExpr.getAnonymousClassBody().isPresent()
        ) {
            return true;
        }
        if (initializer instanceof SwitchExpr) {
            return true;
        }
        if (initializer instanceof MethodCallExpr methodCallExpr) {
            if (methodCallExpr.getScope().filter(ArrayAccessExpr.class::isInstance).isPresent()) {
                return true;
            }
            return methodCallExpr.getScope()
                    .filter(ArrayCreationExpr.class::isInstance)
                    .map(ArrayCreationExpr.class::cast)
                    .map(this::arrayCreationHasOwnBreak)
                    .orElse(false);
        }
        return false;
    }

    /**
     * Treats array initializers and genuinely-overflowing generic array types as already owning the assignment
     * continuation shape.
     *
     * <p>An array with an initializer always owns its break (the initializer drives the layout). An array without an
     * initializer only owns a break when its generic type arguments overflow at the continuation baseline and therefore
     * take the width-driven last-resort break; a short generic array type whose compact prefix fits its continuation line
     * does not claim an own-break and lets the surrounding assignment decide where to break. The continuation baseline is
     * used so this stays consistent with {@link #variableWithBrokenArrayCreation}: a generic array type only reports an
     * own-break when it would still overflow after the assignment cleanly broke at {@code =}.
     */
    private boolean arrayCreationHasOwnBreak(ArrayCreationExpr expression) {
        return expression.getInitializer().isPresent()
            || arrayCreationTypeBreaks.test(expression, layoutWidth::continuationStatement);
    }

    private String commentText(Doc comment) {
        if (comment instanceof Doc.Text text) {
            return text.value();
        }
        return "";
    }
}
