package dev.lanwen.frmtr.java;

import com.github.javaparser.GeneratedJavaParserConstants;
import com.github.javaparser.JavaToken;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
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
 * entries, explicit semicolon recovery, body orphan-comment placement, and enum constant argument layout. It
 * intentionally leaves member declaration rendering, expression formatting, and type-clause formatting with {@link
 * JavaPrinter}; callers provide those decisions as callbacks so enum bodies can keep the same sequencing as other
 * member blocks.
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

    private final Function<NodeList<ClassOrInterfaceType>, String> flatImplementsTypes;

    private final Function<List<? extends Node>, String> compactJoin;

    private final Function<Expression, Doc> expression;

    private final ToIntFunction<String> currentIndentedWidth;

    private final BiFunction<NodeList<BodyDeclaration<?>>, Node, Doc> memberBlockRenderer;

    private final Function<BodyDeclaration<?>, Doc> memberRenderer;

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
            Function<NodeList<ClassOrInterfaceType>, String> flatImplementsTypes,
            Function<List<? extends Node>, String> compactJoin,
            Function<Expression, Doc> expression,
            ToIntFunction<String> currentIndentedWidth,
            BiFunction<NodeList<BodyDeclaration<?>>, Node, Doc> memberBlockRenderer,
            Function<BodyDeclaration<?>, Doc> memberRenderer
    ) {
        this.comments = context.comments;
        this.commentPlacement = context.commentPlacementPolicy;
        this.enumConstantComments = new EnumConstantComments(context.comments, context.commentPlacementPolicy);
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
        this.flatImplementsTypes = flatImplementsTypes;
        this.compactJoin = compactJoin;
        this.expression = expression;
        this.currentIndentedWidth = currentIndentedWidth;
        this.memberBlockRenderer = memberBlockRenderer;
        this.memberRenderer = memberRenderer;
    }

    /**
     * Prints the full enum declaration while delegating ordinary member declarations back to the caller.
     */
    Doc enumDeclaration(EnumDeclaration declaration) {
        List<Doc> header = new ArrayList<>();
        header.add(annotations.apply(declaration));
        header.add(Doc.text(modifiers.apply(declaration)));
        header.add(Doc.text("enum " + declaration.getNameAsString()));
        boolean breakHeader = shouldBreakEnumHeader(declaration);
        if (breakHeader) {
            brokenImplementsTypes.apply(declaration.getImplementedTypes()).ifPresent(header::add);
            header.add(enumBodyOpenBreak(declaration));
        } else {
            inlineImplementsTypes.apply(declaration.getImplementedTypes()).ifPresent(header::add);
            header.add(Doc.text(" "));
        }
        header.add(enumBlock(declaration));
        return Doc.concat(header);
    }

    /**
     * Breaks long {@code implements} clauses before the enum body, accounting for whether the body can still be empty.
     */
    private boolean shouldBreakEnumHeader(EnumDeclaration declaration) {
        if (declaration.getImplementedTypes().isEmpty()) {
            return false;
        }
        String flatHeader = modifiers.apply(declaration)
            + "enum "
            + declaration.getNameAsString()
            + flatImplementsTypes.apply(declaration.getImplementedTypes());
        int blockWidth =
            declaration.getEntries().isEmpty()
            && declaration.getMembers().isEmpty()
            && declaration.getOrphanComments().isEmpty()
                ? "{}".length()
                : "{".length();
        return flatHeader.length() + 1 + blockWidth > options.lineWidth();
    }

    /**
     * Keeps broken headers with empty enum bodies on one physical line before {@code {}}.
     */
    private Doc enumBodyOpenBreak(EnumDeclaration declaration) {
        if (
            declaration.getEntries().isEmpty()
            && declaration.getMembers().isEmpty()
            && declaration.getOrphanComments().isEmpty()
        ) {
            return Doc.text(" ");
        }
        return Doc.HARD_LINE;
    }

    /**
     * Builds the enum body by keeping constants, body orphan comments, and ordinary members in their current order
     * bands.
     */
    private Doc enumBlock(EnumDeclaration declaration) {
        Optional<EnumEntryList> entries = enumEntryList(declaration);
        List<Doc> members = declaration.getMembers().stream().map(memberRenderer).toList();
        List<Doc> bodyComments = enumBodyComments(declaration);
        if (entries.isEmpty() && members.isEmpty() && bodyComments.isEmpty()) {
            return Doc.text("{}");
        }
        List<Doc> contents = new ArrayList<>();
        if (entries.isPresent()) {
            EnumEntryList entryList = entries.orElseThrow();
            contents.add(entryList.doc());
            if (!entryList.rawOwnsTrailingComma() || !members.isEmpty() || !bodyComments.isEmpty()) {
                contents.add(Doc.text(members.isEmpty() && bodyComments.isEmpty() ? "," : ";"));
            }
            // The last constant's trailing comment was hoisted past the terminating separator so the separator is not
            // commented out; re-emit it after the separator on the same line (`NAME; // comment`).
            if (entryList.lastTrailingComment() != Doc.EMPTY) {
                contents.add(Doc.text(" "));
                contents.add(entryList.lastTrailingComment());
            }
        } else if (!members.isEmpty() && enumHasExplicitSemicolon(declaration)) {
            contents.add(Doc.text(";"));
        }
        if (!bodyComments.isEmpty()) {
            if (!contents.isEmpty()) {
                contents.add(Doc.HARD_LINE);
                contents.add(Doc.HARD_LINE);
            }
            contents.add(Doc.join(Doc.HARD_LINE, bodyComments));
        }
        if (!members.isEmpty()) {
            if (!contents.isEmpty()) {
                contents.add(enumMemberSeparator(declaration, bodyComments));
            }
            contents.add(Doc.join(Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE), members));
        }
        return Doc.concat(
            Doc.text("{"),
            Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.concat(contents))),
            Doc.HARD_LINE,
            Doc.text("}")
        );
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
     * Builds the formatter-owned enum entry list, hoisting the last constant's trailing comment out so it follows the
     * list-terminating separator rather than swallowing it.
     *
     * <p>All but the last constant keep their inline {@code , // comment} shape, where the comma is the separator to the
     * next constant. The last constant has no following constant, so its separator is the list terminator ({@code ;} when
     * members or body comments follow, otherwise {@code ,}). That terminator is added by {@link #enumBlock}; emitting a
     * trailing {@code //} comment before it would comment the terminator out and drop a required separator. The last
     * constant is therefore rendered without its trailing comment, which is returned separately for the caller to place
     * after the terminator.
     */
    private EnumEntryList formattedEnumEntryList(EnumDeclaration declaration) {
        List<EnumConstantDeclaration> entries = declaration.getEntries();
        int lastIndex = entries.size() - 1;
        List<EnumConstantComments.Tail> tails = enumConstantComments.tails(declaration);
        EnumConstantComments.Tail lastTail = tails.get(lastIndex);

        List<Doc> entryDocs = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            EnumConstantDeclaration entry = entries.get(i);
            EnumConstantComments.Tail tail = tails.get(i);
            if (i == lastIndex && tail.hasComment()) {
                entryDocs.add(enumConstantWithoutTrailingComment(entry));
            } else {
                entryDocs.add(enumConstant(entry, tail));
            }
        }
        return new EnumEntryList(
            enumEntryList(declaration, entryDocs, tails),
            false,
            lastTail.comment()
        );
    }

    /**
     * Interleaves printed constants with source-sensitive separators.
     *
     * <p>The inter-constant separator is normally suppressed when the previous constant already carries an inline
     * {@code , // comment} tail, because {@link #enumConstant} emits that comma itself. The caller passes the already
     * resolved tail for each printed constant, so separator ownership and rendered comment text come from the same source
     * decision instead of repeating comment-placement predicates.
     */
    private Doc enumEntryList(
            EnumDeclaration declaration,
            List<Doc> entries,
            List<EnumConstantComments.Tail> tails
    ) {
        List<Doc> docs = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                EnumConstantDeclaration previous = declaration.getEntries().get(i - 1);
                EnumConstantDeclaration current = declaration.getEntries().get(i);
                boolean previousOwnsTrailingComma = tails.get(i - 1).ownsInlineComma();
                List<JavaCommentTrivia> gapComments = commentPlacement.standaloneLineCommentsBetween(
                    declaration,
                    previous,
                    current
                );
                docs.add(enumEntrySeparator(previous, current, previousOwnsTrailingComma, gapComments));
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
                        enumConstant(
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
        return new EnumEntryList(Doc.concat(docs), rawOwnsTrailingComma, Doc.EMPTY);
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
                    false,
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
            boolean previousOwnsTrailingComma,
            List<JavaCommentTrivia> gapComments
    ) {
        return enumEntrySeparator(previous, current, previousOwnsTrailingComma, gapComments, OptionalInt.empty());
    }

    private Doc enumEntrySeparator(
            EnumConstantDeclaration previous,
            EnumConstantDeclaration current,
            boolean previousOwnsTrailingComma,
            List<JavaCommentTrivia> gapComments,
            OptionalInt recoveredGapBeginLine
    ) {
        boolean hasBlankLineBetween = previous.getRange()
                .flatMap(previousRange -> current.getRange().map(
                        currentRange -> enumEntryBeginLine(
                            previous,
                            current,
                            currentRange.begin.line,
                            gapComments,
                            recoveredGapBeginLine
                        ) > previousRange.end.line + 1
                ))
                .orElse(false);
        Doc separator = previousOwnsTrailingComma ? Doc.EMPTY : Doc.text(",");
        return hasBlankLineBetween
            ? Doc.concat(separator, Doc.HARD_LINE, Doc.HARD_LINE)
            : Doc.concat(separator, Doc.HARD_LINE);
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
            List<JavaCommentTrivia> gapComments
    ) {
        return enumEntryBeginLine(previous, current, fallback, gapComments, OptionalInt.empty());
    }

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
     */
    private List<Doc> enumBodyComments(EnumDeclaration declaration) {
        return comments.orphanCommentStatements(declaration, comment -> declaration.getEntries().stream().noneMatch(
                entry -> CommentIndex.startsOnEndLine(entry, comment)
        ));
    }

    /**
     * Chooses the vertical gap between body orphan comments and the first ordinary enum member.
     *
     * <p>A semicolon written after body comments already acts as the source separator. Otherwise the formatter preserves
     * whether the source had a blank line before the first member.
     */
    private Doc enumMemberSeparator(EnumDeclaration declaration, List<Doc> bodyComments) {
        if (bodyComments.isEmpty() || declaration.getMembers().isEmpty()) {
            return Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE);
        }
        if (enumSemicolonFollowsBodyComments(declaration)) {
            return Doc.HARD_LINE;
        }
        return enumBodyCommentsHaveBlankLineBeforeFirstMember(declaration)
            ? Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE)
            : Doc.HARD_LINE;
    }

    /**
     * Checks whether body comments were visually separated from the first member by a blank source line.
     */
    private boolean enumBodyCommentsHaveBlankLineBeforeFirstMember(EnumDeclaration declaration) {
        int lastCommentLine = declaration.getOrphanComments()
                .stream()
                .flatMap(comment -> comment.getRange().stream())
                .mapToInt(range -> range.end.line)
                .max()
                .orElse(Integer.MAX_VALUE);
        return declaration.getMembers()
                .stream()
                .findFirst()
                .flatMap(Node::getRange)
                .map(range -> range.begin.line > lastCommentLine + 1)
                .orElse(false);
    }

    /**
     * Detects the source shape where the enum semicolon is written after body orphan comments and before members.
     */
    private boolean enumSemicolonFollowsBodyComments(EnumDeclaration declaration) {
        String raw = declaration.getTokenRange().map(Object::toString).orElseGet(
            () -> rawSource.rawWithoutOwnComment(
                declaration
            )
        );
        int firstMember = declaration.getMembers()
                .stream()
                .findFirst()
                .flatMap(member -> member.getTokenRange().map(Object::toString))
                .map(raw::indexOf)
                .filter(index -> index >= 0)
                .orElse(raw.length());
        String beforeMember = raw.substring(0, firstMember);
        int semicolon = beforeMember.lastIndexOf(';');
        int lineComment = beforeMember.lastIndexOf("//");
        int blockComment = beforeMember.lastIndexOf("/*");
        return semicolon > Math.max(lineComment, blockComment);
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

    /**
     * Prints one enum constant, including leading comments, arguments, and comments attached to the constant's tail.
     */
    private Doc enumConstant(
            EnumDeclaration owner,
            EnumConstantDeclaration declaration,
            EnumConstantDeclaration next
    ) {
        return enumConstant(declaration, enumConstantComments.tail(owner, declaration, next));
    }

    private Doc enumConstant(EnumConstantDeclaration declaration, EnumConstantComments.Tail tail) {
        return Doc.concat(
            enumConstantComments.leading(declaration),
            enumConstantAnnotations(declaration),
            Doc.text(declaration.getNameAsString()),
            enumConstantArguments(declaration),
            enumConstantClassBody(declaration),
            tail.inline()
        );
    }

    /**
     * Prints the body part of an enum constant — leading comments, annotations, name, and arguments — without the inline
     * comma and trailing comment.
     *
     * <p>The final enum constant's trailing comment cannot be rendered inline before the list-terminating separator: when
     * a {@code ;} (members follow) or a {@code ,} would be appended after a {@code //} line comment, that separator is
     * swallowed by the comment and lost from the program. The caller therefore renders the last constant through this
     * method and re-emits the separator and trailing comment in the correct order ({@code NAME; // comment}).
     */
    private Doc enumConstantWithoutTrailingComment(EnumConstantDeclaration declaration) {
        return Doc.concat(
            enumConstantComments.leading(declaration),
            enumConstantAnnotations(declaration),
            Doc.text(declaration.getNameAsString()),
            enumConstantArguments(declaration),
            enumConstantClassBody(declaration)
        );
    }

    private Doc enumConstantClassBody(EnumConstantDeclaration declaration) {
        if (!hasExplicitEnumConstantClassBody(declaration)) {
            return Doc.EMPTY;
        }
        return Doc.concat(Doc.text(" "), memberBlockRenderer.apply(declaration.getClassBody(), declaration));
    }

    private boolean hasExplicitEnumConstantClassBody(EnumConstantDeclaration declaration) {
        SourceRegion nameRegion = declaration.getName()
                .getRange()
                .map(sourceText::region)
                .orElseThrow(() -> new IllegalArgumentException("enum constant name is missing a source range"));
        return declaration
                .getTokenRange()
                .map(tokenRange -> {
                    boolean afterName = false;
                    int parenDepth = 0;
                    for (JavaToken token : tokenRange) {
                        if (!afterName) {
                            afterName = tokenMatchesRegion(token, nameRegion);
                            continue;
                        }
                        if (token.getKind() == GeneratedJavaParserConstants.LPAREN) {
                            parenDepth++;
                            continue;
                        }
                        if (token.getKind() == GeneratedJavaParserConstants.RPAREN && parenDepth > 0) {
                            parenDepth--;
                            continue;
                        }
                        if (token.getKind() == GeneratedJavaParserConstants.LBRACE && parenDepth == 0) {
                            return true;
                        }
                    }
                    return false;
                })
                .orElse(false);
    }

    private boolean tokenMatchesRegion(JavaToken token, SourceRegion expected) {
        return token.getRange()
                .map(sourceText::region)
                .map(region -> region.beginOffset() == expected.beginOffset()
                    && region.endOffset() == expected.endOffset())
                .orElse(false);
    }

    private Doc enumConstantAnnotations(EnumConstantDeclaration declaration) {
        if (declaration.getAnnotations().isEmpty()) {
            return Doc.EMPTY;
        }
        return Doc.concat(annotations.apply(declaration), Doc.EMPTY);
    }

    /**
     * Prints enum constant arguments compactly unless a lambda argument needs normal expression docs.
     *
     * <p>Lambda arguments can contain bodies that need formatter-owned breaking decisions, so the helper uses the
     * expression callback for those cases and falls back to one-argument-per-line only when the rendered constant no
     * longer fits.
     */
    private Doc enumConstantArguments(EnumConstantDeclaration declaration) {
        if (declaration.getArguments().isEmpty()) {
            return Doc.EMPTY;
        }
        if (declaration.getArguments().stream().noneMatch(this::enumConstantArgumentNeedsDoc)) {
            return Doc.text("(" + compactJoin.apply(declaration.getArguments()) + ")");
        }
        String flat = declaration.getNameAsString() + "(" + compactJoin.apply(declaration.getArguments()) + ")";
        if (currentIndentedWidth.applyAsInt(flat) <= options.lineWidth()) {
            return Doc.concat(
                Doc.text("("),
                Doc.join(Doc.text(", "), declaration.getArguments().stream().map(expression).toList()),
                Doc.text(")")
            );
        }
        return Doc.concat(
            Doc.text("("),
            Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    Doc.join(
                        Doc.concat(Doc.text(","), Doc.HARD_LINE),
                        declaration.getArguments()
                                .stream()
                                .map(expression)
                                .toList()
                    )
                )
            ),
            Doc.HARD_LINE,
            Doc.text(")")
        );
    }

    /**
     * Detects enum constant arguments that need expression docs instead of compact raw text.
     */
    private boolean enumConstantArgumentNeedsDoc(Expression expression) {
        return expression instanceof LambdaExpr || expression.findFirst(LambdaExpr.class).isPresent();
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
     * @param doc the rendered constants and the separators between them
     * @param rawOwnsTrailingComma whether a recovered raw island already ended with the list-terminating comma, so the
     *     formatter must not add another separator
     * @param lastTrailingComment the last constant's trailing comment, hoisted out so it can follow the terminating
     *     separator instead of swallowing it; {@link Doc#EMPTY} when the last constant has no trailing comment
     */
    private record EnumEntryList(Doc doc, boolean rawOwnsTrailingComma, Doc lastTrailingComment) {
        private EnumEntryList {
            doc = Doc.concat(doc);
        }
    }

    private record RecoveredSourceLineComment(int line, String text) {}
}
