package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Prints method declarations after body dispatch has selected the method branch.
 *
 * <p>This helper owns the structured method-header decision tree: the raw commented-signature escape hatch, declaration
 * annotations, modifiers, method type parameters, return type and name assembly, parameter-list placement,
 * throws-clause placement, and the body-versus-semicolon suffix. It intentionally delegates the raw fallback to
 * {@link CommentedMethodSignaturePrinter}, parameter and type-parameter layout to {@link CallableSignaturePrinter},
 * throws wrapping to the caller, and block rendering back to {@link JavaPrinter} so methods share those rules with
 * constructors, classes, records, and statements.
 *
 * <p>Representative fixture pairs live at
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/generic_class/input.java} and
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/generic_class/frmtr.output.java}; method throws
 * wrapping and abstract-method semicolons are covered by
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/throws/input.java} and
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/throws/frmtr.output.java}.
 */
final class MethodDeclarationPrinter {
    private final RawSource rawSource;
    private final RawPreservedSource rawPreservedSource;
    private final CommentedMethodSignaturePrinter commentedMethodSignatures;
    private final CallableSignaturePrinter callableSignatures;
    private final Function<NodeWithAnnotations<?>, Doc> declarationAnnotations;
    private final Function<NodeWithModifiers<?>, String> modifiers;
    private final Function<NodeList<TypeParameter>, String> flatTypeParameters;
    private final Function<NodeWithAnnotations<?>, String> inlineAnnotations;
    private final Function<Node, String> compact;
    private final Function<Type, Doc> typeBody;
    private final Predicate<Type> typeCanBreak;
    private final ThrowsClauseRenderer throwsClause;
    private final Function<BlockStmt, Doc> block;

    MethodDeclarationPrinter(
            RawSource rawSource,
            RawPreservedSource rawPreservedSource,
            CommentedMethodSignaturePrinter commentedMethodSignatures,
            CallableSignaturePrinter callableSignatures,
            Function<NodeWithAnnotations<?>, Doc> declarationAnnotations,
            Function<NodeWithModifiers<?>, String> modifiers,
            Function<NodeList<TypeParameter>, String> flatTypeParameters,
            Function<NodeWithAnnotations<?>, String> inlineAnnotations,
            Function<Node, String> compact,
            Function<Type, Doc> typeBody,
            Predicate<Type> typeCanBreak,
            ThrowsClauseRenderer throwsClause,
            Function<BlockStmt, Doc> block) {
        this.rawSource = rawSource;
        this.rawPreservedSource = rawPreservedSource;
        this.commentedMethodSignatures = commentedMethodSignatures;
        this.callableSignatures = callableSignatures;
        this.declarationAnnotations = declarationAnnotations;
        this.modifiers = modifiers;
        this.flatTypeParameters = flatTypeParameters;
        this.inlineAnnotations = inlineAnnotations;
        this.compact = compact;
        this.typeBody = typeBody;
        this.typeCanBreak = typeCanBreak;
        this.throwsClause = throwsClause;
        this.block = block;
    }

    /**
     * Prints one method declaration, first giving commented signatures their raw-source fallback and then using the
     * structured header path for methods JavaParser exposes cleanly.
     */
    Doc method(MethodDeclaration declaration) {
        String raw = rawSource.raw(declaration);
        Optional<String> commentedMethod = commentedMethodSignatures.tryFormat(declaration, raw);
        if (commentedMethod.isPresent()) {
            return rawPreservedSource.rawWithoutOwnComment(declaration, commentedMethod.orElseThrow());
        }
        List<Doc> docs = new ArrayList<>();
        docs.add(declarationAnnotations.apply(declaration));
        String prefix = modifiers.apply(declaration);
        docs.add(Doc.text(prefix));
        if (!declaration.getTypeParameters().isEmpty()) {
            String typeParameters = flatTypeParameters.apply(declaration.getTypeParameters()) + " ";
            prefix += typeParameters;
            docs.add(callableSignatures.typeParameters(declaration.getTypeParameters()));
            docs.add(Doc.text(" "));
        }
        String returnType = inlineAnnotations.apply(declaration) + compact.apply(declaration.getType());
        String signature = returnType + " " + declaration.getNameAsString();
        prefix += signature;
        boolean breakReturnType = shouldBreakReturnType(declaration, prefix);
        docs.add(returnType(declaration, returnType, breakReturnType));
        docs.add(callableSignatures.parameters(
                declaration,
                !breakReturnType
                        && callableSignatures.parametersBreak(prefix, declaration, methodParameterSuffix(declaration))));
        if (!declaration.getThrownExceptions().isEmpty()) {
            docs.add(throwsClause.render(
                    prefix,
                    declaration.getParameters(),
                    declaration.getThrownExceptions(),
                    declaration.getBody().isPresent() ? " {" : ";"));
        }
        docs.add(declaration.getBody().map(body -> Doc.concat(Doc.text(" "), block.apply(body))).orElse(Doc.text(";")));
        return Doc.concat(docs);
    }

    /**
     * Chooses the flat suffix used to decide whether method parameters must break.
     *
     * <p>Empty method bodies use {@code {}} as the width estimate, non-empty bodies reserve only the opening brace, and
     * abstract/interface methods reserve the trailing semicolon.
     */
    private String methodParameterSuffix(MethodDeclaration declaration) {
        return declaration.getBody()
                .map(body -> body.getStatements().isEmpty() && body.getOrphanComments().isEmpty() ? " {}" : " {")
                .orElse(";");
    }

    private boolean shouldBreakReturnType(MethodDeclaration declaration, String prefix) {
        return declaration.getParameters().isEmpty()
                && declaration.getReceiverParameter().isEmpty()
                && typeCanBreak.test(declaration.getType())
                && callableSignatures.parametersBreak(prefix, declaration, methodParameterSuffix(declaration));
    }

    private Doc returnType(MethodDeclaration declaration, String returnType, boolean breakReturnType) {
        if (!breakReturnType) {
            return Doc.text(returnType + " " + declaration.getNameAsString());
        }
        return Doc.group(Doc.concat(
                Doc.text(inlineAnnotations.apply(declaration)),
                typeBody.apply(declaration.getType()),
                Doc.text(" " + declaration.getNameAsString())));
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
