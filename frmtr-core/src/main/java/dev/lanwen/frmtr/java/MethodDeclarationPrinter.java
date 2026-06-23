package dev.lanwen.frmtr.java;

import com.github.javaparser.Range;
import com.github.javaparser.ast.Modifier;
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
 * {@code frmtr-core/src/test/resources/format/generic-type-body-breaks/frmtr-default.output.java}; method throws
 * wrapping and abstract-method semicolons are covered by
 * {@code frmtr-core/src/test/resources/format/throws-clause-layout/input.java} and
 * {@code frmtr-core/src/test/resources/format/throws-clause-layout/frmtr-default.output.java}.
 */
final class MethodDeclarationPrinter {

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    private final SourceShapePolicy sourceShapePolicy;

    private final RawSource rawSource;

    private final RawPreservedSource rawPreservedSource;

    private final CommentedMethodSignaturePrinter commentedMethodSignatures;

    private final CallableSignaturePrinter callableSignatures;

    private final Function<NodeWithAnnotations<?>, Doc> declarationAnnotations;

    private final Function<NodeWithModifiers<?>, String> modifiers;

    private final Function<NodeList<TypeParameter>, String> flatTypeParameters;

    private final Function<NodeWithAnnotations<?>, String> inlineAnnotations;

    private final Function<Doc, String> commentText;

    private final Function<Node, String> compact;

    private final Function<Type, Doc> typeBody;

    private final Function<ClassOrInterfaceType, Doc> brokenClassOrInterfaceType;

    private final Predicate<Type> typeCanBreak;

    private final ThrowsClauseRenderer throwsClause;

    private final Function<BlockStmt, Doc> block;

    MethodDeclarationPrinter(
            CommentTracker comments,
            JavaCommentPlacementPolicy commentPlacement,
            SourceShapePolicy sourceShapePolicy,
            RawSource rawSource,
            RawPreservedSource rawPreservedSource,
            CommentedMethodSignaturePrinter commentedMethodSignatures,
            CallableSignaturePrinter callableSignatures,
            Function<NodeWithAnnotations<?>, Doc> declarationAnnotations,
            Function<NodeWithModifiers<?>, String> modifiers,
            Function<NodeList<TypeParameter>, String> flatTypeParameters,
            Function<NodeWithAnnotations<?>, String> inlineAnnotations,
            Function<Doc, String> commentText,
            Function<Node, String> compact,
            Function<Type, Doc> typeBody,
            Function<ClassOrInterfaceType, Doc> brokenClassOrInterfaceType,
            Predicate<Type> typeCanBreak,
            ThrowsClauseRenderer throwsClause,
            Function<BlockStmt, Doc> block
    ) {
        this.comments = comments;
        this.commentPlacement = commentPlacement;
        this.sourceShapePolicy = sourceShapePolicy;
        this.rawSource = rawSource;
        this.rawPreservedSource = rawPreservedSource;
        this.commentedMethodSignatures = commentedMethodSignatures;
        this.callableSignatures = callableSignatures;
        this.declarationAnnotations = declarationAnnotations;
        this.modifiers = modifiers;
        this.flatTypeParameters = flatTypeParameters;
        this.inlineAnnotations = inlineAnnotations;
        this.commentText = commentText;
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
        Optional<Doc> inlineHeaderComment = inlineHeaderCommentAfterModifiers(declaration);
        if (inlineHeaderComment.isPresent()) {
            Doc comment = inlineHeaderComment.orElseThrow();
            docs.add(comment);
            docs.add(Doc.text(" "));
            prefix += commentText.apply(comment) + " ";
        }
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
        boolean sourceParametersBreak = sourceShapePolicy.callableParametersSpanMultipleLines(declaration);
        boolean parametersBreak = !breakReturnType
            && callableSignatures.parametersBreak(prefix, declaration, methodParameterSuffix(declaration));
        boolean compactContinuationParameters = !declaration.getThrownExceptions().isEmpty();
        docs.add(returnType(declaration, returnType, breakReturnType));
        docs.add(parameters(declaration, sourceParametersBreak, parametersBreak, compactContinuationParameters));
        if (!declaration.getThrownExceptions().isEmpty()) {
            docs.add(
                throwsClause.render(
                    prefix,
                    declaration.getParameters(),
                    declaration.getThrownExceptions(),
                    declaration.getBody().isPresent() ? " {" : ";",
                    sourceShapePolicy.throwsStartsOnOwnLine(declaration),
                    sourceParametersBreak || parametersBreak
                )
            );
        }
        Optional<Doc> gapComment = signatureToBodyGapComment(declaration);
        docs.add(
            declaration.getBody()
                    .map(body -> gapComment
                            .map(comment -> Doc.concat(Doc.HARD_LINE, comment, Doc.HARD_LINE, block.apply(body)))
                            .orElseGet(() -> Doc.concat(Doc.text(" "), block.apply(body))))
                    .orElse(Doc.text(";")));
        return Doc.concat(docs);
    }

    /**
     * Recovers the {@code //} line comment written alone between a method signature's {@code )} and its body {@code {}
     * — trivia that the structured {@link JavaPrinter} block rendering never emits — and renders it on its own line
     * below the signature, preserving the source shape: a {@link Doc#HARD_LINE} before the comment drops it onto a fresh
     * line at the member indent and a second {@link Doc#HARD_LINE} after it drops the {@code {} onto the line below.
     *
     * <p>This is the structured-path counterpart of the raw commented-signature fallback handled by
     * {@link CommentedMethodSignaturePrinter}: methods with {@code >=2} statements stay on this structured path, where
     * {@code block.apply(body)} drops the body block's own comment. The trailing {@link Doc#HARD_LINE} is mandatory — a
     * line comment with the brace on the same line would comment the brace out. When no such gap comment exists the slot
     * is empty and the body renders exactly as before, keeping comment-free methods byte-identical.
     */
    private Optional<Doc> signatureToBodyGapComment(MethodDeclaration declaration) {
        return declaration.getBody()
                .flatMap(body -> gapCommentTrivia(declaration, body))
                .map(comments::comment)
                .filter(comment -> comment != Doc.EMPTY);
    }

    /**
     * Finds the line comment trivia in the {@code )}-to-{@code {} gap, looking in every bucket JavaParser may park it in.
     *
     * <p>The same source comment lands in a different parser bucket depending on the whitespace around it, so recovering
     * only one bucket drops the comment under re-shaped layouts and breaks idempotence:
     *
     * <ul>
     *   <li><b>body own comment</b> — the comment on its own line between {@code )} and {@code {} (the issue #23 source
     *       shape) attaches as the body block's own trivia;</li>
     *   <li><b>last-parameter own comment</b> — once this printer renders the comment back onto the signature line
     *       ({@code ) // note} with {@code {} below), re-parsing that output re-buckets it onto the last parameter's own
     *       trivia, so a second format pass must still find it there;</li>
     *   <li><b>method orphan comment</b> — when the comment is isolated by blank lines on both sides (the
     *       expanded-whitespace shape), JavaParser leaves it as an orphan of the method declaration.</li>
     * </ul>
     *
     * <p>Every bucket is filtered to line comments that lie in the gap — after the parameter list ends and before
     * {@code body} begins — so a leading comment before the signature or a comment trailing the body is never claimed.
     * The body-own bucket needs no lower bound because JavaParser only attaches a leading line comment there.
     */
    private Optional<JavaCommentTrivia> gapCommentTrivia(MethodDeclaration declaration, BlockStmt body) {
        Optional<JavaCommentTrivia> bodyOwn = commentPlacement.ownComment(body)
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.startsBefore(body));
        if (bodyOwn.isPresent()) {
            return bodyOwn;
        }
        return rebucketedGapComment(declaration, body);
    }

    /**
     * Recovers the gap comment from the parameter and method-orphan buckets it migrates to when whitespace re-shapes the
     * signature, bounding it to the source-order gap after the last parameter and before {@code body}.
     */
    private Optional<JavaCommentTrivia> rebucketedGapComment(MethodDeclaration declaration, BlockStmt body) {
        NodeList<Parameter> parameters = declaration.getParameters();
        if (parameters.isEmpty()) {
            return Optional.empty();
        }
        Parameter lastParameter = parameters.get(parameters.size() - 1);
        return java.util.stream.Stream.concat(
                commentPlacement.ownComment(lastParameter).stream(),
                commentPlacement.orphanComments(declaration).stream()
        )
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.startsAfterEndOf(lastParameter))
                .filter(comment -> comment.startsBefore(body))
                .findFirst();
    }

    /**
     * Preserves same-line block comments written between modifiers and the method return type.
     *
     * <p>Commented signatures with small bodies use the raw fallback, but larger bodies stay on the structured method
     * path. This slot keeps source shapes such as {@code public /* note *&#47; Result value()} without making the raw
     * fallback format arbitrary method bodies.
     */
    private Optional<Doc> inlineHeaderCommentAfterModifiers(MethodDeclaration declaration) {
        return commentPlacement.containedComments(declaration)
                .stream()
                .filter(JavaCommentTrivia::isBlock)
                .filter(comment -> betweenModifiersAndNextHeaderToken(comment, declaration))
                .findFirst()
                .map(comments::comment)
                .filter(comment -> comment != Doc.EMPTY);
    }

    private boolean betweenModifiersAndNextHeaderToken(JavaCommentTrivia comment, MethodDeclaration declaration) {
        Optional<Range> commentRange = comment.comment().getRange();
        Optional<Range> nextTokenRange = nextHeaderTokenAfterModifiers(declaration);
        if (commentRange.isEmpty() || nextTokenRange.isEmpty()) {
            return false;
        }
        Range commentPosition = commentRange.orElseThrow();
        Range nextTokenPosition = nextTokenRange.orElseThrow();
        if (
            commentPosition.begin.line != nextTokenPosition.begin.line
            || commentPosition.end.line != nextTokenPosition.begin.line
        ) {
            return false;
        }
        if (commentPosition.end.column >= nextTokenPosition.begin.column) {
            return false;
        }
        return commentPosition.begin.column > sameLineModifierEndColumn(declaration, nextTokenPosition.begin.line);
    }

    private Optional<Range> nextHeaderTokenAfterModifiers(MethodDeclaration declaration) {
        if (!declaration.getTypeParameters().isEmpty()) {
            return declaration.getTypeParameters().get(0).getRange();
        }
        return declaration.getType().getRange();
    }

    private int sameLineModifierEndColumn(MethodDeclaration declaration, int line) {
        return declaration.getModifiers()
                .stream()
                .map(Modifier::getRange)
                .flatMap(Optional::stream)
                .filter(range -> range.end.line == line)
                .mapToInt(range -> range.end.column)
                .max()
                .orElse(Integer.MIN_VALUE);
    }

    private Doc annotationMethodGapComments(MethodDeclaration declaration) {
        return Doc.concat(
            annotationMethodGapCommentTrivia(declaration)
                    .map(comments::comment)
                    .filter(comment -> comment != Doc.EMPTY)
                    .map(comment -> Doc.concat(comment, Doc.HARD_LINE))
                    .toList()
        );
    }

    private java.util.stream.Stream<JavaCommentTrivia> annotationMethodGapCommentTrivia(MethodDeclaration declaration) {
        if (declaration.getAnnotations().isEmpty()) {
            return java.util.stream.Stream.empty();
        }
        Optional<Integer> lastAnnotationLine = declaration.getAnnotations()
                .stream()
                .flatMap(annotation -> annotationVisibleEndLine(annotation).stream())
                .max(Integer::compareTo);
        Optional<Integer> nameLine = declaration.getName().getRange().map(range -> range.begin.line);
        if (lastAnnotationLine.isEmpty() || nameLine.isEmpty()) {
            return java.util.stream.Stream.empty();
        }
        return commentPlacement.containedComments(declaration)
                .stream()
                .filter(comment -> comment.beginLine(Integer.MIN_VALUE) > lastAnnotationLine.orElseThrow())
                .filter(comment -> comment.endLine(Integer.MAX_VALUE) < nameLine.orElseThrow());
    }

    private Optional<Integer> annotationVisibleEndLine(AnnotationExpr annotation) {
        return annotation
                .getRange()
                .map(range -> {
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
        String suffix = declaration.getBody()
                .map(body -> body.getStatements().isEmpty() && !blockHasVisibleComments(body) ? " {}" : " {")
                .orElse(";");
        if (declaration.getThrownExceptions().isEmpty()) {
            return suffix;
        }
        return " throws " + compactJoin(declaration.getThrownExceptions()) + suffix;
    }

    private boolean blockHasVisibleComments(BlockStmt body) {
        return !body.getOrphanComments().isEmpty() || !commentPlacement.containedComments(body).isEmpty();
    }

    private Doc parameters(
            MethodDeclaration declaration,
            boolean sourceParametersBreak,
            boolean parametersBreak,
            boolean compactContinuationParameters
    ) {
        if (
            parametersBreak
            && compactContinuationParameters
            && callableSignatures.parametersFitOnContinuation(declaration)
        ) {
            return callableSignatures.compactContinuationParameters(declaration);
        }
        return callableSignatures.parameters(declaration, sourceParametersBreak || parametersBreak);
    }

    private String compactJoin(List<? extends Node> nodes) {
        return nodes.stream().map(compact).reduce((left, right) -> left + ", " + right).orElse("");
    }

    private boolean shouldBreakReturnType(MethodDeclaration declaration, String prefix) {
        return declaration.getReceiverParameter().isEmpty()
            && typeCanBreak.test(declaration.getType())
            && sourceShapePolicy.wasMultiline(declaration.getType())
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
                Doc.text(" " + declaration.getNameAsString())
            );
        }
        return Doc.concat(
            Doc.text(inlineAnnotations.apply(declaration)),
            typeBody.apply(declaration.getType()),
            Doc.text(" " + declaration.getNameAsString())
        );
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
                boolean forceBreak,
                boolean parametersBreak
        );
    }
}
