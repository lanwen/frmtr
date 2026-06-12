package dev.lanwen.frmtr.java;

import com.github.javaparser.GeneratedJavaParserConstants;
import com.github.javaparser.JavaToken;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.type.Type;
import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Prints annotation type declarations after body dispatch has selected the annotation branch.
 *
 * <p>This helper owns the annotation-specific declaration tree: the {@code @interface} header, the empty member-block
 * shape, the blank-line separation between annotation members, and the optional default value on annotation member
 * declarations. It intentionally delegates member declarations back through the caller because annotation bodies can
 * contain ordinary declarations, and those declarations must keep using the same pragma, body-leading comment, and
 * declaration formatting decisions as the rest of {@link JavaPrinter}.
 *
 * <p>Representative fixture pairs live at
 * {@code frmtr-core/src/test/resources/format/annotation-interface-declaration/input.java} and
 * {@code frmtr-core/src/test/resources/format/annotation-interface-declaration/frmtr-120.output.java}.
 */
final class AnnotationDeclarationPrinter {
    private static final String ANNOTATION_MEMBER_LIST_RECOVERY_FAILURE =
            "Unable to recover Java parse error inside annotation declaration member list: ";

    private final FormatterOptions options;
    private final LayoutWidth layoutWidth;
    private final SourceText sourceText;
    private final RecoveredListPlanner recoveredListPlanner;
    private final RecoveredRawGapPrinter rawGaps;
    private final boolean recoverParseProblems;
    private final Function<NodeWithAnnotations<?>, Doc> annotations;
    private final Function<NodeWithModifiers<?>, String> modifiers;
    private final Function<Type, String> compactTypeLike;
    private final Function<Type, Doc> typeBody;
    private final Function<Expression, Doc> expression;
    private final Function<BodyDeclaration<?>, Doc> memberRenderer;

    /**
     * Names the previous recovered annotation-body item so raw gaps and formatted members do not duplicate separators.
     */
    private enum EntryKind {
        /** No recovered annotation body content has been emitted yet. */
        NONE,

        /** The previous recovered annotation body item was a parsed member rendered structurally. */
        VALID_MEMBER,

        /** The previous recovered annotation body item was a raw source gap that owns the following separator. */
        RAW_GAP,

        /** The previous recovered annotation body item was a raw source gap whose trailing break moved to formatter docs. */
        RAW_GAP_WITH_TRAILING_BREAK
    }

    AnnotationDeclarationPrinter(
            JavaFormatContext context,
            Function<NodeWithAnnotations<?>, Doc> annotations,
            Function<NodeWithModifiers<?>, String> modifiers,
            Function<Type, String> compactTypeLike,
            Function<Type, Doc> typeBody,
            Function<Expression, Doc> expression,
            Function<BodyDeclaration<?>, Doc> memberRenderer) {
        this.options = context.options;
        this.layoutWidth = context.layoutWidth;
        this.sourceText = context.sourceText;
        this.recoveredListPlanner = context.recoveredListPlanner;
        this.rawGaps = new RecoveredRawGapPrinter(context, AnnotationDeclarationPrinter::annotationMemberListRecoveryFailure);
        this.recoverParseProblems = context.recoverParseProblems;
        this.annotations = annotations;
        this.modifiers = modifiers;
        this.compactTypeLike = compactTypeLike;
        this.typeBody = typeBody;
        this.expression = expression;
        this.memberRenderer = memberRenderer;
    }

    /**
     * Prints the full annotation type declaration while leaving body member rendering to the supplied callback.
     */
    Doc annotationDeclaration(AnnotationDeclaration declaration) {
        List<Doc> header = new ArrayList<>();
        header.add(annotations.apply(declaration));
        header.add(Doc.text(modifiers.apply(declaration)));
        header.add(Doc.text("@interface " + declaration.getNameAsString() + " "));
        header.add(annotationMemberBlock(declaration));
        return Doc.concat(header);
    }

    /**
     * Chooses between the compact empty block and the member-list block with blank lines between rendered members.
     *
     * <p>Each member is rendered through the caller so nested declarations, pragmas, and comments use the shared body
     * formatting path instead of a local annotation-only shortcut.
     */
    private Doc annotationMemberBlock(AnnotationDeclaration declaration) {
        Optional<RecoveredListPlanner.Plan<BodyDeclaration<?>>> recoveryPlan = recoveryPlan(declaration);
        if (recoveryPlan.isPresent() && hasRawGap(recoveryPlan.orElseThrow())) {
            return recoveredAnnotationMemberBlock(declaration, recoveryPlan.orElseThrow());
        }
        if (declaration.getMembers().isEmpty()) {
            return Doc.text("{}");
        }
        List<Doc> memberDocs = declaration.getMembers().stream().map(memberRenderer).toList();
        return Doc.concat(
                Doc.text("{"),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE), memberDocs))),
                Doc.HARD_LINE,
                Doc.text("}"));
    }

    /**
     * Emits a recovered annotation member block while leaving valid members on their normal renderer.
     *
     * <p>Raw gaps are limited to the annotation body interior. They own malformed members and the source separators
     * around them, while adjacent valid members keep the annotation body's normal blank-line separation.
     */
    private Doc recoveredAnnotationMemberBlock(
            AnnotationDeclaration declaration,
            RecoveredListPlanner.Plan<BodyDeclaration<?>> plan) {
        List<RecoveredRawGapPrinter.RawGapRegion> rawGapRegions = rawGaps.rawGapRegions(plan);
        rawGaps.requireRecoverableRawRegions(declaration, rawGapRegions);

        List<Doc> contents = new ArrayList<>();
        EntryKind previousEntry = EntryKind.NONE;
        int rawGapIndex = 0;
        for (RecoveredListPlanner.Entry<BodyDeclaration<?>> entry : plan.entries()) {
            switch (entry) {
                case RecoveredListPlanner.ValidSibling<?> valid -> {
                    BodyDeclaration<?> currentMember = (BodyDeclaration<?>) valid.sibling();
                    appendSeparatorBeforeRecoveredAnnotationMember(contents, previousEntry);
                    contents.add(memberRenderer.apply(currentMember));
                    previousEntry = EntryKind.VALID_MEMBER;
                }
                case RecoveredListPlanner.RawGap<?> ignored -> {
                    RecoveredRawGapPrinter.RawGapRegion rawRegion = rawGapRegions.get(rawGapIndex++);
                    if (rawRegion.region().beginOffset() < rawRegion.region().endOffset()) {
                        contents.add(rawGaps.raw(declaration, rawRegion, "annotationDeclarationMemberList"));
                    }
                    previousEntry = rawRegion.trailingBreakReplaced()
                            ? EntryKind.RAW_GAP_WITH_TRAILING_BREAK
                            : EntryKind.RAW_GAP;
                }
            }
        }
        if (contents.isEmpty()) {
            return Doc.text("{}");
        }
        Doc closingBreak = previousEntry == EntryKind.RAW_GAP ? Doc.EMPTY : Doc.HARD_LINE;
        return Doc.concat(Doc.text("{"), Doc.indent(Doc.concat(contents)), closingBreak, Doc.text("}"));
    }

    private void appendSeparatorBeforeRecoveredAnnotationMember(
            List<Doc> contents,
            EntryKind previousEntry) {
        switch (previousEntry) {
            case NONE -> contents.add(Doc.HARD_LINE);
            case VALID_MEMBER -> contents.add(annotationMemberSeparator());
            case RAW_GAP_WITH_TRAILING_BREAK -> contents.add(Doc.HARD_LINE);
            case RAW_GAP -> {
                // Raw source already owns the separation before this formatted member.
            }
        }
    }

    private Doc annotationMemberSeparator() {
        return Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE);
    }

    private Optional<RecoveredListPlanner.Plan<BodyDeclaration<?>>> recoveryPlan(AnnotationDeclaration declaration) {
        if (!recoverParseProblems || !hasRecoverableAnnotationMemberListProblem(declaration)) {
            return Optional.empty();
        }
        RecoveredListPlanner.Plan<BodyDeclaration<?>> plan = recoveredListPlanner.plan(
                declaration,
                requireAnnotationBodyInteriorRegion(declaration),
                declaration.getMembers(),
                member -> member.getParsed() == Node.Parsedness.PARSED);
        if (!plan.isSafe()) {
            throw annotationMemberListRecoveryFailure(plan.unsafe().orElseThrow().reason());
        }
        return Optional.of(plan);
    }

    private SourceRegion requireAnnotationBodyInteriorRegion(AnnotationDeclaration declaration) {
        try {
            return annotationBodyInteriorRegion(declaration);
        } catch (IllegalArgumentException exception) {
            throw annotationMemberListRecoveryFailure(exception.getMessage(), exception);
        }
    }

    private SourceRegion annotationBodyInteriorRegion(AnnotationDeclaration declaration) {
        List<JavaToken> tokens = declaration.getTokenRange()
                .map(tokenRange -> {
                    List<JavaToken> collected = new ArrayList<>();
                    tokenRange.forEach(collected::add);
                    return collected;
                })
                .orElseThrow(() -> new IllegalArgumentException("annotation declaration is missing a token range"));
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
            throw new IllegalArgumentException("annotation declaration body source range must contain matching braces");
        }
        SourceRegion openingRegion = tokenRegion(openingBrace, "opening brace");
        SourceRegion closingRegion = tokenRegion(closingBrace, "closing brace");
        if (closingRegion.beginOffset() < openingRegion.endOffset()) {
            throw new IllegalArgumentException("annotation declaration body braces are not ordered");
        }
        return sourceText.region(openingRegion.endOffset(), closingRegion.beginOffset());
    }

    private SourceRegion tokenRegion(JavaToken token, String description) {
        return token.getRange()
                .map(sourceText::region)
                .orElseThrow(() -> new IllegalArgumentException(
                        "annotation declaration body " + description + " is missing a source range"));
    }

    /**
     * Prints one annotation member declaration, appending the default-value clause only when the source declares one.
     */
    Doc annotationMember(AnnotationMemberDeclaration declaration) {
        List<Doc> docs = new ArrayList<>();
        docs.add(annotations.apply(declaration));
        String modifierText = modifiers.apply(declaration);
        docs.add(Doc.text(modifierText));
        Doc defaultValue = declaration.getDefaultValue()
                .map(value -> Doc.indent(Doc.concat(Doc.LINE, Doc.text("default "), expression.apply(value))))
                .orElse(Doc.EMPTY);
        docs.add(Doc.group(Doc.concat(
                annotationMemberSignature(declaration, modifierText),
                defaultValue,
                Doc.text(";"))));
        return Doc.concat(docs);
    }

    /**
     * Keeps the annotation member type and method name together when only the default-value clause forces a break.
     */
    private Doc annotationMemberSignature(AnnotationMemberDeclaration declaration, String modifierText) {
        String flatSignature = compactTypeLike.apply(declaration.getType()) + " " + declaration.getNameAsString() + "()";
        if (layoutWidth.currentIndented(modifierText + flatSignature) <= options.lineWidth()) {
            return Doc.text(flatSignature);
        }
        return Doc.concat(typeBody.apply(declaration.getType()), Doc.text(" " + declaration.getNameAsString() + "()"));
    }

    static boolean hasRecoverableAnnotationMemberListProblem(AnnotationDeclaration declaration) {
        return declaration.getParsed() == Node.Parsedness.PARSED
                && declaration.getMembers().stream().anyMatch(member -> !isFullyParsed(member))
                && declaration.stream()
                        .filter(node -> node != declaration)
                        .filter(node -> node.getParsed() != Node.Parsedness.PARSED)
                        .allMatch(node -> nearestAnnotationMemberListSibling(node)
                                .filter(declaration.getMembers()::contains)
                                .isPresent());
    }

    static boolean isRecoverableAnnotationMemberListSibling(BodyDeclaration<?> member) {
        return member.getParentNode()
                .filter(AnnotationDeclaration.class::isInstance)
                .map(AnnotationDeclaration.class::cast)
                .filter(AnnotationDeclarationPrinter::hasRecoverableAnnotationMemberListProblem)
                .isPresent();
    }

    static Optional<BodyDeclaration<?>> nearestAnnotationMemberListSibling(Node recoveredNode) {
        Optional<Node> current = Optional.of(recoveredNode);
        while (current.isPresent()) {
            Node node = current.orElseThrow();
            if (node instanceof BodyDeclaration<?> declaration && isAnnotationMemberListSibling(declaration)) {
                return Optional.of(declaration);
            }
            current = node.getParentNode();
        }
        return Optional.empty();
    }

    private static boolean isAnnotationMemberListSibling(BodyDeclaration<?> declaration) {
        return declaration.getParentNode()
                .filter(AnnotationDeclaration.class::isInstance)
                .map(AnnotationDeclaration.class::cast)
                .filter(owner -> owner.getMembers().contains(declaration))
                .isPresent();
    }

    private static boolean isFullyParsed(Node node) {
        return node.stream().allMatch(descendant -> descendant.getParsed() == Node.Parsedness.PARSED);
    }

    private static boolean hasRawGap(RecoveredListPlanner.Plan<BodyDeclaration<?>> plan) {
        return plan.entries().stream().anyMatch(RecoveredListPlanner.RawGap.class::isInstance);
    }

    private static FormatterException annotationMemberListRecoveryFailure(String reason) {
        return new FormatterException(ANNOTATION_MEMBER_LIST_RECOVERY_FAILURE + reason);
    }

    private static FormatterException annotationMemberListRecoveryFailure(String reason, Throwable cause) {
        return new FormatterException(ANNOTATION_MEMBER_LIST_RECOVERY_FAILURE + reason, cause);
    }
}
