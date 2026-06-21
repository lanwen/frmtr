package dev.lanwen.frmtr.java;

import com.github.javaparser.GeneratedJavaParserConstants;
import com.github.javaparser.JavaToken;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
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
        Doc openingBraceTrailingComment = openingBraceTrailingLineComment(owner);
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
     * Recovers a line comment written after the opening brace of a member block.
     *
     * <p>This raw-source path handles the layout case where the comment belongs to the brace line rather than to a
     * declaration. Comments after the first newline continue through normal orphan-comment sequencing.
     */
    private Doc openingBraceTrailingLineComment(Node node) {
        return openingBraceTrailingLineCommentTrivia(node)
                .map(comments::comment)
                .filter(doc -> doc != Doc.EMPTY)
                .orElse(Doc.EMPTY);
    }

    /**
     * Chooses how much vertical space follows the opening brace before the first member.
     *
     * <p>Records and interfaces are commonly written as compact declaration lists, so they skip the extra blank line
     * that class, enum, and annotation bodies keep for the formatter's member-block style.
     */
    private Doc memberBlockOpeningBreak(Node owner) {
        if (
            owner instanceof RecordDeclaration
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
        return Doc.concat(
            commentInterleaver.interleave(
                members,
                commentPlacement.orphanCommentsInSourceOrder(owner),
                (previous, current, index) -> Optional.of(memberDocs.get(index)),
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
        Optional<SourceRegion> openingBraceTrailingCommentRegion = openingBraceTrailingLineCommentRegion(owner);
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

    private Optional<SourceRegion> openingBraceTrailingLineCommentRegion(Node owner) {
        return openingBraceTrailingLineCommentTrivia(owner)
                .flatMap(comment -> comment.comment().getRange())
                .map(sourceText::region);
    }

    private Optional<JavaCommentTrivia> openingBraceTrailingLineCommentTrivia(Node owner) {
        SourceRegion interior;
        try {
            interior = memberBlockInteriorRegion(owner);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        String raw = sourceText.slice(interior);
        int lineEnd = firstLineEnd(raw);
        int lineEndOffset = lineEnd < 0 ? interior.endOffset() : interior.beginOffset() + lineEnd;
        // A comment only trails the opening brace when nothing else in the block precedes it. When whitespace is
        // collapsed the opening brace and the first member can share a physical line, so the first line break is no
        // longer a reliable boundary: a member's own trailing comment (e.g. an enum constant's `// note`) would sit on
        // the same line as the brace and be mistaken for a brace comment, stealing it from the member that owns it.
        // Cap the boundary at the first member's source start so only comments written before any member qualify.
        int firstLineEndOffset = Math.min(lineEndOffset, firstMemberBeginOffset(owner, interior).orElse(lineEndOffset));
        return commentPlacement.containedComments(owner)
                .stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.comment()
                            .getRange()
                            .map(sourceText::region)
                            .filter(region -> RecoveredRawGapPrinter.contains(interior, region))
                            .filter(region -> region.beginLine() == interior.beginLine())
                            .filter(region -> region.beginOffset() < firstLineEndOffset)
                            .isPresent()
                )
                .findFirst();
    }

    /**
     * Finds the source begin offset of the first content child written inside {@code owner}'s brace-delimited body.
     *
     * <p>Used to bound the opening-brace trailing-comment scan: a comment can only trail the opening brace if it is
     * written before any member or enum constant. Only child nodes that begin inside {@code interior} (after the opening
     * brace) count — the owner's own name, type parameters, and {@code extends}/{@code implements} clauses sit before the
     * brace and must not pull the boundary back ahead of a legitimate brace-line comment. Comments are skipped because
     * they are exactly what the scan is trying to classify; only non-comment members mark where body content starts.
     */
    private Optional<Integer> firstMemberBeginOffset(Node owner, SourceRegion interior) {
        return owner.getChildNodes()
                .stream()
                .filter(child -> !(child instanceof com.github.javaparser.ast.comments.Comment))
                .flatMap(child -> child.getRange().stream())
                .map(range -> sourceText.region(range).beginOffset())
                .filter(offset -> offset >= interior.beginOffset())
                .min(Integer::compareTo);
    }

    private SourceRegion requireMemberBlockInteriorRegion(Node owner) {
        try {
            return memberBlockInteriorRegion(owner);
        } catch (IllegalArgumentException exception) {
            throw memberDeclarationListRecoveryFailure(exception.getMessage(), exception);
        }
    }

    private SourceRegion memberBlockInteriorRegion(Node owner) {
        List<JavaToken> tokens = owner.getTokenRange()
                .map(tokenRange -> {
                    List<JavaToken> collected = new ArrayList<>();
                    tokenRange.forEach(collected::add);
                    return collected;
                })
                .orElseThrow(() -> new IllegalArgumentException("member block owner is missing a token range"));
        JavaToken closingBrace = null;
        JavaToken openingBrace = null;
        int depth = 0;
        for (int i = tokens.size() - 1; i >= 0; i--) {
            JavaToken token = tokens.get(i);
            if (token.getKind() == GeneratedJavaParserConstants.RBRACE) {
                if (closingBrace == null) {
                    closingBrace = token;
                }
                depth++;
                continue;
            }
            if (token.getKind() == GeneratedJavaParserConstants.LBRACE && closingBrace != null) {
                depth--;
                if (depth == 0) {
                    openingBrace = token;
                    break;
                }
            }
        }
        if (openingBrace == null || closingBrace == null) {
            throw new IllegalArgumentException("member block source range must contain matching braces");
        }
        SourceRegion openingRegion = tokenRegion(openingBrace, "opening brace");
        SourceRegion closingRegion = tokenRegion(closingBrace, "closing brace");
        if (closingRegion.beginOffset() < openingRegion.endOffset()) {
            throw new IllegalArgumentException("member block braces are not ordered");
        }
        return sourceText.region(openingRegion.endOffset(), closingRegion.beginOffset());
    }

    private SourceRegion tokenRegion(JavaToken token, String description) {
        return token.getRange()
                .map(sourceText::region)
                .orElseThrow(
                    () -> new IllegalArgumentException("member block " + description + " is missing a source range")
                );
    }

    private static boolean hasRawGap(RecoveredListPlanner.Plan<BodyDeclaration<?>> plan) {
        return plan.entries().stream().anyMatch(RecoveredListPlanner.RawGap.class::isInstance);
    }

    private static int firstLineEnd(String raw) {
        int lf = raw.indexOf('\n');
        int cr = raw.indexOf('\r');
        if (lf < 0) {
            return cr;
        }
        if (cr < 0) {
            return lf;
        }
        return Math.min(lf, cr);
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
        Optional<Boolean> hasSourceBlankLineBetween = hasSourceBlankLineBetween(previous, current);
        if (hasSourceBlankLineBetween.orElse(false)) {
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

    private Optional<Boolean> hasSourceBlankLineBetween(BodyDeclaration<?> previous, BodyDeclaration<?> current) {
        if (previous.getRange().isEmpty() || current.getRange().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(sourceShapePolicy.hadBlankLineBetween(previous, current));
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
