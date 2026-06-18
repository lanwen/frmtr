package dev.lanwen.frmtr.java;

import com.github.javaparser.Range;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.ReceiverParameter;
import com.github.javaparser.ast.comments.BlockComment;
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

    private final Function<Node, String> compact;

    private final Function<Node, String> compactTypeLike;

    private final Function<Type, Doc> typeBody;

    private final JavaFormatRule<AnnotationExpr> annotation;

    private final Function<AnnotationExpr, String> annotationFlatText;

    private final Function<Modifier, String> modifier;

    private final Predicate<Type> typeCanBreak;

    private final Function<Node, Doc> unattachedTrailingBlockComment;

    private final Function<Doc, String> commentText;

    CallableSignaturePrinter(
            CommentTracker comments,
            RawSource rawSource,
            FormatterOptions options,
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
        this.compact = compact;
        this.compactTypeLike = compactTypeLike;
        this.typeBody = typeBody;
        this.annotation = annotation;
        this.annotationFlatText = annotationFlatText;
        this.modifier = modifier;
        this.typeCanBreak = typeCanBreak;
        this.unattachedTrailingBlockComment = unattachedTrailingBlockComment;
        this.commentText = commentText;
    }

    /**
     * Prints a simple parameter list for syntax nodes that expose only ordinary parameters.
     *
     * <p>This keeps the compact group shape used by declaration-like nodes that do not have receiver parameters or a
     * caller-provided force-break decision.
     */
    Doc parameters(NodeList<Parameter> parameters) {
        return Doc.group(
            Doc.concat(
                Doc.text("("),
                Doc.indent(
                    Doc.indent(
                        Doc.concat(
                            Doc.SOFT_LINE,
                            Doc.joinComma(parameters.stream().map(this::parameter).toList())
                        )
                    )
                ),
                Doc.SOFT_LINE,
                Doc.text(")")
            )
        );
    }

    /**
     * Prints a callable parameter list using the default soft-line grouping policy.
     */
    Doc parameters(CallableDeclaration<?> declaration) {
        return parameters(declaration, false);
    }

    /**
     * Prints callable parameters, including an optional receiver parameter before ordinary parameters.
     *
     * <p>The force-break branch is chosen by the caller after it knows the full signature prefix and suffix, because
     * parameters may need to break to leave room for throws clauses or a following block.
     */
    Doc parameters(CallableDeclaration<?> declaration, boolean forceBreak) {
        List<Doc> parameters = new ArrayList<>();
        declaration.getReceiverParameter().map(this::receiverParameter).ifPresent(parameters::add);
        declaration.getParameters().stream().map(this::parameter).forEach(parameters::add);
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
        return Doc.concat(
            Doc.text("("),
            Doc.indent(Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(parameters)))),
            Doc.HARD_LINE,
            Doc.text(")")
        );
    }

    boolean parametersFitOnContinuation(CallableDeclaration<?> declaration) {
        String continuationLine = options.indentUnit().repeat(2) + callableParameterText(declaration);
        return currentIndentedWidth(continuationLine) <= options.lineWidth();
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
     */
    boolean parametersBreak(String prefix, CallableDeclaration<?> declaration, String suffix) {
        String parameters = callableParameterText(declaration);
        return currentIndentedWidth(prefix + "(" + parameters + ")" + suffix) > options.lineWidth();
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
     * Prints ordinary parameters, preserving the source-layout cases that affect callable signatures: breakable generic
     * types, varargs annotations before {@code ...}, leading own block comments, and trailing block comments after the
     * last callable parameter.
     */
    Doc parameter(Parameter parameter) {
        if (!parameter.isVarArgs() && typeCanBreak.test(parameter.getType())) {
            List<Doc> parts = new ArrayList<>();
            parameterLeadingBlockComment(parameter).ifPresent(parts::add);
            parts.add(parameterModifierAnnotationPrefix(parameter));
            parts.add(typeBody.apply(parameter.getType()));
            parts.add(Doc.text(" " + parameter.getNameAsString() + parameterTrailingBlockCommentText(parameter)));
            return Doc.group(
                Doc.concat(
                    parts
                )
            );
        }
        List<Doc> prefix = new ArrayList<>();
        parameterLeadingBlockComment(parameter).ifPresent(prefix::add);
        prefix.add(parameterModifierAnnotationPrefix(parameter));
        prefix.add(Doc.text(parameterTypeAndNameText(parameter) + parameterTrailingBlockCommentText(parameter)));
        return Doc.concat(prefix);
    }

    private Optional<Doc> parameterLeadingBlockComment(Parameter parameter) {
        Doc leadingBlockComment = comments.ownComment(parameter, BlockComment.class::isInstance);
        if (leadingBlockComment == Doc.EMPTY) {
            return Optional.empty();
        }
        return Optional.of(Doc.text(commentText.apply(leadingBlockComment) + " "));
    }

    private Doc parameterModifierAnnotationPrefix(Parameter parameter) {
        List<ParameterPrefixPart> parts = parameterPrefixParts(parameter);
        if (parts.isEmpty()) {
            return Doc.EMPTY;
        }
        if (!parameterAnnotationSourceBreaks(parameter, parts)) {
            return Doc.text(parameterPrefixText(parts));
        }
        return Doc.concat(
            Doc.join(
                Doc.text(" "),
                parts.stream().map(this::parameterPrefixPartDoc).toList()
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
        parts.sort(Comparator
                .comparingInt((ParameterPrefixPart part) -> rangeBeginLine(part.range()))
                .thenComparingInt(part -> rangeBeginColumn(part.range()))
                .thenComparingInt(ParameterPrefixPart::fallbackOrder)
        );
        return parts;
    }

    private Doc parameterPrefixPartDoc(ParameterPrefixPart part) {
        return part.annotation().map(annotation::format).orElseGet(() -> Doc.text(part.flatText()));
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

    private boolean parameterAnnotationSourceBreaks(Parameter parameter, List<ParameterPrefixPart> parts) {
        return parameter.getAnnotations().stream().flatMap(annotation -> annotation.getRange().stream()).anyMatch(
            range -> range.begin.line < range.end.line
        ) && currentIndentedWidth(parameterFlat(parameter, parts)) > options.lineWidth();
    }

    private String parameterFlat(Parameter parameter) {
        return parameterFlat(parameter, parameterPrefixParts(parameter));
    }

    private String parameterFlat(Parameter parameter, List<ParameterPrefixPart> parts) {
        return parameterPrefixText(parts) + parameterTypeAndNameText(parameter);
    }

    private String parameterTypeAndNameText(Parameter parameter) {
        List<String> parts = new ArrayList<>();
        String type = compact.apply(parameter.getType());
        if (parameter.isVarArgs()) {
            String varargsAnnotations = compactJoin(parameter.getVarArgsAnnotations());
            type += varargsAnnotations.isEmpty() ? "..." : " " + varargsAnnotations + "...";
        }
        parts.add(type);
        parts.add(parameter.getNameAsString());
        return String.join(" ", parts);
    }

    private String parameterTrailingBlockCommentText(Parameter parameter) {
        Doc trailingBlockComment = unattachedTrailingBlockComment.apply(parameter);
        if (trailingBlockComment == Doc.EMPTY) {
            trailingBlockComment = parameterTrailingBlockComment(parameter);
        }
        if (trailingBlockComment != Doc.EMPTY) {
            return " " + commentText.apply(trailingBlockComment);
        }
        return "";
    }

    /**
     * Finds block comments that JavaParser leaves inside the callable rather than attaching to the last parameter node.
     */
    Doc parameterTrailingBlockComment(Parameter parameter) {
        if (!lastCallableParameter(parameter)) {
            return Doc.EMPTY;
        }
        return parameter.getParentNode()
                .stream()
                .flatMap(parent -> parent.getAllContainedComments().stream())
                .filter(BlockComment.class::isInstance)
                .filter(comment -> CommentIndex.startsAfterNodeOnSameLine(parameter, comment))
                .findFirst()
                .map(comments::comment)
                .orElse(Doc.EMPTY);
    }

    /**
     * Checks whether trailing callable comments can be associated with this parameter.
     *
     * <p>Only the last ordinary parameter can own a same-line block comment that JavaParser left at callable scope; for
     * earlier parameters the comment would belong to a comma-separated gap instead.
     */
    boolean lastCallableParameter(Parameter parameter) {
        return parameter.getParentNode()
                .filter(CallableDeclaration.class::isInstance)
                .map(CallableDeclaration.class::cast)
                .map(declaration -> !declaration.getParameters().isEmpty()
                        && declaration.getParameters().get(declaration.getParameters().size() - 1) == parameter
                )
                .orElse(false);
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
