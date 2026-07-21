package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Hugs a single inner call/creation argument onto a wrapping call's opener
 * ({@code when(sharePartition.acquire(} ⏎ arguments ⏎ {@code ))}, {@code List.of(new TopicData<>(} ⏎ …) instead of
 * stranding {@code inner(} on its own indented line. Extracted from {@link MethodCallPrinter} so the hug is a
 * self-contained, named {@link BreakRule} rather than an imperative branch in that already-large printer.
 *
 * <p>The caller ({@code MethodCallPrinter}'s single-argument dispatch) still decides whether this shape is reached; this
 * helper owns only the applicability test and the renderer-ranked broken shape, and leans back on the caller's
 * prefix / argument-list / broken-call renderers (method-call or object-creation) so the hugged arguments render exactly
 * as the ordinary call path would.
 */
final class SingleCallArgumentOpenerHugLayout {

    /** A wrapping call is "short" when its selector name is under this many symbols (brackets and dots excluded). */
    private static final int SHORT_CALL_NAME_LIMIT = 8;

    private final FormatterOptions options;

    private final SourceShapePolicy sourceShapePolicy;

    private final CompactSourceText compactSource;

    private final LayoutWidth layoutWidth;

    private final Function<MethodCallExpr, String> methodCallPrefix;

    private final BiFunction<NodeList<Expression>, Doc, Doc> methodCallArgumentList;

    private final Function<MethodCallExpr, Doc> brokenMethodCall;

    private final Function<ObjectCreationExpr, String> objectCreationPrefix;

    private final Function<ObjectCreationExpr, Doc> brokenObjectCreation;

    private final BreakRuleRegistry<Request> rules = BreakRuleRegistry.of(List.of(
        BreakRule.of("single-call-argument-opener-hug", this::applies, this::layout)
    ));

    SingleCallArgumentOpenerHugLayout(
            FormatterOptions options,
            SourceShapePolicy sourceShapePolicy,
            CompactSourceText compactSource,
            LayoutWidth layoutWidth,
            Function<MethodCallExpr, String> methodCallPrefix,
            BiFunction<NodeList<Expression>, Doc, Doc> methodCallArgumentList,
            Function<MethodCallExpr, Doc> brokenMethodCall,
            Function<ObjectCreationExpr, String> objectCreationPrefix,
            Function<ObjectCreationExpr, Doc> brokenObjectCreation
    ) {
        this.options = options;
        this.sourceShapePolicy = sourceShapePolicy;
        this.compactSource = compactSource;
        this.layoutWidth = layoutWidth;
        this.methodCallPrefix = methodCallPrefix;
        this.methodCallArgumentList = methodCallArgumentList;
        this.brokenMethodCall = brokenMethodCall;
        this.objectCreationPrefix = objectCreationPrefix;
        this.brokenObjectCreation = brokenObjectCreation;
    }

    /** Resolves the one hug rule against the candidate; empty when it does not apply, so the caller keeps its dispatch. */
    Optional<Doc> hug(String prefix, MethodCallExpr expression) {
        Request request = new Request(prefix, expression);
        return rules.select(request).map(rule -> rule.layout(request));
    }

    /** The candidate handed to the hug rule: the wrapping call's opener prefix plus the call itself. */
    private record Request(String prefix, MethodCallExpr expression) {}

    /** The single call/creation argument to hug: its opener text ({@code inner} / {@code new Type<>}) and own arguments. */
    private record InnerCall(Expression node, String opener, NodeList<Expression> arguments, Doc broken) {}

    /**
     * The inner call/creation this hug wraps, when the sole argument is one with its own arguments. Both a nested method
     * call ({@code inner(a, b)}) and an object creation ({@code new Type<>(a, b)}) read as the single entity the hug keeps
     * on the opener line; each carries its type-appropriate opener text and broken renderer so the exploded fallback
     * matches the ordinary call path.
     */
    private Optional<InnerCall> innerCall(Expression argument) {
        if (argument instanceof MethodCallExpr call && !call.getArguments().isEmpty()) {
            return Optional.of(
                new InnerCall(call, methodCallPrefix.apply(call), call.getArguments(), brokenMethodCall.apply(call))
            );
        }
        if (
            argument instanceof ObjectCreationExpr creation
            && !creation.getArguments().isEmpty()
            // An anonymous class body ({@code new Probe(...) { ... }}) is not part of the compact opener text or the broken
            // renderer this hug uses, so hugging would strand — and drop — the body. Leave it to the ordinary path.
            && creation.getAnonymousClassBody().isEmpty()
        ) {
            return Optional.of(new InnerCall(
                creation,
                objectCreationPrefix.apply(creation),
                creation.getArguments(),
                brokenObjectCreation.apply(creation)
            ));
        }
        return Optional.empty();
    }

    /**
     * Whether the opener-hug owns this call: a single inner call/creation argument with its own arguments, no comments on
     * either the wrapping call or the inner argument, and no inner lambda argument (those keep their own layouts). The hug
     * decision is structural and indent-independent — the single argument ({@code inner(…)} or {@code new Type<>(…)}) is
     * "the single entity" a wrapping call keeps on its opener line — but only a <em>short</em> wrapping call
     * ({@code List.of}, {@code when}, {@code verify}: selector name under {@link #SHORT_CALL_NAME_LIMIT} symbols) reads
     * well as a {@code short(inner(} opener; a longer wrapper keeps the exploded list. The remaining {@code nodeIndentWidth}
     * read is purely the break-versus-flat gate: while the flat whole-call may fit, defer to the generic group, which alone
     * measures the caller-appended terminator ({@code ;}) / chain suffix that a {@link Doc#bestFitting} flat arm cannot see,
     * so the hug only fires once flat overflows even at that lower-bound column. A pure function of the AST (no source-shape
     * read), so the verdict is a fixpoint across passes.
     */
    private boolean applies(Request request) {
        MethodCallExpr expression = request.expression();
        if (
            expression.getArguments().size() != 1
            || sourceShapePolicy.hasContainedComments(expression)
            || expression.getNameAsString().length() >= SHORT_CALL_NAME_LIMIT
        ) {
            return false;
        }
        Optional<InnerCall> inner = innerCall(expression.getArgument(0));
        if (
            inner.isEmpty()
            || sourceShapePolicy.hasContainedComments(inner.orElseThrow().node())
            || inner.orElseThrow().arguments().stream().anyMatch(LambdaExpr.class::isInstance)
        ) {
            return false;
        }
        // Measure the inner call source-neutrally: compact() normalizes the token range, so an array argument keeps the
        // author's interior spacing and trailing comma ({@code new int[] {a, b,}}), which shifts this width across a
        // re-format and flips the hug verdict. commentFree() rebuilds the call from the AST, so the width is invariant.
        String flatCall = request.prefix() + "(" + compactSource.commentFree(inner.orElseThrow().node()) + ")";
        return layoutWidth.nodeIndentWidth(expression) + flatCall.length() > options.lineWidth();
    }

    /**
     * The renderer-ranked broken shape the hug emits: {@link Doc#bestFitting} of the hug and the exploded list, ranked
     * flattest-first at the live output column. The hug ({@code wrap(inner(} ⏎ arguments ⏎ {@code ))}) uses fewer lines
     * than the exploded list, so it wins whenever it fits; the exploded list is the always-valid fallback when the hug
     * opener overflows. No flat arm — {@link #applies} already deferred the fitting case to the generic group.
     */
    private Doc layout(Request request) {
        String prefix = request.prefix();
        InnerCall inner = innerCall(request.expression().getArgument(0)).orElseThrow();
        Doc hugged = Doc.concat(
            Doc.text(prefix + "(" + inner.opener() + "("),
            Doc.indent(Doc.concat(Doc.HARD_LINE, methodCallArgumentList.apply(inner.arguments(), Doc.HARD_LINE))),
            Doc.HARD_LINE,
            Doc.text("))")
        );
        Doc exploded = Doc.concat(
            Doc.text(prefix + "("),
            Doc.indent(Doc.concat(Doc.HARD_LINE, inner.broken())),
            Doc.HARD_LINE,
            Doc.text(")")
        );
        return Doc.bestFitting(List.of(hugged, exploded));
    }
}
