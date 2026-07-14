package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Renders parenthesized expression lists that contain line comments between elements.
 *
 * <p>This helper owns the reusable argument-list shape for calls and constructors when JavaParser exposes comments as
 * source trivia around arguments rather than as ordinary expression content. The boundary exists so method-call and
 * object-creation printers can keep their syntax-specific branching while comma placement and source-position comment
 * gaps stay in one implementation.
 *
 * <p>Callers still decide when specialized lambda, text-block, binary, or chain layouts should run before this fallback,
 * and they provide the prefix text plus the expression renderer for individual arguments.
 */
final class CommentedExpressionListPrinter {

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    private final CompactSourceText compactSource;

    private final Function<Expression, Doc> expressionRenderer;

    CommentedExpressionListPrinter(JavaFormatContext context, Function<Expression, Doc> expressionRenderer) {
        this.comments = context.comments;
        this.commentPlacement = context.commentPlacementPolicy;
        this.compactSource = context.compactSource;
        this.expressionRenderer = expressionRenderer;
    }

    /**
     * Returns a broken parenthesized list only when unclaimed line comments sit directly in its argument gaps.
     */
    Optional<Doc> parenthesized(String prefix, Node container, NodeList<Expression> arguments) {
        if (arguments.isEmpty()) {
            return Optional.empty();
        }
        List<List<JavaCommentTrivia>> commentGaps = argumentCommentGaps(container, arguments);
        if (!hasUnprintedComments(container, commentGaps)) {
            return Optional.empty();
        }

        List<Doc> lines = new ArrayList<>();
        addCommentDocs(lines, commentGaps.getFirst());
        for (int index = 0; index < arguments.size(); index++) {
            Expression argument = arguments.get(index);
            boolean hasNext = index + 1 < arguments.size();
            List<JavaCommentTrivia> trailingComments = commentGaps.get(index + 1);
            Doc argumentLine = argumentLine(argument, trailingComments);
            List<Doc> inlineTrailingComments = new ArrayList<>();
            List<Doc> trailingCommentLines = new ArrayList<>();
            for (JavaCommentTrivia comment : trailingComments) {
                // A gap comment between this container's arguments is offered under the container's own INTERLEAVED
                // anchor, so ownership disambiguates the two competing offers without a build-order isPrinted skip:
                //   - the argument's own trailing comment (e.g. a chain's final comment under (finalCall, INTERLEAVED))
                //     is a different key, so ownsHere blocks this slot and comment(...) returns Doc.EMPTY here;
                //   - a comment the argument render left untouched is owned by this container slot and placed here.
                // The argument node is deliberately NOT the anchor: a single-argument call's argument is often the node
                // the chain printer's finalTrailingLineComment anchors on, so (argument, INTERLEAVED) would collide and
                // double-render. argumentCommentGaps de-dups by identity, so the same comment is never offered twice here.
                Doc commentDoc = comments.comment(comment, container, OwnerSlot.INTERLEAVED);
                if (commentDoc == Doc.EMPTY) {
                    continue;
                }
                if (
                    comment.startsOnEndLine(argument)
                    || comment.startsAfterNodeOnSameLine(argument)
                    || argumentContainsComment(argument, comment)
                ) {
                    inlineTrailingComments.add(commentDoc);
                } else {
                    trailingCommentLines.add(commentDoc);
                }
            }
            if (hasNext) {
                argumentLine = Doc.concat(argumentLine, Doc.text(","));
            }
            for (Doc inlineTrailingComment : inlineTrailingComments) {
                argumentLine = Doc.concat(
                    argumentLine,
                    Doc.lineSuffix(Doc.concat(Doc.text(" "), inlineTrailingComment))
                );
            }
            lines.add(argumentLine);
            lines.addAll(trailingCommentLines);
        }

        if (lines.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, lines))),
                Doc.HARD_LINE,
                Doc.text(")")
            )
        );
    }

    /**
     * The argument-gap comments around a single argument that a specialized hug layout renders itself, already rendered
     * to {@link Doc}s and classified for placement:
     *
     * <ul>
     *   <li>{@code leading} — comments in the gap before the argument, each to be laid on its own line above it;
     *   <li>{@code inlineTrailing} — comments that trail the argument on its own last line, each to be deferred as a
     *       {@link Doc#lineSuffix} after it;
     *   <li>{@code trailingLines} — the remaining after-argument comments, each on its own line below it.
     * </ul>
     */
    record HugGapComments(List<Doc> leading, List<Doc> inlineTrailing, List<Doc> trailingLines) {}

    /**
     * Renders the argument-gap line and block comments around the single argument of {@code container} for a
     * specialized hug layout that lays the argument out itself (the single text-block call), so the hug can keep those
     * gap comments instead of deferring to the broken {@link #parenthesized} list.
     *
     * <p>The gaps are computed by SOURCE ORDER — not by source line — through the same {@link #argumentCommentGaps}
     * this class's parenthesized list uses, and each comment is offered under the SAME ownership anchor: a leading gap
     * comment (before the argument) under its own {@link OwnerSlot#INTERLEAVED} slot, a trailing gap comment (after the
     * argument) under {@code container}'s {@link OwnerSlot#INTERLEAVED} slot. Sharing the gaps and anchors means comment
     * ownership resolves identically whether the hug layout or the parenthesized list renders, so a whitespace
     * perturbation that floats a comment off the block's own line cannot drop or duplicate it — the shape-dependent
     * own-comment / same-line-orphan lookups the hug used before could not see such a moved comment, which is why the
     * hug dropped it on collapsed/expanded shapes and had to defer to the parenthesized list.
     *
     * <p>Trailing comments are split exactly as {@link #parenthesized} splits them: a comment on the argument's own end
     * line (or trailing it on that line, or contained by it) is {@code inlineTrailing}; every other trailing comment is
     * a {@code trailingLine}. Requires {@code container} to hold a single argument.
     */
    HugGapComments singleArgumentHugGapComments(Node container, NodeList<Expression> arguments) {
        List<List<JavaCommentTrivia>> commentGaps = argumentCommentGaps(container, arguments);
        Expression argument = arguments.get(0);
        List<Doc> leading = new ArrayList<>();
        addCommentDocs(leading, commentGaps.getFirst());
        List<Doc> inlineTrailing = new ArrayList<>();
        List<Doc> trailingLines = new ArrayList<>();
        for (JavaCommentTrivia comment : commentGaps.get(1)) {
            Doc commentDoc = comments.comment(comment, container, OwnerSlot.INTERLEAVED);
            if (commentDoc == Doc.EMPTY) {
                continue;
            }
            if (
                comment.startsOnEndLine(argument)
                || comment.startsAfterNodeOnSameLine(argument)
                || argumentContainsComment(argument, comment)
            ) {
                inlineTrailing.add(commentDoc);
            } else {
                trailingLines.add(commentDoc);
            }
        }
        return new HugGapComments(leading, inlineTrailing, trailingLines);
    }

    private Doc argumentLine(Expression argument, List<JavaCommentTrivia> trailingComments) {
        if (argument instanceof MethodCallExpr methodCall && hasInlineTrailingComment(argument, trailingComments)) {
            if (hasContainedCommentOutsideTrailingComments(argument, trailingComments)) {
                return expressionRenderer.apply(argument);
            }
            return Doc.text(compactSource.commentFree(methodCall));
        }
        return expressionRenderer.apply(argument);
    }

    private boolean hasContainedCommentOutsideTrailingComments(
            Expression argument,
            List<JavaCommentTrivia> trailingComments
    ) {
        return argument.getAllContainedComments()
                .stream()
                .anyMatch(contained -> trailingComments.stream().noneMatch(comment -> comment.comment() == contained));
    }

    private boolean hasInlineTrailingComment(Expression argument, List<JavaCommentTrivia> trailingComments) {
        return trailingComments.stream()
                .anyMatch(comment -> comment.startsOnEndLine(argument)
                        || comment.startsAfterNodeOnSameLine(argument)
                        || argumentContainsComment(argument, comment)
                );
    }

    private boolean argumentContainsComment(Expression argument, JavaCommentTrivia comment) {
        return argument.getAllContainedComments()
                .stream()
                .anyMatch(contained -> contained == comment.comment());
    }

    /**
     * Reports whether a parenthesized expression list has unclaimed line comments in one of its argument gaps.
     */
    boolean hasUnprintedLineComments(Node container, NodeList<Expression> arguments) {
        return !arguments.isEmpty() && hasUnprintedComments(container, argumentCommentGaps(container, arguments));
    }

    /**
     * Reports source-line comments in argument gaps without consulting printed-comment state.
     */
    boolean hasLineComments(Node container, NodeList<Expression> arguments) {
        return !arguments.isEmpty()
            && argumentCommentGaps(container, arguments).stream().flatMap(List::stream).findAny().isPresent();
    }

    private List<List<JavaCommentTrivia>> argumentCommentGaps(Node container, NodeList<Expression> arguments) {
        List<List<JavaCommentTrivia>> gaps = new ArrayList<>();
        gaps.add(
            new ArrayList<>(
                commentPlacement.lineCommentsBeforeFirst(container, arguments.get(0))
                        .stream()
                        .filter(comment -> !comment.startsBeforeBeginLine(argumentListAnchor(container)))
                        .toList()
            )
        );
        for (int index = 0; index < arguments.size(); index++) {
            Expression argument = arguments.get(index);
            if (index + 1 < arguments.size()) {
                gaps.add(
                    new ArrayList<>(commentPlacement.lineCommentsBetween(container, argument, arguments.get(index + 1)))
                );
            } else {
                gaps.add(new ArrayList<>(trailingArgumentComments(container, argument)));
            }
        }
        addArgumentBlockComments(container, arguments, gaps);
        gaps.replaceAll(CommentedExpressionListPrinter::dedupByCommentIdentity);
        return gaps;
    }

    /**
     * Drops repeats of the same JavaParser comment within one gap, keeping the first occurrence in source order.
     *
     * <p>{@link JavaCommentPlacementPolicy#containedComments(Node)} can list the same comment twice when a whitespace
     * perturbation reaches it through two containment paths (a collapsed argument list, where a call's after-last-argument
     * comment appears twice in the trailing gap). Since the gap is offered under one shared {@code (container, INTERLEAVED)}
     * owner, an undeduplicated duplicate renders twice; de-duplicating by identity offers each once. Distinct nodes with
     * the same text are both kept.
     */
    private static List<JavaCommentTrivia> dedupByCommentIdentity(List<JavaCommentTrivia> gap) {
        java.util.Set<Comment> seen =
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
     * Adds the block comments that sit in the argument-list gaps, which the line-only gap queries never gather.
     *
     * <p>JavaParser parks an argument-list {@code /* … *}{@code /} block on different buckets by shape — trailing an
     * argument ({@code update((byte) input /* >> 0 *}{@code /)}) it is the call's contained/orphan trivia, leading the
     * next argument ({@code confirm(true, /* note *}{@code / ledger)}) it is that argument's own comment — but the call's
     * recursive {@link JavaCommentPlacementPolicy#containedComments(Node)} view holds every such block. The line-comment
     * gap queries skip them, so {@link #parenthesized} drops them; this recovers each into the gap it belongs to.
     *
     * <p>Placement is by source order (see {@link CommentIndex#liesBetween(Comment, Node, Node)},
     * {@link CommentIndex#startsAfterEndOf(Node, Comment)}), so a block keeps its gap however whitespace lays it out. A
     * block inside an argument's own range is left to that argument's renderer, and one past the closing {@code )} to the
     * enclosing syntax. Each block is placed in one gap and deduped by identity against the line comments already
     * gathered; {@link CommentTracker#comment(JavaCommentTrivia)} renders an already-claimed block as {@link Doc#EMPTY},
     * so a block another path renders at {@code @default} is left untouched.
     */
    private void addArgumentBlockComments(
            Node container,
            NodeList<Expression> arguments,
            List<List<JavaCommentTrivia>> gaps
    ) {
        List<JavaCommentTrivia> placed = new ArrayList<>();
        gaps.forEach(placed::addAll);
        Node regionStart = argumentRegionStart(container, arguments.get(0));
        commentPlacement.containedComments(container)
                .stream()
                .filter(JavaCommentTrivia::isBlock)
                .filter(comment -> placed.stream().noneMatch(existing -> existing.comment() == comment.comment()))
                .filter(comment -> arguments.stream().noneMatch(argument -> startsInside(argument, comment)))
                .filter(comment -> comment.startsAfterEndOf(regionStart))
                .filter(comment -> CommentIndex.startsBeforeEnd(comment.comment(), container))
                .sorted(
                    java.util.Comparator.comparing(JavaCommentTrivia::comment, CommentIndex.sourceOrderComparator())
                )
                .forEach(comment -> {
                    gaps.get(blockCommentGapIndex(arguments, comment)).add(comment);
                    placed.add(comment);
                });
    }

    /**
     * Reports whether a comment begins strictly inside {@code argument}'s own token range (after its first token, before
     * its last).
     *
     * <p>A comment before or after the argument belongs to a gap; only one genuinely nested inside the expression (e.g.
     * {@code foo(bar(/* x *}{@code / baz))}) is the argument renderer's. The source-order test keeps such nested trivia
     * out of gap recovery without mistaking a same-line leading-own block for nested content.
     */
    private boolean startsInside(Expression argument, JavaCommentTrivia comment) {
        return !comment.startsBefore(argument)
            && !comment.startsAfterEndOf(argument)
            && CommentIndex.startsBeforeEnd(comment.comment(), argument);
    }

    /**
     * Returns the argument-gap index a block comment belongs to, by source order: gap {@code 0} before the first
     * argument, gap {@code i + 1} for a block in the source-order gap that follows {@code arguments[i]}.
     */
    private int blockCommentGapIndex(NodeList<Expression> arguments, JavaCommentTrivia comment) {
        if (comment.startsBefore(arguments.get(0))) {
            return 0;
        }
        for (int index = 0; index + 1 < arguments.size(); index++) {
            if (comment.liesBetween(arguments.get(index), arguments.get(index + 1))) {
                return index + 1;
            }
        }
        return arguments.size();
    }

    /**
     * Keeps comments after the completed call or constructor out of the final argument gap.
     *
     * <p>A line comment in {@code call(arg) // note} belongs to the enclosing syntax, not to {@code arg}; chain and
     * statement printers need to keep that comment at the completed-call boundary.
     */
    private List<JavaCommentTrivia> trailingArgumentComments(Node container, Expression argument) {
        List<JavaCommentTrivia> sourceComments = new ArrayList<>(
            commentPlacement.lineCommentsAfterLast(
                container,
                argument
            )
        );
        commentPlacement.trailingLineComment(argument)
                .filter(
                    comment -> sourceComments.stream().noneMatch(existing -> existing.comment() == comment.comment())
                )
                .ifPresent(sourceComments::add);
        commentPlacement.containedComments(argument)
                .stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.startsOnEndLine(argument) || comment.startsAfterNodeOnSameLine(argument))
                .filter(
                    comment -> sourceComments.stream().noneMatch(existing -> existing.comment() == comment.comment())
                )
                .forEach(sourceComments::add);
        int argumentEndLine = CommentIndex.endLine(argument, Integer.MIN_VALUE);
        argument.getAllContainedComments()
                .stream()
                .filter(LineComment.class::isInstance)
                .map(JavaCommentTrivia::from)
                .filter(comment -> comment.beginLine(Integer.MAX_VALUE) == argumentEndLine)
                .filter(
                    comment -> sourceComments.stream().noneMatch(existing -> existing.comment() == comment.comment())
                )
                .forEach(sourceComments::add);
        return sourceComments
                .stream()
                .filter(comment -> !startsInsideOtherDirectChild(container, argument, comment))
                .filter(comment -> !comment.startsAfterNodeOnSameLine(container))
                .toList();
    }

    private boolean startsInsideOtherDirectChild(Node container, Expression argument, JavaCommentTrivia comment) {
        return container.getChildNodes()
                .stream()
                .filter(child -> !(child instanceof Comment))
                .filter(child -> child != argument)
                .anyMatch(comment::isInsideNotTrailing);
    }

    private Node argumentListAnchor(Node container) {
        return container instanceof MethodCallExpr methodCall ? methodCall.getName() : container;
    }

    /**
     * Returns the node whose end marks the start of the argument-list region, used as the source-order lower bound when
     * recovering block comments so trivia attached to the callee — a method-call selector, or an object-creation type —
     * is not pulled into the argument gaps.
     *
     * <p>For a method call the region begins after the method name; for an object creation it begins after the created
     * type (which ends just before the open parenthesis). Other containers fall back to the first argument, which keeps
     * only genuinely after-{@code (} block comments since the first-argument gap (gap {@code 0}) still admits comments
     * that begin before the first argument.
     */
    private Node argumentRegionStart(Node container, Expression first) {
        if (container instanceof MethodCallExpr methodCall) {
            return methodCall.getName();
        }
        if (container instanceof com.github.javaparser.ast.expr.ObjectCreationExpr objectCreation) {
            return objectCreation.getType();
        }
        return first;
    }

    private boolean hasUnprintedComments(Node container, List<List<JavaCommentTrivia>> commentGaps) {
        for (int index = 0; index < commentGaps.size(); index++) {
            for (JavaCommentTrivia comment : commentGaps.get(index)) {
                // Mirror parenthesized's per-gap render anchor: gap 0 uses the comment's own (comment, INTERLEAVED) key,
                // later gaps (container, INTERLEAVED). A gap comment is "unplaced here" — the reason to choose the broken
                // layout — when this list's slot still owns it (ownsHere admits it: unmigrated or recorded to this slot).
                // A comment the dry-run recorded to another slot is placed there instead and does not force this layout.
                Node anchor = index == 0 ? comment.comment() : container;
                if (comments.ownsHere(comment, anchor, OwnerSlot.INTERLEAVED)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void addCommentDocs(List<Doc> lines, List<JavaCommentTrivia> sourceComments) {
        sourceComments.stream()
                .map(comments::comment)
                .filter(doc -> doc != Doc.EMPTY)
                .forEach(lines::add);
    }
}
