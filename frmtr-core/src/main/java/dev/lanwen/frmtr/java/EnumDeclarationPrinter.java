package dev.lanwen.frmtr.java;

import com.github.javaparser.GeneratedJavaParserConstants;
import com.github.javaparser.JavaToken;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Prints enum declarations after the surrounding body-dispatch decision has already selected the enum branch.
 *
 * <p>This helper owns the enum-specific declaration tree: header wrapping, entry sequencing, source blank lines between
 * entries, explicit semicolon recovery, and body orphan-comment placement. Per-constant rendering — annotations,
 * arguments, an explicit class body, and the leading / trailing comment slots — is delegated to {@link
 * EnumConstantLayout}. It intentionally leaves member declaration rendering, expression formatting, and type-clause
 * formatting with {@link JavaPrinter}; callers provide those decisions as callbacks so enum bodies can keep the same
 * sequencing as other member blocks.
 *
 * <p>Representative fixture pairs live at
 * {@code frmtr-core/src/test/resources/format/enum-declaration-layout/input.java} and
 * {@code frmtr-core/src/test/resources/format/enum-declaration-layout/frmtr-default.output.java}; enum constants with
 * lambda arguments are also covered by
 * {@code frmtr-core/src/test/resources/format/block-lambda-arrow-parens-avoid/input.java}.
 */
final class EnumDeclarationPrinter {

    private static final String ENUM_CONSTANT_LIST_RECOVERY_FAILURE =
        "Unable to recover Java parse error inside enum constant list: ";

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    private final EnumConstantComments enumConstantComments;

    private final SourceShapePolicy sourceShapePolicy;

    private final RawSource rawSource;

    private final FormatterOptions options;

    private final SourceText sourceText;

    private final RecoveredListPlanner recoveredListPlanner;

    private final RecoveredRawGapPrinter rawGaps;

    private final boolean recoverParseProblems;

    private final Function<NodeWithAnnotations<?>, Doc> annotations;

    private final Function<NodeWithModifiers<?>, String> modifiers;

    private final Function<NodeList<ClassOrInterfaceType>, Optional<Doc>> brokenImplementsTypes;

    private final Function<NodeList<ClassOrInterfaceType>, Optional<Doc>> inlineImplementsTypes;

    private final Function<BodyDeclaration<?>, Doc> memberRenderer;

    private final EnumConstantLayout enumConstantLayout;

    private final SourceOrderedCommentInterleaver<BodyDeclaration<?>> commentInterleaver;

    /**
     * Names the previous recovered enum-body item so raw gaps and formatted constants do not duplicate separators.
     */
    private enum EntryKind {
        /** No recovered enum constant-list content has been emitted yet. */
        NONE,

        /** The previous recovered enum constant-list item was a parsed enum constant rendered structurally. */
        VALID_ENTRY,

        /** The previous recovered enum constant-list item was a raw source gap that owns the following separator. */
        RAW_GAP,

        /** The previous recovered enum constant-list item was a raw source gap whose trailing break moved to formatter docs. */
        RAW_GAP_WITH_TRAILING_BREAK,
    }

    EnumDeclarationPrinter(
            JavaFormatContext context,
            Function<NodeWithAnnotations<?>, Doc> annotations,
            Function<NodeWithModifiers<?>, String> modifiers,
            Function<NodeList<ClassOrInterfaceType>, Optional<Doc>> brokenImplementsTypes,
            Function<NodeList<ClassOrInterfaceType>, Optional<Doc>> inlineImplementsTypes,
            Function<List<? extends Node>, String> compactJoin,
            Function<Expression, Doc> expression,
            ToIntFunction<String> currentIndentedWidth,
            BiFunction<NodeList<BodyDeclaration<?>>, Node, Doc> memberBlockRenderer,
            Function<BodyDeclaration<?>, Doc> memberRenderer
    ) {
        this.comments = context.comments;
        this.commentPlacement = context.commentPlacementPolicy;
        this.commentInterleaver = new SourceOrderedCommentInterleaver<>(context.comments);
        this.enumConstantComments = new EnumConstantComments(context.comments, context.commentPlacementPolicy);
        this.sourceShapePolicy = context.sourceShapePolicy;
        this.rawSource = context.rawSource;
        this.options = context.options;
        this.sourceText = context.sourceText;
        this.recoveredListPlanner = context.recoveredListPlanner;
        this.rawGaps = new RecoveredRawGapPrinter(context, EnumDeclarationPrinter::enumConstantListRecoveryFailure);
        this.recoverParseProblems = context.recoverParseProblems;
        this.annotations = annotations;
        this.modifiers = modifiers;
        this.brokenImplementsTypes = brokenImplementsTypes;
        this.inlineImplementsTypes = inlineImplementsTypes;
        this.memberRenderer = memberRenderer;
        this.enumConstantLayout = new EnumConstantLayout(
            this.enumConstantComments,
            this.sourceText,
            this.options,
            annotations,
            compactJoin,
            expression,
            currentIndentedWidth,
            memberBlockRenderer
        );
    }

    /**
     * Prints the full enum declaration while delegating ordinary member declarations back to the caller.
     */
    Doc enumDeclaration(EnumDeclaration declaration) {
        List<Doc> header = new ArrayList<>();
        header.add(annotations.apply(declaration));
        header.add(Doc.text(modifiers.apply(declaration)));
        header.add(Doc.text("enum " + declaration.getNameAsString()));
        header.add(enumImplementsAndOpener(declaration));
        header.add(enumBlock(declaration));
        return Doc.concat(header);
    }

    /**
     * Ranks the flat vs. broken {@code implements} clause at the true rendered column, reserving room for the block
     * opener that follows on the same line so the ranking sees the space actually left at the enum's real nesting depth.
     */
    private Doc enumImplementsAndOpener(EnumDeclaration declaration) {
        if (declaration.getImplementedTypes().isEmpty()) {
            return Doc.text(" ");
        }
        Doc flatCandidate = Doc.concat(
            inlineImplementsTypes.apply(declaration.getImplementedTypes()).orElse(Doc.EMPTY),
            Doc.text(" ")
        );
        Doc brokenCandidate = Doc.concat(
            brokenImplementsTypes.apply(declaration.getImplementedTypes()).orElse(Doc.EMPTY),
            enumBodyOpenBreak(declaration)
        );
        return Doc.reserving(
            Doc.conditionalGroup(List.of(flatCandidate, brokenCandidate)),
            emptyBlockOpener(declaration) ? "{}".length() : "{".length()
        );
    }

    /**
     * Keeps broken headers with empty enum bodies on one physical line before {@code {}}.
     */
    private Doc enumBodyOpenBreak(EnumDeclaration declaration) {
        return emptyBlockOpener(declaration) ? Doc.text(" ") : Doc.HARD_LINE;
    }

    /**
     * Whether the enum body has nothing to print, so a broken header can still collapse its brace onto one line.
     */
    private boolean emptyBlockOpener(EnumDeclaration declaration) {
        return declaration.getEntries().isEmpty()
            && declaration.getMembers().isEmpty()
            && declaration.getOrphanComments().isEmpty();
    }

    /**
     * Builds the enum body by keeping constants, body orphan comments, and ordinary members in their current order
     * bands.
     */
    private Doc enumBlock(EnumDeclaration declaration) {
        Optional<EnumEntryList> entries = enumEntryList(declaration);
        List<BodyDeclaration<?>> memberNodes = declaration.getMembers();
        List<Doc> memberDocs = memberNodes.stream().map(memberRenderer).toList();
        boolean hasMembers = !memberDocs.isEmpty();
        // Body comments render as an ORPHAN band only when there are no members; with members present the interleaver
        // owns them under INTERLEAVED, so materializing the ORPHAN docs here too would double-claim and drop them.
        List<Doc> bandComments = hasMembers ? List.of() : enumBodyComments(declaration);
        boolean hasBodyComments = hasMembers ? !bodyOrphanCommentTrivia(declaration).isEmpty() : !bandComments.isEmpty();
        if (entries.isEmpty() && !hasMembers && !hasBodyComments) {
            return Doc.text("{}");
        }
        List<Doc> contents = new ArrayList<>();
        if (entries.isPresent()) {
            EnumEntryList entryList = entries.orElseThrow();
            contents.add(entryList.doc());
            // The list terminator is emitted unconditionally (the last constant's trailing comment is a deferred line
            // suffix that flushes after it at the next break), except when a recovered raw island already supplied it.
            if (!entryList.rawOwnsTrailingComma() || hasMembers || hasBodyComments) {
                contents.add(Doc.text(!hasMembers && !hasBodyComments ? "," : ";"));
            }
        } else if (hasMembers && enumHasExplicitSemicolon(declaration)) {
            contents.add(Doc.text(";"));
        }
        if (!hasMembers) {
            if (!bandComments.isEmpty()) {
                if (!contents.isEmpty()) {
                    contents.add(Doc.HARD_LINE);
                    contents.add(Doc.HARD_LINE);
                }
                contents.add(Doc.join(Doc.HARD_LINE, bandComments));
            }
        } else {
            // The constant list (or brace) is separated from the body section by a blank line; members and body orphan
            // comments then interleave by source line, so a comment run leading a later member is not hoisted above the
            // first member the way a single pre-member band would place it.
            if (!contents.isEmpty()) {
                contents.add(Doc.HARD_LINE);
                contents.add(Doc.HARD_LINE);
            }
            contents.addAll(interleavedEnumBody(declaration, memberNodes, memberDocs));
        }
        return Doc.concat(
            Doc.text("{"),
            Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.concat(contents))),
            Doc.HARD_LINE,
            Doc.text("}")
        );
    }

    /**
     * Interleaves the enum's ordinary members with its body orphan comments by source line.
     *
     * <p>Members keep the blank-line separation the enum body applies between declarations; comments restore their
     * source-order position, so a comment run sitting directly above a later member is not hoisted above the first one.
     */
    private List<Doc> interleavedEnumBody(
            EnumDeclaration declaration,
            List<BodyDeclaration<?>> memberNodes,
            List<Doc> memberDocs
    ) {
        return commentInterleaver.interleave(
            declaration,
            memberNodes,
            bodyOrphanCommentTrivia(declaration),
            (previous, current, index) -> Optional.of(memberDocs.get(index)),
            new SourceOrderedCommentInterleaver.Spacing<>() {
                @Override
                public int beginLine(BodyDeclaration<?> member) {
                    return enumMemberBeginLine(member);
                }

                @Override
                public int endLine(BodyDeclaration<?> member) {
                    return CommentIndex.endLine(member, Integer.MAX_VALUE);
                }

                @Override
                public Doc separatorBeforeSibling(
                        SourceOrderedCommentInterleaver.PreviousEntry<BodyDeclaration<?>> previous,
                        BodyDeclaration<?> member
                ) {
                    // Two adjacent members always keep a blank line; a member following a comment tracks the source gap.
                    if (previous.kind() == SourceOrderedCommentInterleaver.EntryKind.SIBLING) {
                        return Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE);
                    }
                    return sourceLineSeparator(previous.endLine(), beginLine(member));
                }

                @Override
                public Doc separatorBeforeComment(
                        SourceOrderedCommentInterleaver.PreviousEntry<BodyDeclaration<?>> previous,
                        JavaCommentTrivia comment
                ) {
                    return sourceLineSeparator(previous.endLine(), comment.beginLine(Integer.MAX_VALUE));
                }
            }
        );
    }

    /**
     * The member's source begin line, pulled up to a leading comment JavaParser attached directly above it.
     */
    private int enumMemberBeginLine(BodyDeclaration<?> member) {
        int declarationBeginLine = CommentIndex.beginLine(member, Integer.MAX_VALUE);
        return commentPlacement.leadingComment(member)
                .map(comment -> comment.beginLine(declarationBeginLine))
                .filter(commentBeginLine -> commentBeginLine < declarationBeginLine)
                .orElse(declarationBeginLine);
    }

    /**
     * The body orphan comments eligible for interleaving: every declaration orphan except one trailing a constant on
     * its own end line, which belongs to the constant list rather than the member body.
     */
    private List<JavaCommentTrivia> bodyOrphanCommentTrivia(EnumDeclaration declaration) {
        return commentPlacement.orphanCommentsInSourceOrder(declaration).stream()
                .filter(comment -> declaration.getEntries().stream().noneMatch(
                    entry -> CommentIndex.startsOnEndLine(entry, comment.comment())
                ))
                .toList();
    }

    /**
     * Preserves whether source trivia left a blank physical line between two printed body items.
     */
    private Doc sourceLineSeparator(int previousEndLine, int currentBeginLine) {
        return currentBeginLine > previousEndLine + 1 ? Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE) : Doc.HARD_LINE;
    }

    private Optional<EnumEntryList> enumEntryList(EnumDeclaration declaration) {
        if (declaration.getEntries().isEmpty()) {
            return Optional.empty();
        }
        Optional<RecoveredListPlanner.Plan<EnumConstantDeclaration>> recoveryPlan = recoveryPlan(declaration);
        if (recoveryPlan.isPresent() && hasRawGap(recoveryPlan.orElseThrow())) {
            return Optional.of(recoveredEnumEntryList(declaration, recoveryPlan.orElseThrow()));
        }
        return Optional.of(formattedEnumEntryList(declaration));
    }

    /**
     * Builds the formatter-owned enum entry list.
     *
     * <p>Each constant defers its trailing comment to a {@link Doc#lineSuffix(Doc)} (see {@link
     * EnumConstantComments.Tail#suffix()}) that flushes at the next break, so the inter-constant separator and the list
     * terminator (added by {@link #enumBlock}) always print unconditionally before the comment — removing the
     * comma/comment coupling and the need to hoist the last constant's comment past the terminator.
     */
    private EnumEntryList formattedEnumEntryList(EnumDeclaration declaration) {
        List<EnumConstantDeclaration> entries = declaration.getEntries();
        List<EnumConstantComments.Tail> tails = enumConstantComments.tails(declaration);

        List<Doc> entryDocs = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            EnumConstantDeclaration entry = entries.get(i);
            Doc leading = i == 0
                ? enumConstantComments.firstConstantLeading(declaration, entry)
                : enumConstantComments.leading(entry);
            entryDocs.add(enumConstantLayout.enumConstant(leading, entry, tails.get(i)));
        }
        return new EnumEntryList(enumEntryList(declaration, entryDocs), false);
    }

    /**
     * Interleaves printed constants with source-sensitive separators.
     *
     * <p>The inter-constant separator is always an unconditional comma: each constant defers its trailing comment to a
     * line suffix, so the comma prints before the comment flushes and can never be swallowed by a {@code //} comment.
     */
    private Doc enumEntryList(EnumDeclaration declaration, List<Doc> entries) {
        List<Doc> docs = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                EnumConstantDeclaration previous = declaration.getEntries().get(i - 1);
                EnumConstantDeclaration current = declaration.getEntries().get(i);
                List<JavaCommentTrivia> gapComments = commentPlacement.standaloneLineCommentsBetween(
                    declaration,
                    previous,
                    current
                );
                docs.add(enumEntrySeparator(previous, current, gapComments));
                docs.add(enumCommentLines(gapComments));
            }
            docs.add(entries.get(i));
        }
        return Doc.concat(docs);
    }

    /**
     * Emits a recovered enum constant list while keeping enum braces and body members formatter-owned.
     *
     * <p>Raw gaps are limited to the enum-constant-list region before the enum body semicolon or closing brace. Safe
     * constant siblings still use ordinary enum-constant rendering, and unsafe constants preserve their original source
     * as raw islands.
     */
    private EnumEntryList recoveredEnumEntryList(
            EnumDeclaration declaration,
            RecoveredListPlanner.Plan<EnumConstantDeclaration> plan
    ) {
        List<RecoveredRawGapPrinter.RawGapRegion> rawGapRegions = rawGaps.rawGapRegions(plan);
        rawGaps.requireRecoverableRawRegions(declaration, rawGapRegions);

        List<Doc> docs = new ArrayList<>();
        EntryKind previousEntry = EntryKind.NONE;
        EnumConstantDeclaration previousValid = null;
        boolean rawOwnsTrailingComma = false;
        int rawGapIndex = 0;
        for (int i = 0; i < plan.entries().size(); i++) {
            RecoveredListPlanner.Entry<EnumConstantDeclaration> entry = plan.entries().get(i);
            switch (entry) {
                case RecoveredListPlanner.ValidSibling<?> valid -> {
                    EnumConstantDeclaration currentEntry = (EnumConstantDeclaration) valid.sibling();
                    appendSeparatorBeforeRecoveredEnumConstant(
                        docs,
                        declaration,
                        previousValid,
                        currentEntry,
                        previousEntry
                    );
                    docs.add(
                        enumConstantLayout.enumConstant(
                            declaration,
                            currentEntry,
                            nextValidEnumConstant(plan, i).orElse(null)
                        )
                    );
                    previousValid = currentEntry;
                    previousEntry = EntryKind.VALID_ENTRY;
                }
                case RecoveredListPlanner.RawGap<?> ignored -> {
                    RecoveredRawGapPrinter.RawGapRegion rawRegion = rawGapRegions.get(rawGapIndex++);
                    if (rawRegion.region().beginOffset() < rawRegion.region().endOffset()) {
                        docs.add(rawGaps.raw(declaration, rawRegion, "enumConstantList"));
                        rawOwnsTrailingComma = i == plan.entries().size() - 1 && rawRegionEndsWithComma(
                            rawRegion.region()
                        );
                    }
                    previousValid = null;
                    previousEntry = rawRegion.trailingBreakReplaced()
                        ? EntryKind.RAW_GAP_WITH_TRAILING_BREAK
                        : EntryKind.RAW_GAP;
                }
            }
        }
        return new EnumEntryList(Doc.concat(docs), rawOwnsTrailingComma);
    }

    private Optional<EnumConstantDeclaration> nextValidEnumConstant(
            RecoveredListPlanner.Plan<EnumConstantDeclaration> plan,
            int entryIndex
    ) {
        if (entryIndex + 1 >= plan.entries().size()) {
            return Optional.empty();
        }
        return switch (plan.entries().get(entryIndex + 1)) {
            case RecoveredListPlanner.ValidSibling<?> valid -> Optional.of((EnumConstantDeclaration) valid.sibling());
            case RecoveredListPlanner.RawGap<?> ignored -> Optional.empty();
        };
    }

    private void appendSeparatorBeforeRecoveredEnumConstant(
            List<Doc> docs,
            EnumDeclaration declaration,
            EnumConstantDeclaration previousValid,
            EnumConstantDeclaration currentEntry,
            EntryKind previousEntry
    ) {
        if (previousValid != null) {
            List<JavaCommentTrivia> gapComments = commentPlacement.standaloneLineCommentsBetween(
                declaration,
                previousValid,
                currentEntry
            );
            List<RecoveredSourceLineComment> recoveredGapComments = gapComments.isEmpty()
                ? recoveredSourceLineCommentsBetween(previousValid, currentEntry)
                : List.of();
            docs.add(
                enumEntrySeparator(
                    previousValid,
                    currentEntry,
                    gapComments,
                    recoveredGapComments.stream().mapToInt(RecoveredSourceLineComment::line).min()
                )
            );
            docs.add(enumCommentLines(gapComments));
            docs.add(recoveredEnumCommentLines(recoveredGapComments));
            return;
        }
        if (previousEntry == EntryKind.RAW_GAP_WITH_TRAILING_BREAK) {
            docs.add(Doc.HARD_LINE);
        }
    }

    private Optional<RecoveredListPlanner.Plan<EnumConstantDeclaration>> recoveryPlan(EnumDeclaration declaration) {
        if (!recoverParseProblems || !hasRecoverableEnumConstantListProblem(declaration)) {
            return Optional.empty();
        }
        RecoveredListPlanner.Plan<EnumConstantDeclaration> plan = recoveredListPlanner.plan(
            declaration,
            requireEnumConstantListRegion(declaration),
            declaration.getEntries(),
            entry -> entry.getParsed() == Node.Parsedness.PARSED
        );
        if (!plan.isSafe()) {
            throw enumConstantListRecoveryFailure(plan.unsafe().orElseThrow().reason());
        }
        return Optional.of(plan);
    }

    /**
     * Preserves intentional blank lines between neighboring enum constants.
     *
     * <p>Leading comments attached to the current constant count as the current entry's source start so a commented
     * constant does not accidentally erase a blank separator that belongs before the comment.
     */
    private Doc enumEntrySeparator(
            EnumConstantDeclaration previous,
            EnumConstantDeclaration current,
            List<JavaCommentTrivia> gapComments
    ) {
        return enumEntrySeparator(previous, current, gapComments, OptionalInt.empty());
    }

    private Doc enumEntrySeparator(
            EnumConstantDeclaration previous,
            EnumConstantDeclaration current,
            List<JavaCommentTrivia> gapComments,
            OptionalInt recoveredGapBeginLine
    ) {
        boolean hasBlankLineBetween = current.getRange()
                .map(currentRange -> sourceShapePolicy.hadBlankLineBefore(
                    previous,
                    enumEntryBeginLine(
                        previous,
                        current,
                        currentRange.begin.line,
                        gapComments,
                        recoveredGapBeginLine
                    )
                ))
                .orElse(false);
        return hasBlankLineBetween
            ? Doc.concat(Doc.text(","), Doc.HARD_LINE, Doc.HARD_LINE)
            : Doc.concat(Doc.text(","), Doc.HARD_LINE);
    }

    /**
     * Uses gap comments as the next entry's source start when they visually belong between enum constants.
     *
     * <p>JavaParser can leave a line comment before a commented-out constant as an enum-body orphan. Treating that
     * orphan as the first gap line keeps blank-line preservation local to the constant list instead of moving the
     * comment down to body members.
     */
    private int enumEntryBeginLine(
            EnumConstantDeclaration previous,
            EnumConstantDeclaration current,
            int fallback,
            List<JavaCommentTrivia> gapComments,
            OptionalInt recoveredGapBeginLine
    ) {
        int firstTriviaLine = gapComments.stream()
                .filter(comment -> !comment.startsOnEndLine(previous))
                .mapToInt(comment -> comment.beginLine(Integer.MAX_VALUE))
                .min()
                .orElse(Integer.MAX_VALUE);
        int firstRecoveredLine = recoveredGapBeginLine.orElse(Integer.MAX_VALUE);
        int currentLine = enumEntryBeginLine(current, fallback);
        return Math.min(Math.min(firstTriviaLine, firstRecoveredLine), currentLine);
    }

    /**
     * Finds the first source line that belongs to an enum constant, including its attached leading comment.
     */
    private int enumEntryBeginLine(EnumConstantDeclaration declaration, int fallback) {
        return declaration.getComment()
                .flatMap(Node::getRange)
                .map(range -> range.begin.line)
                .orElse(fallback);
    }

    /**
     * Renders claimed enum-constant gap comments as standalone lines before the next constant.
     */
    private Doc enumCommentLines(List<JavaCommentTrivia> comments) {
        List<Doc> commentDocs = comments.stream()
                .map(this.comments::comment)
                .filter(doc -> doc != Doc.EMPTY)
                .toList();
        return commentDocs.isEmpty()
            ? Doc.EMPTY
            : Doc.concat(Doc.join(Doc.HARD_LINE, commentDocs), Doc.HARD_LINE);
    }

    /**
     * Finds standalone line comments between recovered enum constants when JavaParser dropped them from the comment map.
     */
    private List<RecoveredSourceLineComment> recoveredSourceLineCommentsBetween(
            EnumConstantDeclaration previous,
            EnumConstantDeclaration current
    ) {
        return previous.getRange()
                .flatMap(previousRange -> current.getRange().map(
                        currentRange -> recoveredSourceLineComments(
                            sourceText.sliceBetween(previousRange, currentRange),
                            previousRange.end.line
                        )
                ))
                .orElseGet(List::of);
    }

    private List<RecoveredSourceLineComment> recoveredSourceLineComments(String rawGap, int firstLine) {
        String[] lines = rawGap.split("\\R", -1);
        List<RecoveredSourceLineComment> comments = new ArrayList<>();
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].strip();
            if (line.startsWith("//")) {
                comments.add(new RecoveredSourceLineComment(firstLine + index, line));
            }
        }
        return comments;
    }

    private Doc recoveredEnumCommentLines(List<RecoveredSourceLineComment> comments) {
        if (comments.isEmpty()) {
            return Doc.EMPTY;
        }
        return Doc.concat(
            Doc.join(
                Doc.HARD_LINE,
                comments.stream().map(comment -> Doc.text(comment.text())).toList()
            ),
            Doc.HARD_LINE
        );
    }

    private SourceRegion requireEnumConstantListRegion(EnumDeclaration declaration) {
        try {
            return enumConstantListRegion(declaration);
        } catch (IllegalArgumentException exception) {
            throw enumConstantListRecoveryFailure(exception.getMessage(), exception);
        }
    }

    private SourceRegion enumConstantListRegion(EnumDeclaration declaration) {
        List<JavaToken> tokens = tokens(declaration);
        int openingBraceIndex = enumBodyOpeningBraceIndex(tokens);
        JavaToken openingBrace = tokens.get(openingBraceIndex);
        JavaToken boundaryToken = enumConstantListBoundary(tokens, openingBraceIndex);
        SourceRegion openingRegion = tokenRegion(openingBrace, "opening brace");
        SourceRegion boundaryRegion = tokenRegion(boundaryToken, "constant-list boundary");
        if (boundaryRegion.beginOffset() < openingRegion.endOffset()) {
            throw new IllegalArgumentException("enum body boundaries are not ordered");
        }
        return sourceText.region(openingRegion.endOffset(), boundaryRegion.beginOffset());
    }

    private List<JavaToken> tokens(EnumDeclaration declaration) {
        return declaration.getTokenRange()
                .map(tokenRange -> {
                    List<JavaToken> collected = new ArrayList<>();
                    tokenRange.forEach(collected::add);
                    return collected;
                })
                .orElseThrow(() -> new IllegalArgumentException("enum declaration is missing a token range"));
    }

    private int enumBodyOpeningBraceIndex(List<JavaToken> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).getKind() == GeneratedJavaParserConstants.LBRACE) {
                return i;
            }
        }
        throw new IllegalArgumentException("enum body source range is missing an opening brace");
    }

    private JavaToken enumConstantListBoundary(List<JavaToken> tokens, int openingBraceIndex) {
        int braceDepth = 1;
        for (int i = openingBraceIndex + 1; i < tokens.size(); i++) {
            JavaToken token = tokens.get(i);
            if (token.getKind() == GeneratedJavaParserConstants.LBRACE) {
                braceDepth++;
                continue;
            }
            if (token.getKind() == GeneratedJavaParserConstants.RBRACE) {
                braceDepth--;
                if (braceDepth == 0) {
                    return token;
                }
                continue;
            }
            if (braceDepth == 1 && token.asString().equals(";")) {
                return token;
            }
        }
        throw new IllegalArgumentException("enum body source range is missing a closing brace");
    }

    private SourceRegion tokenRegion(JavaToken token, String description) {
        return token.getRange()
                .map(sourceText::region)
                .orElseThrow(
                    () -> new IllegalArgumentException("enum body " + description + " is missing a source range")
                );
    }

    private boolean rawRegionEndsWithComma(SourceRegion region) {
        String raw = sourceText.slice(region).stripTrailing();
        return raw.endsWith(",");
    }

    /**
     * Returns orphan comments that belong to the body section rather than to the trailing side of an enum constant.
     *
     * <p>An empty enum body ({@code enum E { // comment }}) is recovered by source position instead: a whitespace collapse
     * onto the header line re-attaches the comment to the enum's name, so {@code getOrphanComments()} drops it, but it is
     * still a <em>contained</em> comment. {@link #emptyBodyComments(EnumDeclaration)} keeps that ownership by selecting,
     * in source order, the contained comments beginning inside the empty body.
     */
    private List<Doc> enumBodyComments(EnumDeclaration declaration) {
        if (declaration.getEntries().isEmpty() && declaration.getMembers().isEmpty()) {
            return emptyBodyComments(declaration);
        }
        return comments.orphanCommentStatements(declaration, comment -> declaration.getEntries().stream().noneMatch(
                entry -> CommentIndex.startsOnEndLine(entry, comment)
        ));
    }

    /**
     * Recovers the comments inside an empty enum body, independent of source shape.
     *
     * <p>An empty enum body holds no constants or members, so every contained comment after the opening brace (line and
     * block alike, matching the orphan path's unfiltered {@link CommentTracker#orphanCommentStatements}) is a body
     * comment. Selecting by opening-brace position rather than orphan association keeps the comment owned by the body
     * however whitespace lays it out — a strict superset of the orphan path at {@code @default}, where an own-line body
     * comment is both a declaration orphan and a contained comment after the brace.
     */
    private List<Doc> emptyBodyComments(EnumDeclaration declaration) {
        OptionalInt openingBraceOffset = enumBodyOpeningBraceOffset(declaration);
        if (openingBraceOffset.isEmpty()) {
            return List.of();
        }
        int afterOpeningBrace = openingBraceOffset.getAsInt();
        return commentPlacement.containedComments(declaration)
                .stream()
                .filter(comment -> commentBeginOffset(comment) > afterOpeningBrace)
                .sorted(Comparator.comparing(JavaCommentTrivia::comment, CommentIndex.sourceOrderComparator()))
                .map(comments::comment)
                .filter(doc -> doc != Doc.EMPTY)
                .toList();
    }

    /**
     * Returns the source offset just past the enum body's opening brace, or empty when the brace has no source range.
     */
    private OptionalInt enumBodyOpeningBraceOffset(EnumDeclaration declaration) {
        List<JavaToken> tokens = tokens(declaration);
        JavaToken openingBrace = tokens.get(enumBodyOpeningBraceIndex(tokens));
        return openingBrace.getRange()
                .map(sourceText::region)
                .map(region -> OptionalInt.of(region.endOffset()))
                .orElseGet(OptionalInt::empty);
    }

    private int commentBeginOffset(JavaCommentTrivia comment) {
        return comment.comment()
                .getRange()
                .map(range -> sourceText.region(range).beginOffset())
                .orElse(Integer.MAX_VALUE);
    }

    /**
     * Recovers a source-written semicolon before ordinary members when there are no enum constants to force one.
     */
    private boolean enumHasExplicitSemicolon(EnumDeclaration declaration) {
        String raw = rawSource.rawWithoutOwnComment(declaration);
        int open = raw.indexOf('{');
        int firstMember = declaration.getMembers()
                .stream()
                .findFirst()
                .flatMap(member -> member.getTokenRange().map(Object::toString))
                .map(raw::indexOf)
                .filter(index -> index >= 0)
                .orElse(raw.length());
        return open >= 0 && raw.substring(open + 1, firstMember).contains(";");
    }

    static boolean hasRecoverableEnumConstantListProblem(EnumDeclaration declaration) {
        return (
            declaration.getParsed() == Node.Parsedness.PARSED
            && declaration.getEntries().stream().anyMatch(entry -> !isFullyParsed(entry))
            && declaration.stream()
                    .filter(node -> node != declaration)
                    .filter(node -> node.getParsed() != Node.Parsedness.PARSED)
                    .allMatch(node -> nearestEnumConstantListSibling(node)
                                .filter(declaration.getEntries()::contains)
                                .isPresent()
                    )
        );
    }

    static boolean isRecoverableEnumConstantListSibling(EnumConstantDeclaration declaration) {
        return declaration.getParentNode()
                .filter(EnumDeclaration.class::isInstance)
                .map(EnumDeclaration.class::cast)
                .filter(EnumDeclarationPrinter::hasRecoverableEnumConstantListProblem)
                .isPresent();
    }

    static Optional<EnumConstantDeclaration> nearestEnumConstantListSibling(Node recoveredNode) {
        Optional<Node> current = Optional.of(recoveredNode);
        while (current.isPresent()) {
            Node node = current.orElseThrow();
            if (node instanceof EnumConstantDeclaration declaration && isEnumConstantListSibling(declaration)) {
                return Optional.of(declaration);
            }
            current = node.getParentNode();
        }
        return Optional.empty();
    }

    private static boolean isEnumConstantListSibling(EnumConstantDeclaration declaration) {
        return declaration.getParentNode()
                .filter(EnumDeclaration.class::isInstance)
                .map(EnumDeclaration.class::cast)
                .filter(owner -> owner.getEntries().contains(declaration))
                .isPresent();
    }

    private static boolean isFullyParsed(Node node) {
        return node.stream().allMatch(descendant -> descendant.getParsed() == Node.Parsedness.PARSED);
    }

    private static boolean hasRawGap(RecoveredListPlanner.Plan<EnumConstantDeclaration> plan) {
        return plan.entries().stream().anyMatch(RecoveredListPlanner.RawGap.class::isInstance);
    }

    private static FormatterException enumConstantListRecoveryFailure(String reason) {
        return new FormatterException(ENUM_CONSTANT_LIST_RECOVERY_FAILURE + reason);
    }

    private static FormatterException enumConstantListRecoveryFailure(String reason, Throwable cause) {
        return new FormatterException(ENUM_CONSTANT_LIST_RECOVERY_FAILURE + reason, cause);
    }

    /**
     * The rendered enum constant list plus how its terminating separator is owned.
     *
     * @param doc the rendered constants and the separators between them; each constant defers its trailing comment to a
     *     {@link Doc#lineSuffix(Doc)}, so the list terminator added by {@link #enumBlock} prints before that comment
     * @param rawOwnsTrailingComma whether a recovered raw island already ended with the list-terminating comma, so the
     *     formatter must not add another separator
     */
    private record EnumEntryList(Doc doc, boolean rawOwnsTrailingComma) {
        private EnumEntryList {
            doc = Doc.concat(doc);
        }
    }

    private record RecoveredSourceLineComment(int line, String text) {}
}
