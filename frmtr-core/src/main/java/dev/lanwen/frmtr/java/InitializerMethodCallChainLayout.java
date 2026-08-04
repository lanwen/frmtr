package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Owns the method-call-chain seam of the initializer cascade: the ordered broken-call sub-cascade
 * ({@link #methodCallBrokenInitializer}) reached once a flat call overflows, its block-lambda-hug sub-family, the
 * chain-ranking entries ({@link #forcedMethodCallChain}, {@link #variableWithMethodCallChainRanked}) three forced-chain
 * callers route through, and the chain-family predicates those emitters share.
 *
 * <p>The helper claims no ownership of when an initializer is over-width, of the assignment prefix, of the own-break
 * probes that gate the cascade before it is reached, or of the generic continuation-line fallback: it reports
 * {@link Optional#empty()} the moment a shape is out of its remit and hands the value back to the caller's cascade, and
 * calls back through {@code genericBrokenInitializer} for the shared last-resort break.
 */
final class InitializerMethodCallChainLayout {

    private final SourceShapePolicy sourceShapePolicy;

    private final JavaCommentPlacementPolicy commentPlacement;

    private final FormatterOptions options;

    private final LayoutWidth layoutWidth;

    private final Function<Node, String> compact;

    private final Function<List<? extends Node>, String> compactJoin;

    private final Function<Expression, Doc> expression;

    private final Function<Expression, Doc> expressionWithoutOwnComment;

    private final Function<MethodCallExpr, Doc> methodCall;

    private final CommentTracker comments;

    private final VariableInitializerLayout.ForcedChainWithLayout initializerChain;

    private final VariableInitializerLayout.CanonicalFanChain canonicalFanChain;

    private final Function<MethodCallExpr, Doc> singleSelectorDotSplit;

    private final BiFunction<MethodCallExpr, ToIntFunction<String>, Optional<Doc>> packedMethodCallChain;

    private final Function<MethodCallExpr, Optional<Doc>> mixedFieldMethodCallChain;

    private final Predicate<MethodCallExpr> methodCallChainRootIsObjectCreation;

    private final Predicate<MethodCallExpr> methodCallChainIsSourceMultiline;

    private final Function<
        MethodCallExpr,
        MethodCallChainSourcePlanner.InitializerChainShape
    > methodCallChainInitializerShape;

    private final Predicate<Expression> shouldPrintScopeAsDoc;

    private final Function<MethodCallExpr, String> methodCallPrefix;

    private final BiFunction<NodeList<Expression>, Doc, Doc> methodCallArgumentList;

    private final FieldDeclarationPrinter.HuggableArgumentsRenderer huggableBlockLambdaArguments;

    private final InitializerTrailingCommentLayout trailingCommentLayout;

    private final VariableInitializerLayout.GenericBrokenInitializer genericBrokenInitializer;

    InitializerMethodCallChainLayout(
            SourceShapePolicy sourceShapePolicy,
            JavaCommentPlacementPolicy commentPlacement,
            FormatterOptions options,
            LayoutWidth layoutWidth,
            Function<Node, String> compact,
            Function<List<? extends Node>, String> compactJoin,
            Function<Expression, Doc> expression,
            Function<Expression, Doc> expressionWithoutOwnComment,
            Function<MethodCallExpr, Doc> methodCall,
            CommentTracker comments,
            VariableInitializerLayout.ForcedChainWithLayout initializerChain,
            VariableInitializerLayout.CanonicalFanChain canonicalFanChain,
            Function<MethodCallExpr, Doc> singleSelectorDotSplit,
            BiFunction<MethodCallExpr, ToIntFunction<String>, Optional<Doc>> packedMethodCallChain,
            Function<MethodCallExpr, Optional<Doc>> mixedFieldMethodCallChain,
            Predicate<MethodCallExpr> methodCallChainRootIsObjectCreation,
            Predicate<MethodCallExpr> methodCallChainIsSourceMultiline,
            Function<MethodCallExpr, MethodCallChainSourcePlanner.InitializerChainShape> methodCallChainInitializerShape,
            Predicate<Expression> shouldPrintScopeAsDoc,
            Function<MethodCallExpr, String> methodCallPrefix,
            BiFunction<NodeList<Expression>, Doc, Doc> methodCallArgumentList,
            FieldDeclarationPrinter.HuggableArgumentsRenderer huggableBlockLambdaArguments,
            InitializerTrailingCommentLayout trailingCommentLayout,
            VariableInitializerLayout.GenericBrokenInitializer genericBrokenInitializer
    ) {
        this.sourceShapePolicy = sourceShapePolicy;
        this.commentPlacement = commentPlacement;
        this.options = options;
        this.layoutWidth = layoutWidth;
        this.compact = compact;
        this.compactJoin = compactJoin;
        this.expression = expression;
        this.expressionWithoutOwnComment = expressionWithoutOwnComment;
        this.methodCall = methodCall;
        this.comments = comments;
        this.initializerChain = initializerChain;
        this.canonicalFanChain = canonicalFanChain;
        this.singleSelectorDotSplit = singleSelectorDotSplit;
        this.packedMethodCallChain = packedMethodCallChain;
        this.mixedFieldMethodCallChain = mixedFieldMethodCallChain;
        this.methodCallChainRootIsObjectCreation = methodCallChainRootIsObjectCreation;
        this.methodCallChainIsSourceMultiline = methodCallChainIsSourceMultiline;
        this.methodCallChainInitializerShape = methodCallChainInitializerShape;
        this.shouldPrintScopeAsDoc = shouldPrintScopeAsDoc;
        this.methodCallPrefix = methodCallPrefix;
        this.methodCallArgumentList = methodCallArgumentList;
        this.huggableBlockLambdaArguments = huggableBlockLambdaArguments;
        this.trailingCommentLayout = trailingCommentLayout;
        this.genericBrokenInitializer = genericBrokenInitializer;
    }

    /**
     * The {@link VariableInitializerLayout.InitializerLayoutArm#METHOD_CALL_BROKEN} arm: the ordered broken-method-call sub-cascade for an
     * over-width, no-own-break method-call initializer. Each shape is probed in order and the first non-empty one wins;
     * when every probe declines, the call falls back to the generic break.
     */
    Doc methodCallBrokenInitializer(
            VariableDeclarator variable,
            String name,
            String declarationPrefix,
            MethodCallExpr methodCall
    ) {
        // The single-selector, simple-attachable-root fan-out-versus-argument-break convergence
        // (NAME = Collections.newSetFromMap(...)) runs through Doc.bestFitting([argumentBreak@1, collapse@0]), which is
        // idempotent for two reasons: (1) the collapse arm is built source-neutrally (whole call flat on
        // the continuation line, a pure AST function present on every input, so both passes rank the same two
        // candidates — no source-multiline-versus-flat oscillation); and (2) opener-attachment is expressed by the
        // per-alternative priority, placed after the fit gate and before
        // line count, so the opener-attached argument-break is preferred whenever it fits even though the collapse uses
        // fewer lines, and the collapse wins only when the opener overflows the fit gate. Comment-bearing single calls
        // stay on the imperative cascade below (the ranked node is emitted only when the call is comment-free), so no
        // comment is double-claimed. Object-creation-rooted single calls keep their existing imperative
        // branches below — their collapse is a broken-constructor/dot-split shape, not this whole-call collapse, so they
        // are out of this arm's scope (as is the single-simple-argument tail dot-split).
        Optional<Doc> rankedConvergence = rankedSimpleRootSingleCallConvergence(
            variable,
            name,
            declarationPrefix + variable.getNameAsString(),
            methodCall
        );
        if (rankedConvergence.isPresent()) {
            return rankedConvergence.orElseThrow();
        }
        // A multi-link fluent chain that reaches its link-count/root-kind
        // threshold fans one selector per line, and it must do so through the SAME source-neutral fan on every pass.
        // Placed here, ahead of the source-shape-sensitive object-creation, source-multiline, and attachable-scope
        // branches below, so a fan-threshold plain-receiver / type-like chain is claimed by the fan before those
        // branches can pick a source-dependent shape. That source-dependence is exactly the oscillation this seam
        // closes: a flat-source `NAME = a.b().c().find(x)` reaches methodCallHasAttachableScope (the outer selector's
        // scope ends on the name line) and renders the argument-break `find(⏎ x ⏎)`, while its already-fanned re-format
        // fails that same source-line test, falls through to forcedMethodCallChain, and renders the +8 fan
        // — so the two passes disagree forever. Routing through forcedMethodCallChain (which threads the
        // `NAME = ` leftEdgePrefix and reaches MethodCallChainPrinter.chainFanOut, a pure function of the AST) makes
        // both passes rebuild the identical fan. This is the multi-link sibling of the single-call convergence
        // (rankedSimpleRootSingleCallConvergence, above): that ranker withholds the source-sensitive conditionalGroup
        // arms for a SINGLE-call initializer and emits one deterministic ranked shape; this withholds them for the
        // MULTI-LINK fan-threshold case and emits the one deterministic fan shape. Object-creation-rooted chains are
        // intentionally excluded — their dedicated packed / compact / broken-constructor branches below (and the
        // object-creation-rooted single-call / single-simple-argument tail convergence) own their shape, and chainFanOut
        // renders an object-creation root differently than those branches; widening the fan to them is a later seam.
        // Comment- and block-lambda-bearing chains stay on the imperative cascade (re-rendering a comment-bearing root
        // through the fan would double-claim its comments — the same guard the other rankers use).
        Optional<Doc> canonicalFan = variableInitializerCanonicalFan(
            variable,
            name,
            declarationPrefix + variable.getNameAsString(),
            methodCall
        );
        if (canonicalFan.isPresent()) {
            return canonicalFan.orElseThrow();
        }
        // A comment-bearing single call (an interior argument comment, or a re-homed final trailing comment) hugs its
        // opener onto the `= ` line and breaks the argument list in place — the comment-free initializer shape — whenever
        // `NAME = call(` fits. Decided source-neutrally so both comment-attribution passes agree; break-after-`=` stays
        // the fallback when the opener overflows. Placed ahead of the source-shape-sensitive argument-break branches,
        // whose fit verdict reads the author's line breaks and so oscillates for a comment-bearing call.
        Optional<Doc> commentedOpenerHug = variableWithCommentedCallOpenerHug(
            variable,
            name,
            declarationPrefix + variable.getNameAsString(),
            methodCall
        );
        if (commentedOpenerHug.isPresent()) {
            return commentedOpenerHug.orElseThrow();
        }
        if (
            initializerSingleSimpleArgTailDotSplits(
                variable,
                methodCall,
                declarationPrefix + variable.getNameAsString()
            )
        ) {
            // The single-simple-argument tail dot-split. An over-width object-creation-rooted single call whose selector's
            // argument list is exactly one simple argument (a name, field access, this/super, or literal) and whose opener
            // still fits on the assignment line ({@code NAME = new X(...).selector(} within budget) would otherwise reach
            // the object-creation argument-break branch below, which keeps the whole opener on the assignment line and
            // breaks that single argument onto its own line ({@code new X(...).selector(}⏎{@code arg}⏎{@code )}) — opening
            // one simple argument across three lines when {@code .selector(arg)} routinely fits on its own dotted
            // continuation line. Route it through the
            // initializer's chain-continuation (+8) fan-out ({@link #variableWithPackedMethodCallChain}) instead —
            // the same path a long-constructor single-selector tail takes when its opener overflows (the
            // {@code buildLongConstructorStrategy}/{@code buildShortConstructorStrategy} goldens). That path keeps the
            // constructor root on the assignment line and fans the lone selector compact onto its own continuation line at
            // the chain-continuation indent, so this shape is byte-for-byte consistent with its opener-overflow siblings
            // rather than taking the argument-open shape or the shallower {@code objectRootSingleSegmentChain} indent.
            // Emitting it here — before the source-shape-sensitive {@code variableWithCompactObjectCreationChain} collapse
            // below — keys the shape on AST + the opener's fit at the rendered column only, so it wins on every pass and is
            // idempotent: {@code packedMethodCallChain} is a pure width function of the AST, so a re-format of the
            // already-split source re-derives the same packed fan-out rather than collapsing the (now-fitting) whole chain
            // onto the continuation line.
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
            // tail is handled above by the dot-split, so only multi-argument and lambda tails (and
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
        // The single-selector simple-attachable-root fan-out-versus-argument-break convergence is resolved
        // above by rankedSimpleRootSingleCallConvergence, so this force-wide gate only reaches MULTI-SEGMENT
        // type-like chains (NAME = a.b.C.first(...).second(...)), whose one-per-line forced chain the ranked
        // single-call arm does not build. singleCallConvergesOnArgumentBreak still guards the object-creation single
        // call (whose collapse is a broken-constructor shape rendered by its own branches, not this
        // whole-call collapse); for that shape the predicate keeps the deterministic argument-break decision. It keys
        // purely on AST shape + measured width, never source line breaks, so this path stays idempotent.
        if (
            initializerChainShape.shouldForceWideInitializerChain()
            && !singleCallConvergesOnArgumentBreak(
                methodCall,
                argumentBreakOpenerFits(variable, methodCall, declarationPrefix + variable.getNameAsString())
            )
        ) {
            String probeFlatName = declarationPrefix + variable.getNameAsString();
            Optional<Doc> forcedChain = forcedMethodCallChain(variable, methodCall, probeFlatName)
                .map(chain -> variableWithMethodCallChainRanked(variable, name, probeFlatName, methodCall, chain, new int[] { 1, 0 }));
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
        String fallbackFlatName = declarationPrefix + variable.getNameAsString();
        Optional<Doc> rankedBlockLambdaChain = rankedCommentFreeBlockLambdaInitializerChain(
            variable,
            name,
            fallbackFlatName,
            methodCall
        );
        if (rankedBlockLambdaChain.isPresent()) {
            return rankedBlockLambdaChain.orElseThrow();
        }
        Optional<Doc> forcedChain = forcedMethodCallChain(variable, methodCall, fallbackFlatName)
            .map(chain -> variableWithMethodCallChainRanked(variable, name, fallbackFlatName, methodCall, chain, new int[] { 1, 0 }));
        if (forcedChain.isPresent()) {
            return forcedChain.orElseThrow();
        }
        Optional<Doc> mixedChain = mixedFieldMethodCallChain.apply(methodCall);
        if (mixedChain.isPresent()) {
            return variableWithMethodCallChainRanked(
                variable,
                name,
                declarationPrefix + variable.getNameAsString(),
                methodCall,
                mixedChain.orElseThrow(),
                new int[] { 1, 0 }
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
        return genericBrokenInitializer.apply(variable, methodCall, name);
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
            || sourceShapePolicy.hasContainedComments(methodCall)
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
                // The single-simple-argument tail dot-split. A single-selector object-creation root with a single
                // simple-argument tail whose opener fits on the assignment line is admitted to this +8 fan-out too, so it
                // fans the constructor root on the assignment line and {@code .selector(simpleArg)} compact on its own
                // continuation line — the same shape a long-constructor tail produces when its opener overflows. Without
                // this the shape gate below rejects it (a single-line-source single call is not a compact-object-creation
                // shape) and the call takes the argument-open shape. The width gate that follows also does not reject a
                // single-simple-arg tail as an argument-break candidate. The {@code initializerSingleSimpleArgTailDotSplits}
                // branch of {@code variableInitializerBrokenOrFlat} routes exactly this shape here ahead of the
                // argument-break branch; multi-argument and lambda tails never match {@link #tailHasSingleSimpleArgument}
                // and keep their argument-break / opener-fits behavior.
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
            || (!allowNestedComments && sourceShapePolicy.hasContainedComments(methodCall))
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
        if (sourceShapePolicy.hasContainedComments(methodCall)) {
            return Optional.of(Doc.concat(Doc.text(name + " = "), this.methodCall.apply(methodCall)));
        }
        Doc attach = brokenMethodCallArgumentList(name, methodCall, callPrefix);
        return Optional.of(
            singleSelectorDotSplitArm(name, methodCall)
                    .map(dotSplit -> Doc.bestFitting(List.of(dotSplit, attach)))
                    .orElse(attach)
        );
    }

    /**
     * The dot-split arm: the root on the {@code =} line and the sole selector on its own continuation line, with the
     * selector's argument list pinned flat. Pinning is what makes the arm rankable — left breakable the selector explodes
     * around a lambda argument and still wins on line count, which is the shape the dot-split exists to avoid.
     */
    private Optional<Doc> singleSelectorDotSplitArm(String name, MethodCallExpr methodCall) {
        if (
            !methodCallChainInitializerShape.apply(methodCall).singleCall()
            || !singleCallHasInlineMethodCallRoot(methodCall)
        ) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(Doc.text(name + " = "), Doc.flat(singleSelectorDotSplit.apply(methodCall)))
        );
    }

    /**
     * Routes the over-width, single-selector, simple-attachable-root initializer through the ranked
     * {@link Doc#bestFitting(java.util.List, int[]) bestFitting} engine instead of the imperative
     * fan-out-versus-argument-break convergence, which is not idempotent to route directly (see the note in
     * {@link VariableInitializerLayout#variableInitializerBrokenOrFlat}). Present only for the exact shape the
     * {@link #singleCallConvergesOnArgumentBreak} predicate identifies for a name/type-like/field-access root
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
     *       the continuation line, the same shape the imperative fall-through builds via {@code brokenInitializer} for a
     *       single simple call (whose {@code forcedMethodCallChain} is empty, so it renders the call flat). This is the
     *       single-selector fan-out with the root on the continuation line and no dot-split; it is
     *       built directly here rather than through {@code MethodCallChainPrinter.chainFanOut}, because {@code chainFanOut}
     *       fans a single selector onto its own dotted continuation line ({@code ROOT}⏎{@code .method(...)}), which is a
     *       different (dot-split) shape than this initializer's whole-call collapse and would move the
     *       {@code field-init-typelike-root-idempotence} {@code qualifiedRootProviders} golden. (The single-simple-argument
     *       tail dot-split is deliberately out of scope here.)</li>
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
     * comment-bearing single calls stay on the imperative cascade below, exactly as the other rankers require.
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
            || sourceShapePolicy.hasContainedComments(methodCall)
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
     * Hugs the opener of a comment-bearing single call onto the {@code = } line and breaks its argument list, the
     * comment-free {@code NAME = call(}⏎args⏎{@code )} shape, when the opener fits. A contained argument comment (or a
     * re-homed final trailing comment) otherwise makes the argument-break branches decline — they reject contained
     * comments — so the call strands {@code =} on a flat pass and hugs on a re-format, oscillating.
     *
     * <p>Keyed purely on AST shape and the opener's fit at the rendered column, never on source line breaks, so both
     * comment-attribution passes rebuild the same shape. Scoped to a single call over an absent or simple attachable
     * receiver (unqualified, {@code name.}, {@code this.}, {@code super.}, {@code a.b.c.}); chains, object-creation roots,
     * block-lambda hugs, and enclosed-receiver suffixes keep their own branches. The argument list is rendered through the
     * shared argument printer, which claims the interior comments; the final trailing comment is never rendered here (the
     * caller re-homes it as the statement suffix), so it is never dropped or duplicated.
     */
    private Optional<Doc> variableWithCommentedCallOpenerHug(
            VariableDeclarator variable,
            String name,
            String flatName,
            MethodCallExpr methodCall
    ) {
        if (
            !sourceShapePolicy.hasContainedComments(methodCall)
            && trailingCommentLayout.methodCallFinalTrailingLineComments(methodCall).isEmpty()
        ) {
            return Optional.empty();
        }
        // A leading own comment on the call is placed by the chain path, so don't hug over it (a re-homed final trailing
        // comment is not a leading comment and stays eligible).
        if (methodCall.getComment().isPresent() && commentPlacement.trailingLineComment(methodCall).isEmpty()) {
            return Optional.empty();
        }
        if (
            methodCall.getArguments().isEmpty()
            || methodCallHasBlockLambdaArgument(methodCall)
            || methodCallChainRootIsObjectCreation.test(methodCall)
            || !singleCallHasHuggableOpenerReceiver(methodCall)
            || !argumentBreakOpenerFits(variable, methodCall, flatName)
        ) {
            return Optional.empty();
        }
        // The shared call renderer claims the interior argument comments and breaks the argument list under the hugged
        // opener; it never renders the call's re-homed final trailing comment, which the caller appends as the suffix.
        return Optional.of(Doc.concat(Doc.text(name + " = "), this.methodCall.apply(methodCall)));
    }

    /**
     * A single call whose receiver renders inline before its {@code prefix(} opener: an unqualified call or a call over a
     * simple attachable scope ({@code name.}, {@code this.}, {@code super.}, a {@code a.b.c.} field access). Method-call and
     * object-creation receivers (genuine chains) are excluded — their {@code prefix} would fold a whole sub-chain onto the
     * assignment line rather than a clean opener.
     */
    private boolean singleCallHasHuggableOpenerReceiver(MethodCallExpr methodCall) {
        Optional<Expression> scope = methodCall.getScope();
        if (scope.isEmpty()) {
            return true;
        }
        return scope
                .filter(candidate -> candidate.isNameExpr()
                        || candidate.isThisExpr()
                        || candidate.isSuperExpr()
                        || candidate.isFieldAccessExpr())
                .filter(candidate -> !shouldPrintScopeAsDoc.test(candidate))
                .isPresent();
    }

    /**
     * Routes a multi-link fan-threshold initializer chain onto the source-neutral canonical fan
     * ({@code MethodCallChainPrinter.chainFanOut}, reached through {@link #forcedMethodCallChain} and ranked
     * attach-first via {@link #variableWithMethodCallChainRanked}), the
     * multi-link sibling of {@link #rankedSimpleRootSingleCallConvergence}'s single-call convergence. Present only for the
     * exact shape the canonical fan claims: the chain reaches the link-count/root-kind threshold
     * ({@link MethodCallChainSourcePlanner.InitializerChainShape#chainBreaksByRule()} — the one source of truth for the
     * rule), the root is not an object creation (those keep their dedicated packed / broken-constructor branches, whose
     * collapse shapes {@code chainFanOut} would not reproduce), and the chain carries no own or contained comment and no
     * block-lambda argument (a fan re-renders the root once, so a comment- or block-lambda-bearing root would be
     * double-claimed — the guard the {@code chainFanOut} rankers share).
     *
     * <p><strong>Why it is a fixpoint.</strong> The forced-chain path threads the initializer's {@code NAME = }
     * {@link LayoutContext#leftEdgePrefix() leftEdgePrefix} and lands in {@code MethodCallChainPrinter.methodCallChain},
     * whose canonical-fan route emits {@code chainFanOut} for a fan-threshold, comment-free chain. {@code chainFanOut}
     * builds the root plus one dotted selector per line purely from the AST, so a flat-source initializer and its
     * already-fanned re-format both rebuild the identical fan. Emitting it here — ahead of the object-creation,
     * source-multiline, and {@code methodCallHasAttachableScope} argument-break branches — removes the source
     * dependence those branches introduce, the direct cause of the flat↔fan oscillation this seam closes.
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
            || sourceShapePolicy.hasContainedComments(methodCall)
            || methodCallHasBlockLambdaArgument(methodCall)
        ) {
            return Optional.empty();
        }
        return forcedMethodCallChain(variable, methodCall, flatName)
            .map(chain -> variableWithMethodCallChainRanked(variable, name, flatName, methodCall, chain, new int[] { 1, 0 }));
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
     *   <li>An object-creation root ({@code new X(ctorArgs).method(...)}). This root kind is argument-broken imperatively
     *       (its collapse is a broken-constructor / dot-split shape, not a whole-call
     *       collapse), so this predicate remains its convergence signal.</li>
     *   <li>A simple attachable name/type-like or field-access root ({@code Collections.newSetFromMap(...)},
     *       {@code this.foo(...)}), whose argument-break-versus-collapse choice runs through the ranked
     *       engine ({@link #rankedSimpleRootSingleCallConvergence}, {@code Doc.bestFitting([argument-break@1, collapse@0])}).
     *       That ranked arm pre-empts this shape in {@link VariableInitializerLayout#variableInitializerBrokenOrFlat}, so here the predicate does not
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
            || singleCallHasSimpleAttachableRoot(methodCall)
            || singleCallHasInlineMethodCallRoot(methodCall);
    }

    /**
     * A single-selector chain whose root is itself a method call ({@code root().collect(args)}): the root renders inline
     * before the selector, so with the opener fitting (the caller's {@code openerFits} gate) the whole {@code = root().selector(}
     * prefix stays on the assignment line and only the argument list breaks. That argument-break shape is a fixpoint, so it
     * must be preferred over breaking after {@code =} regardless of whether the author wrote the chain across lines.
     */
    private boolean singleCallHasInlineMethodCallRoot(MethodCallExpr methodCall) {
        return methodCall.getScope()
                .filter(Expression::isMethodCallExpr)
                .filter(scope -> !shouldPrintScopeAsDoc.test(scope))
                .isPresent();
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
     * Measures the {@code NAME = ROOT.method(} argument-break opener at the declaration's real rendered column via
     * {@link LayoutWidth#variableInitializer} (which counts the declarator's block/type nesting depth), floored by
     * {@link LayoutWidth#currentIndented} so it never measures narrower than one indentation unit. The opener stays on the
     * assignment line only when it fits there; measuring at the true column keeps the keep-opener decision stable at deep
     * nesting positions where a one-unit budget would under-count and admit an over-width opener.
     */
    int openerLineWidth(VariableDeclarator variable, String openerLine) {
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
     * dotted continuation line instead of opening the tail's single argument. It holds only for the exact shape that would
     * otherwise arg-open:
     * <ul>
     *   <li>an object-creation root ({@code new X(...)}) with exactly one selector segment — the same
     *       {@code rootIsObjectCreation && singleCall} shape the object-creation argument-break branch owns;</li>
     *   <li>a tail whose argument list is a single <em>simple</em> argument ({@link #tailHasSingleSimpleArgument}); a
     *       multi-argument or lambda tail is not a single-simple-argument tail and keeps opening; and</li>
     *   <li>an opener that still fits on the assignment line ({@link #argumentBreakOpenerFits}, {@code NAME = new X(...).selector(}
     *       within budget). This is the precise boundary that scopes this shape to the calls that would otherwise arg-open.
     *       When the opener-with-selector overflows (a long constructor whose {@code new X(...).selector(} does not fit) the
     *       call already fans onto its own continuation line through {@link #variableWithPackedMethodCallChain} — declining
     *       here leaves that (identical-looking) shape to the overflow path.</li>
     * </ul>
     *
     * <p>The chosen shape is the same chain-continuation (+8) fan-out {@link #variableWithPackedMethodCallChain} already
     * produces for an opener-overflow single-selector tail (the {@code buildLongConstructorStrategy}/
     * {@code buildShortConstructorStrategy} goldens), so this shape fans at the same indent as its opener-overflow siblings
     * rather than at the shallower {@code MethodCallChainPrinter.objectRootSingleSegmentChain} indent the {@code return}
     * chain's dot-split uses. Every input is an AST-shape or rendered-column-width fact, never a source line break, so
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
     * (the classification the return chain's dot-split and {@code objectRootSingleSegmentChain}'s compact-tail branch
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
    Optional<Doc> variableWithLeadingCommentedBlockLambdaMethodCall(
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

    /**
     * Identifies receiver-call initializers where the assignment opener should be tried before chain fallback.
     * A method-call scope attaches only when the chain is a single selector; multi-selector chains are ranked below.
     */
    private boolean methodCallHasAttachableScope(MethodCallExpr methodCall) {
        return methodCall.getScope()
                .filter(scope -> scope.isNameExpr()
                        || scope.isThisExpr()
                        || scope.isSuperExpr()
                        || (scope instanceof MethodCallExpr scopedCall
                            && !sourceShapePolicy.hasContainedComments(scopedCall)
                            && methodCallChainInitializerShape.apply(methodCall).singleCall())
                )
                .isPresent();
    }

    /**
     * Reports a two-selector chain over a plain (non type-like, non object-creation) receiver
     * ({@code env.adminClient().alterStreamsGroupOffsets(args)}, {@code keys.stream().collect(...)}): the first selector's
     * own scope is the receiver root and is not itself a call, so the whole chain is exactly two links. Such a chain fans
     * one dotted selector per line on both flat-source and pre-broken passes — the source-neutral fixpoint.
     *
     * <p>Withheld above the canonical link-count threshold ({@code chainBreaksByRule} — the 3+/call-root fan already owns
     * those), for a block-lambda argument (its hugged fan keeps the block on the last selector's line, which the generic
     * chain path preserves), and for any own/contained/trailing comment (whose placement the terminator-threaded chain
     * path does not own).
     */
    boolean initializerFansWidthDrivenTwoSelectorChain(MethodCallExpr methodCall) {
        MethodCallChainSourcePlanner.InitializerChainShape shape = methodCallChainInitializerShape.apply(methodCall);
        if (
            shape.typeLikeRoot()
            || shape.rootIsObjectCreation()
            || shape.singleCall()
            || shape.chainBreaksByRule()
            || methodCallHasBlockLambdaArgument(methodCall)
            || methodCallHasOwnComment(methodCall)
            || sourceShapePolicy.hasContainedComments(methodCall)
            || !trailingCommentLayout.methodCallFinalTrailingLineComments(methodCall).isEmpty()
        ) {
            return false;
        }
        return methodCall.getScope()
                .filter(MethodCallExpr.class::isInstance)
                .map(MethodCallExpr.class::cast)
                .flatMap(MethodCallExpr::getScope)
                .filter(root -> !(root instanceof MethodCallExpr))
                .isPresent();
    }

    private boolean methodCallHasOwnComment(MethodCallExpr methodCall) {
        return methodCall.getComment().isPresent()
            || methodCall.getName().getComment().isPresent()
            || methodCall.getScope().flatMap(Expression::getComment).isPresent();
    }

    boolean methodCallHasBlockLambdaArgument(MethodCallExpr methodCall) {
        return methodCall.getArguments()
                .stream()
                .anyMatch(argument -> argument instanceof LambdaExpr lambdaExpr
                        && lambdaExpr.getBody().isBlockStmt()
                );
    }

    boolean methodCallHasLeadingCommentedBlockLambdaArgument(MethodCallExpr methodCall) {
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
    boolean methodCallHasContainedCommentObjectCreationBlockLambdaArgument(MethodCallExpr methodCall) {
        return methodCallChainRootIsObjectCreation.test(methodCall)
            && methodCallChainInitializerShape.apply(methodCall).singleCall()
            && methodCallHasBlockLambdaArgument(methodCall)
            && !methodCallHasOwnComment(methodCall)
            && sourceShapePolicy.hasContainedComments(methodCall);
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

    Optional<Doc> variableWithReceiverBreakBeforeOverWidthHuggableBlockLambdaArguments(
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
     * <p>Building the candidate {@link Doc} renders the lambda block purely to measure its fit; the candidate itself is
     * discarded, since when it fits the caller returns {@link Optional#empty()} so the ordinary hug renders elsewhere,
     * and when it does not the receiver-break path renders the call fresh. Both winners re-render the same lambda block,
     * so this measurement must not disturb comment state. It does not: comment rendering is claim-neutral (every comment
     * resolves against its recorded owner and never mutates the print-once set), so building the probe offers each
     * contained comment through its owner without consuming it, and the eventual winner re-offers the same owner and
     * still renders it. The candidate is therefore built and measured directly, with no rollback scope needed.
     */
    private boolean huggableBlockLambdaArgumentsFit(
            VariableDeclarator variable,
            String name,
            String flatName,
            MethodCallExpr methodCall,
            String callPrefix
    ) {
        return variableWithHuggableBlockLambdaArguments(variable, name, flatName, methodCall, callPrefix)
                .isPresent();
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
            || scope.filter(sourceShapePolicy::hasContainedComments).isPresent()
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
     * Ranks the block-lambda hug, open shape (sole argument), and fan (attached/break-after-{@code =}) by true rendered
     * first line. Comment-free and method-call-scoped only: the hug and open arms render the receiver as compact text and
     * carry no comment slot, so the comment-free gate is load-bearing.
     */
    private Optional<Doc> rankedCommentFreeBlockLambdaInitializerChain(
            VariableDeclarator variable,
            String name,
            String flatName,
            MethodCallExpr methodCall
    ) {
        if (
            !methodCallHasBlockLambdaArgument(methodCall)
            || methodCallChainInitializerShape.apply(methodCall).singleCall()
            || methodCallHasOwnComment(methodCall)
            || sourceShapePolicy.hasContainedComments(methodCall)
            || !trailingCommentLayout.methodCallFinalTrailingLineComments(methodCall).isEmpty()
        ) {
            return Optional.empty();
        }
        // Only for method-call scopes — name/this/super/fieldAccess scopes attach via methodCallHasAttachableScope.
        if (methodCall.getScope().filter(MethodCallExpr.class::isInstance).isEmpty()) {
            return Optional.empty();
        }
        String callPrefix = methodCallPrefix.apply(methodCall);
        // Structural hug candidate: skip the fixed-width check (text -> 0 always fits the lineWidth guard),
        // deferring to the first-line ranker to decide at the true rendered column instead.
        Optional<Doc> eligibleHug = huggableBlockLambdaArguments.render(callPrefix, methodCall.getArguments(), text -> 0);
        if (eligibleHug.isEmpty()) {
            return Optional.empty();
        }
        Optional<Doc> fan = forcedMethodCallChain(variable, methodCall, flatName);
        if (fan.isEmpty()) {
            return Optional.empty();
        }
        Doc hugDoc = Doc.concat(Doc.text(name + " = "), eligibleHug.orElseThrow());
        Doc fanDoc = fan.orElseThrow();
        Doc fanAttachedDoc = Doc.concat(Doc.text(name + " = "), fanDoc);
        Doc fanBrokenAfterEqualsDoc = Doc.concat(
            Doc.text(name + " ="),
            Doc.indent(Doc.concat(Doc.HARD_LINE, fanDoc))
        );
        Optional<Doc> openArm = soleBlockLambdaArgumentOpenArm(name, methodCall, callPrefix);
        if (openArm.isPresent()) {
            return Optional.of(
                Doc.bestFittingFirstLine(
                    List.of(hugDoc, openArm.orElseThrow(), fanAttachedDoc, fanBrokenAfterEqualsDoc),
                    new int[] { 3, 2, 1, 0 }
                )
            );
        }
        return Optional.of(
            Doc.bestFittingFirstLine(List.of(hugDoc, fanAttachedDoc, fanBrokenAfterEqualsDoc), new int[] { 2, 1, 0 })
        );
    }

    /** The open shape — receiver compact, argument list broken — when the sole argument is the block lambda. */
    private Optional<Doc> soleBlockLambdaArgumentOpenArm(String name, MethodCallExpr methodCall, String callPrefix) {
        if (methodCall.getArguments().size() != 1) {
            return Optional.empty();
        }
        return Optional.of(brokenMethodCallArgumentList(name, methodCall, callPrefix));
    }

    /**
     * Reports a comment-carrying, single-selector object-creation-rooted chain whose tail call
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
    /**
     * The single-call, empty-tail, object-creation-rooted chain shape ({@code new X(...).method()}): no interior break
     * point exists (the constructor already stands alone, the tail cannot open an empty argument list), so attach versus
     * break-after-{@code =} is decided by ranking the rendered Docs, never by a source-shape read.
     */
    private boolean isSingleCallEmptyTailObjectCreationChain(MethodCallExpr methodCall) {
        return methodCallChainRootIsObjectCreation.test(methodCall)
            && methodCallChainInitializerShape.apply(methodCall).singleCall()
            && methodCall.getArguments().isEmpty()
            && methodCall.getScope().orElse(null) instanceof ObjectCreationExpr;
    }

    /**
     * Ranks ATTACH, the DOT-BREAK shape ({@code = new RelaySubject<>(...)}⏎{@code .withoutAuthentication(); // note}),
     * and break-after-{@code =} for a comment-bearing, empty-tail object-creation chain, by true rendered first line
     * ({@link Doc#bestFittingFirstLine}) over a constructor root Doc built once and shared by all three candidates. Built
     * HERE, ahead of {@link #variableWithMethodCallChainRanked}, so this fan claims the trailing comment itself before
     * the chain doc ({@code methodCallWithSemicolon}) can claim it first and leave this re-render comment-empty.
     */
    Optional<Doc> dotBrokenObjectRootTailChain(
            String name,
            MethodCallExpr methodCall
    ) {
        if (!isSingleCallEmptyTailObjectCreationChain(methodCall)) {
            return Optional.empty();
        }
        // The constructor renders comment-free; only the tail trailing line comment is re-emitted. Bail unless every
        // comment reachable in the chain is one of those tail comments (a constructor-argument or selector-name comment
        // would be dropped by the comment-free constructor render).
        List<JavaCommentTrivia> tailComments = trailingCommentLayout.methodCallFinalTrailingLineComments(methodCall);
        List<Comment> renderedComments = tailComments.stream().map(JavaCommentTrivia::comment).toList();
        List<Comment> allChainComments = new ArrayList<>(methodCall.getAllContainedComments());
        methodCall.getComment().ifPresent(allChainComments::add);
        if (!allChainComments.stream().allMatch(renderedComments::contains)) {
            return Optional.empty();
        }
        // The constructor renders through the ordinary comment-blind expression printer, built exactly ONCE and shared
        // by all three ranked candidates below: it carries its own width-driven group (breaks its argument list on
        // overflow), so the renderer — not a string first-line estimate — decides whether the constructor stays flat.
        Doc constructorRoot = expressionWithoutOwnComment.apply(methodCall.getScope().orElseThrow());
        Doc commentSuffix = tailComments.isEmpty()
            ? Doc.EMPTY
            : Doc.lineSuffix(Doc.concat(
                Doc.text(" "),
                Doc.join(Doc.text(" "), tailComments.stream().map(comments::comment).toList())
            ));
        Doc tailSegment = Doc.concat(Doc.text(methodCallSegmentPrefix(methodCall) + "();"), commentSuffix);
        Doc attached = Doc.concat(Doc.text(name + " = "), constructorRoot, tailSegment);
        Doc dotBroken = Doc.concat(
            Doc.text(name + " = "),
            constructorRoot,
            Doc.indent(Doc.indent(Doc.concat(Doc.HARD_LINE, tailSegment)))
        );
        Doc brokenAfterEquals = Doc.concat(
            Doc.text(name + " ="),
            Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.concat(constructorRoot, tailSegment)))
        );
        return Optional.of(
            Doc.bestFittingFirstLine(List.of(attached, dotBroken, brokenAfterEquals), new int[] { 2, 1, 0 })
        );
    }

    /**
     * Ranks the attach-after-{@code =} and break-after-{@code =} shapes of a method-call chain initializer by true
     * rendered first line ({@link Doc#bestFittingFirstLine}), the single seam every {@code initializerChain}-backed
     * chain caller in this class routes through instead of a string first-line estimate.
     */
    Doc variableWithMethodCallChainRanked(
            VariableDeclarator variable,
            String name,
            String flatName,
            MethodCallExpr methodCall,
            Doc chain
    ) {
        return variableWithMethodCallChainRanked(variable, name, flatName, methodCall, chain, new int[0]);
    }

    /**
     * Priority-taking variant: decides attach-after-{@code =} versus break-after-{@code =} by true rendered first line.
     * An empty {@code priorities} keeps the fewest-lines default; {@code {1, 0}} makes attach win whenever its first
     * line fits, regardless of line count.
     */
    Doc variableWithMethodCallChainRanked(
            VariableDeclarator variable,
            String name,
            String flatName,
            MethodCallExpr methodCall,
            Doc chain,
            int[] priorities
    ) {
        Doc attached = Doc.concat(Doc.text(name + " = "), chain);
        Doc brokenAfterEquals = Doc.concat(Doc.text(name + " ="), Doc.indent(Doc.concat(Doc.HARD_LINE, chain)));
        return Doc.bestFittingFirstLine(List.of(attached, brokenAfterEquals), priorities);
    }

    Optional<Doc> forcedMethodCallChain(
            VariableDeclarator variable,
            MethodCallExpr methodCall,
            String flatName
    ) {
        // The initializer's assignment prefix (NAME = ) shares the chain's first line, so hand the chain
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
        // break-after-= collapse the fan-out oscillates with), so it keeps the argument-break shape.
        return initializerChain.apply(
            methodCall,
            firstLineWidth(variable, flatName + " = "),
            LayoutContext.root().withLeftEdgePrefix(flatName + " = ")
        );
    }

    ToIntFunction<String> firstLineWidth(VariableDeclarator variable, String prefix) {
        return text -> layoutWidth.variableInitializer(variable, prefix + text);
    }
}
