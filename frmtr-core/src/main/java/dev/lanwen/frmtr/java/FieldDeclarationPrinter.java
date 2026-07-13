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
            JavaFormatContext context,
            Function<NodeWithAnnotations<?>, Doc> declarationAnnotations,
            Function<NodeWithModifiers<?>, String> modifiers,
            Function<NodeWithAnnotations<?>, String> inlineAnnotations,
            Function<Type, Doc> typeBody,
            Predicate<Type> typeCanBreak,
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
            VariableInitializerLayout.ForcedChainWithLayout initializerChain,
            VariableInitializerLayout.CanonicalFanChain canonicalFanChain,
            BiFunction<MethodCallExpr, ToIntFunction<String>, Optional<Doc>> packedMethodCallChain,
            Function<MethodCallExpr, Doc> methodCallWithSemicolon,
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
            Predicate<Expression> binaryFansChainOperand,
            Function<MethodCallExpr, String> methodCallPrefix,
            BiFunction<NodeList<Expression>, Doc, Doc> methodCallArgumentList,
            HuggableArgumentsRenderer huggableBlockLambdaArguments,
            Function<LambdaExpr, String> lambdaParameters,
            BiPredicate<LambdaExpr, String> lambdaParametersShouldBreak,
            Function<LambdaExpr, Doc> lambdaExpression
    ) {
        this.options = context.options;
        this.layoutWidth = context.layoutWidth;
        this.declarationAnnotations = declarationAnnotations;
        this.modifiers = modifiers;
        this.inlineAnnotations = inlineAnnotations;
        this.compactTypeLike = context.compactSource::compactTypeLike;
        this.typeBody = typeBody;
        this.typeCanBreak = typeCanBreak;
        this.initializers = new VariableInitializerLayout(
            context,
            expression,
            expressionWithoutOwnComment,
            binaryExpressionHasLineComments,
            binaryExpressionLinesWithComments,
            suffixedEnclosedExpression,
            arrayAccessWithBrokenEnclosedName,
            shouldKeepCastDivisionContinuationFlat,
            binaryExpressionLines,
            parenthesizedBreak,
            methodCall,
            brokenMethodCall,
            mixedFieldMethodCallChain,
            initializerChain,
            canonicalFanChain,
            packedMethodCallChain,
            methodCallWithSemicolon,
            mixedFieldMethodCallRoot,
            methodCallChainFirstLine,
            methodCallChainRootIsObjectCreation,
            methodCallChainIsSourceMultiline,
            methodCallChainInitializerShape,
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
            binaryFansChainOperand,
            methodCallPrefix,
            methodCallArgumentList,
            huggableBlockLambdaArguments,
            lambdaParameters,
            lambdaParametersShouldBreak,
            lambdaExpression
        );
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
            Type type = CStyleArrayDeclarators.sharedPrefixType(declaration.getVariables());
            String flatType = inlineAnnotations.apply(declaration) + compactTypeLike.apply(type) + " ";
            declarationPrefix += flatType;
            if (fieldTypeShouldBreak(type, declaration.getVariables(), declarationPrefix)) {
                Doc variables = Doc.joinComma(
                    declaration.getVariables()
                            .stream()
                            .map(variable -> variableDoc(variable, "", isLastVariable(declaration, variable)))
                            .toList()
                );
                docs.add(
                    Doc.group(
                        Doc.concat(
                            Doc.text(inlineAnnotations.apply(declaration)),
                            typeBody.apply(type),
                            Doc.text(" "),
                            variables
                        )
                    )
                );
                return Doc.concat(docs);
            }
            docs.add(Doc.text(flatType));
        }
        String variableDeclarationPrefix = declarationPrefix;
        docs.add(
            Doc.group(
                Doc.joinComma(
                    declaration.getVariables()
                            .stream()
                            .map(variable -> variableDoc(
                                    variable,
                                    variableDeclarationPrefix,
                                    isLastVariable(declaration, variable)
                            ))
                            .toList()
                )
            )
        );
        return Doc.concat(docs);
    }

    /**
     * Lets the type body own generic argument breaks when a field type and name cannot fit on one member line.
     */
    private boolean fieldTypeShouldBreak(
            Type type,
            NodeList<VariableDeclarator> variables,
            String declarationPrefix
    ) {
        return (
            typeCanBreak.test(type)
            && variables.stream()
                    // C10-c: measure the field type-and-name at the declarator's true rendered type-body depth
                    // ({@link LayoutWidth#nodeLine}) instead of the fixed CURRENT baseline, so a field in a nested type is
                    // measured at the column it renders at (F3).
                    .anyMatch(variable -> layoutWidth.nodeLine(
                            variable,
                            declarationPrefix + variable.getNameAsString()
                        ) > options.lineWidth()
                    )
        );
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

    private Doc variableDoc(VariableDeclarator variable, String declarationPrefix, boolean statementTerminator) {
        return statementTerminator
            ? variableWithStatementTerminator(variable, declarationPrefix)
            : variable(variable, declarationPrefix);
    }

    private boolean isLastVariable(FieldDeclaration declaration, VariableDeclarator variable) {
        return declaration.getVariables().getLast().filter(last -> last == variable).isPresent();
    }

    @FunctionalInterface
    interface HuggableArgumentsRenderer {
        Optional<Doc> render(
                String prefix,
                NodeList<Expression> arguments,
                ToIntFunction<String> firstLineWidth
        );
    }
}
