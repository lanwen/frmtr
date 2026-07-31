package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
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
     * Prints the complete record declaration. More than two type parameters force the broken header outright (a
     * readability rule, not a fit test); otherwise the flat and fully broken headers are ranked at the true rendered
     * column, mirroring {@code ClassOrInterfaceDeclarationPrinter}'s header cascade.
     */
    Doc record(RecordDeclaration declaration) {
        Doc prefix = recordPrefix(declaration);
        Doc flatHeader = flatRecordHeader(declaration, prefix);
        Doc brokenHeader = brokenRecordHeader(declaration, prefix);
        // The member block is built last, after both header candidates claim their parameter-list and implements-clause
        // comments, so first-claim-wins comment ownership matches the header's own source order instead of letting the
        // body's orphan scan see a header comment first.
        Doc memberBlockDoc = memberBlock.apply(declaration);
        Doc flat = Doc.concat(flatHeader, Doc.text(" "), memberBlockDoc);
        Doc broken = Doc.concat(brokenHeader, Doc.text(" "), memberBlockDoc);
        if (declaration.getTypeParameters().size() > 2) {
            return broken;
        }
        return Doc.bestFittingFirstLine(List.of(flat, broken), new int[] {1, 0}, "recordHeader");
    }

    /**
     * Builds the header prefix shared by the flat and broken candidates: annotations, modifiers, keyword, name, and
     * type parameters. Built once so both ranked candidates render the identical Doc instance.
     */
    private Doc recordPrefix(RecordDeclaration declaration) {
        List<Doc> prefix = new ArrayList<>();
        prefix.add(annotations.apply(declaration));
        prefix.add(Doc.text(modifiers.apply(declaration) + "record " + declaration.getNameAsString()));
        if (!declaration.getTypeParameters().isEmpty()) {
            prefix.add(typeParameters.apply(declaration.getTypeParameters()));
        }
        return Doc.concat(prefix);
    }

    /**
     * Prints the inline header, without the trailing body: components stay in a self-wrapping group and the
     * {@code implements} clause attaches after the closing paren. Ranked against {@link #brokenRecordHeader} at the
     * true rendered column.
     */
    private Doc flatRecordHeader(RecordDeclaration declaration, Doc prefix) {
        List<Doc> header = new ArrayList<>();
        header.add(prefix);
        header.add(recordParameters(declaration, false));
        recordImplementsClause(declaration, false).ifPresent(header::add);
        return Doc.concat(header);
    }

    /**
     * Prints the broken header selected after the compact header no longer fits, without the trailing body: components
     * hard-line one per line and the {@code implements} clause is ranked flat-attached-vs-one-per-line at the closing
     * paren's own column.
     */
    private Doc brokenRecordHeader(RecordDeclaration declaration, Doc prefix) {
        List<Doc> header = new ArrayList<>();
        header.add(prefix);
        header.add(recordParameters(declaration, true));
        recordImplementsClause(declaration, true).ifPresent(header::add);
        return Doc.concat(header);
    }

    /**
     * Ranks the record's {@code implements} clause: attached flat after the closing paren, or — when that overflows —
     * the keyword stays attached but each type moves to its own indented line, matching the established record header
     * convention (unlike class/interface clauses, the keyword never starts its own broken line).
     */
    private Optional<Doc> recordImplementsClause(RecordDeclaration declaration, boolean forceParametersBreak) {
        NodeList<ClassOrInterfaceType> types = declaration.getImplementedTypes();
        if (types.isEmpty()) {
            return Optional.empty();
        }
        Doc flatClause = Doc.text(" implements " + compactJoinTypeLike.apply(types));
        if (types.size() == 1) {
            return Optional.of(flatClause);
        }
        Doc onePerLineClause = Doc.concat(
            Doc.text(" implements"),
            Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    Doc.join(
                        Doc.concat(Doc.text(","), Doc.HARD_LINE),
                        types.stream().map(type -> Doc.text(compactTypeLike.apply(type))).toList()
                    )
                )
            )
        );
        return Optional.of(Doc.bestFittingFirstLine(List.of(flatClause, onePerLineClause), new int[] {1, 0}));
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
            layouts.add(
                new RecordComponentLayout(recordComponent(parameter, trailing, forceBreak), separator, gapComments)
            );
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
    private Doc recordComponent(Parameter parameter, Doc trailing, boolean forceBreak) {
        List<Doc> parts = new ArrayList<>();
        Doc leading = comments.leading(parameter);
        if (leading != Doc.EMPTY) {
            parts.add(leading);
        }
        if (recordComponentAnnotationsShouldBreak(parameter)) {
            // Each annotation is on its own component-indented line here, so its only same-line context is the extra
            // indent past the member baseline when this candidate actually broke; a wide annotation still breaks
            // internally when it overflows that column.
            LayoutContext stackedLayout = LayoutContext.root()
                    .withLeftEdgePrefix(forceBreak ? options.indentUnit() : "");
            parameter.getAnnotations()
                    .stream()
                    .map(node -> annotation.format(node, stackedLayout))
                    .map(doc -> Doc.concat(doc, Doc.HARD_LINE))
                    .forEach(parts::add);
        } else if (!parameter.getAnnotations().isEmpty()) {
            parts.add(recordComponentAnnotationPrefix(parameter, forceBreak));
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

    private boolean recordComponentAnnotationsShouldBreak(Parameter parameter) {
        if (parameter.getAnnotations().isEmpty()) {
            return false;
        }
        // A component whose annotations fit inline reprints inline regardless of source shape. A component whose
        // annotation is itself width-driven-multiline lets that annotation break internally rather than forcing the
        // whole component prefix to stack.
        if (recordComponentHasWidthDrivenMultilineAnnotation(parameter)) {
            return false;
        }
        return currentIndentedWidth.applyAsInt(recordComponentFlat(parameter)) > options.lineWidth();
    }

    private Doc recordComponentAnnotationPrefix(Parameter parameter, boolean forceBreak) {
        List<AnnotationExpr> annotations = parameter.getAnnotations();
        List<Doc> docs = new ArrayList<>();
        for (int index = 0; index < annotations.size(); index++) {
            docs.add(annotation.format(
                annotations.get(index),
                recordComponentAnnotationLayout(parameter, annotations, index, forceBreak)
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
     * <p>This candidate's own component list only sits one unit past the member baseline {@link #currentIndentedWidth}
     * assumes when it actually broke (either forced or self-wrapped); {@code forceBreak} carries that fact so
     * {@link LayoutContext#leftEdgePrefix()} contributes the extra unit only then, plus every earlier annotation (joined
     * by the same {@code " "} the render uses). The trailing content is the remaining annotations then the
     * {@code " Type name"} tail the component emits after the prefix on the same line, so the annotation breaks only when
     * keeping it flat would push that tail past the width.
     */
    private LayoutContext recordComponentAnnotationLayout(
            Parameter parameter,
            List<AnnotationExpr> annotations,
            int index,
            boolean forceBreak
    ) {
        StringBuilder leftEdge = new StringBuilder(forceBreak ? options.indentUnit() : "");
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
