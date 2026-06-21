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

    private final SourceShape sourceShape;

    private final SourceShapePolicy sourceShapePolicy;

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

    private final Predicate<ArrayCreationExpr> arrayCreationTypeBreaks;

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
            CommentTracker comments,
            JavaCommentPlacementPolicy commentPlacement,
            SourceShape sourceShape,
            SourceShapePolicy sourceShapePolicy,
            FormatterOptions options,
            LayoutWidth layoutWidth,
            Function<Node, String> compactTypeLike,
            Function<Node, String> compact,
            Function<Node, String> compactWithoutOwnComment,
            Function<List<? extends Node>, String> compactJoin,
            Function<Expression, Doc> expression,
            Function<Expression, Doc> expressionWithoutOwnComment,
            Predicate<BinaryExpr> binaryExpressionHasLineComments,
            Function<BinaryExpr, Doc> binaryExpressionLinesWithComments,
            BiFunction<Expression, Boolean, Optional<Doc>> suffixedEnclosedExpression,
            Function<ArrayAccessExpr, Doc> arrayAccessWithBrokenEnclosedName,
            Predicate<BinaryExpr> shouldKeepCastDivisionContinuationFlat,
            BiFunction<Expression, Boolean, Doc> binaryExpressionLines,
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
            Predicate<ArrayCreationExpr> arrayCreationTypeBreaks,
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
        this.comments = comments;
        this.commentPlacement = commentPlacement;
        this.sourceShape = sourceShape;
        this.sourceShapePolicy = sourceShapePolicy;
        this.options = options;
        this.layoutWidth = layoutWidth;
        this.compactTypeLike = compactTypeLike;
        this.compact = compact;
        this.conditionalProjection = new ConditionalExpressionLineProjection(compact::apply);
        this.compactWithoutOwnComment = compactWithoutOwnComment;
        this.compactJoin = compactJoin;
        this.expression = expression;
        this.expressionWithoutOwnComment = expressionWithoutOwnComment;
        this.binaryExpressionHasLineComments = binaryExpressionHasLineComments;
        this.binaryExpressionLinesWithComments = binaryExpressionLinesWithComments;
        this.suffixedEnclosedExpression = suffixedEnclosedExpression;
        this.arrayAccessWithBrokenEnclosedName = arrayAccessWithBrokenEnclosedName;
        this.shouldKeepCastDivisionContinuationFlat = shouldKeepCastDivisionContinuationFlat;
        this.binaryExpressionLines = binaryExpressionLines;
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
        Doc trailingLineComment = comments.trailingLineComment(variable);
        Doc declaration = variable.getInitializer()
                .map(initializer -> variableWithInitializer(variable, initializer, declarationPrefix))
                .orElseGet(() -> Doc.text(variableName(variable)));
        return Doc.concat(declaration, Doc.text(";"), trailingLineComment(trailingLineComment));
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
        String rawOwner = sourceShapePolicy.rawText(owner);
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
     * Chooses the initializer shape while preserving comments around {@code =}, source-leading initializer comments,
     * and construct-specific break rules before falling back to the shared expression renderer.
     */
    Doc variableWithInitializer(
            VariableDeclarator variable,
            Expression initializer,
            String declarationPrefix
    ) {
        String flat = declarationPrefix + variable.getNameAsString() + " = " + compact.apply(initializer) + ";";
        String name = variableName(variable);
        Optional<Doc> preEqualsBlockComment = preEqualsBlockComment(variable, initializer);
        if (preEqualsBlockComment.isPresent()) {
            String commentedName = name + " " + commentText(preEqualsBlockComment.orElseThrow());
            String commentedFlat = declarationPrefix
                + commentedName
                + " = "
                + compactWithoutOwnComment.apply(initializer)
                + ";";
            if (layoutWidth.blockStatement(commentedFlat) > options.lineWidth()) {
                return Doc.concat(
                    Doc.text(commentedName + " ="),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, expressionWithoutOwnComment.apply(initializer)))
                );
            }
            return Doc.concat(Doc.text(commentedName + " = "), expressionWithoutOwnComment.apply(initializer));
        }
        Optional<String> postEqualsBlockComment = postEqualsBlockComment(variable, initializer);
        if (postEqualsBlockComment.isPresent()) {
            String commentedFlat = declarationPrefix
                + name
                + " = "
                + postEqualsBlockComment.orElseThrow()
                + " "
                + compactWithoutOwnComment.apply(initializer)
                + ";";
            if (layoutWidth.blockStatement(commentedFlat) > options.lineWidth()) {
                return Doc.concat(
                    Doc.text(name + " ="),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, expression.apply(initializer)))
                );
            }
        }
        Optional<Doc> leadingInitializerComments = leadingInitializerComments(variable, initializer);
        if (leadingInitializerComments.isPresent()) {
            return Doc.concat(
                Doc.text(name + " ="),
                Doc.indent(
                    Doc.concat(
                        Doc.HARD_LINE,
                        leadingInitializerComments.orElseThrow(),
                        Doc.HARD_LINE,
                        expression.apply(initializer)
                    )
                )
            );
        }
        if (initializer instanceof BinaryExpr binaryExpr && binaryExpressionHasLineComments.test(binaryExpr)) {
            if (binaryInitializerCanKeepFirstOperandWithEquals(variable, declarationPrefix, binaryExpr)) {
                return Doc.concat(
                    Doc.text(name + " = "),
                    Doc.indent(binaryExpressionLinesWithComments.apply(binaryExpr))
                );
            }
            return Doc.concat(
                Doc.text(name + " ="),
                Doc.indent(Doc.concat(Doc.HARD_LINE, binaryExpressionLinesWithComments.apply(binaryExpr)))
            );
        }
        if (
            initializer instanceof ConditionalExpr conditionalExpr
            && conditionalInitializerLineOverflows(variable, declarationPrefix, conditionalExpr)
            && !initializerHasOwnBreak(initializer)
        ) {
            return conditionalInitializer(name, declarationPrefix + variable.getNameAsString(), conditionalExpr);
        }
        if (
            initializer instanceof MethodCallExpr methodCall
            && methodCallHasBlockLambdaArgument(methodCall)
            && !methodCallHasLeadingCommentedBlockLambdaArgument(methodCall)
        ) {
            Optional<Doc> receiverBreakCall = variableWithReceiverBreakBeforeOverWidthHuggableBlockLambdaArguments(
                variable,
                name,
                declarationPrefix + variable.getNameAsString(),
                methodCall
            );
            if (receiverBreakCall.isPresent()) {
                return receiverBreakCall.orElseThrow();
            }
        }
        if (
            initializer instanceof MethodCallExpr methodCall
            && methodCallHasLeadingCommentedBlockLambdaArgument(methodCall)
        ) {
            Optional<Doc> brokenCall = variableWithLeadingCommentedBlockLambdaMethodCall(
                name,
                declarationPrefix + variable.getNameAsString(),
                methodCall
            );
            if (brokenCall.isPresent()) {
                return brokenCall.orElseThrow();
            }
        }
        if (layoutWidth.blockStatement(flat) > options.lineWidth()) {
            Optional<Doc> suffixedEnclosedInitializer = suffixedEnclosedExpression.apply(initializer, true);
            if (suffixedEnclosedInitializer.isPresent()) {
                return Doc.concat(Doc.text(name + " = "), suffixedEnclosedInitializer.orElseThrow());
            }
            if (initializer instanceof ConditionalExpr conditionalExpr && !initializerHasOwnBreak(initializer)) {
                return conditionalInitializer(name, declarationPrefix + variable.getNameAsString(), conditionalExpr);
            }
            if (
                initializer instanceof ArrayAccessExpr arrayAccessExpr
                && arrayAccessExpr.getName().isEnclosedExpr()
            ) {
                return Doc.concat(Doc.text(name + " = "), arrayAccessWithBrokenEnclosedName.apply(arrayAccessExpr));
            }
            if (initializer instanceof ArrayCreationExpr arrayCreationExpr) {
                Optional<Doc> arrayCreation = variableWithBrokenArrayCreation(
                    name,
                    declarationPrefix + variable.getNameAsString(),
                    arrayCreationExpr
                );
                if (arrayCreation.isPresent()) {
                    return arrayCreation.orElseThrow();
                }
            }
            if (initializer instanceof ObjectCreationExpr objectCreationExpr) {
                Optional<Doc> objectCreation = variableWithBrokenObjectCreation(
                    name,
                    declarationPrefix + variable.getNameAsString(),
                    objectCreationExpr
                );
                if (objectCreation.isPresent()) {
                    return objectCreation.orElseThrow();
                }
            }
            if (
                initializer instanceof MethodCallExpr methodCall
                && methodCallChainInitializerShape.apply(methodCall).shouldForceSourceMultilineInitializerChain()
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
            if (initializer instanceof BinaryExpr binaryExpr) {
                if (binaryInitializerCanKeepFirstOperandWithEquals(variable, declarationPrefix, binaryExpr)) {
                    return Doc.concat(
                        Doc.text(name + " = "),
                        Doc.indent(binaryExpressionLines.apply(initializer, true))
                    );
                }
                if (shouldKeepCastDivisionContinuationFlat.test(binaryExpr)) {
                    return Doc.concat(
                        Doc.text(name + " ="),
                        Doc.indent(Doc.concat(Doc.HARD_LINE, expression.apply(binaryExpr)))
                    );
                }
                return Doc.concat(
                    Doc.text(name + " ="),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, binaryExpressionLines.apply(initializer, true)))
                );
            }
        }
        if (
            initializer instanceof MethodCallExpr methodCall
            && methodCall.getScope().filter(TextBlockLiteralExpr.class::isInstance).isPresent()
        ) {
            return Doc.concat(Doc.text(name + " = "), this.methodCall.apply(methodCall));
        }
        if (
            layoutWidth.variableInitializer(variable, flat) > options.lineWidth()
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
            layoutWidth.variableInitializer(variable, flat) > options.lineWidth()
            && initializer instanceof MethodCallExpr methodCall
            && !initializerHasOwnBreak(initializer)
        ) {
            if (methodCallChainRootIsObjectCreation.test(methodCall)) {
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
            if (initializerChainShape.shouldForceWideInitializerChain()) {
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
            layoutWidth.variableInitializer(variable, flat) > options.lineWidth()
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
            layoutWidth.variableInitializer(variable, flat) > options.lineWidth()
            && initializer instanceof CastExpr castExpr
            && castTypeNeedsBreak(declarationPrefix + variable.getNameAsString(), castExpr.getType())
            && !initializerHasOwnBreak(initializer)
        ) {
            return variableWithCastTypeBreak(name, declarationPrefix + variable.getNameAsString(), castExpr);
        }
        if (
            layoutWidth.variableInitializer(variable, flat) > options.lineWidth()
            && initializer instanceof ConditionalExpr conditionalExpr
            && !initializerHasOwnBreak(initializer)
        ) {
            return conditionalInitializer(name, declarationPrefix + variable.getNameAsString(), conditionalExpr);
        }
        if (
            layoutWidth.variableInitializer(variable, flat) > options.lineWidth()
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
            layoutWidth.variableInitializer(variable, flat) > options.lineWidth()
            && initializer instanceof StringLiteralExpr
        ) {
            return Doc.concat(
                Doc.text(name + " ="),
                Doc.indent(Doc.concat(Doc.HARD_LINE, expression.apply(initializer)))
            );
        }
        if (
            layoutWidth.variableInitializer(variable, flat) > options.lineWidth()
            && initializer instanceof ArrayInitializerExpr arrayInitializerExpr
            && sourceSpansMultipleLines(arrayInitializerExpr)
        ) {
            return Doc.concat(Doc.text(name + " = "), arrayInitializer.apply(arrayInitializerExpr, true));
        }
        if (
            layoutWidth.variableInitializer(variable, flat) > options.lineWidth()
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
            || sourceShape.spansMultipleLines(methodCall);
        MethodCallChainSourcePlanner.InitializerChainShape chainShape = methodCallChainInitializerShape.apply(
            methodCall
        );
        if (
            !chainShape.canUseCompactObjectCreationInitializer(
                initializerStartsOnContinuationLine,
                chainSpansMultipleSourceLines,
                sourceShape.methodCallArgumentsSpanMultipleLines(methodCall)
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
            || sourceShape.spansMultipleLines(methodCall);
        MethodCallChainSourcePlanner.InitializerChainShape chainShape = methodCallChainInitializerShape.apply(
            methodCall
        );
        if (
            !methodCallChainRootIsObjectCreation.test(methodCall)
            || !(
                chainShape.canUseCompactObjectCreationInitializer(
                    initializerStartsOnContinuationLine,
                    chainSpansMultipleSourceLines,
                    sourceShape.methodCallArgumentsSpanMultipleLines(methodCall)
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
                .flatMap(rootFirstLine -> sourceShapePolicy.rawTextWithoutOwnComment(methodCall)
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
        String raw = sourceShapePolicy.rawText(variable);
        int equals = raw.indexOf('=');
        int blockComment = raw.indexOf("/*");
        if (blockComment < 0 || equals < 0 || blockComment > equals) {
            return Optional.empty();
        }
        Doc comment = comments.ownComment(initializer, BlockComment.class::isInstance);
        return comment == Doc.EMPTY ? Optional.empty() : Optional.of(comment);
    }

    /**
     * Finds a block comment attached after {@code =}, preserving the source-side comment text as part of the flat-width
     * check.
     */
    private Optional<String> postEqualsBlockComment(VariableDeclarator variable, Expression initializer) {
        String raw = sourceShapePolicy.rawText(variable);
        int equals = raw.indexOf('=');
        int blockComment = raw.indexOf("/*");
        if (blockComment < 0 || equals < 0 || blockComment < equals) {
            return Optional.empty();
        }
        return initializer.getComment()
                .filter(BlockComment.class::isInstance)
                .map(comment -> comment.getTokenRange().map(Object::toString).orElseGet(comment::toString).strip());
    }

    /**
     * Breaks array-creation initializers only after checking whether the array type or initializer already owns a more
     * specific line-breaking shape.
     */
    private Optional<Doc> variableWithBrokenArrayCreation(
            String name,
            String flatName,
            ArrayCreationExpr arrayCreation
    ) {
        if (
            arrayCreation.getInitializer().isEmpty()
            || arrayCreationTypeBreaks.test(arrayCreation)
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
            && (sourceShape.spansMultipleLines(binaryExpr)
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
        if (
            methodCallChainIsSourceMultiline.test(methodCall)
            && blockLambdaCall.isEmpty()
            && !methodCallHasBlockLambdaArgument(methodCall)
        ) {
            return Optional.empty();
        }
        String firstLine = flatName + " = " + callPrefix + "(";
        if (layoutWidth.currentIndented(firstLine) > options.lineWidth()) {
            return Optional.empty();
        }
        if (!methodCall.getAllContainedComments().isEmpty()) {
            return Optional.of(Doc.concat(Doc.text(name + " = "), this.methodCall.apply(methodCall)));
        }
        if (blockLambdaCall.isPresent()) {
            return blockLambdaCall;
        }
        return Optional.of(brokenMethodCallArgumentList(name, methodCall, callPrefix));
    }

    /**
     * Keeps block-lambda method-call arguments with first-statement comments on a direct broken-call layout.
     *
     * <p>The ordinary broken-call fallback rejects nested comments so it does not steal comment ownership from method
     * call rendering. This narrower path is only for source shapes where the comment is the leading cluster before the
     * first statement inside a block lambda argument and the call opener itself can still stay with the assignment.
     */
    private Optional<Doc> variableWithLeadingCommentedBlockLambdaMethodCall(
            String name,
            String flatName,
            MethodCallExpr methodCall
    ) {
        if (
            !methodCallHasLeadingCommentedBlockLambdaArgument(methodCall)
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
            || !sourceShape.spansMultipleLines(methodCall)
            || !chainShape.canUseDirectSourceMultilineInitializer()
            || sourceShape.expressionLambdaStartsOnSelectorLine(methodCall)
            || methodCall.getScope().filter(sourceShape::spansMultipleLines).isPresent()
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
        if (variableWithHuggableBlockLambdaArguments(variable, name, flatName, methodCall, callPrefix).isPresent()) {
            return Optional.empty();
        }
        return variableWithReceiverBreakBeforeHuggableBlockLambdaArguments(variable, name, flatName, methodCall);
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
            || scope.filter(sourceShape::spansMultipleLines).isPresent()
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
            sourceShape.spansMultipleLines(initializer)
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
     * Collects source-leading line comments that JavaParser attaches to either the variable or initializer before the
     * initializer expression starts.
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
        Doc leadingBlockComment = comments.ownComment(variable, BlockComment.class::isInstance);
        if (leadingBlockComment == Doc.EMPTY) {
            return variable.getNameAsString();
        }
        return commentText(leadingBlockComment) + " " + variable.getNameAsString();
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
     * Treats array initializers and broken array types as already owning the assignment continuation shape.
     */
    private boolean arrayCreationHasOwnBreak(ArrayCreationExpr expression) {
        return expression.getInitializer().isPresent() || arrayCreationTypeBreaks.test(expression);
    }

    private String commentText(Doc comment) {
        if (comment instanceof Doc.Text text) {
            return text.value();
        }
        return "";
    }
}
