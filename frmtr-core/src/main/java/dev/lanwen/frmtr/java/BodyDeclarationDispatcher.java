package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import dev.lanwen.frmtr.doc.Doc;
import java.util.function.Function;

/**
 * Routes body declarations through formatter pragma state and then narrows them to declaration-specific printers.
 *
 * <p>This helper owns the body-declaration raw-vs-formatted decision and the broad {@link BodyDeclaration} subtype
 * dispatch. The boundary keeps {@link JavaPrinter} from carrying the declaration-kind decision tree while class,
 * record, enum, annotation, field, method, constructor, and initializer layout remains with specialized declaration
 * printers.
 *
 * <p>Callers still provide leading-comment docs, raw source recovery, compact fallback text, and each specialized
 * renderer. That leaves header layout, member sequencing, width policy, raw-source policy, and compact source text
 * decisions with the printers and source helpers that already own those concerns.
 */
final class BodyDeclarationDispatcher {
    private final FormatterPragmas formatterPragmas;
    private final Function<BodyDeclaration<?>, Doc> leadingComments;
    private final Function<BodyDeclaration<?>, String> rawWithoutOwnComment;
    private final Function<BodyDeclaration<?>, String> compactSource;
    private final Function<ClassOrInterfaceDeclaration, Doc> classOrInterfaces;
    private final Function<RecordDeclaration, Doc> records;
    private final Function<EnumDeclaration, Doc> enums;
    private final Function<AnnotationDeclaration, Doc> annotationDeclarations;
    private final Function<AnnotationMemberDeclaration, Doc> annotationMembers;
    private final Function<FieldDeclaration, Doc> fields;
    private final Function<MethodDeclaration, Doc> methods;
    private final Function<CompactConstructorDeclaration, Doc> compactConstructors;
    private final Function<ConstructorDeclaration, Doc> constructors;
    private final Function<InitializerDeclaration, Doc> initializers;

    BodyDeclarationDispatcher(
            FormatterPragmas formatterPragmas,
            Function<BodyDeclaration<?>, Doc> leadingComments,
            Function<BodyDeclaration<?>, String> rawWithoutOwnComment,
            Function<BodyDeclaration<?>, String> compactSource,
            Function<ClassOrInterfaceDeclaration, Doc> classOrInterfaces,
            Function<RecordDeclaration, Doc> records,
            Function<EnumDeclaration, Doc> enums,
            Function<AnnotationDeclaration, Doc> annotationDeclarations,
            Function<AnnotationMemberDeclaration, Doc> annotationMembers,
            Function<FieldDeclaration, Doc> fields,
            Function<MethodDeclaration, Doc> methods,
            Function<CompactConstructorDeclaration, Doc> compactConstructors,
            Function<ConstructorDeclaration, Doc> constructors,
            Function<InitializerDeclaration, Doc> initializers) {
        this.formatterPragmas = formatterPragmas;
        this.leadingComments = leadingComments;
        this.rawWithoutOwnComment = rawWithoutOwnComment;
        this.compactSource = compactSource;
        this.classOrInterfaces = classOrInterfaces;
        this.records = records;
        this.enums = enums;
        this.annotationDeclarations = annotationDeclarations;
        this.annotationMembers = annotationMembers;
        this.fields = fields;
        this.methods = methods;
        this.compactConstructors = compactConstructors;
        this.constructors = constructors;
        this.initializers = initializers;
    }

    /**
     * Applies body-declaration pragmas before choosing the declaration renderer.
     *
     * <p>When a declaration re-enables formatting, {@link FormatterPragmas.PrintAction#FORMAT_WITH_LEADING} keeps the
     * legacy leading-comment prefix before structured rendering. When formatting is disabled or ignored,
     * {@link FormatterPragmas.PrintAction#RAW} emits recovered source text instead of letting subtype-specific printers
     * make formatting choices.
     */
    Doc body(BodyDeclaration<?> declaration) {
        FormatterPragmas.PrintAction action = formatterPragmas.bodyAction(declaration);
        if (action == FormatterPragmas.PrintAction.FORMAT_WITH_LEADING) {
            return Doc.concat(leadingComments.apply(declaration), bodyContent(declaration));
        }
        if (action == FormatterPragmas.PrintAction.RAW) {
            return rawBody(declaration);
        }
        return bodyContent(declaration);
    }

    /**
     * Emits a raw-passed declaration after printing its leading comments separately.
     *
     * <p>The source text has the declaration's own attached comment removed so raw pragma output does not duplicate the
     * leading comment that has already been claimed by the comment tracker.
     */
    private Doc rawBody(BodyDeclaration<?> declaration) {
        return Doc.concat(leadingComments.apply(declaration), Doc.text(rawWithoutOwnComment.apply(declaration)));
    }

    /**
     * Narrows a declaration to the printer that owns that declaration shape.
     */
    private Doc bodyContent(BodyDeclaration<?> declaration) {
        return switch (declaration) {
            case ClassOrInterfaceDeclaration classDeclaration -> classOrInterfaces.apply(classDeclaration);
            case RecordDeclaration recordDeclaration -> records.apply(recordDeclaration);
            case EnumDeclaration enumDeclaration -> enums.apply(enumDeclaration);
            case AnnotationDeclaration annotationDeclaration -> annotationDeclarations.apply(annotationDeclaration);
            case AnnotationMemberDeclaration annotationMemberDeclaration -> annotationMembers.apply(annotationMemberDeclaration);
            case FieldDeclaration fieldDeclaration -> fields.apply(fieldDeclaration);
            case MethodDeclaration methodDeclaration -> methods.apply(methodDeclaration);
            case CompactConstructorDeclaration compactConstructorDeclaration -> compactConstructors.apply(compactConstructorDeclaration);
            case ConstructorDeclaration constructorDeclaration -> constructors.apply(constructorDeclaration);
            case InitializerDeclaration initializerDeclaration -> initializers.apply(initializerDeclaration);
            default -> rawDeclaration(declaration);
        };
    }

    /**
     * Falls back to compact source text for declaration kinds without a structured formatter.
     *
     * <p>Leading comments are still attached through the normal comment tracker so unknown declarations behave like
     * formatted body members rather than raw pragma ranges.
     */
    private Doc rawDeclaration(BodyDeclaration<?> declaration) {
        return Doc.concat(leadingComments.apply(declaration), Doc.text(compactSource.apply(declaration)));
    }
}
