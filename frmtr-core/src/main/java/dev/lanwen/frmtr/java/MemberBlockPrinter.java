package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.comments.Comment;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Sequences already formatted type members inside Java member blocks.
 *
 * <p>This helper owns the source-range-sensitive ordering of declarations, orphan comments, opening-brace line comments,
 * and blank lines inside class, interface, record, enum, and annotation bodies. The boundary exists so {@link
 * JavaPrinter} can keep declaration formatting decisions local to each declaration printer while member-block trivia
 * rules stay together. It intentionally does not choose how declarations render, how statements inside methods render,
 * or which annotations count as declaration annotations.
 */
final class MemberBlockPrinter {
    private final RawSource rawSource;
    private final JavaFormatter.CommentTracker comments;
    private final Predicate<BodyDeclaration<?>> hasDeclarationAnnotations;

    MemberBlockPrinter(
            RawSource rawSource,
            JavaFormatter.CommentTracker comments,
            Predicate<BodyDeclaration<?>> hasDeclarationAnnotations) {
        this.rawSource = rawSource;
        this.comments = comments;
        this.hasDeclarationAnnotations = hasDeclarationAnnotations;
    }

    /**
     * Prints a brace-delimited member block while preserving source-only spacing signals around members and orphan
     * comments.
     *
     * <p>The member renderer is invoked once per declaration before sequencing starts, so declaration formatting remains
     * owned by the caller while this method owns only the surrounding member-content layout.
     */
    Doc memberBlock(NodeList<BodyDeclaration<?>> members, Node owner, Function<BodyDeclaration<?>, Doc> memberRenderer) {
        List<Doc> memberDocs = new ArrayList<>(members.stream().map(memberRenderer).toList());
        Doc openingBraceTrailingComment = openingBraceTrailingLineComment(owner);
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
                    Doc.text("}"));
        }
        Doc contents = memberContents(owner, members, memberDocs);
        if (openingBraceTrailingComment != Doc.EMPTY) {
            contents = Doc.concat(openingBraceTrailingComment, Doc.HARD_LINE, contents);
        }
        return Doc.concat(
                Doc.text("{"),
                Doc.indent(Doc.concat(memberBlockOpeningBreak(owner), contents)),
                Doc.HARD_LINE,
                Doc.text("}"));
    }

    /**
     * Recovers a line comment written after the opening brace of a member block.
     *
     * <p>This raw-source path handles the layout case where the comment belongs to the brace line rather than to a
     * declaration. Comments after the first newline continue through normal orphan-comment sequencing.
     */
    private Doc openingBraceTrailingLineComment(Node node) {
        String raw = rawSource.raw(node);
        int openingBrace = raw.indexOf('{');
        if (openingBrace < 0) {
            return Doc.EMPTY;
        }
        int commentStart = raw.indexOf("//", openingBrace);
        // Only a comment before the first newline after the brace is attached to the opening brace line.
        if (commentStart < 0 || raw.substring(openingBrace, commentStart).contains("\n")) {
            return Doc.EMPTY;
        }
        int commentEnd = raw.indexOf('\n', commentStart);
        String comment = commentEnd < 0 ? raw.substring(commentStart) : raw.substring(commentStart, commentEnd);
        return Doc.text(comment.stripTrailing());
    }

    /**
     * Chooses how much vertical space follows the opening brace before the first member.
     *
     * <p>Records and interfaces are commonly written as compact declaration lists, so they skip the extra blank line
     * that class, enum, and annotation bodies keep for the formatter's member-block style.
     */
    private Doc memberBlockOpeningBreak(Node owner) {
        if (owner instanceof RecordDeclaration
                || owner instanceof ClassOrInterfaceDeclaration declaration && declaration.isInterface()) {
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
        List<Doc> contents = new ArrayList<>();
        List<Comment> orphanComments = owner.getOrphanComments().stream()
                .sorted(Comparator.comparingInt(comment -> CommentIndex.beginLine(comment, Integer.MAX_VALUE)))
                .toList();
        int orphanIndex = 0;
        int previousEndLine = Integer.MIN_VALUE;
        BodyDeclaration<?> previousMember = null;
        boolean previousWasMember = false;
        for (int i = 0; i < memberDocs.size(); i++) {
            BodyDeclaration<?> currentMember = members.get(i);
            int currentBeginLine = CommentIndex.beginLine(currentMember, Integer.MAX_VALUE);
            // Orphan comments that start before this declaration belong in the source gap before the member.
            while (orphanIndex < orphanComments.size()
                    && CommentIndex.beginLine(orphanComments.get(orphanIndex), Integer.MAX_VALUE) < currentBeginLine) {
                Comment comment = orphanComments.get(orphanIndex++);
                addMemberContentSeparator(
                        contents,
                        owner,
                        previousEndLine,
                        CommentIndex.beginLine(comment, Integer.MAX_VALUE),
                        previousWasMember,
                        null,
                        null);
                Doc commentDoc = comments.comment(comment);
                // Already-printed comments return EMPTY, so separator state only moves when text is emitted.
                if (commentDoc != Doc.EMPTY) {
                    contents.add(commentDoc);
                    previousEndLine = CommentIndex.endLine(comment, Integer.MAX_VALUE);
                    previousWasMember = false;
                }
            }
            addMemberContentSeparator(
                    contents, owner, previousEndLine, currentBeginLine, previousWasMember, previousMember, currentMember);
            contents.add(memberDocs.get(i));
            previousEndLine = CommentIndex.endLine(currentMember, Integer.MAX_VALUE);
            previousMember = currentMember;
            previousWasMember = true;
        }
        // Anything left after the declaration walk is trailing source trivia in the member block.
        while (orphanIndex < orphanComments.size()) {
            Comment comment = orphanComments.get(orphanIndex++);
            addMemberContentSeparator(
                    contents,
                    owner,
                    previousEndLine,
                    CommentIndex.beginLine(comment, Integer.MAX_VALUE),
                    previousWasMember,
                    null,
                    null);
            Doc commentDoc = comments.comment(comment);
            // Already-printed comments return EMPTY, so separator state only moves when text is emitted.
            if (commentDoc != Doc.EMPTY) {
                contents.add(commentDoc);
                previousEndLine = CommentIndex.endLine(comment, Integer.MAX_VALUE);
                previousWasMember = false;
            }
        }
        return Doc.concat(contents);
    }

    /**
     * Appends the separator needed before the next printed member-block item.
     *
     * <p>Member-to-member gaps use semantic declaration rules, while gaps involving orphan comments use only original
     * line distance because comments are source trivia rather than declarations.
     */
    private void addMemberContentSeparator(
            List<Doc> contents,
            Node owner,
            int previousEndLine,
            int currentBeginLine,
            boolean previousWasMember,
            BodyDeclaration<?> previousMember,
            BodyDeclaration<?> currentMember) {
        // The first emitted item is already separated from the opening brace by the block opening break.
        if (contents.isEmpty()) {
            return;
        }
        // Only two real declarations can use member-specific policies such as interface method compaction.
        if (previousWasMember && previousMember != null && currentMember != null) {
            contents.add(memberSeparator(owner, previousMember, currentMember));
            return;
        }
        contents.add(sourceLineSeparator(previousEndLine, currentBeginLine));
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
     * <p>Interfaces keep adjacent unannotated abstract methods tight, adjacent fields preserve the source's blank-line
     * choice unless declaration annotations force separation, and all other member pairs use a blank line.
     */
    private Doc memberSeparator(Node owner, BodyDeclaration<?> previous, BodyDeclaration<?> current) {
        // Interface methods without bodies read as a signature list unless annotations make each item standalone.
        if (owner instanceof ClassOrInterfaceDeclaration declaration
                && declaration.isInterface()
                && previous instanceof MethodDeclaration previousMethod
                && current instanceof MethodDeclaration currentMethod
                && previousMethod.getBody().isEmpty()
                && currentMethod.getBody().isEmpty()
                && !hasDeclarationAnnotations.test(previous)
                && !hasDeclarationAnnotations.test(current)) {
            return Doc.HARD_LINE;
        }
        // Mixed member kinds and non-field declarations keep the formatter's normal blank-line separation.
        if (!(previous instanceof FieldDeclaration) || !(current instanceof FieldDeclaration)) {
            return Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE);
        }
        // Adjacent fields preserve source grouping unless annotations make the declarations visually heavier.
        boolean hasBlankLineBetween = previous.getRange()
                .flatMap(previousRange -> current.getRange()
                        .map(currentRange -> currentRange.begin.line > previousRange.end.line + 1))
                .orElse(true);
        return hasBlankLineBetween || hasDeclarationAnnotations.test(previous) || hasDeclarationAnnotations.test(current)
                ? Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE)
                : Doc.HARD_LINE;
    }
}
