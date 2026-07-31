package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
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
import com.github.javaparser.ast.type.ClassOrInterfaceType;
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
     * <p>The initializer keyword shares the chain's first line with the assignment prefix
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
     * The terminator-threading forced-chain callback: like {@link ForcedChainWithLayout} but folds the initializer's
     * same-line terminator (its {@code ;}) into the chain so its width-driven fit-or-fan verdict counts it. The caller
     * must not also append the terminator after the returned doc.
     */
    @FunctionalInterface
    interface ForcedChainWithTerminator {
        Optional<Doc> apply(
            MethodCallExpr expression,
            String terminator,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout
        );
    }

    /**
     * The source-neutral canonical-fan callback: emits {@code chainFanOut} for a
     * fan-threshold, comment/lambda-free chain independent of the author's source shape, or empty when the chain is
     * withheld (comment / block-lambda / expression-lambda chains — the deferred lambda-arrow seam). This is the same
     * delegate the return-value path uses; the initializer's break-after-{@code =} decider ranks the fan it produces
     * against the break-after-{@code =} shape at the true column, so a fan-threshold chain (even one with source-multiline
     * selector arguments, which the imperative {@code forcedMethodCallChain} would otherwise fall through to) is idempotent.
     */
    @FunctionalInterface
    interface CanonicalFanChain {
        Optional<Doc> apply(MethodCallExpr expression, String suffix, LayoutContext layout);
    }

    /**
     * The shared last-resort continuation-line break, called back into from the chain cascade's fallback arm as well as
     * the lambda and generic-value arms: breaks after {@code =} and renders the value on its own indented line.
     */
    @FunctionalInterface
    interface GenericBrokenInitializer {
        Doc apply(VariableDeclarator variable, Expression initializer, String name);
    }

    /**
     * The initializer's explicit layout classification: one constant per arm of the width-driven
     * broken-or-flat cascade in {@link #variableInitializerBrokenOrFlat}. {@link #classifyBrokenOrFlat} selects the arm
     * from AST shape and rendered width alone, in a fixed order of conditions, and {@link #renderBrokenOrFlat} dispatches
     * each arm to its shape emitter.
     *
     * <p>{@link #METHOD_CALL_BROKEN} and {@link #LAMBDA} delegate to an ordered {@code Optional<Doc>} sub-cascade
     * (canonical fan, packed / compact object-creation chain, forced chain, broken arguments; expression- vs block-lambda),
     * because each sub-arm's selection depends on the previous emitter returning empty -- a fall-through that cannot be
     * reproduced as a pure classification without re-probing (and re-claiming) comments. Splitting those finer arms into
     * their own constants is a later stage.
     */
    enum InitializerLayoutArm {
        /** Whole initializer stays flat on the {@code =} line (also the fallback when a broken arm declines). */
        FLAT,
        /** Over-width method call that already owns an internal break: forced chain, else flat. */
        METHOD_CALL_OWN_BREAK_CHAIN,
        /** Over-width method call with no own break: the ordered broken-chain sub-cascade, else generic break. */
        METHOD_CALL_BROKEN,
        /** Over-width {@code (Type) call}: cast type kept flat on the {@code =} line, the call broken. */
        CAST_METHOD_CALL_BREAK,
        /** Over-width cast whose generic/intersection type must break across the {@code =} line. */
        CAST_TYPE_BREAK,
        /** Over-width ternary, broken by {@link #conditionalInitializer}. */
        CONDITIONAL,
        /** Over-width lambda: expression-body, block-body, or broken-parameter shape, else generic break. */
        LAMBDA,
        /** Over-width string literal, broken onto its own continuation line under {@code =}. */
        STRING_LITERAL_BREAK,
        /** Over-width array initializer: keeps {@code = } on the assignment line, elements one per line. */
        ARRAY_INITIALIZER_BREAK,
        /** Over-width value with no construct-specific shape: break after {@code =}, value indented. */
        GENERIC_BROKEN,
    }

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    private final SourceShapePolicy sourceShapePolicy;

    private final RawSource rawSource;

    private final FormatterOptions options;

    private final LayoutWidth layoutWidth;

    /** Stateless precedence authority, used to measure the canonical (clarity-parenthesized) width for the routing gate. */
    private final NestedBinaryParenthesesLayout binaryParentheses = new NestedBinaryParenthesesLayout();

    private final Function<Node, String> compact;

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

    // The initializer's single forced method-call-chain shape entry, owned by
    // {@link MethodCallPrinter#initializerChain} (initializer analogue of {@link ReturnExpressionPrinter}'s
    // {@code returnChain}). The initializer's chain-shape SELECTION stays here — it interleaves each shape with
    // break-after-{@code =} and {@code NAME = } prefix gating — but the forced-chain shape itself is delegated through this
    // one entry from all three forced-chain positions (direct chain, expression-lambda body, broken-after-{@code =}
    // fallback). The caller threads the {@code NAME = } left-edge prefix (or {@link LayoutContext#root()}) and the
    // first-line width probe.
    private final ForcedChainWithLayout initializerChain;

    private final ForcedChainWithTerminator initializerChainWithTerminator;

    private final CanonicalFanChain canonicalFanChain;

    private final Function<MethodCallExpr, Doc> methodCallWithSemicolon;

    private final Predicate<MethodCallExpr> methodCallChainRootIsObjectCreation;

    private final Function<
        MethodCallExpr,
        MethodCallChainSourcePlanner.InitializerChainShape
    > methodCallChainInitializerShape;

    private final Function<Type, Doc> castType;

    private final BiFunction<ArrayInitializerExpr, Boolean, Doc> arrayInitializer;

    private final Function<ObjectCreationExpr, String> objectCreationPrefix;

    private final Function<ClassOrInterfaceType, String> typeNameWithoutArguments;

    private final Function<ClassOrInterfaceType, Doc> brokenClassOrInterfaceType;

    private final Predicate<Expression> binaryFansChainOperand;

    private final Function<LambdaExpr, String> lambdaParameters;

    private final BiPredicate<LambdaExpr, String> lambdaParametersShouldBreak;

    private final Function<LambdaExpr, Doc> lambdaExpression;

    // Extracted method-call-chain seam: the ordered broken-call sub-cascade reached once a flat call overflows, its
    // block-lambda-hug sub-family, and the forced-chain ranking entries three chain callers route through. See
    // {@link InitializerMethodCallChainLayout}.
    private final InitializerMethodCallChainLayout chainLayout;

    // Extracted arm-emitter cluster: the object-creation broken-shape sub-ladder (type-argument break, constructor-argument
    // break, commented-creation opener-hug, per-argument operand break) reached through a single entry once a flat
    // {@code new Type(...)} initializer overflows. See {@link InitializerObjectCreationLayout}.
    private final InitializerObjectCreationLayout objectCreationLayout;

    // Extracted comment-recovery cluster: the declarator/initializer trailing and pre-semicolon line-comment recovery
    // consumed by {@link #variableWithStatementTerminator} to re-place comments around the statement terminator. See
    // {@link InitializerTrailingCommentLayout}.
    private final InitializerTrailingCommentLayout trailingCommentLayout;

    // Extracted arm-emitter cluster: the array-creation broken-shape ladder (opener-hug, compact-continuation
    // fallback, element-per-line last resort) and the array own-break classification. See {@link InitializerArrayLayout}.
    private final InitializerArrayLayout arrayLayout;

    // Extracted arm-emitter cluster: the cast-type break that keeps the assignment and cast opener together while a
    // generic/intersection cast type absorbs the overflow. See {@link InitializerCastLayout}.
    private final InitializerCastLayout castLayout;

    // Extracted arm-emitter cluster: the ternary-initializer break, its over-width probe, and the binary-initializer
    // first-operand-with-`=` probe consulted by the comment/source-shape pre-empt tier. See
    // {@link InitializerConditionalLayout}.
    private final InitializerConditionalLayout conditionalLayout;

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
            ForcedChainWithTerminator initializerChainWithTerminator,
            CanonicalFanChain canonicalFanChain,
            Function<MethodCallExpr, Doc> singleSelectorDotSplit,
            BiFunction<MethodCallExpr, ToIntFunction<String>, Optional<Doc>> packedMethodCallChain,
            Function<MethodCallExpr, Doc> methodCallWithSemicolon,
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
        this.compact = context.compactSource::compact;
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
        this.initializerChain = initializerChain;
        this.initializerChainWithTerminator = initializerChainWithTerminator;
        this.canonicalFanChain = canonicalFanChain;
        this.methodCallWithSemicolon = methodCallWithSemicolon;
        this.methodCallChainRootIsObjectCreation = methodCallChainRootIsObjectCreation;
        this.methodCallChainInitializerShape = methodCallChainInitializerShape;
        this.castType = castType;
        this.arrayInitializer = arrayInitializer;
        this.objectCreationPrefix = objectCreationPrefix;
        this.typeNameWithoutArguments = typeNameWithoutArguments;
        this.brokenClassOrInterfaceType = brokenClassOrInterfaceType;
        this.binaryFansChainOperand = binaryFansChainOperand;
        this.lambdaParameters = lambdaParameters;
        this.lambdaParametersShouldBreak = lambdaParametersShouldBreak;
        this.lambdaExpression = lambdaExpression;
        this.trailingCommentLayout = new InitializerTrailingCommentLayout(
            this.comments,
            this.commentPlacement,
            this.rawSource
        );
        this.chainLayout = new InitializerMethodCallChainLayout(
            this.sourceShapePolicy,
            this.commentPlacement,
            this.options,
            this.layoutWidth,
            this.compact,
            this.compactJoin,
            this.expression,
            this.expressionWithoutOwnComment,
            this.methodCall,
            this.comments,
            this.initializerChain,
            this.canonicalFanChain,
            singleSelectorDotSplit,
            packedMethodCallChain,
            mixedFieldMethodCallChain,
            this.methodCallChainRootIsObjectCreation,
            methodCallChainIsSourceMultiline,
            this.methodCallChainInitializerShape,
            shouldPrintScopeAsDoc,
            methodCallPrefix,
            methodCallArgumentList,
            huggableBlockLambdaArguments,
            this.trailingCommentLayout,
            this::genericBrokenInitializer
        );
        this.objectCreationLayout = new InitializerObjectCreationLayout(
            context.sourceShapePolicy,
            this.options,
            this.layoutWidth,
            this.compact,
            this.expression,
            this.binaryFansChainOperand,
            this.binaryExpressionHasLineComments,
            this.binaryExpressionLinesWithComments,
            this.binaryExpressionLines,
            this.objectCreationPrefix,
            this.typeNameWithoutArguments,
            this.brokenClassOrInterfaceType,
            this.chainLayout::openerLineWidth
        );
        this.arrayLayout = new InitializerArrayLayout(
            context.sourceShapePolicy,
            this.layoutWidth,
            arrayCreationTypeBreaks,
            arrayCreationPrefix,
            this.arrayInitializer,
            compactArrayInitializerWithSourceSpacing,
            this.compactJoin
        );
        this.castLayout = new InitializerCastLayout(this.expression);
        this.conditionalLayout = new InitializerConditionalLayout(
            this.options,
            this.layoutWidth,
            context.compactSource::compact,
            brokenConditionalExpression,
            shouldBreakBeforeConditionalInitializer
        );
    }

    Doc variableWithStatementTerminator(VariableDeclarator variable, String declarationPrefix) {
        if (
            variable.getInitializer().orElse(null) instanceof MethodCallExpr methodCall
            && trailingCommentLayout.methodCallNeedsStatementTerminatorTail(variable, methodCall)
        ) {
            Doc variableInitializerTailComment = trailingCommentLayout.initializerTailLineComment(variable, methodCall)
                    .map(comments::comment)
                    .orElse(Doc.EMPTY);
            Doc declaration = chainLayout.variableWithMethodCallChainRanked(
                variable,
                variableName(variable),
                declarationPrefix + variable.getNameAsString(),
                methodCall,
                methodCallWithSemicolon.apply(methodCall)
            );
            return Doc.concat(declaration, trailingLineComment(variableInitializerTailComment));
        }
        if (
            variable.getInitializer().orElse(null) instanceof MethodCallExpr methodCall
            && !methodCallFinalTrailingLineComments(methodCall).isEmpty()
        ) {
            // A method-call initializer whose only re-homable comment is the final trailing line comment renders through
            // the stable width gate, not the under-measuring chain path (which omits the {@code NAME = } prefix and so
            // collapses or breaks after {@code =} off the comment-free fixpoint); the tail re-homes as a width-free suffix.
            if (methodCallInitializerReroutesToWidthGate(methodCall)) {
                Doc value = variableWithInitializer(variable, methodCall, declarationPrefix, Doc.text(";"));
                return Doc.concat(value, trailingLineComment(methodCallFinalTrailingLineComment(methodCall)));
            }
            // Whether the chain's final segment carries a trailing line comment
            // ({@code .thenMany(Flux.empty()); // note}) is a structural correctness property, not a source-shape one, so
            // this route claims it whenever such a comment is present, independent of source shape. The chain renderer
            // attaches the comment as a {@code lineSuffix} after the {@code ;} — without this route the comment falls to
            // the generic declarator-trailing path, which drops it onto its own line above a dangling {@code ;}.
            String flatName = declarationPrefix + variable.getNameAsString();
            // A comment-carrying, single-empty-tail object-creation chain whose constructor root
            // fits on the assignment line dot-breaks (constructor on the {@code = } line, tail selector on its own dotted
            // continuation line) rather than breaking after {@code =}. This must be decided BEFORE the chain doc is built:
            // the fan claims the trailing comment itself, and {@code methodCallWithSemicolon} would otherwise claim it first
            // (comment claims fire at doc-build time), leaving the fan's re-render empty and dropping the comment.
            Optional<Doc> dotBrokenTail = chainLayout.dotBrokenObjectRootTailChain(
                variableName(variable),
                methodCall
            );
            if (dotBrokenTail.isPresent()) {
                return dotBrokenTail.orElseThrow();
            }
            return chainLayout.variableWithMethodCallChainRanked(
                variable,
                variableName(variable),
                flatName,
                methodCall,
                methodCallWithSemicolon.apply(methodCall),
                new int[] { 1, 0 }
            );
        }
        if (
            variable.getInitializer().orElse(null) instanceof MethodCallExpr methodCall
            && chainLayout.initializerFansWidthDrivenTwoSelectorChain(methodCall)
            && trailingCommentLayout.preSemicolonInitializerComment(variable) == Doc.EMPTY
        ) {
            // A two-selector chain over a plain receiver fans one dotted selector per line, source-neutrally, when the
            // whole line overflows. Render it through the terminator-threaded chain entry so the fan-versus-flat verdict
            // counts the `;` folded into the last segment: a chain whose flat form fits at exactly the column but
            // overflows with its terminator fans on every pass rather than collapsing to an over-width flat line.
            String flatName = declarationPrefix + variable.getNameAsString();
            Optional<Doc> fannedChain = initializerChainWithTerminator.apply(
                methodCall,
                ";",
                chainLayout.firstLineWidth(variable, flatName + " = "),
                LayoutContext.root().withLeftEdgePrefix(flatName + " = ")
            );
            if (fannedChain.isPresent()) {
                Doc declaration = chainLayout.variableWithMethodCallChainRanked(
                    variable,
                    variableName(variable),
                    flatName,
                    methodCall,
                    fannedChain.orElseThrow()
                );
                return Doc.concat(
                    declaration,
                    trailingLineComment(trailingCommentLayout.trailingDeclaratorLineComment(variable))
                );
            }
        }
        Doc trailingLineComment = trailingCommentLayout.trailingDeclaratorLineComment(variable);
        Doc preSemicolonInitializerComment = trailingCommentLayout.preSemicolonInitializerComment(variable);
        // When the terminating `;` stays on the declaration line (no pre-`;` comment forcing it down), thread it into the
        // initializer so the renderer-measured conditional-group gate measures the flat form's fit *with* its `;` at the
        // true column — the same one column the `compact + ";"` projection counts. When a pre-`;` comment drops the `;`
        // onto its own line the group terminator is empty and the initializer stays on the imperative cascade, with the
        // HARD_LINE + `;` appended after the shape.
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

    private List<JavaCommentTrivia> methodCallFinalTrailingLineComments(MethodCallExpr expression) {
        return trailingCommentLayout.methodCallFinalTrailingLineComments(expression);
    }

    /** Renders the call's final trailing line comment(s) as one claimed Doc, or {@link Doc#EMPTY} when there are none. */
    private Doc methodCallFinalTrailingLineComment(MethodCallExpr expression) {
        return methodCallFinalTrailingLineComments(expression).stream()
                .map(comments::comment)
                .reduce((first, second) -> Doc.concat(first, Doc.text(" "), second))
                .orElse(Doc.EMPTY);
    }

    /**
     * Reports whether a method-call initializer's final trailing line comment can be re-homed as a width-free suffix so the
     * value renders through the width gate. True only for an unqualified call or an enclosed-receiver suffix call (the
     * {@code (a + b).toCharArray(); // NOSONAR} subset), with no leading own comment and a tail JavaParser did not bind
     * inside an argument subtree (which the value path already renders in place). A receiver-qualified call keeps its own
     * attach path, because the width gate would otherwise flip-flop its shape between attach and break-after-{@code =}.
     */
    private boolean methodCallInitializerReroutesToWidthGate(MethodCallExpr methodCall) {
        List<JavaCommentTrivia> finalTrailing = methodCallFinalTrailingLineComments(methodCall);
        if (finalTrailing.isEmpty()) {
            return false;
        }
        // Only an unqualified call or an enclosed-receiver suffix call has a source-neutral width gate. A call over a
        // simple-name, field-access, or method-call/object-creation receiver keeps its attach path, whose shape the width
        // gate would otherwise flip-flop between attach and break-after-`=`.
        if (methodCall.getScope().filter(scope -> !(scope instanceof EnclosedExpr)).isPresent()) {
            return false;
        }
        // A leading own comment on the call keeps the chain path that places it.
        if (methodCall.getComment().isPresent() && commentPlacement.trailingLineComment(methodCall).isEmpty()) {
            return false;
        }
        // Reject a tail JavaParser bound inside an argument subtree: the value path renders it there, so re-homing it as a
        // width-free suffix would duplicate it. The call's own trailing tail is never rendered by the value path.
        return finalTrailing.stream().noneMatch(trailing -> methodCall.getArguments().stream()
                .flatMap(argument -> commentPlacement.containedComments(argument).stream())
                .anyMatch(contained -> contained.comment() == trailing.comment()));
    }

    // Trailing comments are width-free: they can't be moved and must not affect layout decisions. Using lineSuffix
    // matches the same policy in MethodCallPrinter and keeps the comment measurement-neutral across all call sites.
    private Doc trailingLineComment(Doc comment) {
        if (comment == Doc.EMPTY) return Doc.EMPTY;
        return Doc.lineSuffix(Doc.concat(Doc.text(" "), comment));
    }

    /**
     * Chooses the initializer shape for a declarator whose terminator is emitted separately (a non-last multi-declarator
     * variable, whose {@code ,} the joining printer inserts). Passing {@link Doc#EMPTY} as the group terminator keeps this
     * path on the imperative cascade, where the terminator is appended after the chosen shape rather than folded into the
     * renderer-measured conditional-group gate.
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
     * {@link #canMeasureInitializerAtRenderedColumn}), it is folded into both arms of the renderer-measured
     * conditional-group gate so the renderer measures the flat form's fit <em>with</em> its terminator at the true column —
     * matching the {@code compact + ";"} projection width. Otherwise it is appended after the imperative shape.
     */
    Doc variableWithInitializer(
            VariableDeclarator variable,
            Expression initializer,
            String declarationPrefix,
            Doc groupTerminator
    ) {
        String flat = declarationPrefix + variable.getNameAsString() + " = " + compact.apply(initializer) + ";";
        String name = variableName(variable);
        // Checked FIRST — ahead of BOTH the source-shape preempt tier and
        // the renderer-measured conditional-group gate — because the oscillation it closes is exactly those
        // source-shape-gated routes disagreeing across passes for a fan-carrying initializer. The source-shape tier's chain
        // branches (the object-creation source-multiline branches) fire on a source-multiline pass and produce a shape the
        // gate's flat-source pass does not, so a fan-threshold chain routed through them oscillates. Claiming the
        // fan-carrying initializer here — with a Doc.bestFitting whose two
        // AST-derived arms (attach after `NAME = ` versus break after `=`) are ranked at the true column — makes the
        // break-after-`=` verdict a fixpoint by construction and pre-empts the source-shape routes for exactly these
        // chains. It self-gates to comment-free fan carriers (returns empty otherwise), so every comment-bearing or
        // non-fan initializer still reaches the preempt tier and conditional-group gate below unchanged.
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
        // whichever shape they return (the `concat(declaration, ";")` shape). Only when none fires does control reach the
        // renderer-measured conditional-group gate below.
        Optional<Doc> commentOrPreempt = variableInitializerCommentAndSourceShapeTier(
            variable,
            initializer,
            name,
            declarationPrefix,
            flat
        );
        if (commentOrPreempt.isPresent()) {
            return Doc.followedBy(commentOrPreempt.orElseThrow(), groupTerminator);
        }
        // Master over-width gate at the real rendered column: a single Doc.conditionalGroup lets the renderer decide
        // flat-versus-broken at the true running column. The flat arm is ordinary expression dispatch; the broken arm is
        // the construct-kind broken-shape dispatch, reached when the renderer judges the flat form too wide. Measuring at
        // the real column makes the decision a fixpoint by construction rather than by tuning a reconstructed baseline.
        if (canMeasureInitializerAtRenderedColumn(initializer, groupTerminator)) {
            Doc flatInitializer = Doc.followedBy(
                Doc.concat(Doc.text(name + " = "), expression.apply(initializer)),
                groupTerminator
            );
            Doc brokenInitializer = Doc.followedBy(
                variableInitializerBrokenOrFlat(variable, initializer, name, declarationPrefix, flat, true),
                groupTerminator
            );
            // For an OBJECT-CREATION value that force-breaks (e.g. `new X(new Y[]{...})` whose array arg breaks by rule),
            // the flat arm is really the opener-hug shape (`NAME = new X(` + broken body); a conditionalGroup treats it as
            // a dead alternative (it carries a forced break) and always falls to the break-after-`=` arm, which disagrees
            // with the preempt tier's over-width object-creation shape (opener-hug) and flips across passes when the
            // array-spacing width wobbles across the gate. Ranking by line count (bestFitting) keeps opener-hug when its
            // opener fits and still picks the true single-line flat when the value fits flat — a source-neutral fixpoint.
            // Scoped to object creation: other value kinds (an unbreakable over-width string literal) must keep the
            // conditionalGroup, whose fit-first choice never keeps a genuinely over-width single line.
            if (initializer instanceof ObjectCreationExpr) {
                return Doc.bestFitting(List.of(flatInitializer, brokenInitializer));
            }
            return Doc.conditionalGroup(List.of(flatInitializer, brokenInitializer));
        }
        return Doc.followedBy(
            variableInitializerBrokenOrFlat(variable, initializer, name, declarationPrefix, flat, false),
            groupTerminator
        );
    }

    /**
     * Runs the comment-around-{@code =}, source-leading-comment, and source-shape/self-breaking preempt tier, returning the
     * chosen shape when one branch owns the initializer or {@link Optional#empty()} to fall through to the
     * renderer-measured conditional-group gate.
     *
     * <p>This is the initializer's analogue of {@code ReturnExpressionPrinter}'s {@code preemptedReturnValue}: everything
     * whose broken shape is chosen by width-ranking or by a preserved source shape (a source-multiline object creation or
     * chain, a conditional whose broken ternary shape is ranked, a block-lambda-argument receiver break, an over-width
     * {@code blockStatement}-gated array/object/binary/complement break) is decided here, imperatively.
     * This tier owns those shapes, so the renderer-measured conditional-group gate only ever moves the flat-versus-broken
     * verdict for the residual, single-line-flat, comment-free initializers — never a ranked or source-preserved shape. The
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
            return Optional.of(blockCommentedInitializer(commentedName + " =", initializer));
        }
        Optional<Doc> postEqualsBlockComment = postEqualsBlockComment(variable, initializer);
        if (postEqualsBlockComment.isPresent()) {
            String commentText = commentText(postEqualsBlockComment.orElseThrow());
            return Optional.of(blockCommentedInitializer(name + " = " + commentText, initializer));
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
            return Optional.of(
                rankedBinaryInitializer(name, binaryExpr, binaryExpressionLinesWithComments.apply(binaryExpr), null)
            );
        }
        if (
            initializer instanceof ConditionalExpr conditionalExpr
            && conditionalLayout.conditionalInitializerLineOverflows(variable, declarationPrefix, conditionalExpr)
            && !initializerHasOwnBreak(initializer)
        ) {
            return Optional.of(
                conditionalLayout.conditionalInitializer(name, declarationPrefix + variable.getNameAsString(), conditionalExpr)
            );
        }
        if (
            initializer instanceof MethodCallExpr methodCall
            && chainLayout.methodCallHasBlockLambdaArgument(methodCall)
            && !chainLayout.methodCallHasLeadingCommentedBlockLambdaArgument(methodCall)
            && !chainLayout.methodCallHasContainedCommentObjectCreationBlockLambdaArgument(methodCall)
        ) {
            Optional<Doc> receiverBreakCall = chainLayout.variableWithReceiverBreakBeforeOverWidthHuggableBlockLambdaArguments(
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
            && (chainLayout.methodCallHasLeadingCommentedBlockLambdaArgument(methodCall)
                || chainLayout.methodCallHasContainedCommentObjectCreationBlockLambdaArgument(methodCall))
        ) {
            Optional<Doc> brokenCall = chainLayout.variableWithLeadingCommentedBlockLambdaMethodCall(
                variable,
                name,
                declarationPrefix + variable.getNameAsString(),
                methodCall
            );
            if (brokenCall.isPresent()) {
                return Optional.of(brokenCall.orElseThrow());
            }
        }
        // Route on the CANONICAL width (bare compact plus the clarity parens frmtr adds around nested binary operands),
        // not the source form: measuring the parens the renderer inserts either way makes this over-width gate invariant
        // to whether the source already carries them, so a binary initializer routes to the same tier and shape
        // regardless of source parens.
        if (layoutWidth.blockStatement(flat) + binaryParentheses.clarityParenWidth(initializer) > options.lineWidth()) {
            // The initializer line is already over width, so the enclosed suffix receiver must lead with a break; that
            // positional fact rides on the LayoutContext rather than a loose boolean argument.
            Optional<Doc> suffixedEnclosedInitializer =
                suffixedEnclosedExpression.apply(initializer, LayoutContext.root().withLeadingBreak(true));
            if (suffixedEnclosedInitializer.isPresent()) {
                return Optional.of(Doc.concat(Doc.text(name + " = "), suffixedEnclosedInitializer.orElseThrow()));
            }
            if (initializer instanceof ConditionalExpr conditionalExpr && !initializerHasOwnBreak(initializer)) {
                return Optional.of(
                    conditionalLayout.conditionalInitializer(name, declarationPrefix + variable.getNameAsString(), conditionalExpr)
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
                Optional<Doc> arrayCreation = arrayLayout.variableWithBrokenArrayCreation(name, arrayCreationExpr);
                if (arrayCreation.isPresent()) {
                    return Optional.of(arrayCreation.orElseThrow());
                }
            }
            if (initializer instanceof ObjectCreationExpr objectCreationExpr) {
                Optional<Doc> objectCreation = objectCreationLayout.variableWithBrokenObjectCreation(
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
                // A cast-division continuation keeps the whole binary flat under a broken `=` instead of the
                // operand-per-line shape, so it supplies its own broken arm to the same first-operand ranking.
                Doc brokenBody = shouldKeepCastDivisionContinuationFlat.test(binaryExpr)
                    ? expression.apply(binaryExpr)
                    : null;
                return Optional.of(rankedBinaryInitializer(
                    name,
                    binaryExpr,
                    binaryExpressionLines.apply(initializer, true),
                    brokenBody
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
     * Ranks the attach and break-after-{@code =} shapes of an initializer whose block comment already sits on the
     * assignment line, over a value Doc built once so neither arm re-offers the value's comments. {@code head} ends at
     * the seam — after {@code =} for a pre-{@code =} comment, after the comment for a post-{@code =} one — so both
     * comment routes share one ranking.
     */
    private Doc blockCommentedInitializer(String head, Expression initializer) {
        Doc value = expressionWithoutOwnComment.apply(initializer);
        Doc attached = Doc.concat(Doc.text(head + " "), value);
        Doc brokenAfterEquals = Doc.concat(
            Doc.text(head),
            Doc.indent(Doc.concat(Doc.HARD_LINE, value))
        );
        return Doc.bestFitting(List.of(attached, brokenAfterEquals));
    }

    /**
     * Ranks a broken binary initializer's first-operand-on-the-{@code =}-line shape against break-after-{@code =} on the
     * true rendered first line, over one shared operand-per-line Doc. A binary whose first operand is led by a comment
     * cannot ride the {@code =} line at all, so it is pinned to the broken shape. {@code brokenBody} overrides what the
     * broken arm renders when a construct keeps a different continuation shape there; {@code null} reuses {@code lines}.
     */
    private Doc rankedBinaryInitializer(String name, BinaryExpr binaryExpr, Doc lines, Doc brokenBody) {
        Doc brokenAfterEquals = Doc.concat(
            Doc.text(name + " ="),
            Doc.indent(Doc.concat(Doc.HARD_LINE, brokenBody == null ? lines : brokenBody))
        );
        if (conditionalLayout.binaryInitializerMustBreakAfterEquals(binaryExpr)) {
            return brokenAfterEquals;
        }
        Doc attached = Doc.concat(Doc.text(name + " = "), Doc.indent(lines));
        return Doc.bestFittingFirstLine(List.of(attached, brokenAfterEquals), new int[] { 1, 0 });
    }

    /**
     * Decides whether a comment-free initializer's flat-versus-broken verdict may be handed to the renderer-measured
     * {@link Doc#conditionalGroup}, or must stay on the imperative cascade to keep byte-identical output.
     *
     * <p>The conditional group measures its flat arm at the real running column and picks the broken arm when the flat
     * arm does not fit. That matches the imperative cascade's {@code variableInitializer(variable, compact + ";")} width
     * gate only when two conditions hold, so both are required here:
     *
     * <ul>
     *   <li><b>The flat arm is a single line.</b> The compact-projection gate measures the <em>compact</em> (single-line)
     *       projection, whereas the group measures the actual flat {@link Doc}. For a construct that renders its own
     *       internal breaks even when narrow — an own-break initializer (array/switch/anonymous-class), a source-multiline
     *       shape the policy preserves, or a cast wrapping either — the flat {@code Doc} carries a forced break, so the
     *       group would read it as never-fitting and always pick the broken arm, diverging from the compact-measured
     *       verdict. A comment-bearing initializer is excluded for a different reason (both arms would claim its comments),
     *       so the comment guard is folded in here too. These stay on the imperative cascade, which renders the initializer
     *       exactly once via {@code expression.apply} (its own break intact) and applies the per-branch gate.</li>
     *   <li><b>The trailing terminator is measurable inline.</b> The {@code ;} (or {@code ,}) that follows the initializer
     *       on the same line is one column the compact-projection gate counts (its {@code flat} ends with {@code ;}); the
     *       group only counts it when it is part of the measured arm. The statement-terminator caller threads
     *       {@code Doc.text(";")} here so both arms carry it; the multi-declarator comma path and the pre-{@code ;}-comment
     *       path pass {@link Doc#EMPTY} and stay imperative, where the terminator is appended after the shape.</li>
     * </ul>
     */
    private boolean canMeasureInitializerAtRenderedColumn(Expression initializer, Doc groupTerminator) {
        return groupTerminator != Doc.EMPTY
            && initializer.getComment().isEmpty()
            && !sourceShapePolicy.hasContainedComments(initializer)
            && !initializerHasOwnBreak(initializer)
            && !(initializer instanceof CastExpr);
    }

    /**
     * Makes the break-after-{@code =} verdict of a fan-carrying initializer
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
     *       arms render the chain through {@link InitializerMethodCallChainLayout#forcedMethodCallChain} → {@code chainFanOut} (root then one dotted
     *       selector per line, a pure AST function). The attached arm threads the {@code NAME = } leftEdgePrefix; the broken
     *       arm renders the chain at its own indented column with an empty prefix. Object-creation roots are excluded here
     *       (their packed/broken-constructor branches own their shape).</li>
     *   <li><b>Object creation whose constructor argument is a fan-threshold chain</b>
     *       ({@code = new ArrayList<>(entry.entity().entries().size())}). The constructor body already renders
     *       source-neutrally (its argument chain fans by the AST rule regardless of source), so both arms use ordinary
     *       {@code expression.apply}; only the enclosing attach verdict needs stabilizing.</li>
     * </ul>
     * Comment-bearing and block-lambda-bearing values are excluded (they carry their own comment/hug shape and stay on the
     * imperative cascade, which renders the value exactly once so no comment's recorded owner can disagree with the
     * width-picked arm).
     */
    private Optional<Doc> variableInitializerFanBestFitting(
            VariableDeclarator variable,
            Expression initializer,
            String name,
            String declarationPrefix,
            String flat,
            Doc groupTerminator
    ) {
        if (sourceShapePolicy.hasContainedComments(initializer) || initializer.getComment().isPresent()) {
            return Optional.empty();
        }
        String flatName = declarationPrefix + variable.getNameAsString();
        if (initializer instanceof MethodCallExpr methodCall
                && !methodCallChainRootIsObjectCreation.test(methodCall)) {
            // Direct fan-threshold chain: render the fan through the SOURCE-NEUTRAL canonicalFanChain (chainFanOut) — not
            // the imperative forcedMethodCallChain, which falls through to source-shaped rendering on a
            // source-multiline pass and could flip the first-selector attach across passes. chainFanOut renders the
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
        // Binary/logical/string-concat initializer whose operand is a fan-threshold chain (for example
        // {@code long newOffset = log.segments().activeSegment().baseOffset() + 1} or
        // {@code String appId = getClass().getSimpleName().toLowerCase(...) + testId}). The dispatched flat rendering
        // ({@code expression.apply}) already fans that operand by the canonical-fan rule (its inner {@code chainFanOut} is a
        // pure AST function, operator kept inline), so — exactly like the object-creation-argument arm above — both arms
        // are AST-pure and the only remaining choice is attach-after-{@code NAME = } versus break-after-{@code =}, ranked by
        // {@code Doc.bestFitting} at the true column. Without this arm the binary initializer falls to the renderer-measured
        // conditionalGroup gate, whose flat arm carries the operand's hard break and can never be chosen, so it can only
        // break after {@code =}; ranking both AST-pure shapes here lets the attach shape win when it fits. Comment-bearing
        // binaries are excluded above; non-fan binaries return empty here and keep the conditional-group gate.
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
        // ({@code expression.apply}) carries a hard break the renderer-measured conditionalGroup flat arm can never absorb.
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
     * Reports whether {@code call} is a method call whose receiver descends through a
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
     * Reports whether an object-creation constructor argument carries a fan-threshold
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
     * Dispatches the construct-specific broken initializer shape, reached from the renderer-measured conditional-group
     * gate's broken arm and from the comment-bearing imperative path.
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
        return renderBrokenOrFlat(
            classifyBrokenOrFlat(initializer, overWidth),
            variable,
            initializer,
            name,
            declarationPrefix
        );
    }

    /**
     * Selects the {@link InitializerLayoutArm} for the width-driven broken-or-flat cascade from AST shape and rendered
     * width alone, in a fixed order of conditions. Every predicate here is pure
     * (AST inspection plus width probes), so the classification is a fixpoint: the same input always maps to the same arm.
     * The within-arm {@code Optional} fall-throughs of {@link InitializerLayoutArm#METHOD_CALL_OWN_BREAK_CHAIN},
     * {@link InitializerLayoutArm#METHOD_CALL_BROKEN}, and {@link InitializerLayoutArm#LAMBDA} are resolved by
     * {@link #renderBrokenOrFlat}, not here.
     */
    private InitializerLayoutArm classifyBrokenOrFlat(Expression initializer, boolean overWidth) {
        if (!overWidth) {
            return InitializerLayoutArm.FLAT;
        }
        return switch (initializer) {
            case MethodCallExpr methodCall when initializerHasOwnBreak(methodCall) ->
                InitializerLayoutArm.METHOD_CALL_OWN_BREAK_CHAIN;
            case MethodCallExpr methodCall -> InitializerLayoutArm.METHOD_CALL_BROKEN;
            case CastExpr cast when cast.getExpression() instanceof MethodCallExpr && !initializerHasOwnBreak(cast) ->
                InitializerLayoutArm.CAST_METHOD_CALL_BREAK;
            case CastExpr cast when castLayout.castTypeNeedsBreak(cast.getType()) && !initializerHasOwnBreak(cast) ->
                InitializerLayoutArm.CAST_TYPE_BREAK;
            case ConditionalExpr conditional when !initializerHasOwnBreak(conditional) ->
                InitializerLayoutArm.CONDITIONAL;
            case LambdaExpr lambda when !initializerHasOwnBreak(lambda) -> InitializerLayoutArm.LAMBDA;
            case StringLiteralExpr string -> InitializerLayoutArm.STRING_LITERAL_BREAK;
            case ArrayInitializerExpr array -> InitializerLayoutArm.ARRAY_INITIALIZER_BREAK;
            // A text block is excluded from the generic break, so it stays flat rather than falling into GENERIC_BROKEN
            // like other non-string non-own-break values.
            case TextBlockLiteralExpr textBlock -> InitializerLayoutArm.FLAT;
            default -> initializerHasOwnBreak(initializer)
                ? InitializerLayoutArm.FLAT
                : InitializerLayoutArm.GENERIC_BROKEN;
        };
    }

    /**
     * Dispatches a classified {@link InitializerLayoutArm} to its shape emitter. The three cascade arms preserve their
     * ordered {@code Optional<Doc>} fall-through: {@link InitializerLayoutArm#METHOD_CALL_OWN_BREAK_CHAIN} falls back to the
     * flat shape, while {@link InitializerLayoutArm#METHOD_CALL_BROKEN} and {@link InitializerLayoutArm#LAMBDA} fall back to
     * the generic break.
     */
    private Doc renderBrokenOrFlat(
            InitializerLayoutArm arm,
            VariableDeclarator variable,
            Expression initializer,
            String name,
            String declarationPrefix
    ) {
        switch (arm) {
            case FLAT:
                return Doc.concat(Doc.text(name + " = "), expression.apply(initializer));
            case METHOD_CALL_OWN_BREAK_CHAIN: {
                String flatName = declarationPrefix + variable.getNameAsString();
                MethodCallExpr methodCall = (MethodCallExpr) initializer;
                return chainLayout.forcedMethodCallChain(variable, methodCall, flatName)
                    .map(chain -> chainLayout.variableWithMethodCallChainRanked(variable, name, flatName, methodCall, chain, new int[] { 1, 0 }))
                    .orElseGet(() -> Doc.concat(Doc.text(name + " = "), expression.apply(initializer)));
            }
            case METHOD_CALL_BROKEN:
                return chainLayout.methodCallBrokenInitializer(variable, name, declarationPrefix, (MethodCallExpr) initializer);
            case CAST_METHOD_CALL_BREAK: {
                CastExpr castExpr = (CastExpr) initializer;
                return Doc.concat(
                    Doc.text(name + " = "),
                    castType.apply(castExpr.getType()),
                    Doc.text(" "),
                    brokenMethodCall.apply((MethodCallExpr) castExpr.getExpression())
                );
            }
            case CAST_TYPE_BREAK:
                return castLayout.variableWithCastTypeBreak(name, (CastExpr) initializer);
            case CONDITIONAL:
                return conditionalLayout.conditionalInitializer(
                    name,
                    declarationPrefix + variable.getNameAsString(),
                    (ConditionalExpr) initializer
                );
            case LAMBDA:
                return lambdaBrokenInitializer(variable, name, declarationPrefix, (LambdaExpr) initializer);
            case STRING_LITERAL_BREAK:
                return attachOrBreakAfterEquals(name, expression.apply(initializer));
            case ARRAY_INITIALIZER_BREAK:
                return Doc.concat(
                    Doc.text(name + " = "),
                    arrayInitializer.apply((ArrayInitializerExpr) initializer, true)
                );
            case GENERIC_BROKEN:
                return genericBrokenInitializer(variable, initializer, name);
            default:
                throw new IllegalStateException("Unhandled initializer layout arm: " + arm);
        }
    }


    /**
     * The {@link InitializerLayoutArm#LAMBDA} arm: the ordered broken-lambda sub-cascade (expression body, then block
     * body) for an over-width, no-own-break lambda initializer. When both decline, the lambda falls back to the generic
     * break.
     */
    private Doc lambdaBrokenInitializer(
            VariableDeclarator variable,
            String name,
            String declarationPrefix,
            LambdaExpr lambdaExpr
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
        Optional<Doc> blockLambdaInitializer = variableWithBlockLambdaInitializer(name, lambdaExpr);
        if (blockLambdaInitializer.isPresent()) {
            return blockLambdaInitializer.orElseThrow();
        }
        return genericBrokenInitializer(variable, lambdaExpr, name);
    }

    /**
     * The {@link InitializerLayoutArm#GENERIC_BROKEN} arm (and the fall-through target of the method-call and lambda
     * sub-cascades): ranks attach against break-after-{@code =}, the same {@link #attachOrBreakAfterEquals} template
     * CAST/CONDITIONAL/STRING_LITERAL use, over the shared value's own broken shape.
     */
    private Doc genericBrokenInitializer(VariableDeclarator variable, Expression initializer, String name) {
        return attachOrBreakAfterEquals(name, brokenInitializer(variable, initializer));
    }

    /**
     * Ranks attach ({@code NAME = value}) against break-after-{@code =} at the true rendered first line, over one
     * shared value Doc built once so neither arm re-offers the value's comments. Matches the template
     * {@code InitializerCastLayout.variableWithCastTypeBreak} and {@code InitializerConditionalLayout.conditionalInitializer}
     * already use: break-after-{@code =}'s own first line ({@code NAME =}) is always short, so it only wins when attach
     * genuinely does not fit.
     */
    private Doc attachOrBreakAfterEquals(String name, Doc value) {
        Doc attach = Doc.concat(Doc.text(name + " = "), value);
        Doc breakAfterEquals = Doc.concat(Doc.text(name + " ="), Doc.indent(Doc.concat(Doc.HARD_LINE, value)));
        return Doc.bestFittingFirstLine(List.of(attach, breakAfterEquals), new int[] {1, 0});
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
































    /**
     * Decides the arrow seam of {@code NAME = params -> chain} by ranking both statement shapes on true rendered
     * first line ({@link Doc#bestFittingFirstLine}), rather than a string first-line estimate.
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
        String lambdaPrefix = parameters + " ->";
        // The chain here is an expression-lambda body (NAME = params -> chain), a distinct position from the direct
        // initializer chain: its same-line prefix is NAME = params -> , not NAME = . Threading a non-empty leftEdgePrefix
        // here would activate the object-creation dot-split for lambda-body chains too, which is out of the
        // initializer chain's scope (mirroring how the return chain keeps its dot-split scoped to the direct return chain).
        // Pass root(); the firstLineWidth probe still folds the lambda prefix in, so this stays byte-identical.
        Doc body = initializerChain
                .apply(
                    methodCall,
                    chainLayout.firstLineWidth(variable, flatName + " = " + lambdaPrefix + " "),
                    LayoutContext.root()
                )
                .orElseGet(() -> expression.apply(methodCall));
        Doc attached = Doc.concat(Doc.text(name + " = " + lambdaPrefix + " "), body);
        Doc brokenAfterArrow = Doc.concat(
            Doc.text(name + " = " + lambdaPrefix),
            Doc.indent(Doc.concat(Doc.HARD_LINE, body))
        );
        return Optional.of(Doc.bestFittingFirstLine(List.of(attached, brokenAfterArrow)));
    }



    /**
     * Keeps a block-lambda initializer on the assignment line while its opener fits, ranking that attach against
     * break-after-{@code =} on the true rendered first line over one shared lambda Doc. The opener is whichever header
     * the lambda renderer actually emits ({@code params -> {} or a broken parameter list), so no header string is
     * reconstructed here.
     */
    private Optional<Doc> variableWithBlockLambdaInitializer(String name, LambdaExpr lambdaExpr) {
        if (!lambdaExpr.getBody().isBlockStmt()) {
            return Optional.empty();
        }
        Doc lambda = lambdaExpression.apply(lambdaExpr);
        Doc attached = Doc.concat(Doc.text(name + " = "), lambda);
        Doc brokenAfterEquals = Doc.concat(
            Doc.text(name + " ="),
            Doc.indent(Doc.concat(Doc.HARD_LINE, lambda))
        );
        return Optional.of(Doc.bestFittingFirstLine(List.of(attached, brokenAfterEquals), new int[] { 1, 0 }));
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
        // A comment-bearing binary renders its own first-operand leading comment through the comment-aware binary path,
        // so hoisting the initializer's own comment here too would print it twice (and the duplicate grows every pass).
        boolean binaryRendersOwnLeadingComment =
            initializer instanceof BinaryExpr binaryExpr && binaryExpressionHasLineComments.test(binaryExpr);
        if (!binaryRendersOwnLeadingComment) {
            initializer.getComment()
                    .filter(LineComment.class::isInstance)
                    .filter(comment -> CommentIndex.startsBefore(comment, initializer))
                    .ifPresent(leadingComments::add);
        }
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
        return switch (initializer) {
            case ArrayCreationExpr arrayCreation -> arrayLayout.arrayCreationHasOwnBreak(arrayCreation);
            case ArrayAccessExpr ignored -> true;
            case ObjectCreationExpr objectCreation -> objectCreation.getAnonymousClassBody().isPresent();
            case SwitchExpr ignored -> true;
            case MethodCallExpr methodCall -> methodCallScopeHasOwnBreak(methodCall);
            default -> false;
        };
    }

    /**
     * A method call owns its break when its receiver does: an array-access receiver always breaks, and an
     * array-creation receiver breaks when {@link InitializerArrayLayout#arrayCreationHasOwnBreak} reports it does.
     */
    private boolean methodCallScopeHasOwnBreak(MethodCallExpr methodCall) {
        if (methodCall.getScope().filter(ArrayAccessExpr.class::isInstance).isPresent()) {
            return true;
        }
        return methodCall.getScope()
                .filter(ArrayCreationExpr.class::isInstance)
                .map(ArrayCreationExpr.class::cast)
                .map(arrayLayout::arrayCreationHasOwnBreak)
                .orElse(false);
    }

    private String commentText(Doc comment) {
        if (comment instanceof Doc.Text text) {
            return text.value();
        }
        return "";
    }
}
