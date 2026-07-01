package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.stmt.BlockStmt;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Prints constructor declarations after body dispatch has selected a constructor branch.
 *
 * <p>This helper owns normal and compact constructor header assembly: declaration annotations, modifiers, constructor
 * type parameters, constructor names, parameter-list placement, throws-clause placement, and the body handoff. It
 * intentionally delegates parameter-list rules to {@link CallableSignaturePrinter}, throws wrapping to the caller, and
 * block rendering back to {@link JavaPrinter} so method declarations and statement bodies keep their shared behavior
 * source.
 *
 * <p>Representative fixture pairs live at
 * {@code frmtr-core/src/test/resources/format/constructor-chain-roots/input.java} and
 * {@code frmtr-core/src/test/resources/format/constructor-chain-roots/frmtr-default.output.java}; constructor
 * throws wrapping is covered by {@code frmtr-core/src/test/resources/format/throws-clause-layout/input.java}
 * and {@code frmtr-core/src/test/resources/format/throws-clause-layout/frmtr-default.output.java}; compact
 * constructors are covered by {@code frmtr-core/src/test/resources/format/record-component-spacing/input.java}
 * and {@code frmtr-core/src/test/resources/format/record-component-spacing/frmtr-default.output.java}.
 */
final class ConstructorDeclarationPrinter {

    private final CallableSignaturePrinter callableSignatures;

    private final Function<NodeWithAnnotations<?>, Doc> annotations;

    private final Function<NodeWithModifiers<?>, String> modifiers;

    private final Function<List<? extends Node>, String> compactJoin;

    private final ThrowsClauseRenderer throwsClause;

    private final Function<BlockStmt, Doc> block;

    ConstructorDeclarationPrinter(
            CallableSignaturePrinter callableSignatures,
            Function<NodeWithAnnotations<?>, Doc> annotations,
            Function<NodeWithModifiers<?>, String> modifiers,
            Function<List<? extends Node>, String> compactJoin,
            ThrowsClauseRenderer throwsClause,
            Function<BlockStmt, Doc> block
    ) {
        this.callableSignatures = callableSignatures;
        this.annotations = annotations;
        this.modifiers = modifiers;
        this.compactJoin = compactJoin;
        this.throwsClause = throwsClause;
        this.block = block;
    }

    /**
     * Prints a normal constructor declaration with parameters and a required body.
     */
    Doc constructor(ConstructorDeclaration declaration) {
        List<Doc> docs = new ArrayList<>();
        docs.add(annotations.apply(declaration));
        String prefix = modifiers.apply(declaration);
        docs.add(Doc.text(prefix));
        if (!declaration.getTypeParameters().isEmpty()) {
            String typeParameters = "<" + compactJoin.apply(declaration.getTypeParameters()) + "> ";
            prefix += typeParameters;
            docs.add(Doc.text(typeParameters));
        }
        prefix += declaration.getNameAsString();
        docs.add(Doc.text(declaration.getNameAsString()));
        boolean parametersBreak = callableSignatures.parametersBreak(
            prefix,
            declaration,
            constructorParameterSuffix(declaration)
        );
        boolean compactContinuationParameters = !declaration.getThrownExceptions().isEmpty();
        docs.add(parameters(declaration, parametersBreak, compactContinuationParameters));
        if (!declaration.getThrownExceptions().isEmpty()) {
            docs.add(
                throwsClause.render(
                    prefix,
                    declaration.getParameters(),
                    declaration.getThrownExceptions(),
                    LayoutContext.root().withTrailingContent(" {"),
                    false,
                    parametersBreak && callableSignatures.parametersCanBreak(declaration)
                )
            );
        }
        docs.add(Doc.text(" "));
        docs.add(block.apply(declaration.getBody()));
        return Doc.concat(docs);
    }

    /**
     * Prints a compact record constructor, using an empty parameter list only for throws-width decisions because compact
     * constructors do not print their record components in the constructor header.
     */
    Doc compactConstructor(CompactConstructorDeclaration declaration) {
        List<Doc> docs = new ArrayList<>();
        docs.add(annotations.apply(declaration));
        String prefix = modifiers.apply(declaration);
        docs.add(Doc.text(prefix));
        if (!declaration.getTypeParameters().isEmpty()) {
            String typeParameters = "<" + compactJoin.apply(declaration.getTypeParameters()) + "> ";
            prefix += typeParameters;
            docs.add(Doc.text(typeParameters));
        }
        prefix += declaration.getNameAsString();
        docs.add(Doc.text(declaration.getNameAsString()));
        if (!declaration.getThrownExceptions().isEmpty()) {
            docs.add(
                throwsClause.render(
                    prefix,
                    NodeList.nodeList(),
                    declaration.getThrownExceptions(),
                    LayoutContext.root().withTrailingContent(" {"),
                    false,
                    false
                )
            );
        }
        docs.add(Doc.text(" "));
        docs.add(block.apply(declaration.getBody()));
        return Doc.concat(docs);
    }

    private String constructorParameterSuffix(ConstructorDeclaration declaration) {
        if (declaration.getThrownExceptions().isEmpty()) {
            return " {}";
        }
        return " throws " + compactJoin.apply(declaration.getThrownExceptions()) + " {";
    }

    private Doc parameters(
            ConstructorDeclaration declaration,
            boolean parametersBreak,
            boolean compactContinuationParameters
    ) {
        if (
            !parametersBreak
            || !compactContinuationParameters
            || !callableSignatures.parametersFitOnContinuation(declaration)
        ) {
            return callableSignatures.parameters(declaration, parametersBreak);
        }
        return callableSignatures.compactContinuationParameters(declaration);
    }

    /**
     * Keeps throws-clause width logic with the caller because methods and constructors currently share that rule.
     */
    @FunctionalInterface
    interface ThrowsClauseRenderer {
        Doc render(
                String prefix,
                NodeList<Parameter> parameters,
                NodeList<? extends Node> thrownExceptions,
                LayoutContext layout,
                boolean forceBreak,
                boolean parametersBreak
        );
    }
}
