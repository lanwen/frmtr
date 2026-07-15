package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.ArrayCreationLevel;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Renders array expressions after broad expression dispatch has selected array syntax.
 *
 * <p>This helper owns array-access layout, array-creation prefixes and breakable element types, compact literal
 * initializer acceptance, source-spaced compact initializer braces, forced initializer breaks for declaration callers,
 * orphan comments inside initializer braces, and source-position-sensitive block comments between initializer values.
 * The boundary exists because array creation and initializer layout are reused by expression dispatch and
 * field-initializer break decisions, while the formatter still needs one place to preserve compact/raw/comment behavior
 * for array syntax.
 *
 * <p>{@link JavaPrinter} still owns broad expression dispatch, parenthesized suffix breaking, raw-source compact text,
 * width calculations. Field declaration layout still decides when an initializer line has
 * overflowed; this helper only provides the array-specific shapes after that caller decision.
 */
final class ArrayExpressionPrinter {

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    private final SourceShapePolicy sourceShapePolicy;

    private final FormatterOptions options;

    private final ExpressionRendering rendering;

    private final BiFunction<EnclosedExpr, Boolean, Doc> brokenEnclosedForSuffix;

    private final BiFunction<MethodCallExpr, ExpressionTail, Doc> methodCallWithTail;

    private final BiFunction<ObjectCreationExpr, String, Doc> objectCreationWithSuffix;

    private final Function<Node, String> compactTypeLike;

    private final Function<Node, String> compact;

    private final ToIntFunction<String> currentIndentedWidth;

    ArrayExpressionPrinter(
            CommentTracker comments,
            JavaCommentPlacementPolicy commentPlacement,
            SourceShapePolicy sourceShapePolicy,
            FormatterOptions options,
            ExpressionRendering rendering,
            BiFunction<EnclosedExpr, Boolean, Doc> brokenEnclosedForSuffix,
            BiFunction<MethodCallExpr, ExpressionTail, Doc> methodCallWithTail,
            BiFunction<ObjectCreationExpr, String, Doc> objectCreationWithSuffix,
            Function<Node, String> compactTypeLike,
            Function<Node, String> compact,
            ToIntFunction<String> currentIndentedWidth
    ) {
        this.comments = comments;
        this.commentPlacement = commentPlacement;
        this.sourceShapePolicy = sourceShapePolicy;
        this.options = options;
        this.rendering = rendering;
        this.brokenEnclosedForSuffix = brokenEnclosedForSuffix;
        this.methodCallWithTail = methodCallWithTail;
        this.objectCreationWithSuffix = objectCreationWithSuffix;
        this.compactTypeLike = compactTypeLike;
        this.compact = compact;
        this.currentIndentedWidth = currentIndentedWidth;
    }

    Doc arrayAccess(ArrayAccessExpr expression) {
        return Doc.group(
            Doc.concat(
                rendering.render(expression.getName()),
                Doc.text("["),
                Doc.indent(Doc.concat(Doc.SOFT_LINE, rendering.render(expression.getIndex()))),
                Doc.SOFT_LINE,
                Doc.text("]")
            )
        );
    }

    /**
     * Breaks an array access whose name is enclosed so suffix callers keep the parenthesized expression readable.
     *
     * <p>The enclosed name uses the shared suffix breaker because lambdas, conditionals, and binaries each have their
     * own parenthesized multiline shape. The array index intentionally stays inline after the broken name, matching the
     * legacy field-initializer overflow path.
     */
    Doc arrayAccessWithBrokenEnclosedName(ArrayAccessExpr expression) {
        EnclosedExpr enclosed = expression.getName().asEnclosedExpr();
        return Doc.concat(
            brokenEnclosedForSuffix.apply(enclosed, true),
            Doc.text("["),
            rendering.render(expression.getIndex()),
            Doc.text("]")
        );
    }

    Doc arrayCreation(ArrayCreationExpr expression) {
        Doc prefix = Doc.concat(
            Doc.text("new "),
            arrayCreationType(expression),
            Doc.text(compactJoinArrayLevels(expression.getLevels()))
        );
        return expression.getInitializer()
                .map(initializer -> compactArrayCreation(expression, initializer)
                            .filter(flat -> !arrayInitializerFansOnePerLine(initializer))
                            .filter(flat -> currentIndentedWidth.applyAsInt(flat) <= options.lineWidth())
                            .map(Doc::text)
                            .orElseGet(() -> Doc.concat(
                                    prefix,
                                    Doc.text(" "),
                                    arrayInitializer(
                                        initializer,
                                        arrayCreationInitializerRequiresStructuredBreak(initializer)
                                    )
                            ))
                )
                .orElse(prefix);
    }

    private boolean arrayCreationInitializerRequiresStructuredBreak(ArrayInitializerExpr initializer) {
        return initializer.getValues().stream().anyMatch(value -> !value.isLiteralExpr());
    }

    /**
     * Reports whether an array initializer fans one element per line purely because of how many elements it holds.
     *
     * <p>A <em>structural</em> rule, not width-driven: three or more elements always break one-per-line even when the
     * compact {@code {a, b, c}} form would fit; one or two stay compact (still breaking by width on overflow). Past two
     * elements the vertical shape reads better and stays stable under edits (add/remove touches one line, not the whole
     * row), mirroring the constructor-root and nested-array-row lists. Both the {@code = {...}} initializer gate
     * ({@link #arrayInitializer(ArrayInitializerExpr, boolean)}) and the {@code new T[] {...}} creation gate
     * ({@link #arrayCreation(ArrayCreationExpr)}) consult this. Annotation member arrays keep their own width-driven
     * shape.
     */
    private boolean arrayInitializerFansOnePerLine(ArrayInitializerExpr initializer) {
        return initializer.getValues().size() >= 3;
    }

    /**
     * Attempts the fully compact {@code new T[] {...}} shape only for arrays with literal values and no comments.
     *
     * <p>Array creation is rejected from the compact path when the initializer contains any attached comments or when a
     * value is not a literal. Those cases need structured docs so comments and nested expression formatting stay visible
     * to the normal formatter pipeline. The element type is intentionally <em>not</em> rejected here: a generic type only
     * breaks its arguments as a width-driven last resort (see {@link #arrayCreationTypeBreaks}), and the produced text is
     * itself width-checked by the caller, so a fitting {@code new T<...>[] {...}} stays compact.
     */
    private Optional<String> compactArrayCreation(ArrayCreationExpr expression, ArrayInitializerExpr initializer) {
        if (sourceShapePolicy.hasContainedComments(initializer)) {
            return Optional.empty();
        }
        if (initializer.getValues().stream().anyMatch(value -> !value.isLiteralExpr())) {
            return Optional.empty();
        }
        return compactArrayInitializer(initializer).map(
            initializerText -> arrayCreationPrefix(expression) + " " + initializerText
        );
    }

    String arrayCreationPrefix(ArrayCreationExpr expression) {
        return "new "
            + compactTypeLike.apply(expression.getElementType())
            + compactJoinArrayLevels(expression.getLevels());
    }

    private Optional<String> compactArrayInitializer(ArrayInitializerExpr initializer) {
        if (
            sourceShapePolicy.hasContainedComments(initializer)
            || initializer.getValues().stream().anyMatch(value -> !compactArrayInitializerValue(value))
        ) {
            return Optional.empty();
        }
        String values = initializer.getValues()
                .stream()
                .map(compact)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        if (values.isEmpty()) {
            return Optional.of("{}");
        }
        return Optional.of(compactArrayInitializerWithSourceSpacing(initializer, values));
    }

    /**
     * Applies the array-initializer source brace-spacing policy to already-compacted value text.
     *
     * <p>Callers that own special compact-value predicates can still reuse the same brace source-shape decision without
     * duplicating token-range trivia checks outside the array initializer boundary.
     */
    String compactArrayInitializerWithSourceSpacing(ArrayInitializerExpr initializer, String values) {
        return compactArrayInitializerUsesInteriorSpacing(initializer) ? "{ " + values + " }" : "{" + values + "}";
    }

    private boolean compactArrayInitializerUsesInteriorSpacing(ArrayInitializerExpr initializer) {
        return initializer.getTokenRange()
                .map(Object::toString)
                .map(String::strip)
                .filter(source -> !source.contains("\n"))
                .filter(source -> source.length() > 2)
                .map(source -> Character.isWhitespace(source.charAt(1))
                        || Character.isWhitespace(source.charAt(source.length() - 2))
                )
                .orElse(false);
    }

    private boolean compactArrayInitializerValue(Expression value) {
        return (
            value.isLiteralExpr()
            || (!sourceShapePolicy.hasContainedComments(value)
                && !compact.apply(value).contains("\n"))
        );
    }

    /**
     * Renders the array element type, breaking generic type arguments before array levels when needed.
     *
     * <p>Only class/interface element types with type arguments and array levels can take the broken path, and only as a
     * width-driven last resort: the broken {@code new Type<...>[]} shape is reached when the compact prefix overflows the
     * line at the current indentation (see {@link #arrayCreationTypeBreaks}). Otherwise the element type stays compact and
     * the surrounding initializer/assignment ladder breaks at the braces or {@code =}.
     */
    private Doc arrayCreationType(ArrayCreationExpr expression) {
        if (!arrayCreationTypeBreaks(expression, currentIndentedWidth)) {
            return Doc.text(compactTypeLike.apply(expression.getElementType()));
        }
        ClassOrInterfaceType type = expression.getElementType().asClassOrInterfaceType();
        NodeList<com.github.javaparser.ast.type.Type> typeArguments = type.getTypeArguments().orElse(new NodeList<>());
        return Doc.concat(
            Doc.text(type.getNameWithScope()),
            Doc.text("<"),
            Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    Doc.join(
                        Doc.concat(Doc.text(","), Doc.HARD_LINE),
                        typeArguments.stream()
                                .map(argument -> Doc.text(compactTypeLike.apply(argument)))
                                .toList()
                    )
                )
            ),
            Doc.HARD_LINE,
            Doc.text(">")
        );
    }

    /**
     * Reports whether an array creation type must break its generic type arguments before its array-level suffixes.
     *
     * <p>A <em>width-driven last resort</em>, not structural: eligible only for a class/interface element type with type
     * arguments and at least one array level, and it fires only when the compact prefix ({@code new Type<...>[]}, plus a
     * trailing open brace when an initializer follows) overflows at {@code widthAtContinuation}. Otherwise the type stays
     * compact and the surrounding ladder breaks at the braces or {@code =} rather than shattering short generics. The
     * caller supplies the width function because the continuation indent differs between a standalone expression and a
     * field/local initializer.
     */
    boolean arrayCreationTypeBreaks(ArrayCreationExpr expression, ToIntFunction<String> widthAtContinuation) {
        if (
            !expression.getElementType().isClassOrInterfaceType()
            || expression.getElementType().asClassOrInterfaceType().getTypeArguments().isEmpty()
            || expression.getLevels().isEmpty()
        ) {
            return false;
        }
        String compactPrefix = arrayCreationPrefix(expression)
            + (expression.getInitializer().isPresent() ? " {" : "");
        return widthAtContinuation.applyAsInt(compactPrefix) > options.lineWidth();
    }

    Doc arrayInitializer(ArrayInitializerExpr expression) {
        return arrayInitializer(expression, false);
    }

    /**
     * Renders array initializer braces while preserving orphan comments before values.
     *
     * <p>Orphan comments are inserted as their own initializer lines ahead of the values JavaParser exposes. The compact
     * path is skipped when the caller forces a break or when comments/non-literal values need the structured value
     * renderer.
     */
    Doc arrayInitializer(ArrayInitializerExpr expression, boolean forceBreak) {
        List<Doc> comments =
            expression.getValues().isEmpty() ? this.comments.orphanCommentStatements(expression) : List.of();
        if (expression.getValues().isEmpty() && comments.isEmpty()) {
            return Doc.text("{}");
        }
        Optional<String> compact = compactArrayInitializer(expression);
        if (
            !forceBreak
            && !arrayInitializerFansOnePerLine(expression)
            && compact.isPresent()
            && currentIndentedWidth.applyAsInt(compact.orElseThrow()) <= options.lineWidth()
        ) {
            return Doc.text(compact.orElseThrow());
        }
        boolean forceNestedArrayRows = nestedArrayRowsShouldBreak(expression);
        List<Doc> values = new ArrayList<>(comments);
        if (!expression.getValues().isEmpty()) {
            addLineCommentDocs(
                values,
                commentPlacement.lineCommentsBeforeFirst(expression, expression.getValues().get(0))
            );
        }
        for (int i = 0; i < expression.getValues().size(); i++) {
            Expression value = expression.getValues().get(i);
            Expression next = i + 1 < expression.getValues().size() ? expression.getValues().get(i + 1) : null;
            ArrayInitializerValue valueLines = arrayInitializerValueLine(value, next, forceNestedArrayRows, ",");
            values.add(valueLines.line());
            values.addAll(valueLines.trailingCommentLines());
        }
        return Doc.concat(
            Doc.text("{"),
            Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, values))),
            Doc.HARD_LINE,
            Doc.text("}")
        );
    }

    /**
     * Renders one array value and attaches block comments that JavaParser assigns to neighboring values.
     *
     * <p>A block comment before the value is kept before the expression, while a block comment attached to the next
     * value but physically placed after the current value stays on the current line. That source-position check
     * preserves trailing block comments between comma-separated values.
     */
    private Doc arrayInitializerValueExpression(
            Expression value,
            Expression next,
            boolean forceNestedArrayRows,
            String suffix
    ) {
        List<Doc> parts = new ArrayList<>();
        Doc leadingComment = comments.ownComment(
            value,
            comment -> comment instanceof BlockComment
                    && CommentIndex.startsBefore(comment, value)
        );
        if (leadingComment != Doc.EMPTY) {
            parts.add(leadingComment);
            parts.add(Doc.text(" "));
        }
        Doc trailingComment = trailingBlockComment(value, next);
        boolean suffixAppended = false;
        if (forceNestedArrayRows && value instanceof ArrayInitializerExpr nestedArrayInitializer) {
            parts.add(arrayInitializer(nestedArrayInitializer, true));
        } else if (trailingComment == Doc.EMPTY && value instanceof MethodCallExpr methodCall) {
            parts.add(methodCallWithTail.apply(methodCall, ExpressionTail.of(suffix)));
            suffixAppended = true;
        } else if (trailingComment == Doc.EMPTY && value instanceof ObjectCreationExpr objectCreation) {
            parts.add(objectCreationWithSuffix.apply(objectCreation, suffix));
            suffixAppended = true;
        } else {
            parts.add(rendering.render(value));
        }
        if (trailingComment != Doc.EMPTY) {
            parts.add(Doc.text(" "));
            parts.add(trailingComment);
        }
        if (!suffixAppended) {
            parts.add(Doc.text(suffix));
        }
        return Doc.concat(parts);
    }

    private ArrayInitializerValue arrayInitializerValueLine(
            Expression value,
            Expression next,
            boolean forceNestedArrayRows,
            String suffix,
            List<JavaCommentTrivia> trailingLineComments
    ) {
        Doc valueDoc = arrayInitializerValueExpression(value, next, forceNestedArrayRows, suffix);
        Node container = value.getParentNode().orElseThrow();
        List<Doc> trailingCommentLines = new ArrayList<>();
        for (JavaCommentTrivia comment : dedupByCommentIdentity(trailingLineComments)) {
            // A gap comment between elements is offered under the array initializer's own (container, INTERLEAVED) anchor.
            // Anchoring to the container, not the element value, lets ownership disambiguate the competing offers without
            // a build-order isPrinted skip: a method-call-chain element claims its own trailing comment under (finalCall,
            // INTERLEAVED), a different key, so ownsHere blocks this slot and comment(...) returns Doc.EMPTY; a comment
            // the value render left untouched is owned and placed here. (value, INTERLEAVED) is deliberately avoided —
            // for a single-expression element it often collides with the chain printer's finalTrailingLineComment owner
            // and double-renders. Gaps are de-duplicated by identity so the same comment is never offered twice here.
            Doc commentDoc = comments.comment(comment, container, OwnerSlot.INTERLEAVED);
            if (commentDoc == Doc.EMPTY) {
                continue;
            }
            if (
                comment.startsOnEndLine(value)
                || comment.startsAfterNodeOnSameLine(value)
                || valueContainsComment(value, comment)
            ) {
                valueDoc = Doc.concat(valueDoc, Doc.text(" "), commentDoc);
            } else {
                trailingCommentLines.add(commentDoc);
            }
        }
        return new ArrayInitializerValue(valueDoc, trailingCommentLines);
    }

    private ArrayInitializerValue arrayInitializerValueLine(
            Expression value,
            Expression next,
            boolean forceNestedArrayRows,
            String suffix
    ) {
        List<JavaCommentTrivia> trailingLineComments = next == null
            ? commentPlacement.lineCommentsAfterLast(value.getParentNode().orElseThrow(), value)
            : commentPlacement.lineCommentsBetween(value.getParentNode().orElseThrow(), value, next);
        return arrayInitializerValueLine(value, next, forceNestedArrayRows, suffix, trailingLineComments);
    }

    private void addLineCommentDocs(List<Doc> docs, List<JavaCommentTrivia> sourceComments) {
        sourceComments.stream()
                .map(comments::comment)
                .filter(comment -> comment != Doc.EMPTY)
                .forEach(docs::add);
    }

    private boolean valueContainsComment(Expression value, JavaCommentTrivia comment) {
        return value.getAllContainedComments().stream().anyMatch(contained -> contained == comment.comment());
    }

    /**
     * Drops repeats of the same JavaParser comment within one element gap, keeping the first occurrence in source order.
     *
     * <p>The gap queries read {@link JavaCommentPlacementPolicy#containedComments(Node)}, which can list the same comment
     * twice when a perturbation makes it reachable through two containment paths. Since a gap is offered under one shared
     * {@code (container, INTERLEAVED)} owner, a duplicate would render twice through the idempotent-by-owner re-claim
     * branch, so de-duplicating by identity keeps each comment offered exactly once. Distinct nodes with the same text
     * are compared by identity and both kept.
     */
    private static List<JavaCommentTrivia> dedupByCommentIdentity(List<JavaCommentTrivia> gap) {
        java.util.Set<com.github.javaparser.ast.comments.Comment> seen =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        List<JavaCommentTrivia> deduped = new ArrayList<>();
        for (JavaCommentTrivia trivia : gap) {
            if (seen.add(trivia.comment())) {
                deduped.add(trivia);
            }
        }
        return deduped;
    }

    /**
     * Recovers the block comment that trails an array element after its comma
     * ({@code value /* note *}{@code /, next}), independent of source shape.
     *
     * <p>At {@code @default} JavaParser attaches it as {@code next}'s own comment, so the own-comment path renders it. A
     * whitespace perturbation that pushes the comment onto its own line re-buckets it as an {@link ArrayInitializerExpr}
     * orphan; the own path then loses it, so we recover the initializer's orphan block comments that source-order between
     * {@code value} and {@code next}. The recovered set is filtered to {@link CommentIndex#liesBetween(Comment, Node,
     * Node)} so it claims exactly the gap comment and never a later element's trailing comment.
     */
    private Doc trailingBlockComment(Expression value, Expression next) {
        if (next == null) {
            return Doc.EMPTY;
        }
        Doc own = next.getComment()
                .filter(BlockComment.class::isInstance)
                .filter(comment -> CommentIndex.startsAfterNodeOnSameLine(value, comment))
                .filter(comment -> CommentIndex.startsBeforeBeginLine(comment, next))
                .map(comments::comment)
                .orElse(Doc.EMPTY);
        if (own != Doc.EMPTY) {
            return own;
        }
        return value.getParentNode()
                .filter(ArrayInitializerExpr.class::isInstance)
                .map(arrayInitializer -> Doc.concat(
                        commentPlacement.blockCommentsBefore(List.of(arrayInitializer), next)
                                .stream()
                                .filter(comment -> comment.liesBetween(value, next))
                                .map(comments::comment)
                                .filter(comment -> comment != Doc.EMPTY)
                                .toList()
                ))
                .orElse(Doc.EMPTY);
    }

    private boolean nestedArrayRowsShouldBreak(ArrayInitializerExpr expression) {
        if (
            expression.getValues().isEmpty()
            || expression.getValues().stream().anyMatch(value -> !(value instanceof ArrayInitializerExpr))
        ) {
            return false;
        }
        return expression.getValues()
                .stream()
                .map(ArrayInitializerExpr.class::cast)
                .anyMatch(row -> compactArrayInitializer(row)
                            .map(compact -> currentIndentedWidth.applyAsInt(compact) > options.lineWidth())
                            .orElse(true)
                );
    }

    private String compactJoinArrayLevels(NodeList<ArrayCreationLevel> levels) {
        return levels.stream()
                .map(level -> level.getDimension().map(dimension -> "[" + compact.apply(dimension) + "]").orElse("[]"))
                .reduce(String::concat)
                .orElse("");
    }

    private record ArrayInitializerValue(Doc line, List<Doc> trailingCommentLines) {}
}
