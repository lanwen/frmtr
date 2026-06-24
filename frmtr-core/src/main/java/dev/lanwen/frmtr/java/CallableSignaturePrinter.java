package dev.lanwen.frmtr.java;

import com.github.javaparser.GeneratedJavaParserConstants;
import com.github.javaparser.JavaToken;
import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.TokenRange;
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
        // A leading line comment on any parameter cannot share a line with parameter text, so it forces the list to
        // break onto one parameter per line; see parameter(Parameter). Without the break a flat group would try to keep
        // the parameters on one line where the rendered `//` would comment out everything that follows.
        boolean lineComment = parameters.stream().anyMatch(this::parameterHasOwnLeadingLineComment);
        Doc body = Doc.concat(
            Doc.text("("),
            Doc.indent(
                Doc.indent(
                    Doc.concat(
                        lineComment ? Doc.HARD_LINE : Doc.SOFT_LINE,
                        Doc.joinComma(parameters.stream().map(this::parameter).toList())
                    )
                )
            ),
            lineComment ? Doc.HARD_LINE : Doc.SOFT_LINE,
            Doc.text(")")
        );
        return lineComment ? body : Doc.group(body);
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
        // A leading line comment on any parameter must render on its own line above that parameter, which is only safe
        // once the whole list breaks one parameter per line. Force the break here even when the flat list would fit so
        // the rendered `//` never lands mid-line and comment out the rest of the signature.
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
        return Doc.concat(
            Doc.text("("),
            Doc.indent(Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(parameters)))),
            Doc.HARD_LINE,
            Doc.text(")")
        );
    }

    boolean parametersFitOnContinuation(CallableDeclaration<?> declaration) {
        // The compact continuation layout renders every parameter on one line from flat text that carries no comment, so
        // a leading line comment would be dropped there. Refuse the continuation when any parameter has one; the caller
        // then falls back to the broken parameter list, which renders the comment on its own line. Block-comment-only
        // parameters keep their existing continuation eligibility because a block comment can stay inline.
        if (parametersHaveLeadingLineComment(declaration)) {
            return false;
        }
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
        return parameter(null, Optional.empty(), parameter);
    }

    /**
     * Prints one ordinary parameter, prepending any {@code //} line comment that leads it in source order.
     *
     * <p>A leading line comment renders on its own line above the parameter, outside the per-parameter group so the
     * trailing hard line never forces a breakable type's own group to break. The {@code parameters(...)} entry points
     * already force the whole list to break when any parameter has one, so the hard line lands the comment on a fresh line
     * at the parameter indent and the parameter text follows on the next line. The comment is recovered by source order
     * ({@link #parameterLeadingLineComments}) so it stays owned by this parameter however whitespace lays the gap out,
     * which is what keeps the same comment claimed once across the default layout and its collapsed/expanded shapes.
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

    /**
     * Claims and renders the {@code //} line comments that lead {@code parameter} in source order, recovering them
     * however whitespace re-bucketed them across the parameter's neighbors.
     *
     * <p>JavaParser attaches a line comment written above a parameter to that parameter's own trivia at the default
     * layout, but a whitespace collapse/expand re-buckets the same comment onto the <em>previous</em> parameter (as its
     * trailing own comment) even though the AST is unchanged. Selecting by JavaParser's attachment would therefore drop
     * the comment under perturbation. This query is source-order instead: it asks {@link CommentTracker#gapLineCommentsBefore}
     * for the line comments parked on the previous parameter (or receiver) that lie strictly between it and
     * {@code parameter}, and adds {@code parameter}'s own leading line comment for the default layout (which the gap query
     * intentionally excludes as {@code body}'s own trivia). Both paths claim by identity, so a comment is rendered exactly
     * once; in the default layout the own path wins and the gap path returns empty, while under perturbation the gap path
     * recovers the comment from the previous parameter and the own path is empty. The first ordinary parameter (no
     * previous node) uses only the own path, which the default layout always populates.
     */
    private List<Doc> parameterLeadingLineComments(
            CallableDeclaration<?> declaration,
            Optional<Node> previous,
            Parameter parameter
    ) {
        List<Doc> rendered = new ArrayList<>();
        if (declaration != null && previous.isPresent()) {
            // Scan both the previous parameter and the declaration itself: a whitespace collapse parks the comment as the
            // previous parameter's trailing own comment, while a whitespace expand parks it as the declaration's orphan,
            // so both buckets are needed to recover it regardless of shape. gapLineCommentsBefore selects only line
            // comments that lie strictly between the previous parameter and this one and claims each by identity.
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
     * used to force the parameter list to break. This is a pure source read over the declaration's contained comments and
     * the parameter boundaries (the same source-order ownership {@link #parameterLeadingLineComments} renders from) and
     * claims nothing, so it can be asked before the list layout is chosen without disturbing comment accounting. It is
     * shape-independent: a comment the layout pushed onto the previous parameter still counts because it still lies in the
     * gap before the parameter it leads.
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
        parts.sort(
            Comparator.comparingInt((ParameterPrefixPart part) -> rangeBeginLine(part.range()))
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
        List<String> parts = new ArrayList<>();
        String type = compact.apply(parameter.getType());
        if (parameter.isVarArgs()) {
            String varargsAnnotations = compactJoin(parameter.getVarArgsAnnotations());
            type += varargsAnnotations.isEmpty() ? "..." : " " + varargsAnnotations + " ...";
        }
        parts.add(type);
        parts.add(parameter.getNameAsString());
        return String.join(" ", parts);
    }

    private String parameterTrailingBlockCommentText(Parameter parameter) {
        // A trailing block comment is the last parameter's only when it sits inside the parameter list, i.e. before the
        // closing ")". A collapsed layout can slide the next member's leading block comment up onto the last parameter's
        // line, where the same-line recovery would otherwise reach across ")" (and even the method body) and claim a
        // comment that belongs to the following member. Requiring the comment to precede ")" keeps that comment with its
        // real owner. At the default layout the comment is on its own line, so the recovery already matches nothing.
        if (!trailingBlockCommentPrecedesCloseParen(parameter)) {
            return "";
        }
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
     * Reports whether a block comment trailing {@code parameter} lies in the source-order gap between the last
     * parameter's end and the parameter list's closing {@code ")"}, which is the only span from which it can genuinely
     * belong to the last parameter.
     *
     * <p>Selection is by source order, not by same-line: the comment must begin after the parameter ends and before the
     * close paren. A same-line {@code param /* note *​/)} block comment satisfies both bounds, and so does an expanded
     * layout that pushes the comment onto its own line while keeping it before {@code ")"} — the body block's own
     * comment that {@code expand} slides off the last parameter's line. Bounding strictly at {@code ")"} (never the body
     * brace) preserves PR #20's narrowing: under {@code collapse} a following member's leading block comment can slide
     * up onto the last parameter's line, but it begins after {@code ")"}, so it is rejected and stays with its real
     * owner.
     *
     * <p>The closing paren is the first {@code RPAREN} in the callable's token range that begins at or after the last
     * parameter ends; any {@code ")"} from an annotation or type on the parameter itself ends before the parameter does,
     * so it is skipped. When the callable, the parameter, or the close paren has no source range, the gate stays closed
     * and the recovery is suppressed rather than guessed.
     */
    private boolean trailingBlockCommentPrecedesCloseParen(Parameter parameter) {
        Optional<Range> closeParenRange = closeParenForParameterList(parameter);
        if (closeParenRange.isEmpty()) {
            return false;
        }
        Range closeParen = closeParenRange.orElseThrow();
        return parameter.getParentNode()
                .stream()
                .flatMap(parent -> parent.getAllContainedComments().stream())
                .filter(BlockComment.class::isInstance)
                .anyMatch(comment -> precedesCloseParen(parameter, comment, closeParen));
    }

    /**
     * Locates the parameter list's closing {@code ")"} for the last parameter, returning empty when {@code parameter} is
     * not the last ordinary parameter or any required source range is missing.
     */
    private Optional<Range> closeParenForParameterList(Parameter parameter) {
        if (!lastCallableParameter(parameter)) {
            return Optional.empty();
        }
        Optional<Range> parameterRange = parameter.getRange();
        if (parameterRange.isEmpty()) {
            return Optional.empty();
        }
        return parameter.getParentNode()
                .flatMap(Node::getTokenRange)
                .flatMap(tokenRange -> closeParenAfter(tokenRange, parameterRange.orElse(null)));
    }

    /**
     * Reports whether a block {@code comment} lies in the source-order window {@code (parameter end, closeParen)}: it
     * begins after the parameter's last token and before the parameter list's closing {@code ")"}. The lower bound is
     * source-order rather than same-line so a layout perturbation that moves the comment off the parameter's line cannot
     * defeat ownership; the upper bound stays at {@code ")"} so the recovery never reaches past the parameter list.
     */
    private boolean precedesCloseParen(Parameter parameter, Comment comment, Range closeParen) {
        return CommentIndex.startsAfterEndOf(parameter, comment) && CommentIndex.startsBefore(comment, closeParen.begin);
    }

    private Optional<Range> closeParenAfter(TokenRange tokenRange, Range parameterRange) {
        if (parameterRange == null) {
            return Optional.empty();
        }
        for (JavaToken token : tokenRange) {
            if (token.getKind() != GeneratedJavaParserConstants.RPAREN) {
                continue;
            }
            // The parameter list closer is the first ")" that begins strictly after the last parameter ends; any ")"
            // from a parameter annotation or type argument begins within the parameter's own span and is skipped.
            Optional<Range> range = token.getRange().filter(paren -> beginsAfter(paren.begin, parameterRange.end));
            if (range.isPresent()) {
                return range;
            }
        }
        return Optional.empty();
    }

    private static boolean beginsAfter(Position candidateBegin, Position parameterEnd) {
        if (candidateBegin.line != parameterEnd.line) {
            return candidateBegin.line > parameterEnd.line;
        }
        return candidateBegin.column > parameterEnd.column;
    }

    /**
     * Finds block comments that JavaParser leaves inside the callable rather than attaching to the last parameter node.
     *
     * <p>Selection is the same source-order window the gate ({@link #trailingBlockCommentPrecedesCloseParen}) uses: the
     * comment must begin after the last parameter ends and before the parameter list's closing {@code ")"}. Bounding by
     * source order rather than same-line keeps the comment owned by the parameter even when an expanded layout pushes it
     * onto its own line (the body block's own comment that {@code expand} slides off the parameter's line), while the
     * {@code ")"} upper bound keeps the recovery from reaching past the parameter list into a following member.
     */
    Doc parameterTrailingBlockComment(Parameter parameter) {
        Optional<Range> closeParenRange = closeParenForParameterList(parameter);
        if (closeParenRange.isEmpty()) {
            return Doc.EMPTY;
        }
        Range closeParen = closeParenRange.orElseThrow();
        return parameter.getParentNode()
                .stream()
                .flatMap(parent -> parent.getAllContainedComments().stream())
                .filter(BlockComment.class::isInstance)
                .filter(comment -> precedesCloseParen(parameter, comment, closeParen))
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
