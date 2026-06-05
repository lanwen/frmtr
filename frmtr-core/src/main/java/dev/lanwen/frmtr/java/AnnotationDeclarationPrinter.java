package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.type.Type;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Prints annotation type declarations after body dispatch has selected the annotation branch.
 *
 * <p>This helper owns the annotation-specific declaration tree: the {@code @interface} header, the empty member-block
 * shape, the blank-line separation between annotation members, and the optional default value on annotation member
 * declarations. It intentionally delegates member declarations back through the caller because annotation bodies can
 * contain ordinary declarations, and those declarations must keep using the same pragma, comment, and declaration
 * formatting decisions as the rest of {@link JavaPrinter}.
 *
 * <p>Representative fixture pairs live at
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/annotation_interface_declaration/input.java} and
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/annotation_interface_declaration/frmtr.output.java}.
 */
final class AnnotationDeclarationPrinter {
    private final JavaFormatter.CommentTracker comments;
    private final Function<NodeWithAnnotations<?>, Doc> annotations;
    private final Function<NodeWithModifiers<?>, String> modifiers;
    private final Function<Type, String> compactTypeLike;
    private final Function<Expression, Doc> expression;
    private final Function<BodyDeclaration<?>, Doc> memberRenderer;

    AnnotationDeclarationPrinter(
            JavaFormatter.CommentTracker comments,
            Function<NodeWithAnnotations<?>, Doc> annotations,
            Function<NodeWithModifiers<?>, String> modifiers,
            Function<Type, String> compactTypeLike,
            Function<Expression, Doc> expression,
            Function<BodyDeclaration<?>, Doc> memberRenderer) {
        this.comments = comments;
        this.annotations = annotations;
        this.modifiers = modifiers;
        this.compactTypeLike = compactTypeLike;
        this.expression = expression;
        this.memberRenderer = memberRenderer;
    }

    /**
     * Prints the full annotation type declaration while leaving body member rendering to the supplied callback.
     */
    Doc annotationDeclaration(AnnotationDeclaration declaration) {
        List<Doc> header = new ArrayList<>();
        header.add(comments.leading(declaration));
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
     * Prints one annotation member declaration, appending the default-value clause only when the source declares one.
     */
    Doc annotationMember(AnnotationMemberDeclaration declaration) {
        List<Doc> docs = new ArrayList<>();
        docs.add(comments.leading(declaration));
        docs.add(annotations.apply(declaration));
        docs.add(Doc.text(modifiers.apply(declaration)));
        docs.add(Doc.text(compactTypeLike.apply(declaration.getType()) + " " + declaration.getNameAsString() + "()"));
        declaration.getDefaultValue()
                .ifPresent(defaultValue -> docs.add(Doc.concat(Doc.text(" default "), expression.apply(defaultValue))));
        docs.add(Doc.text(";"));
        return Doc.concat(docs);
    }
}
