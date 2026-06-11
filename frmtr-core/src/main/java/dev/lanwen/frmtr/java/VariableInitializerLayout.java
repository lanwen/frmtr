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
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
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
    private final RawSource rawSource;
    private final FormatterOptions options;
    private final LayoutWidth layoutWidth;
    private final Function<Node, String> compactTypeLike;
    private final Function<Node, String> compact;
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
    private final Function<MethodCallExpr, Optional<Doc>> forcedMethodCallChain;
    private final Function<MethodCallExpr, Optional<Doc>> forcedMethodCallChainWithSemicolon;
    private final Predicate<MethodCallExpr> methodCallChainHasFinalTrailingLineComment;
    private final Function<MethodCallExpr, Optional<Expression>> mixedFieldMethodCallRoot;
    private final Function<MethodCallExpr, String> methodCallChainFirstLine;
    private final Predicate<MethodCallExpr> methodCallChainRootIsObjectCreation;
    private final Predicate<MethodCallExpr> methodCallChainIsSourceMultiline;
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
    private final FieldDeclarationPrinter.HuggableArgumentsRenderer huggableBlockLambdaArguments;
    private final Function<LambdaExpr, String> lambdaParameters;
    private final BiPredicate<LambdaExpr, String> lambdaParametersShouldBreak;
    private final Function<LambdaExpr, Doc> lambdaExpression;

    VariableInitializerLayout(
            CommentTracker comments,
            RawSource rawSource,
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
            Function<MethodCallExpr, Optional<Doc>> forcedMethodCallChain,
            Function<MethodCallExpr, Optional<Doc>> forcedMethodCallChainWithSemicolon,
            Predicate<MethodCallExpr> methodCallChainHasFinalTrailingLineComment,
            Function<MethodCallExpr, Optional<Expression>> mixedFieldMethodCallRoot,
            Function<MethodCallExpr, String> methodCallChainFirstLine,
            Predicate<MethodCallExpr> methodCallChainRootIsObjectCreation,
            Predicate<MethodCallExpr> methodCallChainIsSourceMultiline,
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
            FieldDeclarationPrinter.HuggableArgumentsRenderer huggableBlockLambdaArguments,
            Function<LambdaExpr, String> lambdaParameters,
            BiPredicate<LambdaExpr, String> lambdaParametersShouldBreak,
            Function<LambdaExpr, Doc> lambdaExpression) {
        this.comments = comments;
        this.rawSource = rawSource;
        this.options = options;
        this.layoutWidth = layoutWidth;
        this.compactTypeLike = compactTypeLike;
        this.compact = compact;
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
        this.forcedMethodCallChainWithSemicolon = forcedMethodCallChainWithSemicolon;
        this.methodCallChainHasFinalTrailingLineComment = methodCallChainHasFinalTrailingLineComment;
        this.mixedFieldMethodCallRoot = mixedFieldMethodCallRoot;
        this.methodCallChainFirstLine = methodCallChainFirstLine;
        this.methodCallChainRootIsObjectCreation = methodCallChainRootIsObjectCreation;
        this.methodCallChainIsSourceMultiline = methodCallChainIsSourceMultiline;
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
        this.huggableBlockLambdaArguments = huggableBlockLambdaArguments;
        this.lambdaParameters = lambdaParameters;
        this.lambdaParametersShouldBreak = lambdaParametersShouldBreak;
        this.lambdaExpression = lambdaExpression;
    }
    Doc variableWithStatementTerminator(VariableDeclarator variable, String declarationPrefix) {
        if (variable.getInitializer().orElse(null) instanceof MethodCallExpr methodCall
                && methodCallChainHasFinalTrailingLineComment.test(methodCall)) {
            Optional<Doc> chain = forcedMethodCallChainWithSemicolon.apply(methodCall);
            if (chain.isPresent()) {
                return variableWithMethodCallChain(
                        variableName(variable),
                        declarationPrefix + variable.getNameAsString(),
                        methodCall,
                        chain.orElseThrow());
            }
        }
        Doc declaration = variable.getInitializer()
                .map(initializer -> variableWithInitializer(variable, initializer, declarationPrefix))
                .orElseGet(() -> Doc.text(variableName(variable)));
        return Doc.concat(declaration, Doc.text(";"));
    }

    /**
     * Chooses the initializer shape while preserving comments around {@code =}, source-leading initializer comments,
     * and construct-specific break rules before falling back to the shared expression renderer.
     */
    Doc variableWithInitializer(
            VariableDeclarator variable,
            Expression initializer,
            String declarationPrefix) {
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
                        Doc.indent(Doc.concat(Doc.HARD_LINE, expressionWithoutOwnComment.apply(initializer))));
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
                        Doc.indent(Doc.concat(Doc.HARD_LINE, expression.apply(initializer))));
            }
        }
        Optional<Doc> leadingInitializerComments = leadingInitializerComments(variable, initializer);
        if (leadingInitializerComments.isPresent()) {
            return Doc.concat(
                    Doc.text(name + " ="),
                    Doc.indent(Doc.concat(
                            Doc.HARD_LINE,
                            leadingInitializerComments.orElseThrow(),
                            Doc.HARD_LINE,
                            expression.apply(initializer))));
        }
        if (initializer instanceof BinaryExpr binaryExpr && binaryExpressionHasLineComments.test(binaryExpr)) {
            return Doc.concat(
                    Doc.text(name + " ="),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, binaryExpressionLinesWithComments.apply(binaryExpr))));
        }
        if (layoutWidth.blockStatement(flat) > options.lineWidth()) {
            Optional<Doc> suffixedEnclosedInitializer = suffixedEnclosedExpression.apply(initializer, true);
            if (suffixedEnclosedInitializer.isPresent()) {
                return Doc.concat(Doc.text(name + " = "), suffixedEnclosedInitializer.orElseThrow());
            }
            if (initializer instanceof ArrayAccessExpr arrayAccessExpr
                    && arrayAccessExpr.getName().isEnclosedExpr()) {
                return Doc.concat(Doc.text(name + " = "), arrayAccessWithBrokenEnclosedName.apply(arrayAccessExpr));
            }
            if (initializer instanceof ArrayCreationExpr arrayCreationExpr) {
                Optional<Doc> arrayCreation = variableWithBrokenArrayCreation(
                        name,
                        declarationPrefix + variable.getNameAsString(),
                        arrayCreationExpr);
                if (arrayCreation.isPresent()) {
                    return arrayCreation.orElseThrow();
                }
            }
            if (initializer instanceof ObjectCreationExpr objectCreationExpr) {
                Optional<Doc> objectCreation = variableWithBrokenObjectCreation(
                        name,
                        declarationPrefix + variable.getNameAsString(),
                        objectCreationExpr);
                if (objectCreation.isPresent()) {
                    return objectCreation.orElseThrow();
                }
            }
            if (initializer instanceof BinaryExpr binaryExpr) {
                if (shouldKeepCastDivisionContinuationFlat.test(binaryExpr)) {
                    return Doc.concat(
                            Doc.text(name + " ="),
                            Doc.indent(Doc.concat(Doc.HARD_LINE, expression.apply(binaryExpr))));
                }
                return Doc.concat(
                        Doc.text(name + " ="),
                        Doc.indent(Doc.concat(Doc.HARD_LINE, binaryExpressionLines.apply(initializer, true))));
            }
        }
        if (initializer instanceof MethodCallExpr methodCall
                && methodCall.getScope().filter(TextBlockLiteralExpr.class::isInstance).isPresent()) {
            return Doc.concat(Doc.text(name + " = "), this.methodCall.apply(methodCall));
        }
        if (layoutWidth.variableInitializer(variable, flat) > options.lineWidth()
                && initializer instanceof MethodCallExpr methodCall
                && !initializerHasOwnBreak(initializer)) {
            if (methodCallChainRootIsObjectCreation.test(methodCall)) {
                Optional<Doc> directCall = variableWithBrokenMethodCallArguments(
                        name,
                        declarationPrefix + variable.getNameAsString(),
                        methodCall,
                        false);
                if (directCall.isPresent()) {
                    return directCall.orElseThrow();
                }
            }
            if (methodCallHasAttachableScope(methodCall)) {
                Optional<Doc> directCall = variableWithBrokenMethodCallArguments(
                        name,
                        declarationPrefix + variable.getNameAsString(),
                        methodCall,
                        true);
                if (directCall.isPresent()) {
                    return directCall.orElseThrow();
                }
            }
            Optional<Doc> compactObjectCreationChain = variableWithCompactObjectCreationChain(name, methodCall);
            if (compactObjectCreationChain.isPresent()) {
                return compactObjectCreationChain.orElseThrow();
            }
            Optional<Doc> chain = forcedMethodCallChain.apply(methodCall).or(() -> mixedFieldMethodCallChain.apply(methodCall));
            if (chain.isPresent()) {
                return variableWithMethodCallChain(
                        name,
                        declarationPrefix + variable.getNameAsString(),
                        methodCall,
                        chain.orElseThrow());
            }
            Optional<Doc> directCall = variableWithBrokenMethodCallArguments(
                    name,
                    declarationPrefix + variable.getNameAsString(),
                    methodCall,
                    false);
            if (directCall.isPresent()) {
                return directCall.orElseThrow();
            }
        }
        if (layoutWidth.variableInitializer(variable, flat) > options.lineWidth()
                && initializer instanceof CastExpr castExpr
                && castExpr.getExpression() instanceof MethodCallExpr methodCall
                && !initializerHasOwnBreak(initializer)) {
            return Doc.concat(
                    Doc.text(name + " = "),
                    castType.apply(castExpr.getType()),
                    Doc.text(" "),
                    brokenMethodCall.apply(methodCall));
        }
        if (layoutWidth.variableInitializer(variable, flat) > options.lineWidth()
                && initializer instanceof CastExpr castExpr
                && castTypeNeedsBreak(declarationPrefix + variable.getNameAsString(), castExpr.getType())
                && !initializerHasOwnBreak(initializer)) {
            return variableWithCastTypeBreak(name, declarationPrefix + variable.getNameAsString(), castExpr);
        }
        if (layoutWidth.variableInitializer(variable, flat) > options.lineWidth()
                && initializer instanceof ConditionalExpr conditionalExpr
                && !initializerHasOwnBreak(initializer)) {
            return conditionalInitializer(name, declarationPrefix + variable.getNameAsString(), conditionalExpr);
        }
        if (layoutWidth.variableInitializer(variable, flat) > options.lineWidth()
                && initializer instanceof LambdaExpr lambdaExpr
                && !initializerHasOwnBreak(initializer)) {
            Optional<Doc> blockLambdaInitializer = variableWithBlockLambdaInitializer(
                    name,
                    declarationPrefix + variable.getNameAsString(),
                    lambdaExpr);
            if (blockLambdaInitializer.isPresent()) {
                return blockLambdaInitializer.orElseThrow();
            }
            Optional<Doc> lambdaInitializer = variableWithBrokenLambdaParameters(
                    name,
                    declarationPrefix + variable.getNameAsString(),
                    lambdaExpr);
            if (lambdaInitializer.isPresent()) {
                return lambdaInitializer.orElseThrow();
            }
        }
        if (layoutWidth.variableInitializer(variable, flat) > options.lineWidth()
                && initializer instanceof StringLiteralExpr) {
            return Doc.concat(
                    Doc.text(name + " ="),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, expression.apply(initializer))));
        }
        if (layoutWidth.variableInitializer(variable, flat) > options.lineWidth()
                && initializer instanceof ArrayInitializerExpr arrayInitializerExpr
                && sourceSpansMultipleLines(arrayInitializerExpr)) {
            return Doc.concat(Doc.text(name + " = "), arrayInitializer.apply(arrayInitializerExpr, true));
        }
        if (layoutWidth.variableInitializer(variable, flat) > options.lineWidth()
                && !(initializer instanceof StringLiteralExpr)
                && !(initializer instanceof TextBlockLiteralExpr)
                && !initializerHasOwnBreak(initializer)) {
            return Doc.concat(
                    Doc.text(name + " ="),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, brokenInitializer(initializer))));
        }
        return Doc.concat(Doc.text(name + " = "), expression.apply(initializer));
    }

    /**
     * Keeps a compact object-creation method chain on the continuation line when the opener cannot stay with
     * {@code =}, but the whole chain fits after the break.
     */
    private Optional<Doc> variableWithCompactObjectCreationChain(String name, MethodCallExpr methodCall) {
        if (!methodCallChainRootIsObjectCreation.test(methodCall)
                || methodCallChainIsSourceMultiline.test(methodCall)
                || layoutWidth.continuationStatement(compact.apply(methodCall) + ";") > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                Doc.text(name + " ="),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(compact.apply(methodCall))))));
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
     * Finds a block comment attached after {@code =}, preserving the source-side comment text as part of the flat-width
     * check.
     */
    private Optional<String> postEqualsBlockComment(VariableDeclarator variable, Expression initializer) {
        String raw = rawSource.raw(variable);
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
            ArrayCreationExpr arrayCreation) {
        if (arrayCreation.getInitializer().isEmpty()
                || arrayCreationTypeBreaks.test(arrayCreation)
                || !arrayCreation.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        String prefix = arrayCreationPrefix.apply(arrayCreation);
        ArrayInitializerExpr initializer = arrayCreation.getInitializer().orElseThrow();
        if (layoutWidth.currentIndented(flatName + " = " + prefix + " {") <= options.lineWidth()) {
            return Optional.of(Doc.concat(Doc.text(name + " = " + prefix + " "), arrayInitializer.apply(initializer, true)));
        }
        Optional<String> compactContinuation = compactObjectCreationArrayInitializer(initializer);
        if (compactContinuation.isPresent()
                && layoutWidth.currentIndented(prefix + " " + compactContinuation.orElseThrow()) <= options.lineWidth()) {
            return Optional.of(Doc.concat(
                    Doc.text(name + " ="),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(prefix + " " + compactContinuation.orElseThrow())))));
        }
        return Optional.of(Doc.concat(
                Doc.text(name + " ="),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(prefix + " "), arrayInitializer.apply(initializer, true)))));
    }

    /**
     * Keeps an array initializer compact only for the narrow object-creation list that reads better as one continuation.
     */
    private Optional<String> compactObjectCreationArrayInitializer(ArrayInitializerExpr initializer) {
        if (!initializer.getAllContainedComments().isEmpty()
                || initializer.getValues().isEmpty()
                || initializer.getValues().stream().anyMatch(value -> !compactObjectCreationArrayValue(value))) {
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
            ObjectCreationExpr objectCreation) {
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
            ObjectCreationExpr objectCreation) {
        if (objectCreation.getArguments().isEmpty()
                || objectCreation.getComment().filter(BlockComment.class::isInstance).isPresent()
                || objectCreation.getType().getComment().filter(BlockComment.class::isInstance).isPresent()) {
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
            ObjectCreationExpr objectCreation) {
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
        return Optional.of(Doc.concat(
                Doc.text(name + " = " + prefix + "("),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        Doc.join(Doc.concat(Doc.text(","), Doc.HARD_LINE), objectCreation.getArguments().stream()
                                .map(expression)
                                .toList()))),
                Doc.HARD_LINE,
                Doc.text(")")));
    }

    private boolean smallConstructorCanStayFlat(String flatName, ObjectCreationExpr objectCreation) {
        return objectCreation.getArguments().size() <= 3
                && layoutWidth.currentIndented(flatName + " = " + compact.apply(objectCreation) + ";") <= options.lineWidth();
    }

    /**
     * Breaks constructor type arguments for {@code new SomeVeryLongType<...>()} only when there are no constructor
     * arguments or scopes that need a different object-creation layout.
     */
    private Optional<Doc> variableWithBrokenObjectCreationTypeArguments(
            String name,
            String flatName,
            ObjectCreationExpr objectCreation) {
        if (!objectCreation.getArguments().isEmpty()
                || objectCreation.getScope().isPresent()
                || objectCreation.getTypeArguments().isPresent()
                || !objectCreation.getType().isClassOrInterfaceType()) {
            return Optional.empty();
        }
        ClassOrInterfaceType type = objectCreation.getType().asClassOrInterfaceType();
        if (!hasNonEmptyTypeArguments(type)
                || layoutWidth.currentIndented(flatName + " = new " + typeNameWithoutArguments.apply(type) + "<") > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                Doc.text(name + " = new "),
                brokenClassOrInterfaceType.apply(type),
                Doc.text("()")));
    }

    /**
     * Breaks a method-call initializer at its arguments when the call prefix still fits on the assignment line.
     */
    private Optional<Doc> variableWithBrokenMethodCallArguments(
            String name,
            String flatName,
            MethodCallExpr methodCall,
            boolean allowNestedComments) {
        if (methodCall.getArguments().isEmpty()
                || (!allowNestedComments && !methodCall.getAllContainedComments().isEmpty())
                || methodCallChainIsSourceMultiline.test(methodCall)
                || methodCallHasOwnComment(methodCall)
                || methodCall.getScope().filter(shouldPrintScopeAsDoc).isPresent()) {
            return Optional.empty();
        }
        String callPrefix = methodCallPrefix.apply(methodCall);
        String firstLine = flatName + " = " + callPrefix + "(";
        if (layoutWidth.currentIndented(firstLine) > options.lineWidth()) {
            return Optional.empty();
        }
        if (!methodCall.getAllContainedComments().isEmpty()) {
            return Optional.of(Doc.concat(Doc.text(name + " = "), this.methodCall.apply(methodCall)));
        }
        Optional<Doc> blockLambdaCall = variableWithHuggableBlockLambdaArguments(name, flatName, methodCall, callPrefix);
        if (blockLambdaCall.isPresent()) {
            return blockLambdaCall;
        }
        return Optional.of(Doc.concat(
                Doc.text(name + " = " + callPrefix + "("),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        Doc.join(Doc.concat(Doc.text(","), Doc.HARD_LINE), methodCall.getArguments().stream()
                                .map(expression)
                                .toList()))),
                Doc.HARD_LINE,
                Doc.text(")")));
    }

    /**
     * Identifies receiver-call initializers where the assignment opener should be tried before chain fallback.
     */
    private boolean methodCallHasAttachableScope(MethodCallExpr methodCall) {
        return methodCall.getScope()
                .filter(scope -> scope.isNameExpr()
                        || scope.isThisExpr()
                        || scope.isSuperExpr()
                        || scope instanceof MethodCallExpr scopedCall
                                && scopedCall.getAllContainedComments().isEmpty()
                                && methodCallScopeEndsOnNameLine(scopedCall, methodCall))
                .isPresent();
    }

    private boolean methodCallScopeEndsOnNameLine(MethodCallExpr scope, MethodCallExpr methodCall) {
        return scope.getRange()
                .flatMap(scopeRange -> methodCall.getName()
                        .getRange()
                        .map(nameRange -> scopeRange.end.line == nameRange.begin.line))
                .orElse(false);
    }

    private boolean methodCallHasOwnComment(MethodCallExpr methodCall) {
        return methodCall.getComment().isPresent()
                || methodCall.getName().getComment().isPresent()
                || methodCall.getScope().flatMap(Expression::getComment).isPresent();
    }

    /**
     * Keeps block-lambda method-call initializers on the assignment line until the lambda opener no longer fits.
     *
     * <p>The ordinary argument-break fallback remains available for long call prefixes or lambda parameter lists. This
     * branch only wins when the assignment line through the lambda opener fits after the declaration prefix.
     */
    private Optional<Doc> variableWithHuggableBlockLambdaArguments(
            String name,
            String flatName,
            MethodCallExpr methodCall,
            String callPrefix) {
        return huggableBlockLambdaArguments
                .render(callPrefix, methodCall.getArguments(), firstLine -> layoutWidth.currentIndented(flatName + " = " + firstLine))
                .map(call -> Doc.concat(Doc.text(name + " = "), call));
    }

    /**
     * Decides whether a method-call chain can start after {@code =} or must move entirely to an indented continuation.
     */
    private Doc variableWithMethodCallChain(
            String name,
            String flatName,
            MethodCallExpr methodCall,
            Doc chain) {
        String firstLine = mixedFieldMethodCallRoot.apply(methodCall)
                .filter(root -> !(root instanceof ObjectCreationExpr))
                .map(compact)
                .orElseGet(() -> methodCallChainFirstLine.apply(methodCall));
        if (methodCallChainRootIsObjectCreation.test(methodCall)
                && layoutWidth.blockStatement(flatName + " = " + firstLine + ";") > options.lineWidth()) {
            return Doc.concat(Doc.text(name + " ="), Doc.indent(Doc.concat(Doc.HARD_LINE, chain)));
        }
        if (layoutWidth.currentIndented(flatName + " = " + firstLine) > options.lineWidth()) {
            return Doc.concat(Doc.text(name + " ="), Doc.indent(Doc.concat(Doc.HARD_LINE, chain)));
        }
        return Doc.concat(Doc.text(name + " = "), chain);
    }

    /**
     * Chooses the conditional initializer shape by trying the least disruptive break first, then falling back to a
     * fully indented conditional when the condition line itself is too wide.
     */
    private Doc conditionalInitializer(String name, String flatName, ConditionalExpr initializer) {
        String conditionLine = flatName + " = " + compact.apply(initializer.getCondition());
        String compactInitializer = compact.apply(initializer);
        if (rawSource.rawWithoutOwnComment(initializer).contains("\n")
                && layoutWidth.blockStatement(conditionLine + ";") <= options.lineWidth()) {
            return Doc.concat(Doc.text(name + " = "), brokenConditionalExpression.apply(initializer));
        }
        if (layoutWidth.continuationStatement(compactInitializer + ";") <= options.lineWidth()) {
            return Doc.concat(
                    Doc.text(name + " ="),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(compactInitializer))));
        }
        if (shouldBreakBeforeConditionalInitializer.test(initializer)) {
            return Doc.concat(
                    Doc.text(name + " ="),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, brokenConditionalExpression.apply(initializer))));
        }
        if (layoutWidth.blockStatement(conditionLine + ";") <= options.lineWidth()) {
            return Doc.concat(Doc.text(name + " = "), brokenConditionalExpression.apply(initializer));
        }
        if (parenthesizedConditionalConditionOpenerFits(flatName, initializer)) {
            return Doc.concat(Doc.text(name + " = "), brokenConditionalExpression.apply(initializer));
        }
        return Doc.concat(
                Doc.text(name + " ="),
                Doc.indent(Doc.concat(Doc.HARD_LINE, brokenConditionalExpression.apply(initializer))));
    }

    private boolean parenthesizedConditionalConditionOpenerFits(String flatName, ConditionalExpr initializer) {
        return initializer.getCondition() instanceof EnclosedExpr
                && layoutWidth.currentIndented(flatName + " = (") <= options.lineWidth();
    }

    /**
     * Keeps lambda initializer parameters and body with the shared lambda formatter when only the parameter list needs a
     * declaration-width-driven break.
     */
    private Optional<Doc> variableWithBrokenLambdaParameters(
            String name,
            String flatName,
            LambdaExpr lambdaExpr) {
        String parameters = lambdaParameters.apply(lambdaExpr);
        if (!lambdaExpr.getBody().isBlockStmt()
                || !lambdaParametersShouldBreak.test(lambdaExpr, parameters)
                || layoutWidth.currentIndented(flatName + " = (") > options.lineWidth()) {
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
            LambdaExpr lambdaExpr) {
        if (!lambdaExpr.getBody().isBlockStmt()) {
            return Optional.empty();
        }
        String parameters = lambdaParameters.apply(lambdaExpr);
        if (lambdaParametersShouldBreak.test(lambdaExpr, parameters)
                || layoutWidth.currentIndented(flatName + " = " + parameters + " -> {") > options.lineWidth()) {
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
        variable.getOrphanComments().stream()
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
        if (type instanceof ClassOrInterfaceType classOrInterfaceType
                && classOrInterfaceType.getTypeArguments().isPresent()) {
            return "(" + typeNameWithoutArguments.apply(classOrInterfaceType) + "<";
        }
        return "(";
    }

    private boolean castTypeCanBreak(Type type) {
        return type instanceof IntersectionType
                || (type instanceof ClassOrInterfaceType classOrInterfaceType
                        && classOrInterfaceType.getTypeArguments().isPresent());
    }

    private boolean hasNonEmptyTypeArguments(ClassOrInterfaceType type) {
        return type.getTypeArguments().map(arguments -> !arguments.isEmpty()).orElse(false);
    }


    /**
     * Uses the shared method-chain break as the last initializer fallback before normal expression rendering.
     */
    private Doc brokenInitializer(Expression initializer) {
        if (initializer instanceof MethodCallExpr methodCall) {
            return forcedMethodCallChain.apply(methodCall).orElseGet(() -> expression.apply(initializer));
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
        if (initializer instanceof ObjectCreationExpr objectCreationExpr
                && objectCreationExpr.getAnonymousClassBody().isPresent()) {
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
