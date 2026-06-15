package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
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
import java.util.function.ToIntFunction;

/**
 * Prints field declarations and their variable initializer decision tree after body dispatch has selected the field
 * branch.
 *
 * <p>This helper owns declaration-level annotations and modifiers, the comma-joined variable list, comments before and
 * after {@code =}, source-leading initializer line comments, and the field-width forks that decide when array creation,
 * object creation, method-call chains, conditional expressions, direct block-lambda openers, and lambda parameters
 * should break. It intentionally delegates general expression, type, method-call, binary-expression, object-creation,
 * lambda, and raw-source-sensitive
 * formatting back to {@link JavaPrinter}, {@link ObjectCreationPrinter}, and other shared-printer callbacks so those
 * shared printers keep one behavior source.
 *
 * <p>Representative fixture pairs live at
 * {@code frmtr-core/src/test/resources/format/variable-declarations/input.java} and
 * {@code frmtr-core/src/test/resources/format/variable-declarations/frmtr-default.output.java}; the comment cases
 * near {@code variableWithComment1} through {@code variableWithComment4} cover the before/after {@code =} branches.
 */
final class FieldDeclarationPrinter {
    private final FormatterOptions options;
    private final LayoutWidth layoutWidth;
    private final Function<NodeWithAnnotations<?>, Doc> declarationAnnotations;
    private final Function<NodeWithModifiers<?>, String> modifiers;
    private final Function<NodeWithAnnotations<?>, String> inlineAnnotations;
    private final Function<Node, String> compactTypeLike;
    private final Function<Type, Doc> typeBody;
    private final Predicate<Type> typeCanBreak;
    private final VariableInitializerLayout initializers;

    FieldDeclarationPrinter(
            CommentTracker comments,
            RawSource rawSource,
            SourceShape sourceShape,
            FormatterOptions options,
            LayoutWidth layoutWidth,
            Function<NodeWithAnnotations<?>, Doc> declarationAnnotations,
            Function<NodeWithModifiers<?>, String> modifiers,
            Function<NodeWithAnnotations<?>, String> inlineAnnotations,
            Function<Node, String> compactTypeLike,
            Function<Type, Doc> typeBody,
            Predicate<Type> typeCanBreak,
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
            HuggableArgumentsRenderer huggableBlockLambdaArguments,
            Function<LambdaExpr, String> lambdaParameters,
            BiPredicate<LambdaExpr, String> lambdaParametersShouldBreak,
            Function<LambdaExpr, Doc> lambdaExpression) {
        this.options = options;
        this.layoutWidth = layoutWidth;
        this.declarationAnnotations = declarationAnnotations;
        this.modifiers = modifiers;
        this.inlineAnnotations = inlineAnnotations;
        this.compactTypeLike = compactTypeLike;
        this.typeBody = typeBody;
        this.typeCanBreak = typeCanBreak;
        this.initializers = new VariableInitializerLayout(
                comments,
                rawSource,
                sourceShape,
                options,
                layoutWidth,
                compactTypeLike,
                compact,
                compactWithoutOwnComment,
                compactJoin,
                expression,
                expressionWithoutOwnComment,
                binaryExpressionHasLineComments,
                binaryExpressionLinesWithComments,
                suffixedEnclosedExpression,
                arrayAccessWithBrokenEnclosedName,
                shouldKeepCastDivisionContinuationFlat,
                binaryExpressionLines,
                methodCall,
                brokenMethodCall,
                mixedFieldMethodCallChain,
                forcedMethodCallChain,
                forcedMethodCallChainWithSemicolon,
                methodCallChainHasFinalTrailingLineComment,
                mixedFieldMethodCallRoot,
                methodCallChainFirstLine,
                methodCallChainRootIsObjectCreation,
                methodCallChainIsSourceMultiline,
                castType,
                brokenConditionalExpression,
                shouldBreakBeforeConditionalInitializer,
                arrayCreationTypeBreaks,
                arrayCreationPrefix,
                arrayInitializer,
                compactArrayInitializerWithSourceSpacing,
                objectCreationPrefix,
                typeNameWithoutArguments,
                brokenClassOrInterfaceType,
                shouldPrintScopeAsDoc,
                methodCallPrefix,
                huggableBlockLambdaArguments,
                lambdaParameters,
                lambdaParametersShouldBreak,
                lambdaExpression);
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
            Type type = declaration.getVariables().get(0).getType();
            String flatType = inlineAnnotations.apply(declaration) + compactTypeLike.apply(type) + " ";
            declarationPrefix += flatType;
            if (fieldTypeShouldBreak(type, declaration.getVariables(), declarationPrefix)) {
                Doc variables = Doc.joinComma(declaration.getVariables().stream()
                        .map(variable -> variable(variable, ""))
                        .toList());
                docs.add(Doc.group(Doc.concat(
                        Doc.text(inlineAnnotations.apply(declaration)),
                        typeBody.apply(type),
                        Doc.text(" "),
                        variables)));
                docs.add(Doc.text(";"));
                return Doc.concat(docs);
            }
            docs.add(Doc.text(flatType));
        }
        String variableDeclarationPrefix = declarationPrefix;
        docs.add(Doc.group(Doc.joinComma(declaration.getVariables().stream()
                .map(variable -> variable(variable, variableDeclarationPrefix))
                .toList())));
        docs.add(Doc.text(";"));
        return Doc.concat(docs);
    }

    /**
     * Lets the type body own generic argument breaks when a field type and name cannot fit on one member line.
     */
    private boolean fieldTypeShouldBreak(
            Type type,
            NodeList<VariableDeclarator> variables,
            String declarationPrefix) {
        return typeCanBreak.test(type)
                && variables.stream()
                        .anyMatch(variable -> layoutWidth.currentIndented(
                                        declarationPrefix + variable.getNameAsString())
                                > options.lineWidth());
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
                .map(expression -> initializers.variableWithInitializer(variable, expression, declarationPrefix))
                .orElseGet(() -> Doc.text(initializers.variableName(variable)));
    }

    Doc variableWithStatementTerminator(VariableDeclarator variable, String declarationPrefix) {
        return initializers.variableWithStatementTerminator(variable, declarationPrefix);
    }

    @FunctionalInterface
    interface HuggableArgumentsRenderer {
        Optional<Doc> render(
                String prefix,
                NodeList<Expression> arguments,
                ToIntFunction<String> firstLineWidth);
    }
}
