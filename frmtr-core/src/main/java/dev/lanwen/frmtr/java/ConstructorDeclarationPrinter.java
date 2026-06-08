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
 * <p>This helper owns normal and compact constructor header assembly: leading comments, declaration annotations,
 * modifiers, constructor type parameters, constructor names, parameter-list placement, throws-clause placement, and the
 * body handoff. It intentionally delegates parameter-list rules to {@link CallableSignaturePrinter}, throws wrapping to
 * the caller, and block rendering back to {@link JavaPrinter} so method declarations and statement bodies keep their
 * shared behavior source.
 *
 * <p>Representative fixture pairs live at
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/constructors/input.java} and
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/constructors/frmtr.output.java}; constructor
 * throws wrapping is covered by {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/throws/input.java}
 * and {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/throws/frmtr.output.java}; compact
 * constructors are covered by {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/records/input.java}
 * and {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/records/frmtr.output.java}.
 */
final class ConstructorDeclarationPrinter {
    private final CommentTracker comments;
    private final CallableSignaturePrinter callableSignatures;
    private final Function<NodeWithAnnotations<?>, Doc> annotations;
    private final Function<NodeWithModifiers<?>, String> modifiers;
    private final Function<List<? extends Node>, String> compactJoin;
    private final ThrowsClauseRenderer throwsClause;
    private final Function<BlockStmt, Doc> block;

    ConstructorDeclarationPrinter(
            CommentTracker comments,
            CallableSignaturePrinter callableSignatures,
            Function<NodeWithAnnotations<?>, Doc> annotations,
            Function<NodeWithModifiers<?>, String> modifiers,
            Function<List<? extends Node>, String> compactJoin,
            ThrowsClauseRenderer throwsClause,
            Function<BlockStmt, Doc> block) {
        this.comments = comments;
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
        docs.add(comments.leading(declaration));
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
        docs.add(callableSignatures.parameters(
                declaration,
                callableSignatures.parametersBreak(prefix, declaration, " {}")));
        if (!declaration.getThrownExceptions().isEmpty()) {
            docs.add(throwsClause.render(prefix, declaration.getParameters(), declaration.getThrownExceptions(), " {"));
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
        docs.add(comments.leading(declaration));
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
            docs.add(throwsClause.render(prefix, NodeList.nodeList(), declaration.getThrownExceptions(), " {"));
        }
        docs.add(Doc.text(" "));
        docs.add(block.apply(declaration.getBody()));
        return Doc.concat(docs);
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
                String suffix);
    }
}
