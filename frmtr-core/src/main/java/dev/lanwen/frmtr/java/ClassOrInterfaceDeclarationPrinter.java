package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.TypeParameter;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Prints class and interface declarations after body dispatch has selected the class-or-interface branch.
 *
 * <p>This helper owns the declaration decision tree for classes and interfaces: the raw commented-interface escape
 * hatch, the flat-versus-broken header choice, type-parameter break rules, header clause layout, and the same-line or
 * broken body start. It intentionally leaves shared type-clause rendering, type-parameter document internals,
 * annotation/modifier text, and member sequencing with {@link JavaPrinter}, {@link CallableSignaturePrinter}, and
 * {@link MemberBlockPrinter}; callers provide those decisions as callbacks so enum, record, callable, and type-body
 * flows continue to share one implementation.
 *
 * <p>Representative fixture pairs live at
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/comments/interface/input.java} and
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/comments/interface/frmtr.output.java};
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/sealed/input.java} and
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/sealed/frmtr.output.java};
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/extends_abstract_class_and_implements_interfaces/input.java}
 * and
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/extends_abstract_class_and_implements_interfaces/frmtr.output.java};
 * and {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/generic_class/input.java} plus
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/generic_class/frmtr.output.java}.
 */
final class ClassOrInterfaceDeclarationPrinter {
    private final JavaFormatter.CommentTracker comments;
    private final RawSource rawSource;
    private final FormatterOptions options;
    private final CommentedInterfacePrinter commentedInterfaces;
    private final CallableSignaturePrinter callableSignatures;
    private final Function<NodeWithAnnotations<?>, Doc> annotations;
    private final Function<NodeWithModifiers<?>, String> modifiers;
    private final Function<NodeList<ClassOrInterfaceType>, Optional<Doc>> inlineExtendsTypes;
    private final Function<NodeList<ClassOrInterfaceType>, Optional<Doc>> inlineImplementsTypes;
    private final Function<NodeList<ClassOrInterfaceType>, Optional<Doc>> inlinePermitsTypes;
    private final TypeClausePrinter typeClause;
    private final Function<NodeList<TypeParameter>, String> flatTypeParameters;
    private final BiFunction<String, NodeList<ClassOrInterfaceType>, String> flatTypeClause;
    private final ToIntFunction<String> currentIndentedWidth;
    private final Function<ClassOrInterfaceDeclaration, Doc> memberBlock;

    ClassOrInterfaceDeclarationPrinter(
            JavaFormatter.CommentTracker comments,
            RawSource rawSource,
            FormatterOptions options,
            CommentedInterfacePrinter commentedInterfaces,
            CallableSignaturePrinter callableSignatures,
            Function<NodeWithAnnotations<?>, Doc> annotations,
            Function<NodeWithModifiers<?>, String> modifiers,
            Function<NodeList<ClassOrInterfaceType>, Optional<Doc>> inlineExtendsTypes,
            Function<NodeList<ClassOrInterfaceType>, Optional<Doc>> inlineImplementsTypes,
            Function<NodeList<ClassOrInterfaceType>, Optional<Doc>> inlinePermitsTypes,
            TypeClausePrinter typeClause,
            Function<NodeList<TypeParameter>, String> flatTypeParameters,
            BiFunction<String, NodeList<ClassOrInterfaceType>, String> flatTypeClause,
            ToIntFunction<String> currentIndentedWidth,
            Function<ClassOrInterfaceDeclaration, Doc> memberBlock) {
        this.comments = comments;
        this.rawSource = rawSource;
        this.options = options;
        this.commentedInterfaces = commentedInterfaces;
        this.callableSignatures = callableSignatures;
        this.annotations = annotations;
        this.modifiers = modifiers;
        this.inlineExtendsTypes = inlineExtendsTypes;
        this.inlineImplementsTypes = inlineImplementsTypes;
        this.inlinePermitsTypes = inlinePermitsTypes;
        this.typeClause = typeClause;
        this.flatTypeParameters = flatTypeParameters;
        this.flatTypeClause = flatTypeClause;
        this.currentIndentedWidth = currentIndentedWidth;
        this.memberBlock = memberBlock;
    }

    /**
     * Prints the complete class or interface declaration, including the raw-source escape hatch for commented
     * interface headers.
     *
     * <p>The raw fallback is checked only for interfaces whose header contains comments that JavaParser does not expose
     * in a structured way. All other declarations stay on the structured path so ordinary classes, sealed headers,
     * generic classes, and interface bodies keep sharing the same doc pipeline.
     */
    Doc classOrInterface(ClassOrInterfaceDeclaration declaration) {
        String raw = rawSource.raw(declaration);
        if (declaration.isInterface() && commentedInterfaces.hasCommentedHeader(raw)) {
            return Doc.concat(comments.leading(declaration), Doc.text(commentedInterfaces.formatCommentedInterface(raw)));
        }
        if (shouldBreakClassOrInterfaceHeader(declaration)) {
            return brokenClassOrInterface(declaration);
        }
        List<Doc> header = new ArrayList<>();
        header.add(comments.leading(declaration));
        header.add(annotations.apply(declaration));
        header.add(Doc.text(modifiers.apply(declaration)));
        header.add(Doc.text(declaration.isInterface() ? "interface " : "class "));
        header.add(Doc.text(declaration.getNameAsString()));
        if (!declaration.getTypeParameters().isEmpty()) {
            header.add(callableSignatures.typeParameters(declaration.getTypeParameters()));
        }
        inlineExtendsTypes.apply(declaration.getExtendedTypes()).ifPresent(header::add);
        inlineImplementsTypes.apply(declaration.getImplementedTypes()).ifPresent(header::add);
        inlinePermitsTypes.apply(declaration.getPermittedTypes()).ifPresent(header::add);
        header.add(Doc.text(" "));
        header.add(memberBlock.apply(declaration));
        return Doc.concat(header);
    }

    /**
     * Decides whether a header with type clauses must leave the flat path before printing the member block.
     *
     * <p>Headers without {@code extends}, {@code implements}, or {@code permits} clauses keep the simple inline form.
     * Clause-bearing headers compare the compact declaration text plus the following body opener against the configured
     * line width, so a non-empty body budgets for one opening brace while an empty body budgets for the full empty
     * block.
     */
    private boolean shouldBreakClassOrInterfaceHeader(ClassOrInterfaceDeclaration declaration) {
        if (declaration.getExtendedTypes().isEmpty()
                && declaration.getImplementedTypes().isEmpty()
                && declaration.getPermittedTypes().isEmpty()) {
            return false;
        }
        String flatHeader = modifiers.apply(declaration)
                + (declaration.isInterface() ? "interface " : "class ")
                + declaration.getNameAsString()
                + flatTypeParameters.apply(declaration.getTypeParameters())
                + flatTypeClause.apply("extends", declaration.getExtendedTypes())
                + flatTypeClause.apply("implements", declaration.getImplementedTypes())
                + flatTypeClause.apply("permits", declaration.getPermittedTypes());
        return flatHeader.length() + 1 + flatMemberBlockWidth(declaration) > options.lineWidth();
    }

    /**
     * Prints the broken header selected after the compact header no longer fits.
     *
     * <p>Type parameters may break before clauses so long generic heads stay readable. Multiple clauses force each
     * clause to start from the same broken-header shape instead of attaching the first clause to the type-parameter
     * block and making later clauses look unrelated.
     */
    private Doc brokenClassOrInterface(ClassOrInterfaceDeclaration declaration) {
        List<Doc> header = new ArrayList<>();
        header.add(comments.leading(declaration));
        header.add(annotations.apply(declaration));
        header.add(Doc.text(modifiers.apply(declaration)));
        header.add(Doc.text(declaration.isInterface() ? "interface " : "class "));
        header.add(Doc.text(declaration.getNameAsString()));
        boolean breakTypeParameters = classOrInterfaceTypeParametersBreak(declaration);
        if (breakTypeParameters) {
            header.add(callableSignatures.brokenTypeParameters(
                    declaration.getTypeParameters(),
                    classOrInterfaceHeaderClauses(declaration) > 1));
        } else if (!declaration.getTypeParameters().isEmpty()) {
            header.add(callableSignatures.typeParameters(declaration.getTypeParameters()));
        }
        boolean breakClauses = classOrInterfaceHeaderClauses(declaration) > 1 || !breakTypeParameters;
        typeClause.print("extends", declaration.getExtendedTypes(), breakClauses).ifPresent(header::add);
        typeClause.print("implements", declaration.getImplementedTypes(), breakClauses).ifPresent(header::add);
        typeClause.print("permits", declaration.getPermittedTypes(), breakClauses).ifPresent(header::add);
        header.add(emptyMemberBlock(declaration) || !breakClauses ? Doc.text(" ") : Doc.HARD_LINE);
        header.add(memberBlock.apply(declaration));
        return Doc.concat(header);
    }

    /**
     * Decides whether the {@code <...>} section should break before header clauses are printed.
     *
     * <p>Long multi-clause headers first try to keep the declaration head flat; if the head itself overflows, the type
     * parameters must break before the clauses. For single-clause headers, more than two type parameters or an
     * {@code extends} type with its own arguments make the type-parameter list easier to read when it breaks first.
     */
    private boolean classOrInterfaceTypeParametersBreak(ClassOrInterfaceDeclaration declaration) {
        if (declaration.getTypeParameters().isEmpty()) {
            return false;
        }
        if (classOrInterfaceHeaderClauses(declaration) > 1) {
            String headerHead = modifiers.apply(declaration)
                    + (declaration.isInterface() ? "interface " : "class ")
                    + declaration.getNameAsString()
                    + flatTypeParameters.apply(declaration.getTypeParameters());
            return currentIndentedWidth.applyAsInt(headerHead) > options.lineWidth();
        }
        if (declaration.getTypeParameters().size() > 2) {
            return true;
        }
        return classOrInterfaceHeaderClauses(declaration) == 1
                && declaration.getExtendedTypes().stream().anyMatch(this::hasTypeArguments);
    }

    /**
     * Counts the optional header clauses that can appear after a class or interface name.
     */
    private int classOrInterfaceHeaderClauses(ClassOrInterfaceDeclaration declaration) {
        int clauses = 0;
        if (!declaration.getExtendedTypes().isEmpty()) {
            clauses++;
        }
        if (!declaration.getImplementedTypes().isEmpty()) {
            clauses++;
        }
        if (!declaration.getPermittedTypes().isEmpty()) {
            clauses++;
        }
        return clauses;
    }

    /**
     * Reports whether a class/interface type carries explicit generic arguments.
     */
    private boolean hasTypeArguments(ClassOrInterfaceType type) {
        return type.getTypeArguments().map(arguments -> !arguments.isEmpty()).orElse(false);
    }

    /**
     * Treats a declaration with only orphan comments as non-empty because the member block must print a real body.
     */
    private boolean emptyMemberBlock(ClassOrInterfaceDeclaration declaration) {
        return declaration.getMembers().isEmpty() && declaration.getOrphanComments().isEmpty();
    }

    /**
     * Returns the compact body width used by the flat-header fit check.
     */
    private int flatMemberBlockWidth(ClassOrInterfaceDeclaration declaration) {
        return emptyMemberBlock(declaration) ? "{}".length() : "{".length();
    }

    @FunctionalInterface
    interface TypeClausePrinter {
        Optional<Doc> print(String keyword, NodeList<ClassOrInterfaceType> types, boolean breakBeforeClause);
    }
}
