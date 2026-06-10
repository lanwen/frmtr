package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Prints record declarations after the surrounding body-dispatch decision has already selected the record branch.
 *
 * <p>This helper owns the record-specific header decision tree: whether record components wrap, how source blank lines
 * between components are preserved, when component annotations break onto their own lines, how varargs component tails
 * attach annotations before {@code ...}, where the {@code implements} clause is placed, and whether the member body
 * starts on the same line or a broken line. It intentionally leaves unrelated declaration kinds, callable-signature
 * internals, type/member body sequencing, and raw-source fallback decisions with {@link JavaPrinter},
 * {@link CallableSignaturePrinter}, and {@link MemberBlockPrinter}.
 *
 * <p>Representative fixture pairs live at
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/records/input.java} and
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/records/frmtr.output.java}; smaller record cases
 * also appear at {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/sealed/input.java} and
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/annotation_interface_declaration/input.java}.
 */
final class RecordDeclarationPrinter {
    private final CommentTracker comments;
    private final FormatterOptions options;
    private final Function<NodeWithAnnotations<?>, Doc> annotations;
    private final Function<NodeWithModifiers<?>, String> modifiers;
    private final Function<NodeList<TypeParameter>, Doc> typeParameters;
    private final Function<NodeList<TypeParameter>, String> flatTypeParameters;
    private final Function<Node, String> compact;
    private final Function<List<? extends Node>, String> compactJoin;
    private final Function<List<? extends Node>, String> compactJoinTypeLike;
    private final Function<Node, String> compactTypeLike;
    private final Function<Type, Doc> typeBody;
    private final JavaFormatRule<AnnotationExpr> annotation;
    private final Function<AnnotationExpr, String> annotationFlatText;
    private final ToIntFunction<String> currentIndentedWidth;
    private final Function<RecordDeclaration, Doc> memberBlock;

    RecordDeclarationPrinter(
            CommentTracker comments,
            FormatterOptions options,
            Function<NodeWithAnnotations<?>, Doc> annotations,
            Function<NodeWithModifiers<?>, String> modifiers,
            Function<NodeList<TypeParameter>, Doc> typeParameters,
            Function<NodeList<TypeParameter>, String> flatTypeParameters,
            Function<Node, String> compact,
            Function<List<? extends Node>, String> compactJoin,
            Function<List<? extends Node>, String> compactJoinTypeLike,
            Function<Node, String> compactTypeLike,
            Function<Type, Doc> typeBody,
            JavaFormatRule<AnnotationExpr> annotation,
            Function<AnnotationExpr, String> annotationFlatText,
            ToIntFunction<String> currentIndentedWidth,
            Function<RecordDeclaration, Doc> memberBlock) {
        this.comments = comments;
        this.options = options;
        this.annotations = annotations;
        this.modifiers = modifiers;
        this.typeParameters = typeParameters;
        this.flatTypeParameters = flatTypeParameters;
        this.compact = compact;
        this.compactJoin = compactJoin;
        this.compactJoinTypeLike = compactJoinTypeLike;
        this.compactTypeLike = compactTypeLike;
        this.typeBody = typeBody;
        this.annotation = annotation;
        this.annotationFlatText = annotationFlatText;
        this.currentIndentedWidth = currentIndentedWidth;
        this.memberBlock = memberBlock;
    }

    /**
     * Prints the complete record declaration while delegating member sequencing to the supplied member-block renderer.
     */
    Doc record(RecordDeclaration declaration) {
        List<Doc> header = new ArrayList<>();
        header.add(annotations.apply(declaration));
        String prefix = modifiers.apply(declaration) + "record " + declaration.getNameAsString();
        header.add(Doc.text(prefix));
        if (!declaration.getTypeParameters().isEmpty()) {
            header.add(typeParameters.apply(declaration.getTypeParameters()));
            prefix += flatTypeParameters.apply(declaration.getTypeParameters());
        }
        boolean breakParameters = recordParametersBreak(prefix, declaration);
        header.add(recordParameters(declaration, breakParameters));
        recordImplementsTypes(prefix, declaration, breakParameters).ifPresent(header::add);
        header.add(recordBodyBreak(declaration) ? Doc.HARD_LINE : Doc.text(" "));
        header.add(memberBlock.apply(declaration));
        return Doc.concat(header);
    }

    /**
     * Decides whether the component list must use hard lines after considering type parameters, components, implemented
     * types, and the empty-body suffix.
     */
    private boolean recordParametersBreak(String prefix, RecordDeclaration declaration) {
        if (declaration.getTypeParameters().size() > 2) {
            return true;
        }
        String parameters = declaration.getParameters().stream()
                .map(this::recordComponentFlat)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        String parameterHeader = prefix + "(" + parameters + ")";
        if (declaration.getImplementedTypes().isEmpty()) {
            return currentIndentedWidth.applyAsInt(parameterHeader + " {}") > options.lineWidth();
        }
        String implementedTypes = compactJoinTypeLike.apply(declaration.getImplementedTypes());
        return currentIndentedWidth.applyAsInt(parameterHeader + " implements " + implementedTypes + " {}")
                > options.lineWidth();
    }

    /**
     * Prints record components using either soft grouping or the hard-line shape selected from the complete header.
     */
    private Doc recordParameters(RecordDeclaration declaration, boolean forceBreak) {
        if (declaration.getParameters().isEmpty()) {
            return Doc.text("()");
        }
        List<Doc> parameters = new ArrayList<>();
        for (int i = 0; i < declaration.getParameters().size(); i++) {
            if (i > 0) {
                parameters.add(recordParameterSeparator(
                        declaration.getParameters().get(i - 1),
                        declaration.getParameters().get(i),
                        forceBreak));
            }
            parameters.add(recordComponent(declaration.getParameters().get(i)));
        }
        Doc line = forceBreak ? Doc.HARD_LINE : Doc.SOFT_LINE;
        Doc doc = Doc.concat(
                Doc.text("("),
                Doc.indent(Doc.concat(line, Doc.concat(parameters))),
                line,
                Doc.text(")"));
        return forceBreak ? doc : Doc.group(doc);
    }

    /**
     * Preserves intentional blank lines between neighboring components before applying the current wrapping mode.
     */
    private Doc recordParameterSeparator(Parameter previous, Parameter current, boolean forceBreak) {
        boolean blankLineBetween = previous.getRange()
                .flatMap(previousRange -> current.getRange()
                        .map(currentRange -> firstCurrentComponentLine(current, currentRange.begin.line)
                                > previousRange.end.line + 1))
                .orElse(false);
        if (blankLineBetween) {
            return Doc.concat(Doc.text(","), Doc.HARD_LINE, Doc.HARD_LINE);
        }
        return Doc.concat(Doc.text(","), forceBreak ? Doc.HARD_LINE : Doc.LINE);
    }

    private int firstCurrentComponentLine(Parameter current, int parameterBeginLine) {
        return immediateLeadingLineCommentBeginLine(current, parameterBeginLine)
                .or(() -> immediateLeadingLineCommentBeginLine(current.getType(), parameterBeginLine))
                .orElse(parameterBeginLine);
    }

    private Optional<Integer> immediateLeadingLineCommentBeginLine(Node node, int nextLine) {
        return node.getComment()
                .filter(LineComment.class::isInstance)
                .flatMap(comment -> comment.getRange()
                        .filter(range -> range.end.line + 1 == nextLine)
                        .map(range -> range.begin.line));
    }

    /**
     * Prints one record component, including leading component comments, annotation line-break decisions, and a
     * line-comment attached to the component type.
     */
    private Doc recordComponent(Parameter parameter) {
        List<Doc> parts = new ArrayList<>();
        Doc trailing = recordComponentTrailingLineComment(parameter);
        Doc leading = comments.leading(parameter);
        if (leading != Doc.EMPTY) {
            parts.add(leading);
        }
        boolean breakAnnotations = parameter.getAnnotations().size() > 1
                || parameter.getAnnotations().stream()
                        .anyMatch(annotation -> currentIndentedWidth.applyAsInt(
                                        annotationFlatText.apply(annotation) + " " + recordComponentTail(parameter))
                                > options.lineWidth());
        if (breakAnnotations) {
            parameter.getAnnotations().stream()
                    .map(annotation::format)
                    .map(doc -> Doc.concat(doc, Doc.HARD_LINE))
                    .forEach(parts::add);
        } else if (!parameter.getAnnotations().isEmpty()) {
            parts.add(Doc.text(parameter.getAnnotations().stream()
                    .map(annotationFlatText)
                    .reduce((left, right) -> left + " " + right)
                    .orElse("") + " "));
        }
        Doc typeComment = comments.ownComment(parameter.getType(), comment -> comment instanceof LineComment);
        if (typeComment != Doc.EMPTY) {
            parts.add(typeComment);
            parts.add(Doc.HARD_LINE);
        }
        parts.add(recordComponentTailDoc(parameter));
        if (trailing != Doc.EMPTY) {
            parts.add(Doc.text(" "));
            parts.add(trailing);
        }
        return Doc.concat(parts);
    }

    private Doc recordComponentTrailingLineComment(Parameter parameter) {
        Doc parameterTrailing = comments.trailingLineComment(parameter);
        if (parameterTrailing != Doc.EMPTY) {
            return parameterTrailing;
        }
        return comments.ownComment(parameter.getType(), comment -> comment instanceof LineComment
                && CommentIndex.startsAfterNodeOnSameLine(parameter.getName(), comment));
    }

    /**
     * Builds the flat component text used only for header-width decisions.
     */
    private String recordComponentFlat(Parameter parameter) {
        List<String> parts = new ArrayList<>();
        parameter.getAnnotations().stream().map(compact).forEach(parts::add);
        parts.add(recordComponentTail(parameter));
        return String.join(" ", parts);
    }

    /**
     * Builds the type/name tail, keeping varargs annotations directly before the {@code ...} token.
     */
    private String recordComponentTail(Parameter parameter) {
        String type = compactTypeLike.apply(parameter.getType());
        if (parameter.isVarArgs()) {
            String varargsAnnotations = compactJoin.apply(parameter.getVarArgsAnnotations());
            type += varargsAnnotations.isEmpty() ? "..." : " " + varargsAnnotations + "...";
        }
        return type + " " + parameter.getNameAsString();
    }

    /**
     * Builds the rendered component tail while preserving the same varargs suffix spelling as the flat width estimate.
     *
     * <p>The type body is grouped with the component name so ordinary components stay on one line when they fit, while
     * long generic component types can break at type-argument boundaries before the name is appended.
     */
    private Doc recordComponentTailDoc(Parameter parameter) {
        List<Doc> parts = new ArrayList<>();
        parts.add(typeBody.apply(parameter.getType()));
        if (parameter.isVarArgs()) {
            String varargsAnnotations = compactJoin.apply(parameter.getVarArgsAnnotations());
            parts.add(Doc.text(varargsAnnotations.isEmpty() ? "..." : " " + varargsAnnotations + "..."));
        }
        parts.add(Doc.text(" " + parameter.getNameAsString()));
        return Doc.group(Doc.concat(parts));
    }

    /**
     * Places implemented types on the record header when they fit after the closing component list; otherwise breaks
     * them under an {@code implements} continuation.
     */
    private Optional<Doc> recordImplementsTypes(
            String prefix,
            RecordDeclaration declaration,
            boolean parametersBreak) {
        if (declaration.getImplementedTypes().isEmpty()) {
            return Optional.empty();
        }
        String flat = "implements " + compactJoinTypeLike.apply(declaration.getImplementedTypes());
        String parameterHeader = parametersBreak
                ? ")"
                : prefix + "(" + declaration.getParameters().stream()
                        .map(this::recordComponentFlat)
                        .reduce((left, right) -> left + ", " + right)
                        .orElse("") + ")";
        if (currentIndentedWidth.applyAsInt(parameterHeader + " " + flat + " {}") <= options.lineWidth()) {
            return Optional.of(Doc.text(" " + flat));
        }
        return Optional.of(Doc.concat(
                Doc.text(" implements"),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        Doc.join(Doc.concat(Doc.text(","), Doc.HARD_LINE), declaration.getImplementedTypes().stream()
                                .map(type -> Doc.text(compactTypeLike.apply(type)))
                                .toList())))));
    }

    /**
     * Starts the body on a new line only when the implemented-types continuation already forced the header open.
     */
    private boolean recordBodyBreak(RecordDeclaration declaration) {
        if (declaration.getImplementedTypes().isEmpty()) {
            return false;
        }
        String flat = "implements " + compactJoinTypeLike.apply(declaration.getImplementedTypes());
        return currentIndentedWidth.applyAsInt(") " + flat + " {}") > options.lineWidth();
    }
}
