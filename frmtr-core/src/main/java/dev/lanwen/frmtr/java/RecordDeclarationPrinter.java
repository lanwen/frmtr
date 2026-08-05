package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.comments.Comment;
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
 * between components are normalized, when component annotations break onto their own lines, how varargs component
 * tails attach annotations before {@code ...}, where the {@code implements} clause is placed, and whether the member
 * body starts on the same line or a broken line. It intentionally leaves unrelated declaration kinds,
 * callable-signature internals, type/member body sequencing, and raw-source fallback decisions with {@link JavaPrinter},
 * {@link CallableSignaturePrinter}, and {@link MemberBlockPrinter}.
 *
 * <p>Representative fixture pairs live at
 * {@code frmtr-core/src/test/resources/format/record-component-spacing/input.java} and
 * {@code frmtr-core/src/test/resources/format/record-component-spacing/frmtr-default.output.java}; smaller record cases
 * also appear at {@code frmtr-core/src/test/resources/format/interface-and-sealed-type-headers/input.java} and
 * {@code frmtr-core/src/test/resources/format/annotation-interface-declaration/input.java}.
 */
final class RecordDeclarationPrinter {

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    private final SourceShapePolicy sourceShapePolicy;

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

    private final ToIntFunction<String> currentIndentedWidth;

    private final Function<RecordDeclaration, Doc> memberBlock;

    RecordDeclarationPrinter(
            CommentTracker comments,
            JavaCommentPlacementPolicy commentPlacement,
            SourceShapePolicy sourceShapePolicy,
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
            ToIntFunction<String> currentIndentedWidth,
            Function<RecordDeclaration, Doc> memberBlock
    ) {
        this.comments = comments;
        this.commentPlacement = commentPlacement;
        this.sourceShapePolicy = sourceShapePolicy;
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
        String parameters = declaration.getParameters()
                .stream()
                .map(this::recordComponentFlat)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        String parameterHeader = prefix + "(" + parameters + ")";
        if (declaration.getImplementedTypes().isEmpty()) {
            return recordHeaderWidth(declaration, parameterHeader + " {}") > options.lineWidth();
        }
        String implementedTypes = compactJoinTypeLike.apply(declaration.getImplementedTypes());
        return recordHeaderWidth(
            declaration,
            parameterHeader + " implements " + implementedTypes + " {}"
        ) > options.lineWidth();
    }

    /**
     * Prints record components using either soft grouping or the hard-line shape selected from the complete header.
     */
    private Doc recordParameters(RecordDeclaration declaration, boolean forceBreak) {
        if (declaration.getParameters().isEmpty()) {
            return Doc.text("()");
        }
        List<RecordComponentLayout> layouts = recordComponentLayouts(declaration, forceBreak);
        boolean hasGapLineComment = layouts.stream().anyMatch(RecordComponentLayout::hasGapLineComment);
        List<Doc> parameters = layouts.stream()
                .flatMap(layout -> layout.docs().stream())
                .toList();
        // A component's own trailing line comment does not force the hard-line shape here: it emits BreakParent, which
        // poisons this group's fit so it breaks while its soft lines render as the same newlines the hard-line shape
        // produced. Gap line comments still force hard lines directly because they print on their own interleaved lines.
        Doc line = forceBreak || hasGapLineComment ? Doc.HARD_LINE : Doc.SOFT_LINE;
        Doc doc = Doc.concat(
            Doc.text("("),
            Doc.indent(Doc.concat(line, Doc.concat(parameters))),
            line,
            Doc.text(")")
        );
        return forceBreak ? doc : Doc.group(doc);
    }

    /**
     * Precomputes each component together with the separator and gap comments that belong after it.
     */
    private List<RecordComponentLayout> recordComponentLayouts(
            RecordDeclaration declaration,
            boolean forceBreak
    ) {
        List<RecordComponentLayout> layouts = new ArrayList<>();
        for (int i = 0; i < declaration.getParameters().size(); i++) {
            Parameter parameter = declaration.getParameters().get(i);
            boolean hasNext = i + 1 < declaration.getParameters().size();
            Parameter next = hasNext ? declaration.getParameters().get(i + 1) : null;
            Doc trailing = recordComponentTrailingLineComment(declaration, parameter, next);
            Optional<Doc> separator = Optional.empty();
            List<Doc> gapComments = List.of();
            if (hasNext) {
                gapComments = recordComponentGapComments(declaration, parameter, next);
                boolean componentHasGapLineComment = !gapComments.isEmpty();
                separator = Optional.of(
                    recordParameterSeparator(
                        forceBreak || componentHasGapLineComment,
                        !componentHasGapLineComment
                            && next.getComment().isEmpty()
                            && recordComponentsHaveBlankLine(parameter, next)
                    )
                );
            }
            layouts.add(new RecordComponentLayout(recordComponent(parameter, trailing), separator, gapComments));
        }
        return layouts;
    }

    /**
     * Separates neighboring record components without adding blank lines inside the record header.
     *
     * <p>The comma is always emitted: a preceding component's trailing line comment defers to a line suffix, so the
     * comma prints before that comment flushes and can never be commented out.
     */
    private Doc recordParameterSeparator(boolean forceBreak, boolean sourceBlankLine) {
        Doc line = sourceBlankLine ? Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE) : Doc.HARD_LINE;
        return Doc.concat(Doc.text(","), sourceBlankLine ? line : forceBreak ? Doc.HARD_LINE : Doc.LINE);
    }

    private List<Doc> recordComponentGapComments(
            RecordDeclaration declaration,
            Parameter previous,
            Parameter next
    ) {
        return commentPlacement.standaloneLineCommentsBetween(declaration, previous, next)
                .stream()
                .map(comments::comment)
                .filter(comment -> comment != Doc.EMPTY)
                .toList();
    }

    private boolean recordComponentsHaveBlankLine(Parameter previous, Parameter next) {
        return sourceShapePolicy.hadBlankLineBetween(previous, next);
    }

    /**
     * Prints one record component, including leading component comments, annotation line-break decisions, and a
     * line-comment attached to the component type.
     */
    private Doc recordComponent(Parameter parameter, Doc trailing) {
        List<Doc> parts = new ArrayList<>();
        Doc leading = comments.leading(parameter);
        if (leading != Doc.EMPTY) {
            parts.add(leading);
        }
        if (!parameter.getAnnotations().isEmpty()) {
            // Annotations whose compact form overflows the line break internally; the inline context handles them.
            // All others go through a fill so the renderer places as many on each line as fit greedily.
            if (recordComponentHasWidthDrivenMultilineAnnotation(parameter)) {
                parts.add(recordComponentAnnotationPrefix(parameter));
            } else {
                parts.add(recordComponentAnnotationFillPrefix(parameter));
            }
        }
        Doc typeComment = comments.ownComment(parameter.getType(), comment -> comment instanceof LineComment);
        if (typeComment != Doc.EMPTY) {
            parts.add(typeComment);
            parts.add(Doc.HARD_LINE);
        }
        Doc orphanLeading = recordComponentOrphanLeadingLineComments(parameter);
        if (orphanLeading != Doc.EMPTY) {
            parts.add(orphanLeading);
        }
        parts.add(recordComponentTailDoc(parameter));
        Doc trailingBlock = recordComponentTrailingBlockComment(parameter);
        if (trailingBlock != Doc.EMPTY) {
            parts.add(Doc.text(" "));
            parts.add(trailingBlock);
        }
        if (trailing != Doc.EMPTY) {
            // A trailing line comment defers to a line suffix so the separator's unconditional comma prints first and is
            // never commented out; BreakParent forces the component list open (it poisons the enclosing Doc.group(...)
            // recordParameters wraps the soft-line envelope in) so the suffix flushes after the comma. Block comments
            // above stay inline because they sit before the comma.
            parts.add(Doc.BREAK_PARENT);
            parts.add(Doc.lineSuffix(Doc.concat(Doc.text(" "), trailing)));
        }
        return Doc.concat(parts);
    }

    /**
     * Recovers a leading line comment that JavaParser parked as the component's orphan rather than as the type's own
     * comment.
     *
     * <p>A {@code // comment} between a component's last annotation and its type (or type and name) is the type's own
     * trivia at {@code @default} (the {@code typeComment} slot above), so this bucket is empty there. A whitespace
     * expansion re-buckets it onto the {@link Parameter}'s orphans, where without this recovery it is dropped; it is kept
     * directly before the component tail, matching the {@code @default} render.
     */
    private Doc recordComponentOrphanLeadingLineComments(Parameter parameter) {
        return Doc.concat(
            commentPlacement.orphanComments(parameter)
                    .stream()
                    .filter(JavaCommentTrivia::isLine)
                    .filter(comment -> comment.startsBefore(parameter.getName()))
                    .map(comments::comment)
                    .filter(doc -> doc != Doc.EMPTY)
                    .map(doc -> Doc.concat(doc, Doc.HARD_LINE))
                    .toList()
        );
    }

    private Doc recordComponentTrailingBlockComment(Parameter parameter) {
        return commentPlacement.trailingBlockCommentsAfterNode(parameter.getName())
                .stream()
                .filter(comment -> commentEndsBeforeNextRecordComponent(parameter, comment.comment()))
                .findFirst()
                .map(comments::comment)
                .orElse(Doc.EMPTY);
    }

    private boolean commentEndsBeforeNextRecordComponent(Parameter parameter, Comment comment) {
        return parameter.getParentNode()
                .filter(RecordDeclaration.class::isInstance)
                .map(RecordDeclaration.class::cast)
                .flatMap(record -> {
                    int index = record.getParameters().indexOf(parameter);
                    return index >= 0 && index + 1 < record.getParameters().size()
                        ? Optional.of(record.getParameters().get(index + 1))
                        : Optional.<Parameter>empty();
                })
                .map(next -> comment.getRange()
                            .flatMap(commentRange -> next.getRange().map(
                                    nextRange -> CommentIndex.startsBefore(commentRange, nextRange)
                            ))
                            .orElse(false)
                )
                .orElse(true);
    }

    /**
     * Greedily places component annotations using a {@link Doc#fill}: the renderer breaks at each {@link Doc#LINE}
     * separator only when the next annotation would no longer fit on the current line.
     *
     * <p>When the last annotation carries a same-line trailing line comment the type/name must start on the next
     * physical line; {@link Doc#HARD_LINE} is used instead of a space so the tail stays outside the comment body.
     */
    private Doc recordComponentAnnotationFillPrefix(Parameter parameter) {
        LayoutContext context = LayoutContext.root().withLeftEdgePrefix(options.indentUnit());
        NodeList<AnnotationExpr> annotations = parameter.getAnnotations();
        List<Doc> fillParts = new ArrayList<>();
        for (AnnotationExpr ann : annotations) {
            if (!fillParts.isEmpty()) {
                fillParts.add(Doc.LINE);
            }
            fillParts.add(annotation.format(ann, context));
        }
        AnnotationExpr last = annotations.get(annotations.size() - 1);
        boolean lastHasInlineComment = commentPlacement.ownComment(
            last,
            comment -> comment.isLine()
                    && comment.startsOnBeginLine(last.getName())
                    && comment.startsAfterNodeOnSameLine(last.getName())
        ).isPresent();
        return Doc.concat(Doc.fill(fillParts), lastHasInlineComment ? Doc.HARD_LINE : Doc.text(" "));
    }

    private Doc recordComponentAnnotationPrefix(Parameter parameter) {
        List<AnnotationExpr> annotations = parameter.getAnnotations();
        List<Doc> docs = new ArrayList<>();
        for (int index = 0; index < annotations.size(); index++) {
            docs.add(annotation.format(
                annotations.get(index),
                recordComponentAnnotationLayout(parameter, annotations, index)
            ));
        }
        return Doc.concat(
            Doc.join(
                Doc.text(" "),
                docs
            ),
            Doc.text(" ")
        );
    }

    /**
     * Builds the {@link LayoutContext} that tells a breakable inline record-component annotation where it renders, so its
     * flat/structured choice is width-driven at the real column rather than reading the author's source line breaks.
     *
     * <p>A broken record header indents its components one unit past the member baseline {@link #currentIndentedWidth}
     * assumes, so {@link LayoutContext#leftEdgePrefix()} contributes that extra unit plus every earlier annotation
     * (joined by the same {@code " "} the render uses). The trailing content is the remaining annotations then the
     * {@code " Type name"} tail the component emits after the prefix on the same line, so the annotation breaks only when
     * keeping it flat would push that tail past the width.
     */
    private LayoutContext recordComponentAnnotationLayout(
            Parameter parameter,
            List<AnnotationExpr> annotations,
            int index
    ) {
        StringBuilder leftEdge = new StringBuilder(options.indentUnit());
        for (int before = 0; before < index; before++) {
            leftEdge.append(compact.apply(annotations.get(before))).append(' ');
        }
        StringBuilder trailing = new StringBuilder();
        for (int after = index + 1; after < annotations.size(); after++) {
            trailing.append(' ').append(compact.apply(annotations.get(after)));
        }
        trailing.append(' ').append(recordComponentTail(parameter));
        return LayoutContext.root()
                .withLeftEdgePrefix(leftEdge.toString())
                .withTrailingContent(trailing.toString());
    }

    private boolean recordComponentHasWidthDrivenMultilineAnnotation(Parameter parameter) {
        return parameter.getAnnotations()
                .stream()
                .map(compact)
                .anyMatch(annotation -> currentIndentedWidth.applyAsInt(annotation) > options.lineWidth());
    }

    private Doc recordComponentTrailingLineComment(
            RecordDeclaration declaration,
            Parameter parameter,
            Parameter next
    ) {
        Doc parameterTrailing = comments.trailingLineComment(parameter);
        if (parameterTrailing != Doc.EMPTY) {
            return parameterTrailing;
        }
        Doc typeTrailing = comments.ownComment(parameter.getType(), comment -> comment instanceof LineComment
                && CommentIndex.startsAfterNodeOnSameLine(parameter.getName(), comment)
        );
        if (typeTrailing != Doc.EMPTY) {
            return typeTrailing;
        }
        return recordComponentGapTrailingLineComment(declaration, parameter, next);
    }

    /**
     * Recovers a gap line comment that ends up trailing {@code parameter} on its own source line.
     *
     * <p>{@link #recordComponentGapComments} renders gap comments as standalone lines but leaves out those on
     * {@code parameter}'s end line (they trail the component inline). At {@code @default} a gap-leading comment sits on
     * its own line, so this is a no-op; a whitespace collapse moves it up onto {@code parameter}'s end line, where
     * JavaParser attaches it to the component name and neither the parameter own-comment nor the standalone gap path
     * claims it — so it is kept here instead of dropped.
     */
    private Doc recordComponentGapTrailingLineComment(
            RecordDeclaration declaration,
            Parameter parameter,
            Parameter next
    ) {
        if (next == null) {
            return Doc.EMPTY;
        }
        return Doc.concat(
            commentPlacement.lineCommentsBetween(declaration, parameter, next)
                    .stream()
                    .filter(comment -> comment.startsOnEndLine(parameter))
                    .map(comments::comment)
                    .filter(comment -> comment != Doc.EMPTY)
                    .toList()
        );
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
            boolean parametersBreak
    ) {
        if (declaration.getImplementedTypes().isEmpty()) {
            return Optional.empty();
        }
        String flat = "implements " + compactJoinTypeLike.apply(declaration.getImplementedTypes());
        String parameterHeader = parametersBreak
            ? ")"
            : prefix + "(" + declaration.getParameters()
                    .stream()
                    .map(this::recordComponentFlat)
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("") + ")";
        if (recordHeaderWidth(declaration, parameterHeader + " " + flat + " {}") <= options.lineWidth()) {
            return Optional.of(Doc.text(" " + flat));
        }
        return Optional.of(
            Doc.concat(
                Doc.text(" implements"),
                Doc.indent(
                    Doc.concat(
                        Doc.HARD_LINE,
                        Doc.join(
                            Doc.concat(Doc.text(","), Doc.HARD_LINE),
                            declaration.getImplementedTypes()
                                    .stream()
                                    .map(type -> Doc.text(compactTypeLike.apply(type)))
                                    .toList()
                        )
                    )
                )
            )
        );
    }

    /**
     * Starts the body on a new line only when the implemented-types continuation already forced the header open.
     */
    private boolean recordBodyBreak(RecordDeclaration declaration) {
        if (declaration.getImplementedTypes().isEmpty()) {
            return false;
        }
        String flat = "implements " + compactJoinTypeLike.apply(declaration.getImplementedTypes());
        return recordHeaderWidth(declaration, ") " + flat + " {}") > options.lineWidth();
    }

    /**
     * Computes record header width at the declaration's actual nesting depth, preserving the first-member baseline
     * used by other declaration printers.
     */
    private int recordHeaderWidth(RecordDeclaration declaration, String text) {
        return currentIndentedWidth.applyAsInt(text) + extraRecordHeaderIndentWidth(declaration);
    }

    private int extraRecordHeaderIndentWidth(RecordDeclaration declaration) {
        int enclosingTypes = 0;
        Optional<Node> parent = declaration.getParentNode();
        while (parent.isPresent()) {
            Node node = parent.orElseThrow();
            if (node instanceof TypeDeclaration<?>) {
                enclosingTypes++;
            }
            parent = node.getParentNode();
        }
        return Math.max(0, enclosingTypes - 1) * options.indentUnit().length();
    }

    private record RecordComponentLayout(
        Doc component,
        Optional<Doc> separator,
        List<Doc> gapComments
    ) {
        boolean hasGapLineComment() {
            return !gapComments.isEmpty();
        }

        List<Doc> docs() {
            List<Doc> docs = new ArrayList<>();
            docs.add(component);
            separator.ifPresent(docs::add);
            if (!gapComments.isEmpty()) {
                // The two hard lines are not a blank-line idiom (unlike MemberBlockPrinter, which doubles HARD_LINE for a
                // blank line): the join only breaks between multiple gap comments, and the trailing HARD_LINE terminates
                // the block so the next component starts fresh. The separator already emitted the opening comma+HARD_LINE,
                // so no two hard lines are ever adjacent.
                docs.add(Doc.join(Doc.HARD_LINE, gapComments));
                docs.add(Doc.HARD_LINE);
            }
            return docs;
        }
    }
}
