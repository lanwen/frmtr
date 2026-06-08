package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Renders object creation after broad expression dispatch has selected constructor syntax.
 *
 * <p>This helper owns constructor-call layout, forced argument breaks, block comments around {@code new} and the created
 * type, generic type-body breaks for empty constructor calls, lambda arguments that can hug constructor calls, and
 * anonymous class body member sequencing. The boundary exists because object creation is shared by expression dispatch,
 * method-call chain roots, and field-initializer width decisions, while declaration and member rendering still have one
 * canonical path through the existing body dispatcher.
 *
 * <p>{@link JavaPrinter} still owns broad expression dispatch, assignment and field-initializer decisions, compact raw
 * source text, and declaration/member body dispatch. {@link LambdaExpressionPrinter} still owns the lambda argument
 * shapes; this helper only asks whether constructor arguments can use that lambda-specific form.
 */
final class ObjectCreationPrinter {
    private final JavaFormatter.CommentTracker comments;
    private final TypePrinter types;
    private final JavaFormatRule<Expression> expressionRenderer;
    private final BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableBlockLambdaArguments;
    private final JavaFormatRule<BodyDeclaration<?>> bodyRenderer;
    private final Function<Node, String> compact;
    private final Function<List<? extends Node>, String> compactJoin;
    private final Function<Node, String> compactTypeLike;
    private final Function<Node, String> compactTypeLikeWithoutOwnComment;
    private final Function<Doc, String> commentText;

    ObjectCreationPrinter(
            JavaFormatter.CommentTracker comments,
            TypePrinter types,
            JavaFormatRule<Expression> expressionRenderer,
            BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableBlockLambdaArguments,
            JavaFormatRule<BodyDeclaration<?>> bodyRenderer,
            Function<Node, String> compact,
            Function<List<? extends Node>, String> compactJoin,
            Function<Node, String> compactTypeLike,
            Function<Node, String> compactTypeLikeWithoutOwnComment,
            Function<Doc, String> commentText) {
        this.comments = comments;
        this.types = types;
        this.expressionRenderer = expressionRenderer;
        this.huggableBlockLambdaArguments = huggableBlockLambdaArguments;
        this.bodyRenderer = bodyRenderer;
        this.compact = compact;
        this.compactJoin = compactJoin;
        this.compactTypeLike = compactTypeLike;
        this.compactTypeLikeWithoutOwnComment = compactTypeLikeWithoutOwnComment;
        this.commentText = commentText;
    }

    Doc objectCreation(ObjectCreationExpr expression) {
        return objectCreation(expression, false);
    }

    Doc brokenObjectCreation(ObjectCreationExpr expression) {
        return objectCreation(expression, true);
    }

    /**
     * Chooses the object-creation shape after callers have already selected constructor syntax.
     *
     * <p>Anonymous classes reject the compact empty-call and huggable-lambda branches even when the constructor argument
     * list itself is small, because the anonymous body owns the visible layout after the header. Non-anonymous
     * constructor calls then try the broken-type path for empty generic creations before falling through to argument
     * grouping.
     */
    private Doc objectCreation(ObjectCreationExpr expression, boolean forceBreak) {
        String prefix = objectCreationPrefix(expression);
        if (expression.getAnonymousClassBody().isPresent()) {
            return anonymousObjectCreation(expression, prefix);
        }
        if (expression.getArguments().isEmpty()) {
            return objectCreationWithBrokenType(expression).orElseGet(() -> Doc.text(prefix + "()"));
        }
        Optional<Doc> huggableLambda = huggableLambdaArgument(prefix, expression.getArguments());
        if (huggableLambda.isPresent()) {
            return huggableLambda.orElseThrow();
        }
        Doc call = Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(
                        objectCreationLine(forceBreak),
                        Doc.join(Doc.concat(Doc.text(","), Doc.LINE), expression.getArguments().stream()
                                .map(expressionRenderer::format)
                                .toList()))),
                objectCreationLine(forceBreak),
                Doc.text(")"));
        return forceBreak ? call : Doc.group(call);
    }

    /**
     * Lets constructor calls reuse the lambda-argument hugging policy that method calls use.
     *
     * <p>The object-creation helper supplies only the constructor prefix and argument list; lambda parameter/body
     * decisions stay with {@link LambdaExpressionPrinter} so block-lambda arguments do not fork between calls and
     * constructors.
     */
    private Optional<Doc> huggableLambdaArgument(String prefix, NodeList<Expression> arguments) {
        return huggableBlockLambdaArguments.apply(prefix, arguments);
    }

    String objectCreationPrefix(ObjectCreationExpr expression) {
        Doc creationComment = comments.ownComment(expression, BlockComment.class::isInstance);
        Doc typeComment = comments.ownComment(expression.getType(), BlockComment.class::isInstance);
        String type = typeComment == Doc.EMPTY
                ? compactTypeLike.apply(expression.getType())
                : commentText.apply(typeComment) + " " + compactTypeLikeWithoutOwnComment.apply(expression.getType());
        return expression.getScope().map(scope -> compact.apply(scope) + ".").orElse("")
                + (creationComment == Doc.EMPTY ? "new " : commentText.apply(creationComment) + " new ")
                + expression.getTypeArguments().map(typeArguments -> "<" + types.compactJoinTypeLike(typeArguments) + ">").orElse("")
                + type;
    }

    /**
     * Uses the shared generic type-body renderer for empty constructor calls whose created type can break.
     *
     * <p>Scoped creations, explicit constructor type arguments, and block comments stay on the ordinary prefix path so
     * comments around {@code new} or the type do not get separated from the token they annotate.
     */
    private Optional<Doc> objectCreationWithBrokenType(ObjectCreationExpr expression) {
        if (expression.getScope().isPresent()
                || expression.getTypeArguments().isPresent()
                || expression.getComment().filter(BlockComment.class::isInstance).isPresent()
                || expression.getType().getComment().filter(BlockComment.class::isInstance).isPresent()
                || !types.typeCanBreak(expression.getType())) {
            return Optional.empty();
        }
        return Optional.of(Doc.group(Doc.concat(Doc.text("new "), types.typeBody(expression.getType()), Doc.text("()"))));
    }

    /**
     * Renders an anonymous-class creation header while delegating every body declaration through the caller.
     *
     * <p>The constructor arguments remain compact in the anonymous-class header, matching the legacy layout. Member
     * declarations are rendered by the injected body callback so fields, methods, constructors, nested types, comments,
     * and formatter pragmas keep the same behavior they have in normal declaration bodies.
     */
    private Doc anonymousObjectCreation(ObjectCreationExpr expression, String prefix) {
        String arguments = expression.getArguments().isEmpty()
                ? ""
                : compactJoin.apply(expression.getArguments());
        Doc header = Doc.text(prefix + "(" + arguments + ") ");
        List<BodyDeclaration<?>> declarations = expression.getAnonymousClassBody().orElseThrow();
        List<Doc> members = declarations.stream().map(bodyRenderer::format).toList();
        if (members.isEmpty()) {
            return Doc.concat(header, Doc.text("{}"));
        }
        return Doc.concat(
                header,
                Doc.text("{"),
                Doc.indent(Doc.concat(Doc.HARD_LINE, anonymousClassMembers(declarations, members))),
                Doc.HARD_LINE,
                Doc.text("}"));
    }

    /**
     * Sequences anonymous-class members with the same field-adjacency blank-line rule used before extraction.
     *
     * <p>Adjacent fields get one hard line between them; all other neighboring member pairs get a blank line. The loop
     * intentionally looks at the original declarations, not the rendered docs, because the spacing decision is about
     * member kinds rather than rendered text.
     */
    private Doc anonymousClassMembers(List<BodyDeclaration<?>> declarations, List<Doc> members) {
        List<Doc> docs = new ArrayList<>();
        for (int index = 0; index < members.size(); index++) {
            if (index > 0) {
                boolean adjacentFields = declarations.get(index - 1) instanceof FieldDeclaration
                        && declarations.get(index) instanceof FieldDeclaration;
                docs.add(adjacentFields ? Doc.HARD_LINE : Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE));
            }
            docs.add(members.get(index));
        }
        return Doc.concat(docs);
    }

    private Doc objectCreationLine(boolean forceBreak) {
        return forceBreak ? Doc.HARD_LINE : Doc.SOFT_LINE;
    }
}
