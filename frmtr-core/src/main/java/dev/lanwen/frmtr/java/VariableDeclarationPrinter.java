package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.type.Type;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Renders local variable declaration expressions after statement dispatch has selected declaration syntax.
 *
 * <p>This helper owns local-variable declaration layout: annotation and modifier prefix assembly, the first-declarator
 * type prefix, breakable local type bodies, comma-separated declarator lists, and the local-only rules that choose when
 * initialized declarators must hard break. The boundary exists because local declarations share declarator initializer
 * rendering with fields, but their declaration-level wrapping policy is statement-specific.
 *
 * <p>Broad expression dispatch, field declaration rendering, compact source text, type policy, annotation placement
 * policy, and modifier string policy stay with their existing owners. This helper receives those decisions as callbacks
 * and only decides how a {@link VariableDeclarationExpr} joins the already-rendered pieces.
 */
final class VariableDeclarationPrinter {
    private final FormatterOptions options;
    private final Function<NodeWithAnnotations<?>, Doc> annotations;
    private final Function<NodeWithModifiers<?>, String> modifiers;
    private final Function<Node, String> compactTypeLike;
    private final Function<Type, Doc> typeBody;
    private final Predicate<Type> typeCanBreak;
    private final BiFunction<VariableDeclarator, String, Doc> variable;
    private final BiFunction<VariableDeclarator, String, Doc> terminatedVariable;
    private final ToIntFunction<String> currentIndentedWidth;

    VariableDeclarationPrinter(
            FormatterOptions options,
            Function<NodeWithAnnotations<?>, Doc> annotations,
            Function<NodeWithModifiers<?>, String> modifiers,
            Function<Node, String> compactTypeLike,
            Function<Type, Doc> typeBody,
            Predicate<Type> typeCanBreak,
            BiFunction<VariableDeclarator, String, Doc> variable,
            BiFunction<VariableDeclarator, String, Doc> terminatedVariable,
            ToIntFunction<String> currentIndentedWidth) {
        this.options = options;
        this.annotations = annotations;
        this.modifiers = modifiers;
        this.compactTypeLike = compactTypeLike;
        this.typeBody = typeBody;
        this.typeCanBreak = typeCanBreak;
        this.variable = variable;
        this.terminatedVariable = terminatedVariable;
        this.currentIndentedWidth = currentIndentedWidth;
    }

    /**
     * Prints a local variable declaration expression without the trailing semicolon owned by the statement printer.
     *
     * <p>The annotation and modifier callbacks intentionally match the previous {@link JavaPrinter} path, so local
     * annotations stay in the same declaration position and modifier ordering remains caller-owned. When the first
     * variable's type can break, the type body is allowed to own the break before the declarator list; otherwise local
     * declarations either hard-break initialized multi-declarator declarations or use the ordinary grouped comma join.
     */
    Doc variableDeclaration(VariableDeclarationExpr declaration) {
        return variableDeclaration(declaration, false);
    }

    Doc variableDeclarationStatement(VariableDeclarationExpr declaration) {
        return variableDeclaration(declaration, true);
    }

    private Doc variableDeclaration(VariableDeclarationExpr declaration, boolean statementTerminator) {
        List<Doc> docs = new ArrayList<>();
        docs.add(annotations.apply(declaration));
        docs.add(Doc.text(modifiers.apply(declaration)));
        String declarationPrefix = modifiers.apply(declaration);
        if (!declaration.getVariables().isEmpty()) {
            Type type = declaration.getVariables().get(0).getType();
            String flatType = compactTypeLike.apply(type) + " ";
            declarationPrefix += flatType;
            if (localVariableTypeShouldBreak(type, declaration.getVariables(), declarationPrefix)) {
                Doc variables = Doc.join(Doc.concat(Doc.text(","), Doc.LINE), declaration.getVariables().stream()
                        .map(variable -> variableDoc(
                                variable,
                                localVariableDeclarationPrefix(variable, ""),
                                statementTerminator && isLastVariable(declaration, variable)))
                        .toList());
                docs.add(Doc.group(Doc.concat(typeBody.apply(type), Doc.text(" "), variables)));
                return Doc.concat(docs);
            }
            docs.add(Doc.text(flatType));
        }
        String variableDeclarationPrefix = declarationPrefix;
        if (localVariableDeclaratorsShouldBreak(declaration.getVariables())) {
            docs.add(Doc.indent(Doc.join(Doc.concat(Doc.text(","), Doc.HARD_LINE), declaration.getVariables().stream()
                    .map(variable -> variableDoc(
                            variable,
                            localVariableDeclarationPrefix(variable, variableDeclarationPrefix),
                            statementTerminator && isLastVariable(declaration, variable)))
                    .toList())));
            return Doc.concat(docs);
        }
        docs.add(Doc.group(Doc.join(Doc.concat(Doc.text(","), Doc.LINE), declaration.getVariables().stream()
                .map(variable -> variableDoc(
                        variable,
                        localVariableDeclarationPrefix(variable, variableDeclarationPrefix),
                        statementTerminator && isLastVariable(declaration, variable)))
                .toList())));
        return Doc.concat(docs);
    }

    private Doc variableDoc(VariableDeclarator variable, String declarationPrefix, boolean statementTerminator) {
        return statementTerminator
                ? terminatedVariable.apply(variable, declarationPrefix)
                : this.variable.apply(variable, declarationPrefix);
    }

    private boolean isLastVariable(VariableDeclarationExpr declaration, VariableDeclarator variable) {
        return declaration.getVariables().getLast().filter(last -> last == variable).isPresent();
    }

    /**
     * Forces a hard-line declarator list when a single local declaration has multiple initialized variables.
     *
     * <p>The formatter keeps uninitialized comma lists groupable, but initializers make each declarator own enough
     * layout state around {@code =} that the legacy local-variable path separated them with hard lines.
     */
    private boolean localVariableDeclaratorsShouldBreak(NodeList<VariableDeclarator> variables) {
        return variables.size() > 1 && variables.stream().anyMatch(variable -> variable.getInitializer().isPresent());
    }

    /**
     * Chooses the breakable type-body path only when the type printer can break the type and at least one
     * declaration-prefix-plus-name combination would overflow the configured line width.
     *
     * <p>The check deliberately ignores initializer text; initializer wrapping remains with the shared variable
     * declarator renderer after the type/body fork has been chosen.
     */
    private boolean localVariableTypeShouldBreak(
            Type type,
            NodeList<VariableDeclarator> variables,
            String declarationPrefix) {
        return typeCanBreak.test(type)
                && variables.stream()
                        .anyMatch(variable -> currentIndentedWidth.applyAsInt(
                                        declarationPrefix + variable.getNameAsString())
                                > options.lineWidth());
    }

    /**
     * Supplies the already-flat declaration prefix only to initializer shapes that use it for width-sensitive breaking.
     *
     * <p>Array creation, binary, cast, conditional, lambda, method-call, object-creation, and switch-expression
     * initializers can compare their continuation against the full local declaration prefix. Simpler initializers keep
     * an empty prefix so their existing flat rendering is not affected by the surrounding declaration text.
     */
    private String localVariableDeclarationPrefix(VariableDeclarator variable, String declarationPrefix) {
        return variable.getInitializer()
                .filter(initializer -> initializer instanceof ArrayCreationExpr
                        || initializer instanceof BinaryExpr
                        || initializer instanceof CastExpr
                        || initializer instanceof ConditionalExpr
                        || initializer instanceof LambdaExpr
                        || initializer instanceof MethodCallExpr
                        || initializer instanceof ObjectCreationExpr
                        || initializer instanceof SwitchExpr)
                .map(ignored -> options.indentUnit() + declarationPrefix)
                .orElse("");
    }
}
