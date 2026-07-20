package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Sequences already formatted type members inside Java member blocks.
 *
 * <p>This helper owns the source-range-sensitive ordering of declarations, orphan comments, opening-brace line comments,
 * blank lines, and formatter pragma adjacency inside class, interface, record, enum, and annotation bodies. The boundary
 * exists so {@link JavaPrinter} can keep declaration formatting decisions local to each declaration printer while
 * member-block trivia rules stay together. It intentionally does not choose how declarations render, how statements
 * inside methods render, or which annotations count as declaration annotations.
 */
final class MemberBlockPrinter {

    private static final String MEMBER_DECLARATION_LIST_RECOVERY_FAILURE =
        "Unable to recover Java parse error inside member declaration list: ";

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    private final SourceShapePolicy sourceShapePolicy;

    private final SourceText sourceText;

    private final RecoveredListPlanner recoveredListPlanner;

    private final RecoveredRawGapPrinter rawGaps;

    private final SourceOrderedCommentInterleaver<BodyDeclaration<?>> commentInterleaver;

    private final boolean recoverParseProblems;

    private final Predicate<BodyDeclaration<?>> hasDeclarationAnnotations;

    private final Predicate<BodyDeclaration<?>> hasPragma;

    private final MemberBlockBraceLayout brace;

    MemberBlockPrinter(
            JavaFormatContext context,
            Predicate<BodyDeclaration<?>> hasDeclarationAnnotations,
            Predicate<BodyDeclaration<?>> hasPragma
    ) {
        this.comments = context.comments;
        this.commentPlacement = context.commentPlacementPolicy;
        this.sourceShapePolicy = context.sourceShapePolicy;
        this.sourceText = context.sourceText;
        this.recoveredListPlanner = context.recoveredListPlanner;
        this.rawGaps = new RecoveredRawGapPrinter(context, MemberBlockPrinter::memberDeclarationListRecoveryFailure);
        this.commentInterleaver = new SourceOrderedCommentInterleaver<>(comments);
        this.recoverParseProblems = context.recoverParseProblems;
        this.hasDeclarationAnnotations = hasDeclarationAnnotations;
        this.hasPragma = hasPragma;
        this.brace = new MemberBlockBraceLayout(comments, sourceText, commentPlacement);
    }

    /**
     * Prints a brace-delimited member block while preserving source-only spacing signals around members and orphan
     * comments.
     *
     * <p>The member renderer is invoked once per declaration before sequencing starts, so declaration formatting remains
     * owned by the caller while this method owns only the surrounding member-content layout.
     */
    Doc memberBlock(
            NodeList<BodyDeclaration<?>> members,
            Node owner,
            Function<BodyDeclaration<?>, Doc> memberRenderer
    ) {
        Doc openingBraceTrailingComment = brace.openingBraceTrailingLineComment(owner);
        Optional<RecoveredListPlanner.Plan<BodyDeclaration<?>>> recoveryPlan = recoveryPlan(owner, members);
        if (recoveryPlan.isPresent() && hasRawGap(recoveryPlan.orElseThrow())) {
            return recoveredMemberBlock(
                owner,
                recoveryPlan.orElseThrow(),
                openingBraceTrailingComment,
                memberRenderer
            );
        }

        List<Doc> memberDocs = new ArrayList<>(members.stream().map(memberRenderer).toList());
        if (memberDocs.isEmpty()) {
            List<Doc> orphanComments = comments.orphanCommentStatements(owner);
            // Empty member blocks may still carry brace-line or orphan comments that need a real block body.
            if (openingBraceTrailingComment == Doc.EMPTY && orphanComments.isEmpty()) {
                return Doc.text("{}");
            }
            List<Doc> comments = new ArrayList<>();
            if (openingBraceTrailingComment != Doc.EMPTY) {
                comments.add(openingBraceTrailingComment);
            }
            comments.addAll(orphanComments);
            return Doc.concat(
                Doc.text("{"),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, comments))),
                Doc.HARD_LINE,
                Doc.text("}")
            );
        }
        Doc contents = memberContents(owner, members, memberDocs);
        // A line comment written on the same line as the opening brace conceptually belongs to that brace. Annotation
        // bodies keep it inline after the brace (`@interface X { // note`); routing the comment to its own indented line
        // would detach it from the brace it trailed in source. Other member-block owners keep their established own-line
        // placement, so this stays scoped to annotation declarations.
        if (openingBraceTrailingComment != Doc.EMPTY && owner instanceof AnnotationDeclaration) {
            return Doc.concat(
                Doc.text("{"),
                Doc.lineSuffix(Doc.concat(Doc.text(" "), openingBraceTrailingComment)),
                Doc.indent(Doc.concat(memberBlockOpeningBreak(owner), contents)),
                Doc.HARD_LINE,
                Doc.text("}")
            );
        }
        if (openingBraceTrailingComment != Doc.EMPTY) {
            contents = Doc.concat(openingBraceTrailingComment, Doc.HARD_LINE, contents);
        }
        return Doc.concat(
            Doc.text("{"),
            Doc.indent(Doc.concat(memberBlockOpeningBreak(owner), contents)),
            Doc.HARD_LINE,
            Doc.text("}")
        );
    }

    /**
     * Chooses how much vertical space follows the opening brace before the first member.
     *
     * <p>Records, interfaces, and annotation types are commonly written as compact declaration lists, so they skip the
     * extra blank line that class and enum bodies keep for the formatter's member-block style. Annotation bodies join
     * this compact group so that routing them through the shared member-block path preserves their established
     * single-line gap after the opening brace instead of widening it.
     */
    private Doc memberBlockOpeningBreak(Node owner) {
        if (
            owner instanceof RecordDeclaration
            || owner instanceof AnnotationDeclaration
            || (owner instanceof ClassOrInterfaceDeclaration declaration && declaration.isInterface())
        ) {
            return Doc.HARD_LINE;
        }
        return Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE);
    }

    /**
     * Interleaves member declarations and orphan comments by original line number before choosing separators.
     *
     * <p>JavaParser exposes orphan comments separately from body declarations, so this method uses source ranges to put
     * those comments back before, between, or after the already-rendered member docs.
     */
    private Doc memberContents(Node owner, NodeList<BodyDeclaration<?>> members, List<Doc> memberDocs) {
        List<Doc> renderedMembers = new ArrayList<>(memberDocs);
        List<JavaCommentTrivia> orphanComments = attachInlineTrailingMemberComments(
            owner,
            members,
            renderedMembers,
            commentPlacement.orphanCommentsInSourceOrder(owner)
        );
        return Doc.concat(
            commentInterleaver.interleave(
                owner,
                members,
                orphanComments,
                (previous, current, index) -> Optional.of(renderedMembers.get(index)),
                new SourceOrderedCommentInterleaver.Spacing<>() {
                    @Override
                    public int beginLine(BodyDeclaration<?> sibling) {
                        return memberBeginLine(sibling);
                    }

                    @Override
                    public int endLine(BodyDeclaration<?> sibling) {
                        return CommentIndex.endLine(sibling, Integer.MAX_VALUE);
                    }

                    @Override
                    public Doc separatorBeforeSibling(
                            SourceOrderedCommentInterleaver.PreviousEntry<BodyDeclaration<?>> previous,
                            BodyDeclaration<?> currentSibling
                    ) {
                        if (previous.kind() == SourceOrderedCommentInterleaver.EntryKind.SIBLING) {
                            return memberSeparator(owner, previous.sibling().orElseThrow(), currentSibling);
                        }
                        return sourceLineSeparator(previous.endLine(), beginLine(currentSibling));
                    }

                    @Override
                    public Doc separatorBeforeComment(
                            SourceOrderedCommentInterleaver.PreviousEntry<BodyDeclaration<?>> previous,
                            JavaCommentTrivia comment
                    ) {
                        return sourceLineSeparator(previous.endLine(), comment.beginLine(Integer.MAX_VALUE));
                    }
                }
            )
        );
    }

    private int memberBeginLine(BodyDeclaration<?> member) {
        int declarationBeginLine = CommentIndex.beginLine(member, Integer.MAX_VALUE);
        return commentPlacement.leadingComment(member)
                .map(comment -> comment.beginLine(declarationBeginLine))
                .filter(commentBeginLine -> commentBeginLine < declarationBeginLine)
                .orElse(declarationBeginLine);
    }

    /**
     * Keeps a line comment that trails a member's closing brace or statement terminator on the same source line inline
     * after that member instead of routing it to the next own line.
     *
     * <p>A {@code //} after a nested type's closing brace ({@code class Beacon { ... }} {@code // inner beacon}) or after
     * a field whose initializer broke onto its own line ({@code byte[] header = {}{@code ...}}{@code }; // 4096 bytes})
     * belongs to that terminator line, but JavaParser re-buckets it as an enclosing-body orphan once the terminator lands
     * on its own line, so the interleaver would push it to the next line — and only in that broken shape, so the placement
     * flips across passes. This attaches each as a {@link Doc#lineSuffix(Doc)} on the member it trails and returns the
     * remaining orphans for normal interleaving, matching the hugged placement the flat shape already produces.
     *
     * <p>Restricted to {@link TypeDeclaration} members and to {@link FieldDeclaration} members whose initializer is an
     * array (a type's closing brace and a broken array terminator carry this convention), and to line comments beginning
     * strictly after the member's end column on its end line. A method-call-chain field is excluded because its own
     * trailing-comment path already re-homes such a comment, and re-hugging one here would feed the chain's
     * comment-bearing width gate and oscillate. The attached doc claims under the same {@code (owner, INTERLEAVED)} anchor
     * the interleaver would use, so claim ownership and idempotence are unchanged.
     */
    private List<JavaCommentTrivia> attachInlineTrailingMemberComments(
            Node owner,
            NodeList<BodyDeclaration<?>> members,
            List<Doc> renderedMembers,
            List<JavaCommentTrivia> orphanComments
    ) {
        List<JavaCommentTrivia> remaining = new ArrayList<>();
        for (JavaCommentTrivia comment : orphanComments) {
            int memberIndex = inlineTrailingTypeMemberIndex(members, comment);
            if (memberIndex < 0) {
                remaining.add(comment);
                continue;
            }
            Doc commentDoc = comments.comment(comment, owner, OwnerSlot.INTERLEAVED);
            if (commentDoc == Doc.EMPTY) {
                continue;
            }
            renderedMembers.set(
                memberIndex,
                Doc.concat(
                    renderedMembers.get(memberIndex),
                    Doc.lineSuffix(Doc.concat(Doc.text(" "), commentDoc))
                )
            );
        }
        return remaining;
    }

    /**
     * Finds the index of the type or field member that {@code comment} trails inline on the same source line, or
     * {@code -1} when the comment is not an inline trailing line comment of any such member.
     *
     * <p>The comment must also begin before the next member's line so that, when a collapsed re-shape puts several members
     * on one physical line, a comment leading a far-later member is not hugged onto an earlier one it merely shares a line
     * with — only the member the comment genuinely sits after, in the source-order gap before the next member, claims it.
     */
    private int inlineTrailingTypeMemberIndex(NodeList<BodyDeclaration<?>> members, JavaCommentTrivia comment) {
        if (!comment.isLine()) {
            return -1;
        }
        for (int index = 0; index < members.size(); index++) {
            BodyDeclaration<?> member = members.get(index);
            boolean beforeNextMember = index + 1 >= members.size()
                || comment.startsBeforeBeginLine(members.get(index + 1));
            if (
                (member instanceof TypeDeclaration || isArrayInitializerField(member))
                && comment.startsAfterNodeOnSameLine(member)
                && beforeNextMember
            ) {
                return index;
            }
        }
        return -1;
    }

    /** Whether {@code member} is a field whose last declarator is initialized by an array — the broken-terminator shape. */
    private boolean isArrayInitializerField(BodyDeclaration<?> member) {
        if (!(member instanceof FieldDeclaration field) || field.getVariables().isEmpty()) {
            return false;
        }
        return field.getVariable(field.getVariables().size() - 1)
                .getInitializer()
                .filter(ArrayInitializerExpr.class::isInstance)
                .isPresent();
    }

    /**
     * Emits a recovered member block while leaving valid declaration siblings on their normal renderer.
     *
     * <p>Raw member gaps already carry the source whitespace between the nearest valid declarations or braces. This
     * path therefore keeps formatter-owned separators only between adjacent valid declarations and lets raw islands own
     * malformed source, including comments fully contained by those islands.
     */
    private Doc recoveredMemberBlock(
            Node owner,
            RecoveredListPlanner.Plan<BodyDeclaration<?>> plan,
            Doc openingBraceTrailingComment,
            Function<BodyDeclaration<?>, Doc> memberRenderer
    ) {
        Optional<SourceRegion> openingBraceTrailingCommentRegion = brace.openingBraceTrailingLineCommentRegion(owner);
        List<RecoveredRawGapPrinter.RawGapRegion> rawGapRegions = rawGaps.rawGapRegions(
            plan,
            region -> withoutOpeningBraceTrailingComment(
                region,
                openingBraceTrailingCommentRegion
            )
        );
        List<SourceRegion> rawRegions = rawGaps.regions(rawGapRegions);
        rawGaps.requireRecoverableRawRegions(owner, rawGapRegions);
        List<JavaCommentTrivia> orphanComments = orphanCommentsOutsideRawRegions(owner, rawRegions);

        List<Doc> contents = new ArrayList<>();
        EntryKind previousEntry = EntryKind.NONE;
        int previousEndLine = Integer.MIN_VALUE;
        BodyDeclaration<?> previousMember = null;
        int orphanIndex = 0;

        if (openingBraceTrailingComment != Doc.EMPTY) {
            contents.add(memberBlockOpeningBreak(owner));
            contents.add(openingBraceTrailingComment);
            previousEntry = EntryKind.OPENING_BRACE_COMMENT;
            previousEndLine = openingBraceTrailingCommentRegion
                    .map(SourceRegion::endLine)
                    .orElse(CommentIndex.beginLine(owner, Integer.MIN_VALUE));
        }

        int rawGapIndex = 0;
        for (RecoveredListPlanner.Entry<BodyDeclaration<?>> entry : plan.entries()) {
            while (
                orphanIndex < orphanComments.size()
                && orphanComments.get(orphanIndex).beginLine(Integer.MAX_VALUE) < entry.region().beginLine()
            ) {
                previousEntry = appendRecoveredOrphanComment(
                    contents,
                    owner,
                    orphanComments.get(orphanIndex++),
                    previousEntry,
                    previousEndLine
                );
                if (previousEntry == EntryKind.ORPHAN_COMMENT) {
                    previousEndLine = orphanComments.get(orphanIndex - 1).endLine(Integer.MAX_VALUE);
                }
            }
            switch (entry) {
                case RecoveredListPlanner.ValidSibling<?> valid -> {
                    BodyDeclaration<?> currentMember = (BodyDeclaration<?>) valid.sibling();
                    appendSeparatorBeforeRecoveredMember(
                        contents,
                        owner,
                        previousEntry,
                        previousEndLine,
                        previousMember,
                        currentMember
                    );
                    contents.add(memberRenderer.apply(currentMember));
                    previousEndLine = CommentIndex.endLine(currentMember, Integer.MAX_VALUE);
                    previousMember = currentMember;
                    previousEntry = EntryKind.VALID_MEMBER;
                }
                case RecoveredListPlanner.RawGap<?> ignored -> {
                    RecoveredRawGapPrinter.RawGapRegion rawRegion = rawGapRegions.get(rawGapIndex++);
                    if (rawRegion.region().beginOffset() < rawRegion.region().endOffset()) {
                        contents.add(rawGaps.raw(owner, rawRegion, "memberDeclarationList"));
                    }
                    previousEntry = rawRegion.trailingBreakReplaced()
                        ? EntryKind.RAW_GAP_WITH_TRAILING_BREAK
                        : EntryKind.RAW_GAP;
                }
            }
        }
        while (orphanIndex < orphanComments.size()) {
            previousEntry = appendRecoveredOrphanComment(
                contents,
                owner,
                orphanComments.get(orphanIndex++),
                previousEntry,
                previousEndLine
            );
            if (previousEntry == EntryKind.ORPHAN_COMMENT) {
                previousEndLine = orphanComments.get(orphanIndex - 1).endLine(Integer.MAX_VALUE);
            }
        }

        if (contents.isEmpty()) {
            return Doc.text("{}");
        }
        Doc closingBreak = switch (previousEntry) {
            case RAW_GAP -> Doc.EMPTY;
            case NONE, OPENING_BRACE_COMMENT, ORPHAN_COMMENT, VALID_MEMBER, RAW_GAP_WITH_TRAILING_BREAK -> Doc.HARD_LINE;
        };
        return Doc.concat(Doc.text("{"), Doc.indent(Doc.concat(contents)), closingBreak, Doc.text("}"));
    }

    private EntryKind appendRecoveredOrphanComment(
            List<Doc> contents,
            Node owner,
            JavaCommentTrivia comment,
            EntryKind previousEntry,
            int previousEndLine
    ) {
        appendSeparatorBeforeRecoveredOrphanComment(contents, owner, previousEntry, previousEndLine, comment);
        Doc commentDoc = comments.comment(comment);
        if (commentDoc == Doc.EMPTY) {
            return previousEntry;
        }
        contents.add(commentDoc);
        return EntryKind.ORPHAN_COMMENT;
    }

    private void appendSeparatorBeforeRecoveredMember(
            List<Doc> contents,
            Node owner,
            EntryKind previousEntry,
            int previousEndLine,
            BodyDeclaration<?> previousMember,
            BodyDeclaration<?> currentMember
    ) {
        switch (previousEntry) {
            case NONE -> contents.add(memberBlockOpeningBreak(owner));
            case VALID_MEMBER -> contents.add(memberSeparator(owner, previousMember, currentMember));
            case ORPHAN_COMMENT -> contents.add(
                sourceLineSeparator(
                    previousEndLine,
                    CommentIndex.beginLine(currentMember, Integer.MAX_VALUE)
                )
            );
            case OPENING_BRACE_COMMENT, RAW_GAP_WITH_TRAILING_BREAK -> contents.add(Doc.HARD_LINE);
            case RAW_GAP -> {
                // Raw source already owns the separation before this formatted member.
            }
        }
    }

    private void appendSeparatorBeforeRecoveredOrphanComment(
            List<Doc> contents,
            Node owner,
            EntryKind previousEntry,
            int previousEndLine,
            JavaCommentTrivia comment
    ) {
        switch (previousEntry) {
            case NONE -> contents.add(memberBlockOpeningBreak(owner));
            case VALID_MEMBER, ORPHAN_COMMENT -> contents.add(
                sourceLineSeparator(
                    previousEndLine,
                    comment.beginLine(Integer.MAX_VALUE)
                )
            );
            case OPENING_BRACE_COMMENT, RAW_GAP_WITH_TRAILING_BREAK -> contents.add(Doc.HARD_LINE);
            case RAW_GAP -> {
                // Raw source already owns the separation before this orphan comment.
            }
        }
    }

    private Optional<RecoveredListPlanner.Plan<BodyDeclaration<?>>> recoveryPlan(
            Node owner,
            NodeList<BodyDeclaration<?>> members
    ) {
        if (!recoverParseProblems || !hasImmediateMemberListRecoveryProblem(owner, members)) {
            return Optional.empty();
        }
        RecoveredListPlanner.Plan<BodyDeclaration<?>> plan = recoveredListPlanner.plan(
            owner,
            requireMemberBlockInteriorRegion(owner),
            members,
            member -> member.getParsed() == Node.Parsedness.PARSED
        );
        if (!plan.isSafe()) {
            throw memberDeclarationListRecoveryFailure(plan.unsafe().orElseThrow().reason());
        }
        return Optional.of(plan);
    }

    private static boolean hasImmediateMemberListRecoveryProblem(Node owner, NodeList<BodyDeclaration<?>> members) {
        return owner.getParsed() != Node.Parsedness.PARSED
            || members.stream().anyMatch(member -> member.getParsed() != Node.Parsedness.PARSED);
    }

    private SourceRegion withoutOpeningBraceTrailingComment(
            SourceRegion region,
            Optional<SourceRegion> openingBraceTrailingCommentRegion
    ) {
        if (openingBraceTrailingCommentRegion.isEmpty()) {
            return region;
        }
        SourceRegion commentRegion = openingBraceTrailingCommentRegion.orElseThrow();
        if (!RecoveredRawGapPrinter.contains(region, commentRegion)) {
            return region;
        }
        String beforeComment = sourceText.slice(sourceText.region(region.beginOffset(), commentRegion.beginOffset()));
        if (!beforeComment.isBlank()) {
            return region;
        }
        return sourceText.region(commentRegion.endOffset(), region.endOffset());
    }

    private List<JavaCommentTrivia> orphanCommentsOutsideRawRegions(Node owner, List<SourceRegion> rawRegions) {
        return commentPlacement.orphanCommentsInSourceOrder(owner)
                .stream()
                .filter(comment -> comment.comment().getRange().isEmpty()
                        || rawRegions
                                .stream()
                                .noneMatch(region -> RecoveredRawGapPrinter.contains(
                                        region,
                                        sourceText.region(comment.comment().getRange().orElseThrow())
                                ))
                )
                .toList();
    }

    private SourceRegion requireMemberBlockInteriorRegion(Node owner) {
        try {
            return brace.memberBlockInteriorRegion(owner);
        } catch (IllegalArgumentException exception) {
            throw memberDeclarationListRecoveryFailure(exception.getMessage(), exception);
        }
    }

    private static boolean hasRawGap(RecoveredListPlanner.Plan<BodyDeclaration<?>> plan) {
        return plan.entries().stream().anyMatch(RecoveredListPlanner.RawGap.class::isInstance);
    }

    private static FormatterException memberDeclarationListRecoveryFailure(String reason) {
        return new FormatterException(MEMBER_DECLARATION_LIST_RECOVERY_FAILURE + reason);
    }

    private static FormatterException memberDeclarationListRecoveryFailure(String reason, Throwable cause) {
        return new FormatterException(MEMBER_DECLARATION_LIST_RECOVERY_FAILURE + reason, cause);
    }

    /**
     * Preserves whether source trivia had a blank physical line between two printed items.
     */
    private Doc sourceLineSeparator(int previousEndLine, int currentBeginLine) {
        return currentBeginLine > previousEndLine + 1 ? Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE) : Doc.HARD_LINE;
    }

    /**
     * Chooses the blank-line policy between two adjacent member declarations.
     *
     * <p>Formatter pragmas force a single hard line so the pragma comment stays adjacent to the declaration it controls.
     * A blank source line between adjacent members is otherwise preserved. Interfaces keep adjacent unannotated abstract
     * methods tight, adjacent fields are separated as standalone declarations, and all other member pairs use a blank
     * line.
     */
    private Doc memberSeparator(Node owner, BodyDeclaration<?> previous, BodyDeclaration<?> current) {
        if (hasPragma.test(previous) || hasPragma.test(current)) {
            return Doc.HARD_LINE;
        }
        if (hasSourceBlankLineBetween(previous, current)) {
            return Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE);
        }
        // Interface methods without bodies read as a signature list unless annotations make each item standalone.
        if (
            owner instanceof ClassOrInterfaceDeclaration declaration
            && declaration.isInterface()
            && previous instanceof MethodDeclaration previousMethod
            && current instanceof MethodDeclaration currentMethod
            && previousMethod.getBody().isEmpty()
            && currentMethod.getBody().isEmpty()
            && !hasDeclarationAnnotations.test(previous)
            && !hasDeclarationAnnotations.test(current)
        ) {
            return Doc.HARD_LINE;
        }
        if (previous instanceof FieldDeclaration && current instanceof FieldDeclaration) {
            return Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE);
        }
        // Mixed member kinds and non-field declarations keep the formatter's normal blank-line separation.
        return Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE);
    }

    private boolean hasSourceBlankLineBetween(BodyDeclaration<?> previous, BodyDeclaration<?> current) {
        // SourceShapePolicy.hadBlankLineBetween already returns false when either node lacks a source range, so the
        // missing-range case needs no separate guard here; an absent range and a present-but-no-gap range both mean
        // "the author did not leave a blank line".
        return sourceShapePolicy.hadBlankLineBetween(previous, current);
    }

    private enum EntryKind {
        /**
         * No recovered member-block content has been emitted yet, so the next entry opens the block body.
         */
        NONE,

        /**
         * The existing opening-brace line-comment rule emitted a comment before the member declaration list.
         */
        OPENING_BRACE_COMMENT,

        /**
         * The previous emitted entry was an orphan comment outside all recovered raw member gaps.
         */
        ORPHAN_COMMENT,

        /**
         * The previous emitted entry was a parsed member declaration rendered by the normal declaration formatter.
         */
        VALID_MEMBER,

        /**
         * The previous emitted entry was a raw recovered member gap that kept its trailing line break in the raw source.
         */
        RAW_GAP,

        /**
         * The previous emitted entry was a raw recovered member gap whose trailing line break moved to formatter docs.
         */
        RAW_GAP_WITH_TRAILING_BREAK,
    }
}
