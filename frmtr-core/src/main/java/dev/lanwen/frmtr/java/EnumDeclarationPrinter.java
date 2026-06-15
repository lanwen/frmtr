package dev.lanwen.frmtr.java;

import com.github.javaparser.GeneratedJavaParserConstants;
import com.github.javaparser.JavaToken;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.comments.Comment;
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
            Function<BodyDeclaration<?>, Doc> memberRenderer
    ) {
        this.comments = context.comments;
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
                contents.add(enumMemberSeparator(declaration));
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
        EnumConstantDeclaration last = entries.get(lastIndex);
        Doc lastTrailingComment = enumConstantTrailingComment(declaration, last, null);

        List<Doc> entryDocs = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            EnumConstantDeclaration entry = entries.get(i);
            EnumConstantDeclaration next = i + 1 < entries.size() ? entries.get(i + 1) : null;
            if (i == lastIndex && lastTrailingComment != Doc.EMPTY) {
                entryDocs.add(enumConstantWithoutTrailingComment(entry));
            } else {
                entryDocs.add(enumConstant(declaration, entry, next));
            }
        }
        boolean lastTrailingCommentHoisted = lastTrailingComment != Doc.EMPTY;
        return new EnumEntryList(
            enumEntryList(declaration, entryDocs, lastTrailingCommentHoisted),
            false,
            lastTrailingComment
        );
    }

    /**
     * Interleaves printed constants with source-sensitive separators.
     *
     * <p>The inter-constant separator is normally suppressed when the previous constant already carries an inline
     * {@code , // comment} tail, because {@link #enumConstant} emits that comma itself. That assumption breaks for exactly
     * one boundary: when the last constant's trailing comment is hoisted past the list terminator
     * ({@code lastTrailingCommentHoisted}) and that hoisted comment is the same node javaparser also attached to the
     * previous constant (see {@link #previousSharesHoistedComment}). The hoist consumes the shared comment for the last
     * constant, so the previous constant renders bare — suppressing the separator there would drop the only comma before
     * the last constant and emit non-reparseable output. That boundary therefore keeps its normal {@code ,} separator.
     * When the previous constant owns a distinct trailing comment it still renders its own inline comma, so the
     * suppression must stay to avoid emitting a doubled {@code ,}.
     */
    private Doc enumEntryList(EnumDeclaration declaration, List<Doc> entries, boolean lastTrailingCommentHoisted) {
        int lastIndex = entries.size() - 1;
        List<Doc> docs = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                EnumConstantDeclaration previous = declaration.getEntries().get(i - 1);
                EnumConstantDeclaration current = declaration.getEntries().get(i);
                boolean intoHoistedSharedComment = i == lastIndex
                    && lastTrailingCommentHoisted
                    && previousSharesHoistedComment(previous, current);
                boolean previousOwnsTrailingComma = !intoHoistedSharedComment
                    && enumConstantHasTrailingComment(declaration, previous, current);
                docs.add(enumEntrySeparator(previous, current, previousOwnsTrailingComma));
            }
            docs.add(entries.get(i));
        }
        return Doc.concat(docs);
    }

    /**
     * Reports whether {@code previous} and the hoisted last constant {@code current} share the same trailing comment node.
     *
     * <p>When the last two constants end on one physical line with a single {@code //} comment, javaparser attaches that
     * one comment object to both constants. The last constant's hoist consumes it, so {@code previous} renders bare and
     * its inter-constant {@code ,} must be re-supplied. This is identity-based on purpose: when each constant carries its
     * own distinct trailing comment — for example {@code HEART /*..*}{@code /, SPADE /*..*}{@code /} collapsed onto one
     * line — {@code previous} still renders its own inline comma and must keep the suppressed separator to avoid a doubled
     * {@code ,}.
     */
    private boolean previousSharesHoistedComment(
            EnumConstantDeclaration previous,
            EnumConstantDeclaration current
    ) {
        Optional<Comment> previousOwnComment = previous.getComment()
                .filter(comment -> CommentIndex.startsAfterNodeOnSameLine(previous, comment));
        Optional<Comment> currentOwnComment = current.getComment()
                .filter(comment -> CommentIndex.startsAfterNodeOnSameLine(previous, comment));
        return previousOwnComment.isPresent()
            && currentOwnComment.isPresent()
            && previousOwnComment.orElseThrow() == currentOwnComment.orElseThrow();
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
                    appendSeparatorBeforeRecoveredEnumConstant(docs, previousValid, currentEntry, previousEntry);
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
            EnumConstantDeclaration previousValid,
            EnumConstantDeclaration currentEntry,
            EntryKind previousEntry
    ) {
        if (previousValid != null) {
            docs.add(enumEntrySeparator(previousValid, currentEntry, false));
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
            boolean previousOwnsTrailingComma
    ) {
        boolean hasBlankLineBetween = previous.getRange()
                .flatMap(previousRange -> current.getRange().map(
                        currentRange -> enumEntryBeginLine(current, currentRange.begin.line) > previousRange.end.line + 1
                ))
                .orElse(false);
        Doc separator = previousOwnsTrailingComma ? Doc.EMPTY : Doc.text(",");
        return hasBlankLineBetween
            ? Doc.concat(separator, Doc.HARD_LINE, Doc.HARD_LINE)
            : Doc.concat(separator, Doc.HARD_LINE);
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
    private Doc enumMemberSeparator(EnumDeclaration declaration) {
        if (declaration.getOrphanComments().isEmpty() || declaration.getMembers().isEmpty()) {
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
        Doc trailing = enumConstantTrailingComment(owner, declaration, next);
        return Doc.concat(
            comments.leading(declaration),
            enumConstantAnnotations(declaration),
            Doc.text(declaration.getNameAsString()),
            enumConstantArguments(declaration),
            trailing == Doc.EMPTY ? Doc.EMPTY : Doc.concat(Doc.text(", "), trailing)
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
            comments.leading(declaration),
            enumConstantAnnotations(declaration),
            Doc.text(declaration.getNameAsString()),
            enumConstantArguments(declaration)
        );
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

    /**
     * Finds comments written after a constant but attached by JavaParser to either the next constant or the enum body.
     */
    private Doc enumConstantTrailingComment(
            EnumDeclaration owner,
            EnumConstantDeclaration declaration,
            EnumConstantDeclaration next
    ) {
        Doc ownTrailing = declaration.getComment()
                .filter(comment -> CommentIndex.startsAfterNodeOnSameLine(declaration, comment))
                .map(comments::comment)
                .orElse(Doc.EMPTY);
        if (ownTrailing != Doc.EMPTY) {
            return ownTrailing;
        }
        Doc containedTrailing = Doc.concat(
            declaration.getAllContainedComments()
                    .stream()
                    .filter(comment -> CommentIndex.startsOnEndLine(declaration, comment))
                    .map(comments::comment)
                    .filter(comment -> comment != Doc.EMPTY)
                    .toList()
        );
        if (containedTrailing != Doc.EMPTY) {
            return containedTrailing;
        }
        if (next != null) {
            Doc nextTrailing = next.getComment()
                    .filter(comment -> CommentIndex.startsAfterNodeOnSameLine(declaration, comment))
                    .map(comments::comment)
                    .orElse(Doc.EMPTY);
            if (nextTrailing != Doc.EMPTY) {
                return nextTrailing;
            }
        }
        if (owner == null) {
            return Doc.EMPTY;
        }
        return Doc.concat(
            comments.orphanCommentStatements(owner, comment -> CommentIndex.startsOnEndLine(declaration, comment))
        );
    }

    private boolean enumConstantHasTrailingComment(
            EnumDeclaration owner,
            EnumConstantDeclaration declaration,
            EnumConstantDeclaration next
    ) {
        if (
            declaration.getComment()
                    .filter(comment -> CommentIndex.startsAfterNodeOnSameLine(declaration, comment))
                    .isPresent()
        ) {
            return true;
        }
        if (
            next != null
            && next.getComment()
                    .filter(comment -> CommentIndex.startsAfterNodeOnSameLine(declaration, comment))
                    .isPresent()
        ) {
            return true;
        }
        if (declaration.getAllContainedComments().stream().anyMatch(
                comment -> CommentIndex.startsOnEndLine(declaration, comment)
            )) {
            return true;
        }
        return (
            owner != null
            && owner.getOrphanComments()
                    .stream()
                    .anyMatch(comment -> CommentIndex.startsOnEndLine(declaration, comment))
        );
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
}
