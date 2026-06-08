package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
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
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/enum/input.java} and
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/enum/frmtr.output.java}; enum constants with
 * lambda arguments are also covered by
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/lambda/arrow-parens-avoid/input.java}.
 */
final class EnumDeclarationPrinter {
    private final CommentTracker comments;
    private final RawSource rawSource;
    private final FormatterOptions options;
    private final Function<NodeWithAnnotations<?>, Doc> annotations;
    private final Function<NodeWithModifiers<?>, String> modifiers;
    private final Function<NodeList<ClassOrInterfaceType>, Optional<Doc>> brokenImplementsTypes;
    private final Function<NodeList<ClassOrInterfaceType>, Optional<Doc>> inlineImplementsTypes;
    private final Function<NodeList<ClassOrInterfaceType>, String> flatImplementsTypes;
    private final Function<List<? extends Node>, String> compactJoin;
    private final Function<Expression, Doc> expression;
    private final ToIntFunction<String> currentIndentedWidth;
    private final Function<BodyDeclaration<?>, Doc> memberRenderer;

    EnumDeclarationPrinter(
            CommentTracker comments,
            RawSource rawSource,
            FormatterOptions options,
            Function<NodeWithAnnotations<?>, Doc> annotations,
            Function<NodeWithModifiers<?>, String> modifiers,
            Function<NodeList<ClassOrInterfaceType>, Optional<Doc>> brokenImplementsTypes,
            Function<NodeList<ClassOrInterfaceType>, Optional<Doc>> inlineImplementsTypes,
            Function<NodeList<ClassOrInterfaceType>, String> flatImplementsTypes,
            Function<List<? extends Node>, String> compactJoin,
            Function<Expression, Doc> expression,
            ToIntFunction<String> currentIndentedWidth,
            Function<BodyDeclaration<?>, Doc> memberRenderer) {
        this.comments = comments;
        this.rawSource = rawSource;
        this.options = options;
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
        int blockWidth = declaration.getEntries().isEmpty()
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
        if (declaration.getEntries().isEmpty()
                && declaration.getMembers().isEmpty()
                && declaration.getOrphanComments().isEmpty()) {
            return Doc.text(" ");
        }
        return Doc.HARD_LINE;
    }

    /**
     * Builds the enum body by keeping constants, body orphan comments, and ordinary members in their current order
     * bands.
     */
    private Doc enumBlock(EnumDeclaration declaration) {
        List<Doc> entries = enumEntries(declaration);
        List<Doc> members = declaration.getMembers().stream().map(memberRenderer).toList();
        List<Doc> bodyComments = enumBodyComments(declaration);
        if (entries.isEmpty() && members.isEmpty() && bodyComments.isEmpty()) {
            return Doc.text("{}");
        }
        List<Doc> contents = new ArrayList<>();
        if (!entries.isEmpty()) {
            contents.add(enumEntryList(declaration, entries));
            contents.add(Doc.text(members.isEmpty() && bodyComments.isEmpty() ? "," : ";"));
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
                Doc.text("}"));
    }

    /**
     * Prints each enum constant with enough owner context to find comments that JavaParser leaves as body trivia.
     */
    private List<Doc> enumEntries(EnumDeclaration declaration) {
        List<Doc> entries = new ArrayList<>();
        for (int i = 0; i < declaration.getEntries().size(); i++) {
            EnumConstantDeclaration entry = declaration.getEntries().get(i);
            EnumConstantDeclaration next = i + 1 < declaration.getEntries().size() ? declaration.getEntries().get(i + 1) : null;
            entries.add(enumConstant(declaration, entry, next, i == declaration.getEntries().size() - 1));
        }
        return entries;
    }

    /**
     * Interleaves printed constants with source-sensitive separators.
     */
    private Doc enumEntryList(EnumDeclaration declaration, List<Doc> entries) {
        List<Doc> docs = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                docs.add(enumEntrySeparator(declaration.getEntries().get(i - 1), declaration.getEntries().get(i)));
            }
            docs.add(entries.get(i));
        }
        return Doc.concat(docs);
    }

    /**
     * Preserves intentional blank lines between neighboring enum constants.
     *
     * <p>Leading comments attached to the current constant count as the current entry's source start so a commented
     * constant does not accidentally erase a blank separator that belongs before the comment.
     */
    private Doc enumEntrySeparator(EnumConstantDeclaration previous, EnumConstantDeclaration current) {
        boolean hasBlankLineBetween = previous.getRange()
                .flatMap(previousRange -> current.getRange()
                        .map(currentRange -> enumEntryBeginLine(current, currentRange.begin.line)
                                > previousRange.end.line + 1))
                .orElse(false);
        return hasBlankLineBetween
                ? Doc.concat(Doc.text(","), Doc.HARD_LINE, Doc.HARD_LINE)
                : Doc.concat(Doc.text(","), Doc.HARD_LINE);
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
     * Returns orphan comments that belong to the body section rather than to the trailing side of an enum constant.
     */
    private List<Doc> enumBodyComments(EnumDeclaration declaration) {
        return comments.orphanCommentStatements(declaration, comment -> declaration.getEntries().stream()
                .noneMatch(entry -> CommentIndex.startsOnEndLine(entry, comment)));
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
        int lastCommentLine = declaration.getOrphanComments().stream()
                .flatMap(comment -> comment.getRange().stream())
                .mapToInt(range -> range.end.line)
                .max()
                .orElse(Integer.MAX_VALUE);
        return declaration.getMembers().stream()
                .findFirst()
                .flatMap(Node::getRange)
                .map(range -> range.begin.line > lastCommentLine + 1)
                .orElse(false);
    }

    /**
     * Detects the source shape where the enum semicolon is written after body orphan comments and before members.
     */
    private boolean enumSemicolonFollowsBodyComments(EnumDeclaration declaration) {
        String raw = declaration.getTokenRange().map(Object::toString).orElseGet(() -> rawSource.rawWithoutOwnComment(declaration));
        int firstMember = declaration.getMembers().stream()
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
        int firstMember = declaration.getMembers().stream()
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
            EnumConstantDeclaration next,
            boolean last) {
        Doc trailing = enumConstantTrailingComment(owner, declaration, next, last);
        return Doc.concat(
                comments.leading(declaration),
                Doc.text(declaration.getNameAsString()),
                enumConstantArguments(declaration),
                trailing == Doc.EMPTY ? Doc.EMPTY : Doc.concat(Doc.text(" "), trailing));
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
                    Doc.text(")"));
        }
        return Doc.concat(
                Doc.text("("),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        Doc.join(Doc.concat(Doc.text(","), Doc.HARD_LINE), declaration.getArguments().stream()
                                .map(expression)
                                .toList()))),
                Doc.HARD_LINE,
                Doc.text(")"));
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
            EnumConstantDeclaration next,
            boolean last) {
        if (next != null) {
            return next.getComment()
                    .filter(BlockComment.class::isInstance)
                    .filter(comment -> CommentIndex.startsAfterNodeOnSameLine(declaration, comment))
                    .map(comments::comment)
                    .orElse(Doc.EMPTY);
        }
        if (owner == null || !last) {
            return Doc.EMPTY;
        }
        return Doc.concat(
                comments.orphanCommentStatements(owner, comment -> CommentIndex.startsOnEndLine(declaration, comment)));
    }
}
