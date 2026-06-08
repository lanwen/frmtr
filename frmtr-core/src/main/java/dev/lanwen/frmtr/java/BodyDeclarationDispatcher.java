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
 * Routes already-formattable body declaration content to declaration-specific printers.
 *
 * <p>This helper owns only the broad {@link BodyDeclaration} subtype dispatch after {@link BodyDeclarationRuleEnvelope}
 * has decided that a declaration should be formatted structurally. The boundary keeps {@link JavaPrinter} from carrying
 * the declaration-kind decision tree while also keeping formatter pragma state, body-level raw-source recovery, and
 * leading-comment attachment out of the content dispatcher.
 *
 * <p>Callers still choose when declaration content rendering is allowed and provide each specialized renderer plus the
 * compact fallback source policy. Class, record, enum, annotation, field, method, constructor, and initializer layout
 * remains with specialized declaration printers.
 */
final class BodyDeclarationDispatcher {
    private final RawPreservedSource rawPreservedSource;
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
     * Chooses the structured content rule for a declaration whose envelope has already allowed formatting.
     */
    Doc bodyContent(BodyDeclaration<?> declaration) {
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
     * <p>This is not the body-level raw pragma gate; the envelope has already allowed formatted content and claimed the
     * declaration's leading comment slot. The fallback still goes through {@link RawPreservedSource} so comments inside
     * the compact source-derived text are accounted as intentionally preserved.
     */
    private Doc rawDeclaration(BodyDeclaration<?> declaration) {
        return rawPreservedSource.rawWithoutOwnComment(declaration, compactSource.apply(declaration));
    }
}
