package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.util.List;
import java.util.function.Function;

/**
 * Builds compact source-equivalent text for syntax nodes.
 *
 * <p>This helper owns the raw and token-derived single-line text used by width gates, fallback expression text, and
 * type-like snippets. The boundary exists so layout printers can request source-equivalent text without also owning
 * raw-token spelling details, comment-stripping clones, or compact method-call reconstruction.
 *
 * <p>Callers still decide when compact text is acceptable, how comments are attached to {@code Doc} values, where line
 * breaks belong, and which structured expression or declaration printer should handle a node.
 */
final class CompactSourceText {

    private final RawSource rawSource;

    private Function<AnnotationExpr, String> annotationFlatText;

    CompactSourceText(RawSource rawSource) {
        this.rawSource = rawSource;
    }

    /**
     * Installs the annotation-expression compacting policy used when type text contains type-use annotations.
     *
     * <p>Annotation layout and compact annotation values belong to {@link AnnotationExpressionPrinter}; this callback
     * lets type compaction reuse that single policy without making this source-text helper reconstruct annotations.
     */
    void useAnnotationFlatText(Function<AnnotationExpr, String> annotationFlatText) {
        this.annotationFlatText = annotationFlatText;
    }

    /**
     * Joins compact node text with the comma spacing expected by caller-owned list layouts.
     */
    String compactJoin(List<? extends Node> nodes) {
        return nodes.stream().map(this::compact).reduce((left, right) -> left + ", " + right).orElse("");
    }

    /**
     * Returns compact source-equivalent text for a node.
     *
     * <p>String and character literals use raw token text so escape spelling and quote-sensitive source details survive
     * the compact path. Field accesses are reconstructed from the compact scope and parsed name so dotted chains keep
     * canonical dot spacing. Comment-free method calls are reconstructed through the same compact policy for scopes, type
     * arguments, and arguments; calls that contain comments stay on normalized token text so the compact path does not
     * silently discard comment content.
     */
    String compact(Node node) {
        if (!isFullyParsed(node)) {
            return compactTokenText(node);
        }
        if (
            node instanceof StringLiteralExpr
            || node instanceof CharLiteralExpr
            || node instanceof TextBlockLiteralExpr
        ) {
            return rawSource.raw(node);
        }
        if (node instanceof ClassExpr classExpr) {
            return compactTypeLike(classExpr.getType()) + ".class";
        }
        if (node instanceof FieldAccessExpr fieldAccessExpr) {
            return compact(fieldAccessExpr.getScope()) + "." + fieldAccessExpr.getNameAsString();
        }
        if (node instanceof EnclosedExpr enclosedExpr && containsRawLiteral(enclosedExpr)) {
            return "(" + compact(enclosedExpr.getInner()) + ")";
        }
        if (node instanceof BinaryExpr binaryExpr && containsRawLiteral(binaryExpr)) {
            return compact(binaryExpr.getLeft())
                + " "
                + binaryExpr.getOperator().asString()
                + " "
                + compact(binaryExpr.getRight());
        }
        if (node instanceof ConditionalExpr conditionalExpr && containsRawLiteral(conditionalExpr)) {
            return compact(conditionalExpr.getCondition())
                + " ? "
                + compact(conditionalExpr.getThenExpr())
                + " : "
                + compact(conditionalExpr.getElseExpr());
        }
        if (node instanceof AssignExpr assignExpr && assignExpr.getAllContainedComments().isEmpty()) {
            return compact(assignExpr.getTarget())
                + " "
                + assignExpr.getOperator().asString()
                + " "
                + compact(assignExpr.getValue());
        }
        if (node instanceof MethodCallExpr methodCallExpr && methodCallExpr.getAllContainedComments().isEmpty()) {
            return compactMethodCall(methodCallExpr);
        }
        return compactTokenText(node);
    }

    private boolean containsRawLiteral(Node node) {
        return node.findFirst(StringLiteralExpr.class).isPresent()
            || node.findFirst(CharLiteralExpr.class).isPresent()
            || node.findFirst(TextBlockLiteralExpr.class).isPresent();
    }

    /**
     * Reconstructs a method call that is already known to have no contained comments.
     *
     * <p>The reconstruction keeps compact field-access and raw string-literal behavior consistent inside scopes,
     * arguments, and type arguments. Type arguments are joined by this helper's type-like compact path rather than by
     * {@link TypePrinter}, which keeps compact source text independent from type-layout policy.
     */
    private String compactMethodCall(MethodCallExpr expression) {
        String prefix = expression.getScope().map(scope -> compact(scope) + ".").orElse("")
            + expression.getTypeArguments()
                    .map(typeArguments -> "<" + compactJoinTypeLike(typeArguments) + ">")
                    .orElse("")
            + expression.getNameAsString();
        return prefix + "(" + compactJoin(expression.getArguments()) + ")";
    }

    /**
     * Returns compact text for a type-like node with generic angle-bracket spacing cleaned up.
     *
     * <p>Token ranges can preserve spaces immediately inside {@code <...>} even when callers need source-equivalent
     * flat type text for signatures, clauses, casts, and method-call type arguments. This cleanup only normalizes those
     * generic delimiters and leaves the rest of the compact token spelling to {@link #compact(Node)}.
     */
    String compactTypeLike(Node node) {
        return compactTypeLikeRaw(node)
                .replaceAll("<\\s+", "<")
                .replaceAll("\\s+>", ">");
    }

    private String compactTypeLikeRaw(Node node) {
        if (!isFullyParsed(node)) {
            return compactTokenText(node);
        }
        if (node instanceof ClassOrInterfaceType type) {
            return compactClassOrInterfaceType(type);
        }
        if (node instanceof Type type && !type.getAnnotations().isEmpty()) {
            return compactTypeAnnotations(type) + compact(node);
        }
        return compact(node);
    }

    private String compactClassOrInterfaceType(ClassOrInterfaceType type) {
        String scope = type.getScope()
                .map(scopedType -> compactClassOrInterfaceType(scopedType) + ".")
                .orElse("");
        String typeArguments = type.getTypeArguments()
                .map(arguments -> "<" + compactJoinTypeLike(arguments) + ">")
                .orElse("");
        return scope + compactTypeAnnotations(type) + type.getNameAsString() + typeArguments;
    }

    private String compactTypeAnnotations(Type type) {
        if (type.getAnnotations().isEmpty()) {
            return "";
        }
        return (
            type.getAnnotations()
                    .stream()
                    .map(this::annotationFlatText)
                    .reduce((left, right) -> left + " " + right)
                    .orElse("")
            + " "
        );
    }

    private String annotationFlatText(AnnotationExpr annotation) {
        if (annotationFlatText == null) {
            throw new IllegalStateException("Annotation flat-text policy has not been installed");
        }
        return annotationFlatText.apply(annotation);
    }

    /**
     * Returns type-like compact text after removing only the node's own attached comment.
     *
     * <p>Callers use this after printing that own comment separately, while still preserving the generic spacing cleanup
     * that type-like compact text applies.
     */
    String compactTypeLikeWithoutOwnComment(Node node) {
        return compactWithoutOwnComment(node)
                .replaceAll("<\\s+", "<")
                .replaceAll("\\s+>", ">");
    }

    /**
     * Removes a node's own attached comment from a clone before compacting it.
     *
     * <p>The clone keeps comment stripping local to this compact-text request, so callers and the shared JavaParser tree
     * keep their existing comment attachment state for later layout decisions.
     */
    String compactWithoutOwnComment(Node node) {
        Node clone = node.clone();
        clone.removeComment();
        return compact(clone);
    }

    /**
     * Returns compact expression text after removing all comments from the expression subtree.
     *
     * <p>Anonymous-class constructor headers use this for their argument list before the body opens; those arguments
     * are source-like header text, not normal expression docs. The method keeps name, field access, method call, and
     * method-reference reconstruction consistent with {@link #compact(Node)} while making the comment-stripping policy
     * explicit for callers that already printed the comments elsewhere.
     */
    String commentFree(Expression expression) {
        if (expression.isNameExpr()) {
            return expression.asNameExpr().getNameAsString();
        }
        if (expression instanceof FieldAccessExpr fieldAccess) {
            return commentFree(fieldAccess.getScope()) + "." + fieldAccess.getNameAsString();
        }
        if (expression instanceof MethodCallExpr methodCall && methodCall.getAllContainedComments().isEmpty()) {
            String scope = methodCall.getScope()
                    .map(this::commentFree)
                    .map(text -> text + ".")
                    .orElse("");
            String typeArguments = methodCall.getTypeArguments()
                    .map(arguments -> "<" + compactJoinTypeLike(arguments) + ">")
                    .orElse("");
            String arguments = methodCall.getArguments()
                    .stream()
                    .map(this::commentFree)
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
            return scope + typeArguments + methodCall.getNameAsString() + "(" + arguments + ")";
        }
        if (
            expression instanceof MethodReferenceExpr methodReference
            && methodReference.getAllContainedComments().isEmpty()
        ) {
            String typeArguments = methodReference.getTypeArguments()
                    .map(arguments -> "<" + compactJoinTypeLike(arguments) + ">")
                    .orElse("");
            return commentFree(methodReference.getScope()) + "::" + typeArguments + methodReference.getIdentifier();
        }
        Expression clone = expression.clone();
        clone.removeComment();
        List.copyOf(clone.getOrphanComments()).forEach(clone::removeOrphanComment);
        List.copyOf(clone.getAllContainedComments()).forEach(Node::remove);
        return clone.toString();
    }

    /**
     * Joins method-call type arguments through this helper's compact type-like policy.
     */
    private String compactJoinTypeLike(List<? extends Node> nodes) {
        return nodes.stream().map(this::compactTypeLike).reduce((left, right) -> left + ", " + right).orElse("");
    }

    private String compactTokenText(Node node) {
        if (node.findFirst(TextBlockLiteralExpr.class).isPresent()) {
            return rawSource.raw(node);
        }
        return node.getTokenRange()
                .map(Object::toString)
                .map(rawSource::normalizeWhitespace)
                .orElseGet(() -> rawSource.normalizeWhitespace(node.toString()));
    }

    private boolean isFullyParsed(Node node) {
        return node.stream().allMatch(descendant -> descendant.getParsed() == Node.Parsedness.PARSED);
    }
}
