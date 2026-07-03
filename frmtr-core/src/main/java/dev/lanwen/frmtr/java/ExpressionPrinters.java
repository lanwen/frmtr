package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.Type;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Wires expression-formatting helpers behind the expression rule envelope.
 *
 * <p>This composer owns the construction order for expression dispatch, expression-specific helper cycles, and the
 * source-sensitive callback graph used by method calls, lambdas, object creation, binaries, casts, assignments, and
 * returns. The boundary exists so the top-level printer can coordinate expression, declaration, and statement groups
 * without carrying every expression helper as a field.
 *
 * <p>Callers still decide when an expression context is needed, when statement or declaration rendering should be
 * entered, and how shared type rendering is constructed. This composer leaves those surrounding grammar choices to the
 * declaration and statement composers and exposes only the existing expression helper callbacks they already used.
 */
final class ExpressionPrinters {

    private final JavaFormatContext context;

    private final BinaryExpressionPrinter binaries;

    private final AnnotationExpressionPrinter annotationExpressions;

    private final ConditionalExpressionPrinter conditionals;

    private final LambdaExpressionPrinter lambdas;

    private final ArrayExpressionPrinter arrays;

    private final ObjectCreationPrinter objectCreations;

    private final TextBlockPrinter textBlocks;

    private final CastExpressionPrinter casts;

    private final ClassExpressionPrinter classExpressions;

    private final EnclosedExpressionPrinter enclosedExpressions;

    private final InstanceOfExpressionPrinter instanceOfExpressions;

    private final FieldAccessPrinter fieldAccesses;

    private final MethodReferencePrinter methodReferences;

    private final MethodCallPrinter methodCalls;

    private final UnaryExpressionPrinter unaries;

    private final EnclosedSuffixDispatcher enclosedSuffixes;

    private final AssignmentExpressionPrinter assignments;

    private final ReturnExpressionPrinter returnExpressions;

    private final ExpressionRuleEnvelope expressionRules;

    ExpressionPrinters(
            JavaFormatContext context,
            TypePrinter types,
            JavaFormatRule<Statement> statementRenderer,
            JavaFormatRule<BlockStmt> blockRenderer,
            JavaFormatRule<BlockStmt> methodChainLambdaBlockRenderer,
            JavaFormatRule<BodyDeclaration<?>> bodyRenderer,
            JavaFormatRule<SwitchExpr> switchExpressionRenderer,
            Function<Doc, String> commentText
    ) {
        this.context = context;
        FormatterOptions options = context.options;
        CommentTracker comments = context.comments;
        JavaCommentPlacementPolicy commentPlacementPolicy = context.commentPlacementPolicy;
        RawSource rawSource = context.rawSource;
        CompactSourceText compactSource = context.compactSource;
        this.binaries = new BinaryExpressionPrinter(
            comments,
            commentPlacementPolicy,
            options,
            (expression, layout) -> expression(expression),
            this::brokenMethodCall,
            this::brokenMethodCallWithClosingLine,
            this::forcedMethodCallChain,
            context.sourceShapePolicy,
            compactSource::compact,
            compactSource::compactWithoutOwnComment,
            context.layoutWidth::continuationStatement,
            context.layoutWidth::blockStatement
        );
        this.annotationExpressions = new AnnotationExpressionPrinter(
            comments,
            commentPlacementPolicy,
            options,
            (expression, layout) -> expression(expression),
            binaries::nestedLines,
            compactSource::compact,
            context.layoutWidth::currentIndented
        );
        compactSource.useAnnotationFlatText(annotationExpressions::annotationFlatText);
        this.conditionals = new ConditionalExpressionPrinter(
            context,
            this::expression,
            this::expressionWithoutOwnComment,
            binaries::lines,
            binaries::nestedLines,
            binaries::expressionHasParenthesizedNestedBinary
        );
        this.lambdas = new LambdaExpressionPrinter(
            comments,
            commentPlacementPolicy,
            rawSource,
            context.sourceShapePolicy,
            context.sourceText,
            context.objectCreationLayoutPolicy,
            options,
            context.layoutWidth,
            (expression, layout) -> expression(expression),
            this::brokenObjectCreation,
            statementRenderer,
            blockRenderer,
            methodChainLambdaBlockRenderer,
            binaries::nestedLines,
            this::brokenMethodCall,
            this::packedExpressionLambdaMethodCallChainBody,
            this::huggedLambdaBodyChain,
            this::methodCallArgumentList,
            compactSource::compact,
            compactSource::compactWithoutOwnComment,
            compactSource::compactJoin,
            context.layoutWidth::currentIndented,
            context.layoutWidth::blockStatement,
            CommentIndex::startsBefore,
            CommentIndex::startsOnSameLine
        );
        this.casts = new CastExpressionPrinter(
            options,
            (expression, layout) -> expression(expression),
            compactSource::compactTypeLike,
            compactSource::compact,
            types::typeBody,
            context.layoutWidth::currentIndented,
            context.layoutWidth::continuationStatement
        );
        this.classExpressions = new ClassExpressionPrinter(compactSource::compactTypeLike);
        this.enclosedExpressions = new EnclosedExpressionPrinter(
            options,
            this::expression,
            binaries::lines,
            binaries::hasLineComments,
            binaries::linesWithComments,
            compactSource::compact,
            context.layoutWidth::currentIndented,
            context.layoutWidth::continuationStatement,
            casts::nestedCastDepth,
            lambdas::parenthesizedLambdaBreak,
            conditionals::conditionalExpression
        );
        this.unaries = new UnaryExpressionPrinter(
            compactSource::compact,
            enclosedExpressions::parenthesizedBreak
        );
        this.objectCreations = new ObjectCreationPrinter(
            context,
            types,
            (expression, layout) -> expression(expression),
            this::brokenArgument,
            lambdas::huggableBlockLambdaArguments,
            bodyRenderer,
            compactSource::compact,
            compactSource::compactJoin,
            compactSource::compactTypeLike,
            compactSource::compactTypeLikeWithoutOwnComment,
            commentText
        );
        this.textBlocks = new TextBlockPrinter(context.rawSource);
        this.instanceOfExpressions = new InstanceOfExpressionPrinter(
            options,
            (expression, layout) -> expression(expression),
            compactSource::compact,
            compactSource::compactTypeLike,
            context.layoutWidth::currentIndented
        );
        this.fieldAccesses = new FieldAccessPrinter(comments, (expression, layout) -> expression(expression));
        this.methodReferences = new MethodReferencePrinter(
            options,
            compactSource::compact,
            types::compactJoinTypeLike,
            enclosedExpressions::brokenEnclosedForSuffix,
            context.layoutWidth::blockStatement
        );
        this.methodCalls = new MethodCallPrinter(
            context,
            types,
            (expression, layout) -> expression(expression),
            enclosedExpressions::brokenEnclosedForSuffix,
            objectCreations::brokenObjectCreation,
            objectCreations::objectCreationWithSuffix,
            objectCreations::objectCreationPrefix,
            lambdas::huggableBlockLambdaArguments,
            lambdas::huggableMethodChainBlockLambdaArguments,
            lambdas::huggableBlockLambdaFirstLine,
            lambdas::commentedExpressionLambdaArgument,
            lambdas::huggableMethodCallExpressionLambdaArguments,
            lambdas::huggableExpressionLambdaArgumentPlan,
            lambdas::huggedGapCommentedLambdaBody,
            lambdas::lambdaParameters,
            textBlocks::renderUnformattedTextBlock,
            this::brokenArgument
        );
        this.arrays = new ArrayExpressionPrinter(
            comments,
            commentPlacementPolicy,
            options,
            (expression, layout) -> expression(expression),
            enclosedExpressions::brokenEnclosedForSuffix,
            (methodCall, tail) -> expressionWithTail(methodCall, tail),
            objectCreations::objectCreationWithSuffix,
            compactSource::compactTypeLike,
            compactSource::compact,
            context.layoutWidth::currentIndented
        );
        this.enclosedSuffixes = new EnclosedSuffixDispatcher(methodCalls, methodReferences);
        this.assignments = new AssignmentExpressionPrinter(
            options,
            comments,
            commentPlacementPolicy,
            this::expression,
            this::expressionWithoutOwnComment,
            this::expressionWithTail,
            compactSource::compact,
            context.layoutWidth::blockStatement,
            enclosedSuffixes::suffixedEnclosedExpression,
            binaries::shouldKeepCastDivisionContinuationFlat,
            binaries::hasLineComments,
            binaries::linesWithComments,
            binaries::lines,
            objectCreations::brokenObjectCreation,
            methodCalls::assignmentWithBrokenMethodCallArguments,
            methodCalls::assignmentWithBrokenMethodCallArgumentsAndSemicolon,
            conditionals::assignmentWithConditionalValue
        );
        ExpressionDispatcher expressionDispatcher = new ExpressionDispatcher(
            (expression, layout) -> assignments.assignment(expression),
            (expression, layout) -> arrays.arrayAccess(expression),
            (expression, layout) -> arrays.arrayCreation(expression),
            (expression, layout) -> arrays.arrayInitializer(expression),
            (expression, layout) -> annotationExpressions.annotation(expression),
            (expression, layout) -> binaries.binaryExpression(expression),
            (expression, layout) -> casts.castExpression(expression),
            (expression, layout) -> classExpressions.classExpression(expression),
            (expression, layout) -> conditionals.conditionalExpression(expression),
            (expression, layout) -> enclosedExpressions.enclosedExpression(expression),
            (expression, layout) -> fieldAccesses.fieldAccess(expression),
            (expression, layout) -> instanceOfExpressions.instanceOfExpression(expression),
            (expression, layout) -> lambdas.lambdaExpression(expression),
            (expression, layout) -> methodCalls.methodCall(expression, layout),
            (expression, layout) -> methodReferences.methodReference(expression),
            (expression, layout) -> objectCreations.objectCreation(expression),
            switchExpressionRenderer,
            (expression, layout) -> textBlocks.textBlockLiteral(expression),
            (expression, layout) -> unaries.unaryExpression(expression),
            compactSource
        );
        this.expressionRules = new ExpressionRuleEnvelope(expressionDispatcher::expressionContent);
        this.returnExpressions = new ReturnExpressionPrinter(
            options,
            context.layoutWidth,
            context.objectCreationLayoutPolicy,
            context.sourceShapePolicy,
            this::expression,
            this::expressionWithTail,
            lambdas::brokenExpressionLambda,
            compactSource::compact,
            methodCalls::sourceMultilineExpressionLambda,
            methodCalls::sourceMultilineArguments,
            methodCalls::compactRootWithBrokenFinalChainSegment,
            methodCalls::forcedMethodCallChain,
            methodCalls::forcedMethodCallChain,
            methodCalls::brokenMethodCall,
            methodCalls::brokenMethodCallWithClosingLine,
            methodCalls::methodCallPrefix,
            methodCalls::methodCallChainIsSourceMultiline,
            methodCalls::methodCallChainHasFinalTrailingLineComment,
            methodCalls::hasSourceMultilineExpressionLambdaBody,
            objectCreations::brokenObjectCreation,
            objectCreations::objectCreationWithSuffix,
            conditionals::conditionalExpression,
            binaries::lines,
            enclosedExpressions::parenthesizedBreak,
            comments::trailingInitializerCommentsBeforeSemicolon,
            (semicolonOwner, value) -> {
                List<JavaCommentTrivia> trailing = commentPlacementPolicy
                        .trailingInitializerCommentsBeforeSemicolon(semicolonOwner, value);
                return !trailing.isEmpty() && trailing.stream().allMatch(JavaCommentTrivia::isBlock);
            },
            (semicolonOwner, value) -> commentPlacementPolicy
                    .trailingInitializerCommentsBeforeSemicolon(semicolonOwner, value)
                    .stream()
                    // One leading space joins each inline trailing comment to the value (" /* note */"); measure the
                    // comment through the non-claiming renderer so width measurement never consumes the comment.
                    .mapToInt(trivia -> 1 + inlineCommentWidth(JavaFormatter.commentDoc(trivia)))
                    .sum(),
            binaries::hasLineComments,
            binaries::linesWithComments,
            binaries::flatLineWithComments,
            binaries::flatLineWithCommentsWidth
        );
    }

    Doc expression(Expression expression) {
        return expressionRules.expression(expression);
    }

    Doc expressionWithTail(Expression expression, ExpressionTail tail) {
        return expressionWithTail(expression, tail, LayoutWidth.LineBudget.CURRENT);
    }

    Doc expressionWithTail(
            Expression expression,
            ExpressionTail tail,
            LayoutWidth.LineBudget lineBudget
    ) {
        if (tail.isEmpty()) {
            return expression(expression);
        }
        if (expression instanceof MethodCallExpr methodCall) {
            return Doc.label(
                "java.expression:" + expression.getClass().getSimpleName(),
                methodCalls.methodCallWithTail(methodCall, tail, lineBudget)
            );
        }
        if (expression instanceof ObjectCreationExpr objectCreation) {
            return objectCreations.objectCreationWithSuffix(objectCreation, tail.text());
        }
        return tail.appendTo(expression(expression));
    }

    Doc forcedMethodCallWithTail(
            MethodCallExpr expression,
            ExpressionTail tail,
            LayoutWidth.LineBudget lineBudget
    ) {
        return methodCalls.forcedMethodCallWithTail(expression, tail, lineBudget);
    }

    Doc expressionWithoutOwnComment(Expression expression) {
        return expressionRules.expressionWithoutOwnComment(expression);
    }

    Doc annotation(AnnotationExpr annotation) {
        return annotationExpressions.annotation(annotation);
    }

    Doc annotationPreservingSourceBreaks(AnnotationExpr annotation) {
        return annotationExpressions.annotationPreservingSourceBreaks(annotation);
    }

    String annotationFlatText(AnnotationExpr annotation) {
        return annotationExpressions.annotationFlatText(annotation);
    }

    Doc assignmentStatement(AssignExpr expression) {
        return assignments.assignmentStatement(expression);
    }

    Doc returnStatement(Expression expression, LayoutContext layout) {
        return returnExpressions.returnStatement(expression, layout);
    }

    Doc objectCreationWithSuffix(ObjectCreationExpr expression, String suffix) {
        return objectCreations.objectCreationWithSuffix(expression, suffix);
    }

    Doc brokenObjectCreation(ObjectCreationExpr expression) {
        return objectCreations.brokenObjectCreation(expression);
    }

    String objectCreationPrefix(ObjectCreationExpr expression) {
        return objectCreations.objectCreationPrefix(expression);
    }

    Doc brokenMethodCall(MethodCallExpr expression) {
        return methodCalls.brokenMethodCall(expression);
    }

    Doc brokenMethodCallWithClosingLine(MethodCallExpr expression, String closingLine) {
        return methodCalls.brokenMethodCallWithClosingLine(expression, closingLine);
    }

    Optional<Doc> packedExpressionLambdaMethodCallChainBody(String firstLine, MethodCallExpr expression) {
        return methodCalls.packedExpressionLambdaMethodCallChainBody(firstLine, expression);
    }

    /**
     * Fans an over-width method-call chain that sits in an expression-lambda body onto dotted continuation lines while it
     * hugs the lambda header on the first line ({@code someCall(x -> assertThat(x)}\n{@code .extracting(...)}\n
     * {@code .containsOnly(...))}).
     *
     * <p>The chain is forced to break — {@link ExpressionLambdaArgumentLayout} already decided the flat chain overflows at
     * its real rendered column — so the chain printer lays the bare-call root on the header line and every
     * {@code .call(...)} on its own continuation line, keeping a single-simple-argument tail compact. The caller owns the
     * trailing call close, so the chain renders without a final terminator.
     *
     * <p>The chain shares its line with {@code firstLine} — the enclosing call opener plus the lambda header up to
     * {@code ->} — so it renders past that prefix, not at the statement's own column. That prefix is threaded as the
     * chain's {@link LayoutContext#leftEdgePrefix()} so every width gate the forced chain consults measures at the real
     * rendered column rather than column zero, matching how the return chain threads its {@code "return "} prefix
     * (LDM-2f #190/#236).
     */
    Optional<Doc> huggedLambdaBodyChain(String firstLine, MethodCallExpr expression) {
        return methodCalls.forcedMethodCallChain(
            expression,
            LayoutWidth.LineBudget.CURRENT,
            LayoutContext.root().withLeftEdgePrefix(firstLine + " ")
        );
    }

    Doc methodCall(MethodCallExpr expression) {
        return methodCalls.methodCall(expression, LayoutContext.root());
    }

    Doc methodCall(MethodCallExpr expression, LayoutContext layout) {
        return methodCalls.methodCall(expression, layout);
    }

    Doc methodCallArgumentList(NodeList<Expression> arguments, Doc line) {
        return methodCalls.methodCallArgumentList(arguments, line);
    }

    private Optional<Doc> brokenArgument(Expression expression) {
        if (expression instanceof BinaryExpr binaryExpr) {
            return Optional.of(binaries.nestedLines(binaryExpr, true));
        }
        if (expression instanceof ConditionalExpr conditionalExpr) {
            return Optional.of(conditionals.conditionalExpression(conditionalExpr, true));
        }
        return Optional.empty();
    }

    String methodCallPrefix(MethodCallExpr expression) {
        return methodCalls.methodCallPrefix(expression);
    }

    Optional<Doc> sourceMultilineMethodCallStatement(MethodCallExpr expression, ExpressionStmt statement) {
        return methodCalls.sourceMultilineMethodCallStatement(expression, statement);
    }

    Optional<Doc> mixedFieldMethodCallChain(MethodCallExpr expression) {
        return methodCalls.mixedFieldMethodCallChain(expression);
    }

    Optional<Expression> mixedFieldMethodCallRoot(MethodCallExpr expression) {
        return methodCalls.mixedFieldMethodCallRoot(expression);
    }

    Optional<Doc> forcedMethodCallChain(MethodCallExpr expression) {
        return methodCalls.forcedMethodCallChain(expression);
    }

    Optional<Doc> forcedMethodCallChain(MethodCallExpr expression, LayoutWidth.LineBudget lineBudget) {
        return methodCalls.forcedMethodCallChain(expression, lineBudget);
    }

    Optional<Doc> forcedMethodCallChain(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth
    ) {
        return methodCalls.forcedMethodCallChain(expression, firstLineWidth);
    }

    // LDM-2f (#190): the layout-carrying overload the initializer chain threads. The initializer supplies its assignment
    // prefix through {@code layout.withLeftEdgePrefix("NAME = ")} so the chain width gate can attribute it at the rendered
    // column and reach the object-creation dot-split tail. Other callers of the two-arg overload above pass no prefix and
    // stay byte-identical.
    Optional<Doc> forcedMethodCallChain(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout
    ) {
        return methodCalls.forcedMethodCallChain(expression, firstLineWidth, layout);
    }

    Optional<Doc> packedMethodCallChain(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth
    ) {
        return methodCalls.packedMethodCallChain(expression, firstLineWidth);
    }

    Optional<String> compactMethodCallChainRoot(MethodCallExpr expression) {
        return methodCalls.compactMethodCallChainRoot(expression);
    }

    boolean methodCallChainHasComments(MethodCallExpr expression) {
        return methodCalls.methodCallChainHasComments(expression);
    }

    boolean methodCallChainHasFinalTrailingLineComment(MethodCallExpr expression) {
        return methodCalls.methodCallChainHasFinalTrailingLineComment(expression);
    }

    boolean methodCallChainIsSourceMultiline(MethodCallExpr expression) {
        return methodCalls.methodCallChainIsSourceMultiline(expression);
    }

    MethodCallChainSourcePlanner.InitializerChainShape methodCallChainInitializerShape(MethodCallExpr expression) {
        return methodCalls.methodCallChainInitializerShape(expression);
    }

    boolean methodCallChainRootIsObjectCreation(MethodCallExpr expression) {
        return methodCalls.methodCallChainRootIsObjectCreation(expression);
    }

    boolean methodCallChainRootIsFieldAccess(MethodCallExpr expression) {
        return methodCalls.methodCallChainRootIsFieldAccess(expression);
    }

    String methodCallChainFirstLine(MethodCallExpr expression) {
        return methodCalls.methodCallChainFirstLine(expression);
    }

    boolean shouldPrintScopeAsDoc(Expression expression) {
        return methodCalls.shouldPrintScopeAsDoc(expression);
    }

    boolean binaryHasLineComments(BinaryExpr expression) {
        return binaries.hasLineComments(expression);
    }

    Doc binaryLinesWithComments(BinaryExpr expression) {
        return binaries.linesWithComments(expression);
    }

    Optional<Doc> binaryFlatLineWithComments(BinaryExpr expression) {
        return binaries.flatLineWithComments(expression);
    }

    int binaryFlatLineWithCommentsWidth(BinaryExpr expression) {
        return binaries.flatLineWithCommentsWidth(expression);
    }

    Doc binaryLines(Expression expression) {
        return binaries.lines(expression);
    }

    Doc binaryLines(Expression expression, boolean forceBreak) {
        return binaries.lines(expression, forceBreak);
    }

    Doc parenthesizedBreak(Expression expression, boolean forceBinaryBreak) {
        return enclosedExpressions.parenthesizedBreak(expression, forceBinaryBreak);
    }

    Doc binaryConditionLines(Expression expression, boolean forceBreak) {
        return binaries.conditionLines(expression, forceBreak);
    }

    boolean expressionHasParenthesizedNestedBinary(Expression expression) {
        return binaries.expressionHasParenthesizedNestedBinary(expression);
    }

    boolean shouldKeepCastDivisionContinuationFlat(BinaryExpr expression) {
        return binaries.shouldKeepCastDivisionContinuationFlat(expression);
    }

    Optional<Doc> suffixedEnclosedExpression(Expression expression, LayoutContext layout) {
        return enclosedSuffixes.suffixedEnclosedExpression(expression, layout);
    }

    Doc arrayAccessWithBrokenEnclosedName(ArrayAccessExpr expression) {
        return arrays.arrayAccessWithBrokenEnclosedName(expression);
    }

    boolean arrayCreationTypeBreaks(ArrayCreationExpr expression, ToIntFunction<String> widthAtContinuation) {
        return arrays.arrayCreationTypeBreaks(expression, widthAtContinuation);
    }

    String arrayCreationPrefix(ArrayCreationExpr expression) {
        return arrays.arrayCreationPrefix(expression);
    }

    Doc arrayInitializer(ArrayInitializerExpr expression, boolean forceBreak) {
        return arrays.arrayInitializer(expression, forceBreak);
    }

    String compactArrayInitializerWithSourceSpacing(ArrayInitializerExpr expression, String values) {
        return arrays.compactArrayInitializerWithSourceSpacing(expression, values);
    }

    Doc castType(Type type) {
        return casts.castType(type);
    }

    Doc conditionalExpression(ConditionalExpr expression, boolean forceBreak) {
        return conditionals.conditionalExpression(expression, forceBreak);
    }

    boolean shouldBreakBeforeConditionalInitializer(ConditionalExpr expression) {
        return conditionals.shouldBreakBeforeConditionalInitializer(expression);
    }

    Optional<Doc> huggableBlockLambdaArguments(String prefix, NodeList<Expression> arguments) {
        return lambdas.huggableBlockLambdaArguments(prefix, arguments);
    }

    Optional<Doc> huggableMethodChainBlockLambdaArguments(String prefix, NodeList<Expression> arguments) {
        return lambdas.huggableMethodChainBlockLambdaArguments(prefix, arguments);
    }

    Optional<Doc> huggableBlockLambdaArguments(
            String prefix,
            NodeList<Expression> arguments,
            ToIntFunction<String> firstLineWidth
    ) {
        return lambdas.huggableBlockLambdaArguments(prefix, arguments, firstLineWidth);
    }

    String lambdaParameters(LambdaExpr expression) {
        return lambdas.lambdaParameters(expression);
    }

    boolean lambdaParametersShouldBreak(LambdaExpr expression, String flatParameters) {
        return lambdas.lambdaParametersShouldBreak(expression, flatParameters);
    }

    Doc lambdaExpression(LambdaExpr expression) {
        return lambdas.lambdaExpression(expression);
    }

    /**
     * Measures the rendered width of a single-line comment doc for the inline trailing-comment fit check, treating any
     * multi-line comment doc as unbounded so a comment that cannot stay on one line never lets the flat shape be picked.
     */
    private static int inlineCommentWidth(Doc doc) {
        return switch (doc) {
            case Doc.Text text -> text.value().length();
            case Doc.Concat concat -> concat.docs().stream().mapToInt(ExpressionPrinters::inlineCommentWidth).sum();
            default -> Integer.MAX_VALUE / 2;
        };
    }
}
