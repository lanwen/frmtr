package dev.lanwen.frmtr.java;

import com.github.javaparser.Range;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.ReceiverParameter;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Prints callable receiver parameters, ordinary parameters, and declaration type-parameter lists.
 *
 * <p>This helper owns the {@code (...)} and {@code <...>} document shape for callable signatures, including
 * source-layout-sensitive forks around receiver names and parameter comments. It intentionally leaves method,
 * constructor, record, and class header assembly to {@link JavaPrinter}, and receives type, modifier, compact-source,
 * and trailing-comment rendering decisions from the caller.
 */
final class CallableSignaturePrinter {

    private final CommentTracker comments;

    private final RawSource rawSource;

    private final FormatterOptions options;

    private final LayoutWidth layoutWidth;

    private final Function<Node, String> compact;

    private final Function<Node, String> compactTypeLike;

    private final Function<Type, Doc> typeBody;

    private final JavaFormatRule<AnnotationExpr> annotation;

    private final Function<AnnotationExpr, String> annotationFlatText;

    private final Function<Modifier, String> modifier;

    private final Predicate<Type> typeCanBreak;

    private final Function<Doc, String> commentText;

    private final ParameterTrailingBlockCommentLayout parameterTrailingBlockComments;

    CallableSignaturePrinter(
            CommentTracker comments,
            RawSource rawSource,
            FormatterOptions options,
            LayoutWidth layoutWidth,
            Function<Node, String> compact,
            Function<Node, String> compactTypeLike,
            Function<Type, Doc> typeBody,
            JavaFormatRule<AnnotationExpr> annotation,
            Function<AnnotationExpr, String> annotationFlatText,
            Function<Modifier, String> modifier,
            Predicate<Type> typeCanBreak,
            Function<Node, Doc> unattachedTrailingBlockComment,
            Function<Doc, String> commentText
    ) {
        this.comments = comments;
        this.rawSource = rawSource;
        this.options = options;
        this.layoutWidth = layoutWidth;
        this.compact = compact;
        this.compactTypeLike = compactTypeLike;
        this.typeBody = typeBody;
        this.annotation = annotation;
        this.annotationFlatText = annotationFlatText;
        this.modifier = modifier;
        this.typeCanBreak = typeCanBreak;
        this.commentText = commentText;
        this.parameterTrailingBlockComments = new ParameterTrailingBlockCommentLayout(
            comments,
            commentText,
            unattachedTrailingBlockComment
        );
    }

    /**
     * Prints callable parameters, including an optional receiver parameter before ordinary parameters.
     *
     * <p>The force-break branch is chosen by the caller after it knows the full signature prefix and suffix, because
     * parameters may need to break to leave room for throws clauses or a following block.
     */
    Doc parameters(CallableDeclaration<?> declaration, boolean forceBreak) {
        // A leading line comment on a parameter must render on its own line above it, which is only safe once the whole
        // list breaks one-per-line. Force the break even when the flat list fits, so the `//` never comments out the
        // rest of the signature.
        forceBreak = forceBreak || parametersHaveLeadingLineComment(declaration);
        List<Doc> parameters = new ArrayList<>();
        Optional<Node> previous = declaration.getReceiverParameter()
                .map(receiver -> {
                    parameters.add(receiverParameter(receiver));
                    return receiver;
                });
        for (Parameter parameter : declaration.getParameters()) {
            parameters.add(parameter(declaration, previous, parameter));
            previous = Optional.of(parameter);
        }
        if (parameters.isEmpty()) {
            return Doc.text("()");
        }
        Doc doc = Doc.concat(
            Doc.text("("),
            Doc.indent(
                Doc.indent(
                    Doc.concat(
                        forceBreak ? Doc.HARD_LINE : Doc.SOFT_LINE,
                        Doc.joinComma(parameters)
                    )
                )
            ),
            forceBreak ? Doc.HARD_LINE : Doc.SOFT_LINE,
            Doc.text(")")
        );
        return forceBreak ? doc : Doc.group(doc);
    }

    /**
     * Prints parameters on a continuation line while keeping the parameter text compact.
     *
     * <p>This is used when a flat signature only overflows because a following clause such as {@code throws} needs room,
     * and the parameter list itself still fits as one continuation line.
     */
    Doc compactContinuationParameters(CallableDeclaration<?> declaration) {
        String parameters = callableParameterText(declaration);
        if (parameters.isEmpty()) {
            return Doc.text("()");
        }
        // callableParameterText already carries each parameter-name block comment inline (via the non-claiming flat-text
        // producer), so claim each one here to account for it exactly once; the visible text comes from the flat string.
        declaration.getParameters().forEach(this::claimParameterNameBlockComment);
        return Doc.concat(
            Doc.text("("),
            Doc.indent(Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(parameters)))),
            Doc.HARD_LINE,
            Doc.text(")")
        );
    }

    boolean parametersFitOnContinuation(CallableDeclaration<?> declaration) {
        // The compact continuation renders every parameter on one line from comment-free flat text, so a leading line
        // comment would be dropped there; refuse the continuation when any parameter has one (the caller falls back to
        // the broken list, which gives the comment its own line). Block-comment-only parameters stay eligible.
        if (parametersHaveLeadingLineComment(declaration)) {
            return false;
        }
        String continuationLine = options.indentUnit().repeat(2) + callableParameterText(declaration);
        return currentIndentedWidth(continuationLine) <= options.lineWidth();
    }

    /**
     * Ranks the parameter list's flat, compact-continuation, and one-per-line shapes at the true rendered column,
     * reserving {@code suffixLength} columns of trailing same-line content (a throws clause or the body/semicolon
     * opener) so a list that only fits alone still yields to a broken shape when that content needs the room.
     *
     * <p>A parameter leading-line-comment keeps forcing the one-per-line shape build-time, unranked, because
     * {@link #parameters(CallableDeclaration, boolean)} always breaks once any parameter has one regardless of the
     * flag passed — flattening that shape would flatten its comma separators while the comment's own hard line
     * survives, mangling it. {@link Doc#flatCandidate} guards the same hazard for a parameter whose own content (an
     * annotation preserving a source multi-line shape) carries a hard line of its own: it drops the flat shape from
     * the ranking instead of forcing a mangled hybrid. {@code compactContinuationEligible} lets a caller such as a
     * broken-return-type method opt the continuation shape out entirely.
     */
    Doc rankedParameters(CallableDeclaration<?> declaration, boolean compactContinuationEligible, int suffixLength) {
        if (declaration.getParameters().isEmpty() && declaration.getReceiverParameter().isEmpty()) {
            // An empty `()` has no break group and no flat-vs-broken choice, so ranking it would only wrap an
            // unconditionally identical pair of alternatives in a pointless BestFitting node.
            return Doc.text("()");
        }
        if (parametersHaveLeadingLineComment(declaration)) {
            return parameters(declaration, false);
        }
        Optional<Doc> flat = Doc.flatCandidate(parameters(declaration, false));
        Doc broken = parameters(declaration, true);
        Optional<Doc> continuation = compactContinuationEligible && parametersFitOnContinuation(declaration)
            ? Optional.of(compactContinuationParameters(declaration))
            : Optional.empty();
        List<Doc> alternatives = new ArrayList<>();
        List<Integer> priorities = new ArrayList<>();
        flat.ifPresent(doc -> {
            alternatives.add(doc);
            priorities.add(2);
        });
        continuation.ifPresent(doc -> {
            alternatives.add(doc);
            priorities.add(1);
        });
        alternatives.add(broken);
        priorities.add(0);
        Doc ranked = alternatives.size() == 1
            ? broken
            : Doc.bestFittingFirstLine(alternatives, priorities.stream().mapToInt(Integer::intValue).toArray());
        return Doc.reserving(ranked, suffixLength);
    }

    /**
     * Prints a receiver parameter as one compact signature item.
     */
    Doc receiverParameter(ReceiverParameter parameter) {
        return Doc.text(compactReceiverParameter(parameter));
    }

    /**
     * Builds the flat receiver parameter text used by both rendered docs and line-width estimates.
     */
    String compactReceiverParameter(ReceiverParameter parameter) {
        List<String> parts = new ArrayList<>();
        parameter.getAnnotations().stream().map(annotationFlatText).forEach(parts::add);
        parts.add(compactTypeLike.apply(parameter.getType()));
        parts.add(receiverName(parameter));
        return String.join(" ", parts);
    }

    /**
     * Recovers the receiver name from the original token range because receiver parameters can carry source-only
     * qualification details that are easier to preserve from compact raw text than from JavaParser's split fields.
     */
    String receiverName(ReceiverParameter parameter) {
        return parameter.getTokenRange()
                .map(Object::toString)
                .map(rawSource::normalizeWhitespace)
                .map(text -> text.substring(text.lastIndexOf(' ') + 1))
                .orElseGet(() -> compact.apply(parameter.getName()));
    }

    /**
     * Decides whether the callable parameter list must break before later signature clauses are appended.
     *
     * <p>The flat signature is measured at the declaration's true rendered column ({@link LayoutWidth#nodeLine} counts
     * every enclosing {@code TypeDeclaration}/{@code BlockStmt}, floored at one unit by {@code currentIndentedWidth}), so
     * a signature that fits at one level can still overflow when nested. The caller's {@code prefix}/{@code suffix} (the
     * {@code "throws … {"}/{@code ";"} appended on this line) are folded into the measured text.
     */
    boolean parametersBreak(String prefix, CallableDeclaration<?> declaration, String suffix) {
        String parameters = callableParameterText(declaration);
        String signatureLine = prefix + "(" + parameters + ")" + suffix;
        int width = Math.max(
            layoutWidth.nodeLine(declaration, signatureLine),
            currentIndentedWidth(signatureLine)
        );
        return width > options.lineWidth();
    }

    /**
     * Reports whether a declaration's return type plus name-and-open-paren opener is itself too wide to leave the
     * parameter list any first-line room — the width signal that the return type's own type arguments must break rather
     * than the parameter list.
     *
     * <p>A wide generic return type ({@code Function<Collection<X>, Publisher<Y>> decode()},
     * {@code Mono<VeryLongRouteKey> resolveRoute(a, b)}) overflows on the opener up to {@code (}, which breaking the
     * parameters cannot rescue, so the type's arguments must fan. Measured at the declaration's rendered column like
     * {@link #parametersBreak(String, CallableDeclaration, String)}.
     */
    boolean returnTypeOverflows(String returnTypeNamePrefix) {
        String openerLine = returnTypeNamePrefix + "(";
        return currentIndentedWidth(openerLine) > options.lineWidth();
    }

    /**
     * Builds the flat parameter text used only for deciding whether the rendered parameter list should break.
     */
    String callableParameterText(CallableDeclaration<?> declaration) {
        List<String> parameters = new ArrayList<>();
        declaration.getReceiverParameter().map(this::compactReceiverParameter).ifPresent(parameters::add);
        declaration.getParameters().stream().map(this::parameterFlat).forEach(parameters::add);
        return String.join(", ", parameters);
    }

    /**
     * Prints declaration type parameters using a grouped soft-line layout.
     */
    Doc typeParameters(NodeList<TypeParameter> typeParameters) {
        return Doc.group(
            Doc.concat(
                Doc.text("<"),
                Doc.indent(
                    Doc.concat(
                        Doc.SOFT_LINE,
                        Doc.joinComma(
                            typeParameters.stream()
                                    .map(this::typeParameter)
                                    .toList()
                        )
                    )
                ),
                Doc.SOFT_LINE,
                Doc.text(">")
            )
        );
    }

    /**
     * Prints declaration type parameters with one hard line per parameter.
     *
     * <p>Class and interface headers sometimes need the closing bracket indented with the parameter block so following
     * clauses still read as part of the same broken header.
     */
    Doc brokenTypeParameters(NodeList<TypeParameter> typeParameters, boolean indentClosingBracket) {
        Doc parameters = Doc.concat(
            Doc.text("<"),
            Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    Doc.join(
                        Doc.concat(Doc.text(","), Doc.HARD_LINE),
                        typeParameters.stream()
                                .map(this::typeParameter)
                                .toList()
                    )
                )
            ),
            Doc.HARD_LINE,
            Doc.text(">")
        );
        return indentClosingBracket ? Doc.indent(parameters) : parameters;
    }

    /**
     * Breaks type-parameter bounds only when the declaration has a plain source shape that can be safely reconstructed
     * from the parsed bound list.
     */
    Doc typeParameter(TypeParameter typeParameter) {
        String flat = compactTypeLike.apply(typeParameter);
        if (
            !typeParameter.getAnnotations().isEmpty()
            || typeParameter.getTypeBound().isEmpty()
            || flat.contains("@")
        ) {
            return Doc.text(compactTypeLike.apply(typeParameter));
        }
        Doc firstBound = typeBody.apply(typeParameter.getTypeBound().get(0));
        List<Doc> trailingBounds = typeParameter.getTypeBound()
                .stream()
                .skip(1)
                .map(bound -> Doc.concat(Doc.text("& "), typeBody.apply(bound)))
                .toList();
        if (trailingBounds.isEmpty()) {
            return Doc.group(Doc.concat(Doc.text(typeParameter.getNameAsString() + " extends "), firstBound));
        }
        return Doc.group(
            Doc.concat(
                Doc.text(typeParameter.getNameAsString() + " extends "),
                firstBound,
                Doc.indent(Doc.concat(Doc.LINE, Doc.join(Doc.LINE, trailingBounds)))
            )
        );
    }

    /**
     * Prints one ordinary parameter, prepending any {@code //} line comment that leads it in source order.
     *
     * <p>The leading comment renders on its own line above the parameter, outside the per-parameter group so its hard
     * line never forces a breakable type's group to break; the {@code parameters(...)} entry points already break the
     * whole list when any parameter has one. It is recovered by source order ({@link #parameterLeadingLineComments}) so
     * it stays owned by this parameter and claimed once across the default, collapsed, and expanded shapes.
     */
    private Doc parameter(CallableDeclaration<?> declaration, Optional<Node> previous, Parameter parameter) {
        List<Doc> rendered = new ArrayList<>();
        for (Doc comment : parameterLeadingLineComments(declaration, previous, parameter)) {
            rendered.add(Doc.concat(comment, Doc.HARD_LINE));
        }
        rendered.add(parameterCore(parameter));
        return rendered.size() == 1 ? rendered.getFirst() : Doc.concat(rendered);
    }

    private Doc parameterCore(Parameter parameter) {
        // A C-style array parameter (byte b[]) must use the non-breaking text path: its after-name brackets have no
        // group to break, and the breakable path would print the name-spanning type body then the name again (the
        // duplicated-name bug). parameterTypeAndNameText handles the element-type prefix and after-name bracket suffix.
        if (
            !parameter.isVarArgs()
            && !CStyleArrayDeclarators.parameterHasCStyleBrackets(parameter)
            && typeCanBreak.test(parameter.getType())
        ) {
            // The breakable branch emits type body and name as separate docs, so the between-type-and-name comment
            // cannot ride the flat name text; claim and render it here, between the two, so it survives a flat or broken
            // type group.
            List<Doc> parts = new ArrayList<>();
            parameterLeadingBlockComment(parameter).ifPresent(parts::add);
            parts.add(parameterModifierAnnotationPrefix(parameter));
            parts.add(typeBody.apply(parameter.getType()));
            parts.add(Doc.text(
                " "
                    + claimParameterNameBlockComment(parameter).orElse("")
                    + parameter.getNameAsString()
                    + parameterTrailingBlockComments.parameterTrailingBlockCommentText(parameter)
            ));
            return Doc.group(
                Doc.concat(
                    parts
                )
            );
        }
        // The non-breakable branch renders from flat text, where parameterTypeAndNameText already places the inline
        // comment between the type and the name. Claim it here (discarding the rendered doc) so the comment is accounted
        // exactly once; the visible text comes from the flat path.
        claimParameterNameBlockComment(parameter);
        List<Doc> prefix = new ArrayList<>();
        parameterLeadingBlockComment(parameter).ifPresent(prefix::add);
        prefix.add(parameterModifierAnnotationPrefix(parameter));
        prefix.add(Doc.text(
            parameterTypeAndNameText(parameter)
                + parameterTrailingBlockComments.parameterTrailingBlockCommentText(parameter)
        ));
        return Doc.concat(prefix);
    }

    private Optional<Doc> parameterLeadingBlockComment(Parameter parameter) {
        Doc leadingBlockComment = comments.ownComment(parameter, BlockComment.class::isInstance);
        if (leadingBlockComment == Doc.EMPTY) {
            return Optional.empty();
        }
        return Optional.of(Doc.text(commentText.apply(leadingBlockComment) + " "));
    }

    /**
     * Selects the inline block comment JavaParser attaches to a parameter's <em>name</em> (the {@code /* c *​/} in
     * {@code Type /* c *​/ name}) rather than to the {@link Parameter} node.
     *
     * <p>JavaParser parks such a comment on {@code parameter.getName().getComment()}, where the parameter-level
     * leading/trailing recovery never looks, so it would be dropped. The selector is intentionally narrow: a block comment
     * that begins before the name node, i.e. between the type and the name. A leading {@code //} line comment above the
     * parameter is owned and rendered by {@link #parameterLeadingLineComments}, so it is excluded here.
     */
    private Optional<Comment> parameterNameBlockComment(Parameter parameter) {
        return parameter.getName()
                .getComment()
                .filter(BlockComment.class::isInstance)
                .filter(comment -> CommentIndex.startsBefore(comment, parameter.getName()));
    }

    /**
     * Renders the parameter-name block comment as flat text without claiming it.
     *
     * <p>This feeds the shared flat-text producer ({@link #parameterTypeAndNameText}) so every render path that prints
     * from flat text — the non-breakable parameter and the compact-continuation layout — keeps the comment inline, and so
     * the width estimates account for its width. Claiming is intentionally left to the render entry points
     * ({@link #parameterCore} and {@link #compactContinuationParameters}) because this producer is shared with the
     * width-decision paths, which must not consume the single comment claim.
     */
    private String parameterNameBlockCommentText(Parameter parameter) {
        return parameterNameBlockComment(parameter)
                .map(comment -> commentText.apply(JavaFormatter.commentDoc(comment)) + " ")
                .orElse("");
    }

    /**
     * Claims the parameter-name block comment for accounting and returns its rendered text, or empty when there is none.
     *
     * <p>The render entry points call this exactly once per parameter so the comment is claimed a single time per print
     * pass; the breakable branch uses the returned text directly while the flat-text branches discard it because the text
     * already arrived through {@link #parameterNameBlockCommentText}.
     */
    private Optional<String> claimParameterNameBlockComment(Parameter parameter) {
        if (parameterNameBlockComment(parameter).isEmpty()) {
            return Optional.empty();
        }
        Doc comment = comments.ownComment(parameter.getName(), BlockComment.class::isInstance);
        if (comment == Doc.EMPTY) {
            return Optional.empty();
        }
        return Optional.of(commentText.apply(comment) + " ");
    }

    /**
     * Claims and renders the {@code //} line comments that lead {@code parameter} in source order, recovering them
     * however whitespace re-bucketed them across the parameter's neighbors.
     *
     * <p>A whitespace collapse/expand can re-bucket a parameter's leading comment onto the <em>previous</em> parameter's
     * trailing trivia, so selecting by JavaParser attachment drops it. This query is source-order instead: it unions the
     * gap comments parked between the previous parameter (or receiver) and {@code parameter}
     * ({@link CommentTracker#gapLineCommentsBefore}) with {@code parameter}'s own leading comment. Both claim by identity,
     * so the comment renders exactly once — the own path wins at the default layout, the gap path recovers it under
     * perturbation. The first parameter has no previous node and uses only the own path.
     */
    private List<Doc> parameterLeadingLineComments(
            CallableDeclaration<?> declaration,
            Optional<Node> previous,
            Parameter parameter
    ) {
        List<Doc> rendered = new ArrayList<>();
        if (declaration != null && previous.isPresent()) {
            // Scan both the previous parameter and the declaration: a collapse parks the comment as the previous
            // parameter's trailing own comment, an expand as the declaration's orphan, so both buckets are needed.
            // gapLineCommentsBefore selects only line comments strictly between the two and claims each by identity.
            rendered.addAll(
                comments.gapLineCommentsBefore(previous.orElseThrow(), parameter, List.of(previous.orElseThrow(), declaration))
            );
        }
        Doc ownLeadingLineComment = comments.ownComment(
            parameter,
            comment -> comment instanceof LineComment && CommentIndex.startsBefore(comment, parameter)
        );
        if (ownLeadingLineComment != Doc.EMPTY) {
            rendered.add(ownLeadingLineComment);
        }
        return rendered;
    }

    /**
     * Reports whether any ordinary parameter of {@code declaration} is led by a {@code //} line comment in source order,
     * used to force the parameter list to break. A pure source read (the same source-order ownership
     * {@link #parameterLeadingLineComments} renders from) that claims nothing, so it can be asked before the layout is
     * chosen. Shape-independent: a comment the layout pushed onto the previous parameter still lies in the gap before the
     * parameter it leads.
     */
    private boolean parametersHaveLeadingLineComment(CallableDeclaration<?> declaration) {
        List<Comment> lineComments = declaration.getAllContainedComments()
                .stream()
                .filter(LineComment.class::isInstance)
                .toList();
        if (lineComments.isEmpty()) {
            return false;
        }
        Node previous = declaration.getReceiverParameter().map(Node.class::cast).orElse(null);
        for (Parameter parameter : declaration.getParameters()) {
            if (previous != null && leadsParameterInGap(lineComments, previous, parameter)) {
                return true;
            }
            if (parameterHasOwnLeadingLineComment(parameter)) {
                return true;
            }
            previous = parameter;
        }
        return false;
    }

    private boolean leadsParameterInGap(List<Comment> lineComments, Node previous, Parameter parameter) {
        return lineComments.stream().anyMatch(comment -> CommentIndex.liesBetween(comment, previous, parameter));
    }

    private boolean parameterHasOwnLeadingLineComment(Parameter parameter) {
        return parameter.getComment()
                .filter(LineComment.class::isInstance)
                .filter(comment -> CommentIndex.startsBefore(comment, parameter))
                .isPresent();
    }

    private Doc parameterModifierAnnotationPrefix(Parameter parameter) {
        List<ParameterPrefixPart> parts = parameterPrefixParts(parameter);
        if (parts.isEmpty()) {
            return Doc.EMPTY;
        }
        if (!parameterAnnotationPrefixOverflows(parameter, parts)) {
            return Doc.text(parameterPrefixText(parts));
        }
        List<Doc> partDocs = new ArrayList<>();
        for (int index = 0; index < parts.size(); index++) {
            partDocs.add(parameterPrefixPartDoc(parts.get(index), parts, index, parameter));
        }
        return Doc.concat(
            Doc.join(
                Doc.text(" "),
                partDocs
            ),
            Doc.text(" ")
        );
    }

    private List<ParameterPrefixPart> parameterPrefixParts(Parameter parameter) {
        List<ParameterPrefixPart> parts = new ArrayList<>();
        int fallbackOrder = 0;
        for (AnnotationExpr annotationExpr : parameter.getAnnotations()) {
            String text = annotationFlatText.apply(annotationExpr);
            parts.add(new ParameterPrefixPart(
                annotationExpr.getRange(),
                fallbackOrder++,
                text,
                Optional.of(annotationExpr)
            ));
        }
        for (Modifier modifierNode : parameter.getModifiers()) {
            String text = modifier.apply(modifierNode);
            parts.add(new ParameterPrefixPart(
                modifierNode.getRange(),
                fallbackOrder++,
                text,
                Optional.empty()
            ));
        }
        parts.sort(
            Comparator.comparingInt((ParameterPrefixPart part) -> rangeBeginLine(part.range()))
                    .thenComparingInt(part -> rangeBeginColumn(part.range()))
                    .thenComparingInt(ParameterPrefixPart::fallbackOrder)
        );
        return parts;
    }

    private Doc parameterPrefixPartDoc(
            ParameterPrefixPart part,
            List<ParameterPrefixPart> parts,
            int index,
            Parameter parameter
    ) {
        return part.annotation()
                .map(node -> annotation.format(node, parameterAnnotationLayout(parts, index, parameter)))
                .orElseGet(() -> Doc.text(part.flatText()));
    }

    /**
     * Builds the {@link LayoutContext} that tells a breakable parameter annotation where it renders, so its
     * flat/structured choice is width-driven at the real column instead of reading source line breaks.
     *
     * <p>{@link LayoutContext#leftEdgePrefix()} carries the extra indent unit a broken parameter list adds past the
     * member baseline plus every earlier prefix part; the trailing content carries the rest of the prefix and the
     * {@code " Type name"} emitted after it. The annotation then breaks only when keeping it flat would push that
     * type/name past the width.
     */
    private LayoutContext parameterAnnotationLayout(List<ParameterPrefixPart> parts, int index, Parameter parameter) {
        StringBuilder leftEdge = new StringBuilder(options.indentUnit());
        for (int before = 0; before < index; before++) {
            leftEdge.append(parts.get(before).flatText()).append(' ');
        }
        StringBuilder trailing = new StringBuilder();
        for (int after = index + 1; after < parts.size(); after++) {
            trailing.append(' ').append(parts.get(after).flatText());
        }
        trailing.append(' ').append(parameterTypeAndNameText(parameter));
        return LayoutContext.root()
                .withLeftEdgePrefix(leftEdge.toString())
                .withTrailingContent(trailing.toString());
    }

    private int rangeBeginLine(Optional<Range> range) {
        return range.map(value -> value.begin.line).orElse(Integer.MAX_VALUE);
    }

    private int rangeBeginColumn(Optional<Range> range) {
        return range.map(value -> value.begin.column).orElse(Integer.MAX_VALUE);
    }

    private String parameterPrefixText(List<ParameterPrefixPart> parts) {
        return parts.stream()
                .map(ParameterPrefixPart::flatText)
                .reduce((left, right) -> left + " " + right)
                .map(text -> text + " ")
                .orElse("");
    }

    private boolean parameterAnnotationPrefixOverflows(Parameter parameter, List<ParameterPrefixPart> parts) {
        // A parameter's annotation prefix renders structured (each annotation breakable) purely when the flat
        // parameter overflows the line.
        return !parameter.getAnnotations().isEmpty()
            && currentIndentedWidth(parameterFlat(parameter, parts)) > options.lineWidth();
    }

    /**
     * Builds compact ordinary parameter text with source-ordered modifiers and annotations.
     */
    String parameterFlat(Parameter parameter) {
        return parameterFlat(parameter, parameterPrefixParts(parameter));
    }

    private String parameterFlat(Parameter parameter, List<ParameterPrefixPart> parts) {
        return parameterPrefixText(parts) + parameterTypeAndNameText(parameter);
    }

    private String parameterTypeAndNameText(Parameter parameter) {
        // A C-style parameter (byte b[]) carries its brackets after the name, so the type's token range spans the name
        // and compact text already reads "byte b[]". Rendering the element type as prefix and re-appending the brackets
        // avoids emitting the name twice while keeping the after-name position the AST-equivalence guardrail requires
        // (see CStyleArrayDeclarators).
        if (CStyleArrayDeclarators.parameterHasCStyleBrackets(parameter)) {
            return compactTypeLike.apply(CStyleArrayDeclarators.parameterElementType(parameter))
                + " "
                + parameter.getNameAsString()
                + CStyleArrayDeclarators.parameterBracketsAfterName(parameter);
        }
        List<String> parts = new ArrayList<>();
        String type = compact.apply(parameter.getType());
        if (parameter.isVarArgs()) {
            String varargsAnnotations = compactJoin(parameter.getVarArgsAnnotations());
            type += varargsAnnotations.isEmpty() ? "..." : " " + varargsAnnotations + " ...";
        }
        parts.add(type);
        // An inline block comment JavaParser parked on the name node (Type /* c */ name) goes between the type and the
        // name; parameterNameBlockCommentText reads it without claiming, leaving the single claim to the render paths.
        parts.add(parameterNameBlockCommentText(parameter) + parameter.getNameAsString());
        return String.join(" ", parts);
    }

    private int currentIndentedWidth(String text) {
        return options.indentUnit().length() + text.length();
    }

    private String compactJoin(List<? extends Node> nodes) {
        return nodes.stream().map(compact).reduce((left, right) -> left + ", " + right).orElse("");
    }

    private record ParameterPrefixPart(
        Optional<Range> range,
        int fallbackOrder,
        String flatText,
        Optional<AnnotationExpr> annotation
    ) {}
}
