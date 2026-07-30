package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Hugs a single block-body lambda argument onto its enclosing call or constructor opener — the block-body counterpart to
 * {@link ExpressionLambdaArgumentLayout}.
 *
 * <p>This helper owns the eligibility rules and the rendering for the {@code call(leading, param -> {}⏎ … ⏎{@code })}
 * shape: a lambda whose body is a brace block sitting at the START or END of the argument list keeps the call's ordinary
 * argument prefix / suffix while only the block breaks multi-line. It answers the shared question four ways — the fully
 * rendered hug gated on a fixed-width probe ({@link #huggableBlockLambdaArguments}), the same hug left ungated for a
 * caller that ranks it against its own fallback at the true rendered column ({@link #eligibleBlockLambdaHug}), the
 * method-chain variant whose block renders through the chain block renderer ({@link #huggableMethodChainBlockLambdaArguments}),
 * and the bare first-line probe callers width-check before committing ({@link #huggableBlockLambdaFirstLine}) — all off
 * one eligibility gate ({@code huggableBlockLambdaArgument}) so the probe and the render never disagree. It also owns
 * the suppression rules that keep the hug from producing a worse
 * shape: a middle-position lambda or a second lambda argument, source-multiline lambda parameters (delegated to
 * {@link SourceMultilineLambdaCallLayout}), and a non-lambda argument whose object-creation chain root is heavy
 * ({@link ArgumentHeaviness}) or too wide to stay compact ({@link ObjectCreationLayoutPolicy}).
 *
 * <p>The boundary exists because a block lambda is reached both by ordinary expression dispatch and as an argument that
 * reshapes method-call, object-creation, field-declaration, and chain layout, so those callers need one authority for
 * "can this block lambda hug, and what does its opener line look like?" without carrying the scan inline. The helper
 * claims no ownership of the lambda header text, the comment-drop gate, or the non-hug lambda body: it renders the
 * parameter header through the injected {@link LambdaParameterHeaderLayout}, consults the caller's injected
 * {@code hugWouldDropComment} gate for comment safety, and defers the full lambda decision tree to the injected
 * {@code lambdaExpression} renderer, so a body that cannot hug still routes through the owner of that shape.
 */
final class BlockLambdaArgumentLayout {

    private final LambdaParameterHeaderLayout lambdaParameterHeaders;

    private final FormatterOptions options;

    private final ObjectCreationLayoutPolicy objectCreationLayoutPolicy;

    private final JavaFormatRule<BlockStmt> blockRenderer;

    private final JavaFormatRule<BlockStmt> methodChainLambdaBlockRenderer;

    private final Function<LambdaExpr, Doc> lambdaExpression;

    private final Predicate<LambdaExpr> hugWouldDropComment;

    private final Function<Node, String> compact;

    private final Function<List<? extends Node>, String> compactJoin;

    private final ToIntFunction<String> currentIndentedWidth;

    private final ToIntFunction<String> blockStatementWidth;

    private final ArgumentHeaviness argumentHeaviness = new ArgumentHeaviness();

    BlockLambdaArgumentLayout(
            LambdaParameterHeaderLayout lambdaParameterHeaders,
            FormatterOptions options,
            ObjectCreationLayoutPolicy objectCreationLayoutPolicy,
            JavaFormatRule<BlockStmt> blockRenderer,
            JavaFormatRule<BlockStmt> methodChainLambdaBlockRenderer,
            Function<LambdaExpr, Doc> lambdaExpression,
            Predicate<LambdaExpr> hugWouldDropComment,
            Function<Node, String> compact,
            Function<List<? extends Node>, String> compactJoin,
            ToIntFunction<String> currentIndentedWidth,
            ToIntFunction<String> blockStatementWidth
    ) {
        this.lambdaParameterHeaders = lambdaParameterHeaders;
        this.options = options;
        this.objectCreationLayoutPolicy = objectCreationLayoutPolicy;
        this.blockRenderer = blockRenderer;
        this.methodChainLambdaBlockRenderer = methodChainLambdaBlockRenderer;
        this.lambdaExpression = lambdaExpression;
        this.hugWouldDropComment = hugWouldDropComment;
        this.compact = compact;
        this.compactJoin = compactJoin;
        this.currentIndentedWidth = currentIndentedWidth;
        this.blockStatementWidth = blockStatementWidth;
    }

    /**
     * Hugs a single block-body lambda argument when it is at the start or end of the argument list.
     *
     * <p>Those edge positions let the call keep the ordinary argument prefix or suffix without hiding another argument
     * after the lambda body. A block lambda in the middle would make the remaining arguments read like part of the
     * lambda block, so the normal call formatter handles that case.
     */
    Optional<Doc> huggableBlockLambdaArguments(String prefix, NodeList<Expression> arguments) {
        return huggableBlockLambdaArguments(
            prefix,
            arguments,
            blockStatementWidth,
            lambdaExpression,
            blockRenderer
        );
    }

    Optional<Doc> huggableMethodChainBlockLambdaArguments(String prefix, NodeList<Expression> arguments) {
        return huggableBlockLambdaArguments(
            prefix,
            arguments,
            blockStatementWidth,
            this::methodChainLambdaExpression,
            methodChainLambdaBlockRenderer
        );
    }

    /**
     * Builds the EXPLODED shape (the lambda header on its own indented line, opener and closer each on their own line)
     * for a block-lambda argument that is also hug-eligible, with the body routed through the chain-specific block
     * renderer so its width probes measure the fan's true continuation depth instead of the generic block's shallow
     * one. Empty for every reason the hug itself declines, plus a leading/trailing argument alongside the lambda — the
     * rarer combined shape this caller does not build, left on the generic argument-list rendering instead.
     */
    Optional<Doc> explodedMethodChainBlockLambdaArgument(String prefix, NodeList<Expression> arguments) {
        Optional<HuggableBlockLambdaArgument> huggable = huggableBlockLambdaArgument(prefix, arguments);
        if (huggable.isEmpty()) {
            return Optional.empty();
        }
        HuggableBlockLambdaArgument argument = huggable.orElseThrow();
        if (!argument.leadingArguments().isEmpty() || argument.lambdaIndex() < arguments.size() - 1) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
            Doc.text(prefix + "("),
            Doc.indent(Doc.concat(Doc.HARD_LINE, methodChainLambdaExpression(argument.lambdaExpr()))),
            Doc.HARD_LINE,
            Doc.text(")")
        ));
    }

    /**
     * Hugs a block-lambda argument after the caller supplies the width check for the first rendered line.
     *
     * <p>Statement, method-call, and object-creation contexts use normal block-statement width. Field declarations include
     * the declaration prefix before the call, so they provide their own width probe while sharing the same eligibility and
     * rendering rules.
     */
    Optional<Doc> huggableBlockLambdaArguments(
            String prefix,
            NodeList<Expression> arguments,
            ToIntFunction<String> firstLineWidth
    ) {
        return huggableBlockLambdaArguments(prefix, arguments, firstLineWidth, lambdaExpression, blockRenderer);
    }

    /**
     * Structural-eligibility-only hug candidate: same rules as {@link #huggableBlockLambdaArguments(String, NodeList)}
     * but skips the fixed-width first-line check, so a caller can rank this Doc against its own fallback shape (for
     * example via {@code Doc.bestFittingFirstLine}) at the renderer's true column instead of a build-time probe.
     */
    Optional<Doc> eligibleBlockLambdaHug(String prefix, NodeList<Expression> arguments) {
        Optional<HuggableBlockLambdaArgument> huggable = huggableBlockLambdaArgument(prefix, arguments);
        if (huggable.isEmpty()) {
            return Optional.empty();
        }
        HuggableBlockLambdaArgument argument = huggable.orElseThrow();
        Optional<Doc> sourceMultilineParameters = sourceMultilineParameters(prefix, arguments, argument, blockRenderer);
        if (sourceMultilineParameters.isPresent()) {
            return sourceMultilineParameters;
        }
        return Optional.of(buildHug(prefix, arguments, argument, lambdaExpression));
    }

    private Optional<Doc> huggableBlockLambdaArguments(
            String prefix,
            NodeList<Expression> arguments,
            ToIntFunction<String> firstLineWidth,
            Function<LambdaExpr, Doc> lambdaRenderer,
            JavaFormatRule<BlockStmt> lambdaBlockRenderer
    ) {
        Optional<HuggableBlockLambdaArgument> huggable = huggableBlockLambdaArgument(prefix, arguments);
        if (huggable.isEmpty()) {
            return Optional.empty();
        }
        HuggableBlockLambdaArgument argument = huggable.orElseThrow();
        Optional<Doc> sourceMultilineParameters =
            sourceMultilineParameters(prefix, arguments, argument, lambdaBlockRenderer);
        if (sourceMultilineParameters.isPresent()) {
            return sourceMultilineParameters;
        }
        if (firstLineWidth.applyAsInt(argument.firstLine()) > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(buildHug(prefix, arguments, argument, lambdaRenderer));
    }

    private Optional<Doc> sourceMultilineParameters(
            String prefix,
            NodeList<Expression> arguments,
            HuggableBlockLambdaArgument argument,
            JavaFormatRule<BlockStmt> lambdaBlockRenderer
    ) {
        return SourceMultilineLambdaCallLayout.blockLambdaArgumentWithSourceMultilineParameters(
            prefix,
            arguments,
            argument.lambdaIndex(),
            argument.lambdaExpr(),
            argument.leadingArguments(),
            compactJoin,
            lambdaParameterHeaders,
            lambdaBlockRenderer
        );
    }

    private Doc buildHug(
            String prefix,
            NodeList<Expression> arguments,
            HuggableBlockLambdaArgument argument,
            Function<LambdaExpr, Doc> lambdaRenderer
    ) {
        String trailingArguments = compactJoin.apply(arguments.subList(argument.lambdaIndex() + 1, arguments.size()));
        return Doc.concat(
            Doc.text(prefix + "(" + (argument.leadingArguments().isEmpty() ? "" : argument.leadingArguments() + ", ")),
            lambdaRenderer.apply(argument.lambdaExpr()),
            Doc.text((trailingArguments.isEmpty() ? "" : ", " + trailingArguments) + ")")
        );
    }

    private Doc methodChainLambdaExpression(LambdaExpr expression) {
        String parameters = lambdaParameterHeaders.parameters(expression);
        if (expression.getBody().isBlockStmt()) {
            return Doc.concat(
                lambdaParameterHeaders.forHeader(expression, parameters),
                Doc.text(" -> "),
                methodChainLambdaBlockRenderer.format(expression.getBody().asBlockStmt(), LayoutContext.root())
            );
        }
        return lambdaExpression.apply(expression);
    }

    /**
     * Returns the exact first line used by the huggable block-lambda argument layout before width is considered.
     */
    Optional<String> huggableBlockLambdaFirstLine(String prefix, NodeList<Expression> arguments) {
        return huggableBlockLambdaArgument(prefix, arguments).map(HuggableBlockLambdaArgument::firstLine);
    }

    /**
     * Applies the shared block-lambda argument eligibility rules for both rendering and external first-line probing.
     */
    private Optional<HuggableBlockLambdaArgument> huggableBlockLambdaArgument(
            String prefix,
            NodeList<Expression> arguments
    ) {
        int lambdaIndex = SourceMultilineLambdaCallLayout.blockLambdaArgumentIndex(arguments);
        if (lambdaIndex < 0 || (lambdaIndex > 0 && lambdaIndex < arguments.size() - 1)) {
            return Optional.empty();
        }
        if (SourceMultilineLambdaCallLayout.hasOtherLambdaArgument(arguments, lambdaIndex)) {
            return Optional.empty();
        }
        if (nonLambdaArgumentHasConstructorChainRootNeedingBreak(arguments, lambdaIndex)) {
            return Optional.empty();
        }
        LambdaExpr lambdaExpr = (LambdaExpr) arguments.get(lambdaIndex);
        if (hugWouldDropComment.test(lambdaExpr)) {
            return Optional.empty();
        }
        String parameters = lambdaParameterHeaders.parameters(lambdaExpr);
        if (lambdaParameterHeaders.shouldBreak(lambdaExpr, parameters)) {
            return Optional.empty();
        }
        String leadingArguments = compactJoin.apply(arguments.subList(0, lambdaIndex));
        String firstLine = prefix
            + "("
            + (leadingArguments.isEmpty() ? "" : leadingArguments + ", ")
            + parameters
            + " -> {";
        return Optional.of(new HuggableBlockLambdaArgument(lambdaIndex, lambdaExpr, leadingArguments, firstLine));
    }

    private boolean nonLambdaArgumentHasConstructorChainRootNeedingBreak(
            NodeList<Expression> arguments,
            int lambdaIndex
    ) {
        for (int index = 0; index < arguments.size(); index++) {
            if (index != lambdaIndex && expressionHasConstructorChainRootNeedingBreak(arguments.get(index))) {
                return true;
            }
        }
        return false;
    }

    private boolean expressionHasConstructorChainRootNeedingBreak(Expression expression) {
        return expression.findAll(MethodCallExpr.class)
                .stream()
                .anyMatch(this::methodCallRootConstructorNeedsBreak);
    }

    private boolean methodCallRootConstructorNeedsBreak(MethodCallExpr expression) {
        List<MethodCallExpr> calls = new ArrayList<>();
        Expression root = methodCallChainRoot(expression, calls);
        if (calls.isEmpty() || !(root instanceof ObjectCreationExpr objectCreation)) {
            return false;
        }
        // A "heavy" root constructor breaks its argument list even when it fits the width (see ArgumentHeaviness), so a
        // trailing lambda must not hug it flat onto the opener; suppress the hug so the enclosing call explodes its
        // arguments and the constructor root breaks on its own line.
        if (argumentHeaviness.isHeavy(objectCreation.getArguments(), true)) {
            return true;
        }
        int compactRootWidth = currentIndentedWidth.applyAsInt(compact.apply(objectCreation));
        boolean compactRootCanStay = objectCreationLayoutPolicy.canKeepCompactChainRoot(
            objectCreation,
            compactRootWidth,
            options.lineWidth()
        );
        return !compactRootCanStay;
    }

    private Expression methodCallChainRoot(MethodCallExpr expression, List<MethodCallExpr> calls) {
        if (expression.getScope().orElse(null) instanceof MethodCallExpr methodCallExpr) {
            Expression root = methodCallChainRoot(methodCallExpr, calls);
            calls.add(expression);
            return root;
        }
        if (expression.getScope().isEmpty()) {
            return expression;
        }
        calls.add(expression);
        return expression.getScope().orElseThrow();
    }

    private record HuggableBlockLambdaArgument(
        int lambdaIndex,
        LambdaExpr lambdaExpr,
        String leadingArguments,
        String firstLine
    ) {}
}
