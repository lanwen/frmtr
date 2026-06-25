package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.comments.LineComment;
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
 * {@code frmtr-core/src/test/resources/format/comment-preservation-interface-declaration/input.java} and
 * {@code frmtr-core/src/test/resources/format/comment-preservation-interface-declaration/frmtr-default.output.java};
 * {@code frmtr-core/src/test/resources/format/interface-and-sealed-type-headers/input.java} and
 * {@code frmtr-core/src/test/resources/format/interface-and-sealed-type-headers/frmtr-default.output.java};
 * {@code frmtr-core/src/test/resources/format/interface-and-sealed-type-headers/input.java}
 * and
 * {@code frmtr-core/src/test/resources/format/interface-and-sealed-type-headers/frmtr-default.output.java};
 * and {@code frmtr-core/src/test/resources/format/generic-type-body-breaks/input.java} plus
 * {@code frmtr-core/src/test/resources/format/generic-type-body-breaks/frmtr-default.output.java}.
 */
final class ClassOrInterfaceDeclarationPrinter {

    private final CommentTracker comments;

    private final SourceShapePolicy sourceShapePolicy;

    private final RawSource rawSource;

    private final RawPreservedSource rawPreservedSource;

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

    private final Function<ClassOrInterfaceDeclaration, Doc> memberBlock;

    ClassOrInterfaceDeclarationPrinter(
            CommentTracker comments,
            SourceShapePolicy sourceShapePolicy,
            RawSource rawSource,
            RawPreservedSource rawPreservedSource,
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
            Function<ClassOrInterfaceDeclaration, Doc> memberBlock
    ) {
        this.comments = comments;
        this.sourceShapePolicy = sourceShapePolicy;
        this.rawSource = rawSource;
        this.rawPreservedSource = rawPreservedSource;
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
            return rawPreservedSource.rawWithoutOwnComment(
                declaration,
                commentedInterfaces.formatCommentedInterface(raw)
            );
        }
        if (shouldBreakClassOrInterfaceHeader(declaration)) {
            return brokenClassOrInterface(declaration);
        }
        List<Doc> header = new ArrayList<>();
        header.add(annotations.apply(declaration));
        header.add(nameLeadingLineComment(declaration));
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
        if (
            declaration.getExtendedTypes().isEmpty()
            && declaration.getImplementedTypes().isEmpty()
            && declaration.getPermittedTypes().isEmpty()
        ) {
            return false;
        }
        String flatHeader = modifiers.apply(declaration)
            + (declaration.isInterface() ? "interface " : "class ")
            + declaration.getNameAsString()
            + flatTypeParameters.apply(declaration.getTypeParameters())
            + flatTypeClause.apply("extends", declaration.getExtendedTypes())
            + flatTypeClause.apply("implements", declaration.getImplementedTypes())
            + flatTypeClause.apply("permits", declaration.getPermittedTypes());
        return classOrInterfaceHeaderWidth(
            declaration,
            flatHeader + " " + (emptyMemberBlock(declaration) ? "{}" : "{")
        ) > options.lineWidth();
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
        header.add(annotations.apply(declaration));
        header.add(nameLeadingLineComment(declaration));
        header.add(Doc.text(modifiers.apply(declaration)));
        header.add(Doc.text(declaration.isInterface() ? "interface " : "class "));
        header.add(Doc.text(declaration.getNameAsString()));
        boolean breakTypeParameters = classOrInterfaceTypeParametersBreak(declaration);
        if (breakTypeParameters) {
            header.add(
                callableSignatures.brokenTypeParameters(
                    declaration.getTypeParameters(),
                    classOrInterfaceHeaderClauses(declaration) > 1
                )
            );
        } else if (!declaration.getTypeParameters().isEmpty()) {
            header.add(callableSignatures.typeParameters(declaration.getTypeParameters()));
        }
        boolean breakClauses = classOrInterfaceHeaderClauses(declaration) > 1 || !breakTypeParameters;
        typeClause.print(
                    "extends",
                    declaration.getExtendedTypes(),
                    breakClauses,
                    text -> classOrInterfaceClauseWidth(declaration, text)
                )
                .ifPresent(header::add);
        typeClause.print(
                    "implements",
                    declaration.getImplementedTypes(),
                    breakClauses,
                    text -> classOrInterfaceClauseWidth(declaration, text)
                )
                .ifPresent(header::add);
        typeClause.print(
                    "permits",
                    declaration.getPermittedTypes(),
                    breakClauses,
                    text -> classOrInterfaceClauseWidth(declaration, text)
                )
                .ifPresent(header::add);
        header.add(Doc.text(" "));
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
            return classOrInterfaceHeaderWidth(declaration, headerHead) > options.lineWidth();
        }
        if (declaration.getTypeParameters().size() > 2) {
            return true;
        }
        String headerHead = modifiers.apply(declaration)
            + (declaration.isInterface() ? "interface " : "class ")
            + declaration.getNameAsString()
            + flatTypeParameters.apply(declaration.getTypeParameters());
        return classOrInterfaceHeaderWidth(declaration, headerHead) > options.lineWidth();
    }

    private Doc nameLeadingLineComment(ClassOrInterfaceDeclaration declaration) {
        Doc comment = comments.ownComment(
            declaration.getName(),
            candidate -> candidate instanceof LineComment
                    && CommentIndex.startsBeforeBeginLine(candidate, declaration.getName())
        );
        return comment == Doc.EMPTY ? Doc.EMPTY : Doc.concat(comment, Doc.HARD_LINE);
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
     * Treats a declaration with only orphan comments as non-empty because the member block must print a real body.
     */
    private boolean emptyMemberBlock(ClassOrInterfaceDeclaration declaration) {
        return declaration.getMembers().isEmpty() && declaration.getOrphanComments().isEmpty();
    }

    private int classOrInterfaceHeaderWidth(ClassOrInterfaceDeclaration declaration, String text) {
        return classOrInterfaceIndentWidth(declaration) + text.length();
    }

    private int classOrInterfaceClauseWidth(ClassOrInterfaceDeclaration declaration, String text) {
        return classOrInterfaceIndentWidth(declaration) + options.indentUnit().length() + text.length();
    }

    private int classOrInterfaceIndentWidth(ClassOrInterfaceDeclaration declaration) {
        int enclosingTypes = 0;
        Optional<Node> parent = declaration.getParentNode();
        while (parent.isPresent()) {
            Node node = parent.orElseThrow();
            if (node instanceof TypeDeclaration<?>) {
                enclosingTypes++;
            }
            parent = node.getParentNode();
        }
        return enclosingTypes * options.indentUnit().length();
    }

    @FunctionalInterface
    interface TypeClausePrinter {
        Optional<Doc> print(
                String keyword,
                NodeList<ClassOrInterfaceType> types,
                boolean breakBeforeClause,
                ToIntFunction<String> width
        );
    }
}
