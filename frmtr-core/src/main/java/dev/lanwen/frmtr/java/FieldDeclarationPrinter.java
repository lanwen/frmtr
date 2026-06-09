package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
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
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
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

/**
 * Prints field declarations and their variable initializer decision tree after body dispatch has selected the field
 * branch.
 *
 * <p>This helper owns declaration-level annotations and modifiers, the comma-joined variable list, comments before and
 * after {@code =}, source-leading initializer line comments, and the field-width forks that decide when array creation,
 * object creation, method-call chains, conditional expressions, and lambda parameters should break. It intentionally
 * delegates general expression, type, method-call, binary-expression, object-creation, lambda, and raw-source-sensitive
 * formatting back to {@link JavaPrinter}, {@link ObjectCreationPrinter}, and other shared-printer callbacks so those
 * shared printers keep one behavior source.
 *
 * <p>Representative fixture pairs live at
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/variables/input.java} and
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/variables/frmtr.output.java}; the comment cases
 * near {@code variableWithComment1} through {@code variableWithComment4} cover the before/after {@code =} branches.
 */
final class FieldDeclarationPrinter {
    private final CommentTracker comments;
    private final RawSource rawSource;
    private final FormatterOptions options;
    private final Function<NodeWithAnnotations<?>, Doc> declarationAnnotations;
    private final Function<NodeWithModifiers<?>, String> modifiers;
    private final Function<NodeWithAnnotations<?>, String> inlineAnnotations;
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
    private final Function<MethodCallExpr, Optional<Expression>> mixedFieldMethodCallRoot;
    private final BiFunction<MethodCallExpr, List<MethodCallExpr>, Expression> methodCallChainRoot;
    private final Predicate<MethodCallExpr> methodCallChainRootIsObjectCreation;
    private final Function<Type, Doc> castType;
    private final Function<ConditionalExpr, Doc> brokenConditionalExpression;
    private final Predicate<ConditionalExpr> shouldBreakBeforeConditionalInitializer;
    private final Predicate<ArrayCreationExpr> arrayCreationTypeBreaks;
    private final Function<ArrayCreationExpr, String> arrayCreationPrefix;
    private final BiFunction<ArrayInitializerExpr, Boolean, Doc> arrayInitializer;
    private final Function<ObjectCreationExpr, String> objectCreationPrefix;
    private final Function<ClassOrInterfaceType, String> typeNameWithoutArguments;
    private final Function<ClassOrInterfaceType, Doc> brokenClassOrInterfaceType;
    private final Predicate<Expression> shouldPrintScopeAsDoc;
    private final Function<MethodCallExpr, String> methodCallPrefix;
    private final Function<LambdaExpr, String> lambdaParameters;
    private final BiPredicate<LambdaExpr, String> lambdaParametersShouldBreak;
    private final Function<LambdaExpr, Doc> lambdaExpression;

    FieldDeclarationPrinter(
            CommentTracker comments,
            RawSource rawSource,
            FormatterOptions options,
            Function<NodeWithAnnotations<?>, Doc> declarationAnnotations,
            Function<NodeWithModifiers<?>, String> modifiers,
            Function<NodeWithAnnotations<?>, String> inlineAnnotations,
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
            Function<MethodCallExpr, Optional<Expression>> mixedFieldMethodCallRoot,
            BiFunction<MethodCallExpr, List<MethodCallExpr>, Expression> methodCallChainRoot,
            Predicate<MethodCallExpr> methodCallChainRootIsObjectCreation,
            Function<Type, Doc> castType,
            Function<ConditionalExpr, Doc> brokenConditionalExpression,
            Predicate<ConditionalExpr> shouldBreakBeforeConditionalInitializer,
            Predicate<ArrayCreationExpr> arrayCreationTypeBreaks,
            Function<ArrayCreationExpr, String> arrayCreationPrefix,
            BiFunction<ArrayInitializerExpr, Boolean, Doc> arrayInitializer,
            Function<ObjectCreationExpr, String> objectCreationPrefix,
            Function<ClassOrInterfaceType, String> typeNameWithoutArguments,
            Function<ClassOrInterfaceType, Doc> brokenClassOrInterfaceType,
            Predicate<Expression> shouldPrintScopeAsDoc,
            Function<MethodCallExpr, String> methodCallPrefix,
            Function<LambdaExpr, String> lambdaParameters,
            BiPredicate<LambdaExpr, String> lambdaParametersShouldBreak,
            Function<LambdaExpr, Doc> lambdaExpression) {
        this.comments = comments;
        this.rawSource = rawSource;
        this.options = options;
        this.declarationAnnotations = declarationAnnotations;
        this.modifiers = modifiers;
        this.inlineAnnotations = inlineAnnotations;
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
        this.mixedFieldMethodCallRoot = mixedFieldMethodCallRoot;
        this.methodCallChainRoot = methodCallChainRoot;
        this.methodCallChainRootIsObjectCreation = methodCallChainRootIsObjectCreation;
        this.castType = castType;
        this.brokenConditionalExpression = brokenConditionalExpression;
        this.shouldBreakBeforeConditionalInitializer = shouldBreakBeforeConditionalInitializer;
        this.arrayCreationTypeBreaks = arrayCreationTypeBreaks;
        this.arrayCreationPrefix = arrayCreationPrefix;
        this.arrayInitializer = arrayInitializer;
        this.objectCreationPrefix = objectCreationPrefix;
        this.typeNameWithoutArguments = typeNameWithoutArguments;
        this.brokenClassOrInterfaceType = brokenClassOrInterfaceType;
        this.shouldPrintScopeAsDoc = shouldPrintScopeAsDoc;
        this.methodCallPrefix = methodCallPrefix;
        this.lambdaParameters = lambdaParameters;
        this.lambdaParametersShouldBreak = lambdaParametersShouldBreak;
        this.lambdaExpression = lambdaExpression;
    }

    /**
     * Prints one field declaration, keeping declaration annotations/modifiers with the shared declaration callbacks and
     * grouping comma-separated variables under the common field type prefix.
     */
    Doc field(FieldDeclaration declaration) {
        List<Doc> docs = new ArrayList<>();
        docs.add(declarationAnnotations.apply(declaration));
        docs.add(Doc.text(modifiers.apply(declaration)));
        String declarationPrefix = modifiers.apply(declaration);
        if (!declaration.getVariables().isEmpty()) {
            String type = inlineAnnotations.apply(declaration)
                    + compactTypeLike.apply(declaration.getVariables().get(0).getType())
                    + " ";
            declarationPrefix += type;
            docs.add(Doc.text(type));
        }
        String variableDeclarationPrefix = declarationPrefix;
        docs.add(Doc.group(Doc.join(Doc.concat(Doc.text(","), Doc.LINE), declaration.getVariables().stream()
                .map(variable -> variable(variable, variableDeclarationPrefix))
                .toList())));
        docs.add(Doc.text(";"));
        return Doc.concat(docs);
    }

    /**
     * Prints one variable declarator without a declaration prefix, used when callers format a declarator outside a
     * shared field prefix.
     */
    Doc variable(VariableDeclarator variable) {
        return variable(variable, "");
    }

    /**
     * Prints one variable declarator with the already-flat declaration prefix available for line-width decisions.
     */
    Doc variable(VariableDeclarator variable, String declarationPrefix) {
        return variable.getInitializer()
                .map(expression -> variableWithInitializer(variable, expression, declarationPrefix))
                .orElseGet(() -> Doc.text(variableName(variable)));
    }

    /**
     * Chooses the initializer shape while preserving comments around {@code =}, source-leading initializer comments,
     * and construct-specific break rules before falling back to the shared expression renderer.
     */
    private Doc variableWithInitializer(
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
            if (blockStatementWidth(commentedFlat) > options.lineWidth()) {
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
            if (blockStatementWidth(commentedFlat) > options.lineWidth()) {
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
        if (blockStatementWidth(flat) > options.lineWidth()) {
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
        if (currentIndentedWidth(flat) > options.lineWidth()
                && initializer instanceof MethodCallExpr methodCall
                && !initializerHasOwnBreak(initializer)) {
            Optional<Doc> compactObjectCreationChain = variableWithCompactObjectCreationChain(name, methodCall);
            if (compactObjectCreationChain.isPresent()) {
                return compactObjectCreationChain.orElseThrow();
            }
            Optional<Doc> chain = mixedFieldMethodCallChain.apply(methodCall).or(() -> forcedMethodCallChain.apply(methodCall));
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
                    methodCall);
            if (directCall.isPresent()) {
                return directCall.orElseThrow();
            }
        }
        if (currentIndentedWidth(flat) > options.lineWidth()
                && initializer instanceof CastExpr castExpr
                && castExpr.getExpression() instanceof MethodCallExpr methodCall
                && !initializerHasOwnBreak(initializer)) {
            return Doc.concat(
                    Doc.text(name + " = "),
                    castType.apply(castExpr.getType()),
                    Doc.text(" "),
                    brokenMethodCall.apply(methodCall));
        }
        if (currentIndentedWidth(flat) > options.lineWidth()
                && initializer instanceof ConditionalExpr conditionalExpr
                && !initializerHasOwnBreak(initializer)) {
            return conditionalInitializer(name, declarationPrefix + variable.getNameAsString(), conditionalExpr);
        }
        if (currentIndentedWidth(flat) > options.lineWidth()
                && initializer instanceof LambdaExpr lambdaExpr
                && !initializerHasOwnBreak(initializer)) {
            Optional<Doc> lambdaInitializer = variableWithBrokenLambdaParameters(
                    name,
                    declarationPrefix + variable.getNameAsString(),
                    lambdaExpr);
            if (lambdaInitializer.isPresent()) {
                return lambdaInitializer.orElseThrow();
            }
        }
        if (currentIndentedWidth(flat) > options.lineWidth()
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
     * Keeps a compact object-creation method chain on the continuation line when the whole chain fits after the break.
     */
    private Optional<Doc> variableWithCompactObjectCreationChain(String name, MethodCallExpr methodCall) {
        if (!methodCallChainRootIsObjectCreation.test(methodCall)
                || continuationStatementWidth(compact.apply(methodCall) + ";") > options.lineWidth()) {
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
        if (currentIndentedWidth(flatName + " = " + prefix + " {") <= options.lineWidth()) {
            return Optional.of(Doc.concat(Doc.text(name + " = " + prefix + " "), arrayInitializer.apply(initializer, true)));
        }
        Optional<String> compactContinuation = compactObjectCreationArrayInitializer(initializer);
        if (compactContinuation.isPresent()
                && currentIndentedWidth(prefix + " " + compactContinuation.orElseThrow()) <= options.lineWidth()) {
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
        return Optional.of("{" + compactJoin.apply(initializer.getValues()) + "}");
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
        if (currentIndentedWidth(flatName + " = " + prefix + "(") > options.lineWidth()) {
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
        if (currentIndentedWidth(flatName + " = " + prefix + "(") > options.lineWidth()) {
            return Optional.empty();
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
        if (type.getTypeArguments().isEmpty()
                || currentIndentedWidth(flatName + " = new " + typeNameWithoutArguments.apply(type) + "<") > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                Doc.text(name + " = new "),
                brokenClassOrInterfaceType.apply(type),
                Doc.text("()")));
    }

    /**
     * Breaks a direct method-call initializer at its arguments when the call prefix still fits on the assignment line.
     */
    private Optional<Doc> variableWithBrokenMethodCallArguments(
            String name,
            String flatName,
            MethodCallExpr methodCall) {
        if (methodCall.getArguments().isEmpty()
                || !methodCall.getAllContainedComments().isEmpty()
                || methodCall.getScope().filter(shouldPrintScopeAsDoc).isPresent()) {
            return Optional.empty();
        }
        String callPrefix = methodCallPrefix.apply(methodCall);
        String firstLine = flatName + " = " + callPrefix + "(";
        if (currentIndentedWidth(firstLine) > options.lineWidth()) {
            return Optional.empty();
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
     * Decides whether a method-call chain can start after {@code =} or must move entirely to an indented continuation.
     */
    private Doc variableWithMethodCallChain(
            String name,
            String flatName,
            MethodCallExpr methodCall,
            Doc chain) {
        String firstLine = mixedFieldMethodCallRoot.apply(methodCall)
                .map(compact)
                .orElseGet(() -> methodCallChainFirstLine(methodCall));
        if (methodCallChainRootIsObjectCreation.test(methodCall)
                && blockStatementWidth(flatName + " = " + firstLine + ";") > options.lineWidth()) {
            return Doc.concat(Doc.text(name + " ="), Doc.indent(Doc.concat(Doc.HARD_LINE, chain)));
        }
        if (currentIndentedWidth(flatName + " = " + firstLine) > options.lineWidth()) {
            return Doc.concat(Doc.text(name + " ="), Doc.indent(Doc.concat(Doc.HARD_LINE, chain)));
        }
        return Doc.concat(Doc.text(name + " = "), chain);
    }

    /**
     * Finds the text that will occupy the first line of a broken method-call chain for assignment-width checks.
     */
    private String methodCallChainFirstLine(MethodCallExpr methodCall) {
        List<MethodCallExpr> calls = new ArrayList<>();
        Expression root = methodCallChainRoot.apply(methodCall, calls);
        if (root instanceof MethodCallExpr && calls.size() == 1) {
            return compact.apply(methodCall);
        }
        return compact.apply(root);
    }

    /**
     * Chooses the conditional initializer shape by trying the least disruptive break first, then falling back to a
     * fully indented conditional when the condition line itself is too wide.
     */
    private Doc conditionalInitializer(String name, String flatName, ConditionalExpr initializer) {
        String conditionLine = flatName + " = " + compact.apply(initializer.getCondition());
        String compactInitializer = compact.apply(initializer);
        if (continuationStatementWidth(compactInitializer + ";") <= options.lineWidth()) {
            return Doc.concat(
                    Doc.text(name + " ="),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(compactInitializer))));
        }
        if (shouldBreakBeforeConditionalInitializer.test(initializer)) {
            return Doc.concat(
                    Doc.text(name + " ="),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, brokenConditionalExpression.apply(initializer))));
        }
        if (blockStatementWidth(conditionLine + ";") <= options.lineWidth()) {
            return Doc.concat(Doc.text(name + " = "), brokenConditionalExpression.apply(initializer));
        }
        return Doc.concat(
                Doc.text(name + " ="),
                Doc.indent(Doc.concat(Doc.HARD_LINE, brokenConditionalExpression.apply(initializer))));
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
                || currentIndentedWidth(flatName + " = (") > options.lineWidth()) {
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
    private String variableName(VariableDeclarator variable) {
        Doc leadingBlockComment = comments.ownComment(variable, BlockComment.class::isInstance);
        if (leadingBlockComment == Doc.EMPTY) {
            return variable.getNameAsString();
        }
        return commentText(leadingBlockComment) + " " + variable.getNameAsString();
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
     * Treats array initializers and broken array types as already owning the assignment continuation shape.
     */
    private boolean arrayCreationHasOwnBreak(ArrayCreationExpr expression) {
        return expression.getInitializer().isPresent() || arrayCreationTypeBreaks.test(expression);
    }

    private int currentIndentedWidth(String text) {
        return options.indentUnit().length() + text.length();
    }

    private int blockStatementWidth(String text) {
        return (options.indentUnit().length() * 2) + text.length();
    }

    private int continuationStatementWidth(String text) {
        return (options.indentUnit().length() * 3) + text.length();
    }

    private String commentText(Doc comment) {
        if (comment instanceof Doc.Text text) {
            return text.value();
        }
        return "";
    }
}
