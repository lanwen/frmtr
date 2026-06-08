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
    private final RawPreservedSource rawPreservedSource;
    private final FormatterPragmas formatterPragmas;
    private final Function<BodyDeclaration<?>, Doc> leadingComments;
    private final Function<BodyDeclaration<?>, String> compactSource;
    private final JavaFormatRule<ClassOrInterfaceDeclaration> classOrInterfaces;
    private final JavaFormatRule<RecordDeclaration> records;
    private final JavaFormatRule<EnumDeclaration> enums;
    private final JavaFormatRule<AnnotationDeclaration> annotationDeclarations;
    private final JavaFormatRule<AnnotationMemberDeclaration> annotationMembers;
    private final JavaFormatRule<FieldDeclaration> fields;
    private final JavaFormatRule<MethodDeclaration> methods;
    private final JavaFormatRule<CompactConstructorDeclaration> compactConstructors;
    private final JavaFormatRule<ConstructorDeclaration> constructors;
    private final JavaFormatRule<InitializerDeclaration> initializers;

    BodyDeclarationDispatcher(
            RawPreservedSource rawPreservedSource,
            FormatterPragmas formatterPragmas,
            Function<BodyDeclaration<?>, Doc> leadingComments,
            Function<BodyDeclaration<?>, String> compactSource,
            JavaFormatRule<ClassOrInterfaceDeclaration> classOrInterfaces,
            JavaFormatRule<RecordDeclaration> records,
            JavaFormatRule<EnumDeclaration> enums,
            JavaFormatRule<AnnotationDeclaration> annotationDeclarations,
            JavaFormatRule<AnnotationMemberDeclaration> annotationMembers,
            JavaFormatRule<FieldDeclaration> fields,
            JavaFormatRule<MethodDeclaration> methods,
            JavaFormatRule<CompactConstructorDeclaration> compactConstructors,
            JavaFormatRule<ConstructorDeclaration> constructors,
            JavaFormatRule<InitializerDeclaration> initializers) {
        this.rawPreservedSource = rawPreservedSource;
        this.formatterPragmas = formatterPragmas;
        this.leadingComments = leadingComments;
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
        return Doc.concat(leadingComments.apply(declaration), rawPreservedSource.rawWithoutOwnComment(declaration));
    }

    /**
     * Narrows a declaration to the printer that owns that declaration shape.
     */
    private Doc bodyContent(BodyDeclaration<?> declaration) {
        return switch (declaration) {
            case ClassOrInterfaceDeclaration classDeclaration -> classOrInterfaces.format(classDeclaration);
            case RecordDeclaration recordDeclaration -> records.format(recordDeclaration);
            case EnumDeclaration enumDeclaration -> enums.format(enumDeclaration);
            case AnnotationDeclaration annotationDeclaration -> annotationDeclarations.format(annotationDeclaration);
            case AnnotationMemberDeclaration annotationMemberDeclaration -> annotationMembers.format(annotationMemberDeclaration);
            case FieldDeclaration fieldDeclaration -> fields.format(fieldDeclaration);
            case MethodDeclaration methodDeclaration -> methods.format(methodDeclaration);
            case CompactConstructorDeclaration compactConstructorDeclaration -> compactConstructors.format(compactConstructorDeclaration);
            case ConstructorDeclaration constructorDeclaration -> constructors.format(constructorDeclaration);
            case InitializerDeclaration initializerDeclaration -> initializers.format(initializerDeclaration);
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
        return Doc.concat(
                leadingComments.apply(declaration),
                rawPreservedSource.rawWithoutOwnComment(declaration, compactSource.apply(declaration)));
    }
}
