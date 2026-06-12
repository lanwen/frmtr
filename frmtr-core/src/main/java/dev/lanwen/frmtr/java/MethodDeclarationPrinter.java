package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
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
 * {@code frmtr-core/src/test/resources/format/generic-type-body-breaks/input.java} and
 * {@code frmtr-core/src/test/resources/format/generic-type-body-breaks/frmtr.output.java}; method throws
 * wrapping and abstract-method semicolons are covered by
 * {@code frmtr-core/src/test/resources/format/throws-clause-layout/input.java} and
 * {@code frmtr-core/src/test/resources/format/throws-clause-layout/frmtr-120.output.java}.
 */
final class MethodDeclarationPrinter {
    private final CommentTracker comments;
    private final JavaCommentPlacementPolicy commentPlacement;
    private final RawSource rawSource;
    private final SourceShape sourceShape;
    private final RawPreservedSource rawPreservedSource;
    private final CommentedMethodSignaturePrinter commentedMethodSignatures;
    private final CallableSignaturePrinter callableSignatures;
    private final Function<NodeWithAnnotations<?>, Doc> declarationAnnotations;
    private final Function<NodeWithModifiers<?>, String> modifiers;
    private final Function<NodeList<TypeParameter>, String> flatTypeParameters;
    private final Function<NodeWithAnnotations<?>, String> inlineAnnotations;
    private final Function<Node, String> compact;
    private final Function<Type, Doc> typeBody;
    private final Function<ClassOrInterfaceType, Doc> brokenClassOrInterfaceType;
    private final Predicate<Type> typeCanBreak;
    private final ThrowsClauseRenderer throwsClause;
    private final Function<BlockStmt, Doc> block;

    MethodDeclarationPrinter(
            CommentTracker comments,
            JavaCommentPlacementPolicy commentPlacement,
            RawSource rawSource,
            SourceShape sourceShape,
            RawPreservedSource rawPreservedSource,
            CommentedMethodSignaturePrinter commentedMethodSignatures,
            CallableSignaturePrinter callableSignatures,
            Function<NodeWithAnnotations<?>, Doc> declarationAnnotations,
            Function<NodeWithModifiers<?>, String> modifiers,
            Function<NodeList<TypeParameter>, String> flatTypeParameters,
            Function<NodeWithAnnotations<?>, String> inlineAnnotations,
            Function<Node, String> compact,
            Function<Type, Doc> typeBody,
            Function<ClassOrInterfaceType, Doc> brokenClassOrInterfaceType,
            Predicate<Type> typeCanBreak,
            ThrowsClauseRenderer throwsClause,
            Function<BlockStmt, Doc> block) {
        this.comments = comments;
        this.commentPlacement = commentPlacement;
        this.rawSource = rawSource;
        this.sourceShape = sourceShape;
        this.rawPreservedSource = rawPreservedSource;
        this.commentedMethodSignatures = commentedMethodSignatures;
        this.callableSignatures = callableSignatures;
        this.declarationAnnotations = declarationAnnotations;
        this.modifiers = modifiers;
        this.flatTypeParameters = flatTypeParameters;
        this.inlineAnnotations = inlineAnnotations;
        this.compact = compact;
        this.typeBody = typeBody;
        this.brokenClassOrInterfaceType = brokenClassOrInterfaceType;
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
        docs.add(annotationMethodGapComments(declaration));
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
        boolean sourceParametersBreak = sourceShape.callableParametersSpanMultipleLines(declaration);
        docs.add(returnType(declaration, returnType, breakReturnType));
        docs.add(callableSignatures.parameters(
                declaration,
                sourceParametersBreak
                        || !breakReturnType
                        && callableSignatures.parametersBreak(prefix, declaration, methodParameterSuffix(declaration))));
        if (!declaration.getThrownExceptions().isEmpty()) {
            docs.add(throwsClause.render(
                    prefix,
                    declaration.getParameters(),
                    declaration.getThrownExceptions(),
                    declaration.getBody().isPresent() ? " {" : ";",
                    sourceShape.throwsStartsOnOwnLine(declaration)));
        }
        docs.add(declaration.getBody().map(body -> Doc.concat(Doc.text(" "), block.apply(body))).orElse(Doc.text(";")));
        return Doc.concat(docs);
    }

    private Doc annotationMethodGapComments(MethodDeclaration declaration) {
        return Doc.concat(annotationMethodGapCommentTrivia(declaration)
                .map(comments::comment)
                .filter(comment -> comment != Doc.EMPTY)
                .map(comment -> Doc.concat(comment, Doc.HARD_LINE))
                .toList());
    }

    private java.util.stream.Stream<JavaCommentTrivia> annotationMethodGapCommentTrivia(MethodDeclaration declaration) {
        if (declaration.getAnnotations().isEmpty()) {
            return java.util.stream.Stream.empty();
        }
        Optional<Integer> lastAnnotationLine = declaration.getAnnotations().stream()
                .flatMap(annotation -> annotationVisibleEndLine(annotation).stream())
                .max(Integer::compareTo);
        Optional<Integer> nameLine = declaration.getName().getRange().map(range -> range.begin.line);
        if (lastAnnotationLine.isEmpty() || nameLine.isEmpty()) {
            return java.util.stream.Stream.empty();
        }
        return commentPlacement.containedComments(declaration).stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.beginLine(Integer.MIN_VALUE) > lastAnnotationLine.orElseThrow())
                .filter(comment -> comment.endLine(Integer.MAX_VALUE) < nameLine.orElseThrow());
    }

    private Optional<Integer> annotationVisibleEndLine(AnnotationExpr annotation) {
        return annotation.getRange().map(range -> {
            long lineCount = rawSource.rawWithoutOwnComment(annotation).lines().count();
            return range.begin.line + Math.toIntExact(lineCount) - 1;
        });
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
        return declaration.getReceiverParameter().isEmpty()
                && typeCanBreak.test(declaration.getType())
                && sourceShape.spansMultipleLines(declaration.getType())
                && callableSignatures.parametersBreak(prefix, declaration, methodParameterSuffix(declaration));
    }

    private Doc returnType(MethodDeclaration declaration, String returnType, boolean breakReturnType) {
        if (!breakReturnType) {
            return Doc.text(returnType + " " + declaration.getNameAsString());
        }
        if (declaration.getType() instanceof ClassOrInterfaceType classOrInterfaceType) {
            return Doc.concat(
                    Doc.text(inlineAnnotations.apply(declaration)),
                    brokenClassOrInterfaceType.apply(classOrInterfaceType),
                    Doc.text(" " + declaration.getNameAsString()));
        }
        return Doc.concat(
                Doc.text(inlineAnnotations.apply(declaration)),
                typeBody.apply(declaration.getType()),
                Doc.text(" " + declaration.getNameAsString()));
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
                String suffix,
                boolean forceBreak);
    }
}
