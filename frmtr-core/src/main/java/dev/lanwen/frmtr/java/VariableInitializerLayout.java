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

    private final BiFunction<Expression, Boolean, Optional<Doc>> suffixedEnclosedExpression;

    private final Function<ArrayAccessExpr, Doc> arrayAccessWithBrokenEnclosedName;

    private final Predicate<BinaryExpr> shouldKeepCastDivisionContinuationFlat;

    private final BiFunction<Expression, Boolean, Doc> binaryExpressionLines;

    private final BiFunction<Expression, Boolean, Doc> parenthesizedBreak;

    private final Function<MethodCallExpr, Doc> methodCall;

    private final Function<MethodCallExpr, Doc> brokenMethodCall;

    private final Function<MethodCallExpr, Optional<Doc>> mixedFieldMethodCallChain;

    private final BiFunction<MethodCallExpr, ToIntFunction<String>, Optional<Doc>> forcedMethodCallChain;

    private final BiFunction<MethodCallExpr, ToIntFunction<String>, Optional<Doc>> packedMethodCallChain;

    private final Function<MethodCallExpr, Optional<String>> compactMethodCallChainRoot;

    private final Function<MethodCallExpr, Doc> methodCallWithSemicolon;

    private final Predicate<MethodCallExpr> methodCallChainHasFinalTrailingLineComment;

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
            BiFunction<Expression, Boolean, Optional<Doc>> suffixedEnclosedExpression,
            Function<ArrayAccessExpr, Doc> arrayAccessWithBrokenEnclosedName,
            Predicate<BinaryExpr> shouldKeepCastDivisionContinuationFlat,
            BiFunction<Expression, Boolean, Doc> binaryExpressionLines,
            BiFunction<Expression, Boolean, Doc> parenthesizedBreak,
            Function<MethodCallExpr, Doc> methodCall,
            Function<MethodCallExpr, Doc> brokenMethodCall,
            Function<MethodCallExpr, Optional<Doc>> mixedFieldMethodCallChain,
            BiFunction<MethodCallExpr, ToIntFunction<String>, Optional<Doc>> forcedMethodCallChain,
            BiFunction<MethodCallExpr, ToIntFunction<String>, Optional<Doc>> packedMethodCallChain,
            Function<MethodCallExpr, Optional<String>> compactMethodCallChainRoot,
            Function<MethodCallExpr, Doc> methodCallWithSemicolon,
            Predicate<MethodCallExpr> methodCallChainHasFinalTrailingLineComment,
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
        this.forcedMethodCallChain = forcedMethodCallChain;
        this.packedMethodCallChain = packedMethodCallChain;
        this.compactMethodCallChainRoot = compactMethodCallChainRoot;
        this.methodCallWithSemicolon = methodCallWithSemicolon;
        this.methodCallChainHasFinalTrailingLineComment = methodCallChainHasFinalTrailingLineComment;
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
            && methodCallChainIsSourceMultiline.test(methodCall)
            && !methodCallFinalTrailingLineComments(methodCall).isEmpty()
        ) {
            return variableWithMethodCallChain(
                variableName(variable),
                declarationPrefix + variable.getNameAsString(),
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
                name,
                declarationPrefix + variable.getNameAsString(),
                methodCall
            );
            if (brokenCall.isPresent()) {
                return Optional.of(brokenCall.orElseThrow());
            }
        }
        if (layoutWidth.blockStatement(flat) > options.lineWidth()) {
            Optional<Doc> suffixedEnclosedInitializer = suffixedEnclosedExpression.apply(initializer, true);
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
                    name,
                    declarationPrefix + variable.getNameAsString(),
                    objectCreationExpr
                );
                if (objectCreation.isPresent()) {
                    return Optional.of(objectCreation.orElseThrow());
                }
            }
            if (
                initializer instanceof MethodCallExpr methodCall
                && methodCallChainInitializerShape.apply(methodCall).shouldForceSourceMultilineInitializerChain()
                && !singleCallConvergesOnArgumentBreak(
                    methodCall,
                    argumentBreakOpenerFits(methodCall, declarationPrefix + variable.getNameAsString())
                )
            ) {
                Optional<Doc> forcedChain = variableWithForcedMethodCallChain(
                    variable,
                    name,
                    declarationPrefix + variable.getNameAsString(),
                    methodCall
                );
                if (forcedChain.isPresent()) {
                    return Optional.of(forcedChain.orElseThrow());
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
            && !(initializer instanceof CastExpr)
            && !sourceShapePolicy.wasMultiline(initializer);
    }

    /**
     * Dispatches the construct-specific broken initializer shape, reached from the {@code (A)} master gate's broken arm
     * (and, unchanged, from the comment-bearing imperative path).
     *
     * <p>{@code forceBroken} carries the renderer's flat-versus-broken verdict into what used to be ~10 repeated
     * {@code variableInitializer(variable, flat) > lineWidth} tests. When the caller is the {@link Doc#conditionalGroup}
     * broken arm it passes {@code true} — the renderer already judged the flat form too wide, so every branch that used to
     * gate on that reconstructed width now gates on the real column instead. When the caller is the comment-bearing path
     * it passes {@code false}, reproducing the historical per-branch width gate exactly so that path stays byte-identical.
     * Each branch's broken shape is unchanged: the master gate only moves the flat-versus-broken decision, not which
     * broken shape a construct takes when it is broken. A construct that owns its own break (arrays, switch, anonymous
     * object creation) falls through to the final flat dispatch in both modes, because {@code expression.apply} already
     * renders its internal break.
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
            if (
                methodCallChainRootIsObjectCreation.test(methodCall)
                && methodCallChainInitializerShape.apply(methodCall).singleCall()
            ) {
                // Only single-segment object-creation roots (new X(args).onlyCall(...)) keep the call on the
                // assignment line and break its argument list. Multi-segment constructor chains fall through to the
                // one-per-line chain below so the root sits alone and every .call() gets its own line, instead of
                // greedy-packing the root plus the leading calls onto the assignment line.
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
            // C10 (B) — left imperative deliberately. The intent was to replace this fan-out-versus-argument-break choice
            // with Doc.bestFitting([argumentBreak, fanOut]) and let the renderer rank them. Analysis of the shipped
            // bestFitting metric shows that is not a safe swap here:
            //  * argument-break keeps `NAME = ROOT.call(` on the assignment line, so for a single-selector call it always
            //    wraps into strictly fewer lines than the one-per-line fan-out. bestFitting ranks flattest-first by line
            //    count, so it would ALWAYS pick argument-break — the ranking never selects fan-out, so it adds no
            //    behavior over "prefer argument-break when it is offered".
            //  * The real, idempotence-critical work is deciding *whether argument-break is offered at all*:
            //    singleCallConvergesOnArgumentBreak gates on the opener fitting (argumentBreakOpenerFits) and an
            //    attachable root. DocWidths.LineCount#betterThan prioritizes line count over overflow, so a bestFitting
            //    node would keep an argument-break whose opener overflows (fewer lines) instead of the fan-out — exactly
            //    the regression field-init-typelike-root-idempotence's `qualifiedRootProviders` locks against. bestFitting
            //    cannot express the opener-fit gate, so it cannot replace this predicate without reintroducing it.
            //  * Both arms render the call, so a bestFitting would double-claim comments for this method's comment-bearing
            //    caller (variableInitializerBrokenOrFlat is also reached from the imperative comment path).
            // The predicate already keys purely on AST shape + measured width (never source line breaks), so it is
            // idempotent by construction; leaving it imperative keeps that guarantee. (LDM-3/B8 territory once the ranking
            // metric grows an overflow-first tie-break that can decline an overflowing opener.)
            if (
                initializerChainShape.shouldForceWideInitializerChain()
                && !singleCallConvergesOnArgumentBreak(
                    methodCall,
                    argumentBreakOpenerFits(methodCall, declarationPrefix + variable.getNameAsString())
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
            Optional<Doc> sourceMultilineCall = variableWithSourceMultilineMethodCallInitializer(
                variable,
                name,
                declarationPrefix + variable.getNameAsString(),
                methodCall
            );
            if (sourceMultilineCall.isPresent()) {
                return sourceMultilineCall.orElseThrow();
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
        if (
            overWidth
            && initializer instanceof ArrayInitializerExpr arrayInitializerExpr
            && sourceSpansMultipleLines(arrayInitializerExpr)
        ) {
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
        boolean initializerStartsOnContinuationLine = initializerStartsOnContinuationLine(variable, methodCall);
        boolean chainSpansMultipleSourceLines = methodCallChainIsSourceMultiline.test(methodCall)
            || sourceShapePolicy.wasMultiline(methodCall);
        MethodCallChainSourcePlanner.InitializerChainShape chainShape = methodCallChainInitializerShape.apply(
            methodCall
        );
        if (
            !chainShape.canUseCompactObjectCreationInitializer(
                initializerStartsOnContinuationLine,
                chainSpansMultipleSourceLines,
                sourceShapePolicy.methodCallArgumentsSpanMultipleLines(methodCall)
            )
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
        boolean initializerStartsOnContinuationLine = initializerStartsOnContinuationLine(variable, methodCall);
        boolean chainSpansMultipleSourceLines = methodCallChainIsSourceMultiline.test(methodCall)
            || sourceShapePolicy.wasMultiline(methodCall);
        MethodCallChainSourcePlanner.InitializerChainShape chainShape = methodCallChainInitializerShape.apply(
            methodCall
        );
        if (
            !methodCallChainRootIsObjectCreation.test(methodCall)
            || !(
                chainShape.canUseCompactObjectCreationInitializer(
                    initializerStartsOnContinuationLine,
                    chainSpansMultipleSourceLines,
                    sourceShapePolicy.methodCallArgumentsSpanMultipleLines(methodCall)
                )
                || sourceFirstLineKeepsChainAfterRoot(methodCall)
            )
            || (!methodCall.getArguments().isEmpty()
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

    private boolean sourceFirstLineKeepsChainAfterRoot(MethodCallExpr methodCall) {
        return compactMethodCallChainRoot.apply(methodCall)
                .flatMap(rootFirstLine -> rawSource.rawWithoutOwnComment(methodCall)
                            .lines()
                            .findFirst()
                            .map(String::strip)
                            .filter(firstSourceLine -> firstSourceLine.startsWith(rootFirstLine))
                            .filter(firstSourceLine -> firstSourceLine.length() > rootFirstLine.length())
                )
                .isPresent();
    }

    private boolean initializerStartsOnContinuationLine(VariableDeclarator variable, Expression initializer) {
        return variable.getName()
                .getRange()
                .flatMap(nameRange -> initializer.getRange().map(
                        initializerRange -> initializerRange.begin.line > nameRange.end.line
                ))
                .orElse(false);
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
     * continuation line where option-3 below would place it, so short generics are no longer shattered into
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
        if (layoutWidth.currentIndented(flatName + " = " + prefix + " {") <= options.lineWidth()) {
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
            String name,
            String flatName,
            ObjectCreationExpr objectCreation
    ) {
        if (objectCreation.getAnonymousClassBody().isPresent()) {
            return Optional.empty();
        }
        if (!objectCreation.getAllContainedComments().isEmpty()) {
            return variableWithCommentedObjectCreation(name, flatName, objectCreation);
        }
        Optional<Doc> typeArguments = variableWithBrokenObjectCreationTypeArguments(name, flatName, objectCreation);
        if (typeArguments.isPresent()) {
            return typeArguments;
        }
        return variableWithBrokenObjectCreationArguments(name, flatName, objectCreation);
    }

    /**
     * Keeps {@code name = new Type(} together for commented constructor calls when that first line still fits, while
     * leaving the nested comment placement to the normal object-creation renderer.
     */
    private Optional<Doc> variableWithCommentedObjectCreation(
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
        if (layoutWidth.currentIndented(flatName + " = " + prefix + "(") > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(Doc.text(name + " = "), expression.apply(objectCreation)));
    }

    /**
     * Breaks constructor arguments when the assignment and constructor prefix still fit, so only the argument list moves
     * to hard lines.
     */
    private Optional<Doc> variableWithBrokenObjectCreationArguments(
            String name,
            String flatName,
            ObjectCreationExpr objectCreation
    ) {
        if (objectCreation.getArguments().isEmpty()) {
            return Optional.empty();
        }
        String prefix = objectCreationPrefix.apply(objectCreation);
        if (layoutWidth.currentIndented(flatName + " = " + prefix + "(") > options.lineWidth()) {
            return Optional.empty();
        }
        if (smallConstructorCanStayFlat(flatName, objectCreation)) {
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
        if (
            argument instanceof BinaryExpr binaryExpr
            && (sourceShapePolicy.wasMultiline(binaryExpr)
                || layoutWidth.continuationStatement(compact.apply(binaryExpr)) > options.lineWidth())
        ) {
            if (binaryExpressionHasLineComments.test(binaryExpr)) {
                return binaryExpressionLinesWithComments.apply(binaryExpr);
            }
            return binaryExpressionLines.apply(binaryExpr, true);
        }
        return expression.apply(argument);
    }

    private boolean smallConstructorCanStayFlat(String flatName, ObjectCreationExpr objectCreation) {
        return objectCreation.getArguments().size() <= 3
            && layoutWidth.currentIndented(
                flatName + " = " + compact.apply(objectCreation) + ";"
            ) <= options.lineWidth();
    }

    /**
     * Breaks constructor type arguments for {@code new SomeVeryLongType<...>()} only when there are no constructor
     * arguments or scopes that need a different object-creation layout.
     */
    private Optional<Doc> variableWithBrokenObjectCreationTypeArguments(
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
            || layoutWidth.currentIndented(
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
        boolean openerFits = layoutWidth.currentIndented(firstLine) <= options.lineWidth();
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
     *   <li>An object-creation root ({@code new X(ctorArgs).method(...)}) — the original #48 case.</li>
     *   <li>A simple attachable name/type-like or field-access root ({@code Collections.newSetFromMap(...)},
     *       {@code this.foo(...)}) — the deferred case where {@code shouldForceSourceMultilineInitializerChain}/
     *       {@code shouldForceWideInitializerChain} would otherwise collapse a selector-broken source into the
     *       break-after-{@code =} shape that the re-format then re-attaches.</li>
     * </ul>
     * When it holds, the argument-break shape is chosen on every pass, so the layout is idempotent. Multi-segment chains,
     * method-call roots (which carry their own attach logic), and openers that do not fit are intentionally left to the
     * source-shape gates and the forced-chain fallbacks.
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
    private boolean argumentBreakOpenerFits(MethodCallExpr methodCall, String flatName) {
        return layoutWidth.currentIndented(
            flatName + " = " + methodCallPrefix.apply(methodCall) + "("
        ) <= options.lineWidth();
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
        if (layoutWidth.currentIndented(firstLine) > options.lineWidth()) {
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
     * Keeps a source-multiline direct call opener attached to {@code =} when that opener still fits.
     */
    private Optional<Doc> variableWithSourceMultilineMethodCallInitializer(
            VariableDeclarator variable,
            String name,
            String flatName,
            MethodCallExpr methodCall
    ) {
        MethodCallChainSourcePlanner.InitializerChainShape chainShape = methodCallChainInitializerShape.apply(
            methodCall
        );
        if (
            methodCall.getArguments().isEmpty()
            || !sourceShapePolicy.wasMultiline(methodCall)
            || !chainShape.canUseDirectSourceMultilineInitializer()
            || sourceShapePolicy.expressionLambdaStartsOnSelectorLine(methodCall)
            || methodCall.getScope().filter(sourceShapePolicy::wasMultiline).isPresent()
            || (methodCallChainRootIsObjectCreation.test(methodCall)
                && layoutWidth.variableInitializer(variable, flatName + " = " + compact.apply(methodCall) + ";")
                    > options.lineWidth())
        ) {
            return Optional.empty();
        }
        String callPrefix = methodCallPrefix.apply(methodCall);
        if (layoutWidth.variableInitializer(variable, flatName + " = " + callPrefix + "(") > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(Doc.text(name + " = "), this.methodCall.apply(methodCall)));
    }

    private Optional<Doc> variableWithForcedMethodCallChain(
            VariableDeclarator variable,
            String name,
            String flatName,
            MethodCallExpr methodCall
    ) {
        return forcedMethodCallChain(variable, methodCall, flatName).map(chain -> variableWithMethodCallChain(
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
            || scope.filter(sourceShapePolicy::wasMultiline).isPresent()
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
     * Decides whether a method-call chain can start after {@code =} or must move entirely to an indented continuation.
     */
    private Doc variableWithMethodCallChain(
            String name,
            String flatName,
            MethodCallExpr methodCall,
            String firstLine,
            Doc chain
    ) {
        if (
            methodCallChainRootIsObjectCreation.test(methodCall)
            && layoutWidth.blockStatement(flatName + " = " + firstLine + ";") > options.lineWidth()
        ) {
            return Doc.concat(Doc.text(name + " ="), Doc.indent(Doc.concat(Doc.HARD_LINE, chain)));
        }
        if (layoutWidth.currentIndented(flatName + " = " + firstLine) > options.lineWidth()) {
            return Doc.concat(Doc.text(name + " ="), Doc.indent(Doc.concat(Doc.HARD_LINE, chain)));
        }
        return Doc.concat(Doc.text(name + " = "), chain);
    }

    private String mixedFieldMethodCallFirstLine(MethodCallExpr methodCall) {
        return mixedFieldMethodCallRoot.apply(methodCall)
                .filter(root -> !(root instanceof ObjectCreationExpr))
                .map(compact)
                .orElseGet(() -> methodCallChainFirstLine.apply(methodCall));
    }

    /**
     * Chooses the conditional initializer shape by trying the least disruptive break first, then falling back to a
     * fully indented conditional when the condition line itself is too wide.
     */
    private Doc conditionalInitializer(String name, String flatName, ConditionalExpr initializer) {
        String conditionLine = flatName + " = " + compact.apply(initializer.getCondition());
        String compactInitializer = compact.apply(initializer);
        if (
            sourceShapePolicy.wasMultiline(initializer)
            && layoutWidth.blockStatement(conditionLine + ";") <= options.lineWidth()
        ) {
            return Doc.concat(Doc.text(name + " = "), brokenConditionalExpression.apply(initializer));
        }
        if (layoutWidth.continuationStatement(compactInitializer + ";") <= options.lineWidth()) {
            return Doc.concat(
                Doc.text(name + " ="),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(compactInitializer)))
            );
        }
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
        return Doc.concat(
            Doc.text(name + " ="),
            Doc.indent(Doc.concat(Doc.HARD_LINE, brokenConditionalExpression.apply(initializer)))
        );
    }

    private boolean parenthesizedConditionalConditionOpenerFits(String flatName, ConditionalExpr initializer) {
        return initializer.getCondition() instanceof EnclosedExpr
            && layoutWidth.currentIndented(flatName + " = (") <= options.lineWidth();
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
        Doc body = forcedMethodCallChain
                .apply(methodCall, firstLineWidth(variable, flatName + " = " + lambdaPrefix + " "))
                .orElseGet(() -> expression.apply(methodCall));
        if (
            layoutWidth.currentIndented(flatName + " = " + lambdaPrefix + " " + bodyFirstLine)
                <= options.lineWidth()
        ) {
            return Optional.of(Doc.concat(Doc.text(name + " = " + lambdaPrefix + " "), body));
        }
        if (layoutWidth.currentIndented(flatName + " = " + lambdaPrefix) <= options.lineWidth()) {
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
        return forcedMethodCallChain.apply(methodCall, firstLineWidth(variable, flatName + " = "));
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
            || layoutWidth.currentIndented(flatName + " = (") > options.lineWidth()
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
            || layoutWidth.currentIndented(flatName + " = " + parameters + " -> {") > options.lineWidth()
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
        return castTypeCanBreak(type)
            && layoutWidth.currentIndented(flatName + " = (" + compactTypeLike.apply(type) + ")") > options.lineWidth();
    }

    private boolean castTypeOpenerFitsOnEqualsLine(String flatName, Type type) {
        return layoutWidth.currentIndented(flatName + " = " + castTypeOpener(type)) <= options.lineWidth();
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
            return forcedMethodCallChain
                    .apply(methodCall, text -> layoutWidth.variableInitializer(variable, text))
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

    private boolean sourceSpansMultipleLines(Expression expression) {
        return expression.getRange()
                .map(range -> range.begin.line < range.end.line)
                .orElse(false);
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
