package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.LineComment;
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
        Doc prefix = classOrInterfacePrefix(declaration);
        Doc memberBlockDoc = memberBlock.apply(declaration);
        if (classOrInterfaceHeaderClauses(declaration) == 0) {
            return flatClassOrInterface(declaration, prefix, memberBlockDoc);
        }
        return Doc.bestFittingFirstLine(
            List.of(
                flatClassOrInterface(declaration, prefix, memberBlockDoc),
                brokenClassOrInterface(declaration, prefix, memberBlockDoc)
            ),
            new int[] {1, 0},
            "classHeader"
        );
    }

    /**
     * Builds the header prefix shared by the flat and broken candidates: annotations, leading name comment, modifiers,
     * keyword, and name. Built once so both ranked candidates render the identical Doc instance.
     */
    private Doc classOrInterfacePrefix(ClassOrInterfaceDeclaration declaration) {
        return Doc.concat(
            annotations.apply(declaration),
            nameLeadingLineComment(declaration),
            Doc.text(modifiers.apply(declaration)),
            Doc.text(declaration.isInterface() ? "interface " : "class "),
            Doc.text(declaration.getNameAsString())
        );
    }

    /**
     * Prints the inline header: type clauses attach directly after the name/type-parameters instead of each starting a
     * new line. Ranked against {@link #brokenClassOrInterface} at the true rendered column when clauses are present.
     */
    private Doc flatClassOrInterface(ClassOrInterfaceDeclaration declaration, Doc prefix, Doc memberBlockDoc) {
        List<Doc> header = new ArrayList<>();
        header.add(prefix);
        if (!declaration.getTypeParameters().isEmpty()) {
            header.add(callableSignatures.typeParameters(declaration.getTypeParameters()));
        }
        header.add(clauseLeadingBlockComment(declaration.getExtendedTypes()));
        inlineExtendsTypes.apply(declaration.getExtendedTypes()).ifPresent(header::add);
        header.add(clauseLeadingBlockComment(declaration.getImplementedTypes()));
        inlineImplementsTypes.apply(declaration.getImplementedTypes()).ifPresent(header::add);
        header.add(clauseLeadingBlockComment(declaration.getPermittedTypes()));
        inlinePermitsTypes.apply(declaration.getPermittedTypes()).ifPresent(header::add);
        header.add(Doc.text(" "));
        header.add(memberBlockDoc);
        return Doc.concat(header);
    }

    /**
     * Prints the broken header selected after the compact header no longer fits.
     *
     * <p>When type parameters exist and their attach-vs-break choice is not structurally forced, both header shapes are
     * built and ranked at the true rendered column, nested inside this alternative's own slot in the outer flat-vs-broken
     * rank (see {@link #classOrInterface}).
     */
    private Doc brokenClassOrInterface(ClassOrInterfaceDeclaration declaration, Doc prefix, Doc memberBlockDoc) {
        if (declaration.getTypeParameters().isEmpty()) {
            return brokenClassOrInterfaceHeader(declaration, prefix, memberBlockDoc, false);
        }
        if (typeParametersMustBreak(declaration)) {
            return brokenClassOrInterfaceHeader(declaration, prefix, memberBlockDoc, true);
        }
        return Doc.bestFittingFirstLine(
            List.of(
                brokenClassOrInterfaceHeader(declaration, prefix, memberBlockDoc, false),
                brokenClassOrInterfaceHeader(declaration, prefix, memberBlockDoc, true)
            ),
            new int[] {1, 0}
        );
    }

    /**
     * Forces type parameters to break before clauses even when the attached form would fit: a single-clause header
     * with more than two type parameters is easier to read with each parameter on its own line. Multi-clause headers
     * carry no such structural override — their attach-vs-break choice is always ranked.
     */
    private boolean typeParametersMustBreak(ClassOrInterfaceDeclaration declaration) {
        return classOrInterfaceHeaderClauses(declaration) <= 1 && declaration.getTypeParameters().size() > 2;
    }

    /**
     * Builds one complete broken-header candidate for a fixed type-parameter attach-vs-break choice; clauses break
     * whenever there is more than one, or the type parameters did not, so a lone clause always has a shape to attach to.
     */
    private Doc brokenClassOrInterfaceHeader(
            ClassOrInterfaceDeclaration declaration,
            Doc prefix,
            Doc memberBlockDoc,
            boolean breakTypeParameters
    ) {
        List<Doc> header = new ArrayList<>();
        header.add(prefix);
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
        header.add(clauseLeadingBlockComment(declaration.getExtendedTypes()));
        typeClause.print(
                    "extends",
                    declaration.getExtendedTypes(),
                    breakClauses,
                    text -> classOrInterfaceClauseWidth(declaration, text)
                )
                .ifPresent(header::add);
        header.add(clauseLeadingBlockComment(declaration.getImplementedTypes()));
        typeClause.print(
                    "implements",
                    declaration.getImplementedTypes(),
                    breakClauses,
                    text -> classOrInterfaceClauseWidth(declaration, text)
                )
                .ifPresent(header::add);
        header.add(clauseLeadingBlockComment(declaration.getPermittedTypes()));
        typeClause.print(
                    "permits",
                    declaration.getPermittedTypes(),
                    breakClauses,
                    text -> classOrInterfaceClauseWidth(declaration, text)
                )
                .ifPresent(header::add);
        header.add(Doc.text(" "));
        header.add(memberBlockDoc);
        return Doc.concat(header);
    }

    /**
     * Renders a block comment that JavaParser parked as the leading own comment of a header clause's first type.
     *
     * <p>A comment between the declaration name and its {@code extends}/{@code implements}/{@code permits} keyword
     * ({@code class Min/* note *&#47; implements Serializable}) attaches to the clause's first {@link ClassOrInterfaceType},
     * which {@code compactJoinTypeLike} never reads, so without this it is dropped. Emitting it here (claimed once) keeps
     * it on the header line before the keyword. Only the first type's leading comment is recovered — the name/clause
     * boundary the header layout owns; later inter-type comments stay with the shared type-clause renderer.
     */
    private Doc clauseLeadingBlockComment(NodeList<ClassOrInterfaceType> types) {
        if (types.isEmpty()) {
            return Doc.EMPTY;
        }
        ClassOrInterfaceType firstType = types.get(0);
        Doc comment = comments.ownComment(
            firstType,
            candidate -> candidate instanceof BlockComment
                    && CommentIndex.startsBefore(candidate, firstType)
        );
        return comment == Doc.EMPTY ? Doc.EMPTY : Doc.concat(Doc.text(" "), comment);
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
