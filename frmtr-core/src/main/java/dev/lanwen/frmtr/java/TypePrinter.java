package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Prints shared type-like declaration clauses and breakable generic type bodies.
 *
 * <p>This helper owns {@code extends}, {@code implements}, and {@code permits} clause text, declaration type-parameter
 * flat text, and the two generic type document shapes shared by declaration headers, callable signatures, field
 * initializers, object creation, and local variables. It accepts compact type text from the caller's source-text policy
 * because that path is still tied to raw-source normalization, comment stripping, and expression compacting rules that
 * are not type-clause policy.
 *
 * <p>Representative fixture pairs live at
 * {@code frmtr-core/src/test/resources/format/interface-and-sealed-type-headers/input.java} and
 * {@code frmtr-core/src/test/resources/format/interface-and-sealed-type-headers/frmtr-default.output.java};
 * {@code frmtr-core/src/test/resources/format/interface-and-sealed-type-headers/input.java}
 * and
 * {@code frmtr-core/src/test/resources/format/interface-and-sealed-type-headers/frmtr-default.output.java};
 * {@code frmtr-core/src/test/resources/format/generic-type-body-breaks/input.java} and
 * {@code frmtr-core/src/test/resources/format/generic-type-body-breaks/frmtr-default.output.java}; and
 * {@code frmtr-core/src/test/resources/format/annotated-qualified-types/input.java} plus
 * {@code frmtr-core/src/test/resources/format/annotated-qualified-types/frmtr-default.output.java}.
 */
final class TypePrinter {

    private final FormatterOptions options;

    private final Function<Node, String> compactTypeLike;

    TypePrinter(FormatterOptions options, Function<Node, String> compactTypeLike) {
        this.options = options;
        this.compactTypeLike = compactTypeLike;
    }

    /**
     * Prints an inline {@code extends} clause when a declaration has extended types.
     */
    Optional<Doc> extendsTypes(NodeList<? extends Node> types) {
        if (types.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Doc.text(" extends " + compactJoinTypeLike(types)));
    }

    /**
     * Prints an inline {@code implements} clause when a declaration has implemented types.
     */
    Optional<Doc> implementsTypes(NodeList<ClassOrInterfaceType> types) {
        if (types.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Doc.text(" implements " + compactJoinTypeLike(types)));
    }

    /**
     * Prints an inline {@code permits} clause when a sealed declaration lists permitted types.
     */
    Optional<Doc> permitsTypes(NodeList<ClassOrInterfaceType> types) {
        if (types.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Doc.text(" permits " + compactJoinTypeLike(types)));
    }

    /**
     * Prints a broken declaration header clause using the standard continuation shape.
     */
    <T extends Node> Optional<Doc> typeClause(String keyword, NodeList<T> types) {
        return typeClause(keyword, types, true);
    }

    /**
     * Prints a declaration header clause either attached to the current header line or hard-broken under it.
     *
     * <p>The inline branch is used when a caller has already broken type parameters and still wants the first clause
     * beside the closing {@code >}. The broken branch is used for wider class, interface, and enum headers so
     * each clause starts from the same continuation shape. A single type with more than two generic arguments gets the
     * hard-broken type-argument shape even when the clause keyword stays inline; that keeps long sealed, implements,
     * and generic-class headers readable without forcing unrelated clause items to split.
     */
    <T extends Node> Optional<Doc> typeClause(String keyword, NodeList<T> types, boolean breakBeforeClause) {
        return typeClause(
            keyword,
            types,
            breakBeforeClause,
            text -> text.length() + options.indentUnit().length()
        );
    }

    <T extends Node> Optional<Doc> typeClause(
            String keyword,
            NodeList<T> types,
            boolean breakBeforeClause,
            ToIntFunction<String> width
    ) {
        if (types.isEmpty()) {
            return Optional.empty();
        }
        String flat = keyword + " " + compactJoinTypeLike(types);
        if (!breakBeforeClause) {
            if (
                types.size() == 1
                && types.get(0) instanceof ClassOrInterfaceType type
                && typeArgumentCount(type) > 2
            ) {
                return Optional.of(Doc.concat(Doc.text(" " + keyword + " "), brokenClassOrInterfaceType(type)));
            }
            return Optional.of(Doc.text(" " + flat));
        }
        if (width.applyAsInt(flat) <= options.lineWidth()) {
            return Optional.of(Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(flat))));
        }
        if (
            types.size() == 1
            && types.get(0) instanceof ClassOrInterfaceType type
            && typeArgumentCount(type) > 2
        ) {
            return Optional.of(
                Doc.indent(
                    Doc.concat(
                        Doc.HARD_LINE,
                        Doc.text(keyword + " "),
                        brokenClassOrInterfaceType(type)
                    )
                )
            );
        }
        return Optional.of(
            Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    Doc.text(keyword),
                    Doc.indent(
                        Doc.concat(
                            Doc.HARD_LINE,
                            Doc.join(
                                Doc.concat(Doc.text(","), Doc.HARD_LINE),
                                types.stream()
                                        .map(type -> Doc.text(compactTypeLike.apply(type)))
                                        .toList()
                            )
                        )
                    )
                )
            )
        );
    }

    /**
     * Prints a class/interface type with one hard line per generic argument for declaration clauses that already broke.
     */
    Doc brokenClassOrInterfaceType(ClassOrInterfaceType type) {
        return Doc.concat(
            Doc.text(typeNameWithoutArguments(type) + "<"),
            Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    Doc.join(
                        Doc.concat(Doc.text(","), Doc.HARD_LINE),
                        type.getTypeArguments()
                                .stream()
                                .flatMap(NodeList::stream)
                                .map(this::genericArgumentBody)
                                .toList()
                    )
                )
            ),
            Doc.HARD_LINE,
            Doc.text(">")
        );
    }

    /**
     * Reports whether a type can use the shared generic-body renderer.
     */
    boolean typeCanBreak(Type type) {
        return type instanceof ClassOrInterfaceType classOrInterfaceType
            && hasNonEmptyTypeArguments(classOrInterfaceType);
    }

    /**
     * Prints a type body with soft break opportunities for expression, parameter, and local-variable contexts.
     *
     * <p>Declaration clauses use {@link #brokenClassOrInterfaceType(ClassOrInterfaceType)} after the surrounding header
     * has committed to hard lines. Callable signatures, object creation, and local variables instead need a grouped
     * type body that can stay flat when it fits and break only when the renderer chooses the broken group.
     */
    Doc typeBody(Type type) {
        Optional<Doc> annotatedType = prefixAnnotatedTypeBody(type);
        if (annotatedType.isPresent()) {
            return annotatedType.orElseThrow();
        }
        if (
            type instanceof ClassOrInterfaceType classOrInterfaceType
            && classOrInterfaceType.getTypeArguments().isPresent()
        ) {
            return classOrInterfaceTypeBody(classOrInterfaceType);
        }
        return Doc.text(compactTypeLike.apply(type));
    }

    /**
     * Builds the soft-break generic argument body used by {@link #typeBody(Type)}.
     */
    private Doc classOrInterfaceTypeBody(ClassOrInterfaceType type) {
        return classOrInterfaceTypeBody(type, typeNameWithoutArguments(type));
    }

    private Doc classOrInterfaceTypeBody(ClassOrInterfaceType type, String nameWithoutArguments) {
        return Doc.concat(
            Doc.text(nameWithoutArguments + "<"),
            Doc.indent(
                Doc.concat(
                    Doc.SOFT_LINE,
                    Doc.joinComma(
                        type.getTypeArguments()
                                .stream()
                                .flatMap(NodeList::stream)
                                .map(this::genericArgumentBody)
                                .toList()
                    )
                )
            ),
            Doc.SOFT_LINE,
            Doc.text(">")
        );
    }

    private Doc genericArgumentBody(Type type) {
        List<AnnotationExpr> annotations = type.getAnnotations();
        Optional<PrefixAnnotatedType> annotated = prefixAnnotatedType(type);
        if (annotated.isPresent() && annotations.stream().anyMatch(TypePrinter::annotationHasBreakableBody)) {
            return breakableAnnotatedGenericArgument(annotations, annotated.orElseThrow().tail());
        }
        if (annotated.filter(parsed -> parsed.annotations().size() > 1).isPresent()) {
            PrefixAnnotatedType parsed = annotated.orElseThrow();
            return Doc.concat(
                Doc.join(
                    Doc.HARD_LINE,
                    parsed.annotations()
                            .stream()
                            .map(Doc::text)
                            .toList()
                ),
                Doc.HARD_LINE,
                Doc.text(parsed.tail())
            );
        }
        return groupedTypeBody(type);
    }

    /**
     * Renders an annotated generic argument whose annotation list carries a parenthesized body that can break.
     *
     * <p>This is the width-driven replacement for the retired "was any annotation multiline in the source?" probe. The
     * whole argument stays on one line while the compact form fits and, when it overflows the line at its true rendered
     * column, breaks the parenthesized body of each breakable annotation while keeping the annotations and the trailing
     * type inline. The trailing type text rides inside the group so the fit decision measures {@code @Ann(...) Type} as a
     * unit and never leaves the type name to spill past the line on its own.
     */
    private Doc breakableAnnotatedGenericArgument(List<AnnotationExpr> annotations, String tail) {
        List<Doc> parts = new ArrayList<>();
        for (int index = 0; index < annotations.size(); index++) {
            if (index > 0) {
                parts.add(Doc.text(" "));
            }
            parts.add(groupableTypeAnnotation(annotations.get(index)));
        }
        parts.add(Doc.text(" " + tail));
        return Doc.group(Doc.concat(parts));
    }

    /**
     * A type-use annotation that renders compact while its enclosing group stays flat and breaks its parenthesized body
     * when the group breaks. The flat branch is the caller's compact annotation text, so a fitting argument reproduces
     * the exact compact spelling; only the overflowing branch rebuilds the structured shape. A marker or empty-parens
     * annotation has no body to break, so it stays compact in either mode.
     */
    private Doc groupableTypeAnnotation(AnnotationExpr annotation) {
        if (annotation instanceof NormalAnnotationExpr normalAnnotation && !normalAnnotation.getPairs().isEmpty()) {
            return Doc.ifBreak(brokenNormalAnnotation(normalAnnotation), Doc.text(compactTypeLike.apply(annotation)));
        }
        if (annotation instanceof SingleMemberAnnotationExpr singleMemberAnnotation) {
            return Doc.ifBreak(
                brokenSingleMemberAnnotation(singleMemberAnnotation),
                Doc.text(compactTypeLike.apply(annotation))
            );
        }
        return Doc.text(compactTypeLike.apply(annotation));
    }

    private static boolean annotationHasBreakableBody(AnnotationExpr annotation) {
        return (annotation instanceof NormalAnnotationExpr normalAnnotation && !normalAnnotation.getPairs().isEmpty())
            || annotation instanceof SingleMemberAnnotationExpr;
    }

    private Doc brokenNormalAnnotation(NormalAnnotationExpr annotation) {
        return Doc.concat(
            Doc.text("@" + compactTypeLike.apply(annotation.getName()) + "("),
            Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    Doc.join(
                        Doc.concat(Doc.text(","), Doc.HARD_LINE),
                        annotation.getPairs()
                                .stream()
                                .map(this::annotationPair)
                                .toList()
                    )
                )
            ),
            Doc.HARD_LINE,
            Doc.text(")")
        );
    }

    private Doc brokenSingleMemberAnnotation(SingleMemberAnnotationExpr annotation) {
        return Doc.concat(
            Doc.text("@" + compactTypeLike.apply(annotation.getName()) + "("),
            Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(compactTypeLike.apply(annotation.getMemberValue())))),
            Doc.HARD_LINE,
            Doc.text(")")
        );
    }

    private Doc annotationPair(MemberValuePair pair) {
        return Doc.text(pair.getNameAsString() + " = " + compactTypeLike.apply(pair.getValue()));
    }

    private Doc groupedTypeBody(Type type) {
        return Doc.group(typeBody(type));
    }

    private Optional<Doc> prefixAnnotatedTypeBody(Type type) {
        if (
            type instanceof ClassOrInterfaceType classOrInterfaceType
            && classOrInterfaceType.getTypeArguments().isPresent()
        ) {
            return prefixAnnotatedType(classOrInterfaceType).map(annotated -> Doc.group(
                    Doc.concat(
                        Doc.join(
                            Doc.LINE,
                            annotated.annotations()
                                    .stream()
                                    .map(Doc::text)
                                    .toList()
                        ),
                        Doc.LINE,
                        classOrInterfaceTypeBody(classOrInterfaceType, typeNameWithoutArguments(annotated.tail()))
                    )
            ));
        }
        return prefixAnnotatedFlatTypeBody(type);
    }

    private Optional<Doc> prefixAnnotatedFlatTypeBody(Type type) {
        Optional<PrefixAnnotatedType> parsed = prefixAnnotatedType(type);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        PrefixAnnotatedType annotated = parsed.orElseThrow();
        return Optional.of(
            Doc.group(
                Doc.concat(
                    Doc.join(
                        Doc.LINE,
                        annotated.annotations()
                                .stream()
                                .map(Doc::text)
                                .toList()
                    ),
                    Doc.LINE,
                    Doc.text(annotated.tail())
                )
            )
        );
    }

    private Optional<PrefixAnnotatedType> prefixAnnotatedType(Type type) {
        if (type.getAnnotations().isEmpty()) {
            return Optional.empty();
        }
        String compact = compactTypeLike.apply(type);
        if (!compact.startsWith("@")) {
            return Optional.empty();
        }
        return Optional.of(
            new PrefixAnnotatedType(
                type.getAnnotations()
                        .stream()
                        .map(compactTypeLike)
                        .toList(),
                compactTypeLike.apply(unannotated(type))
            )
        );
    }

    private Type unannotated(Type type) {
        Type clone = type.clone();
        clone.getAnnotations().clear();
        return clone;
    }

    private record PrefixAnnotatedType(List<String> annotations, String tail) {}

    /**
     * Returns the compact type prefix before {@code <...>} without rewriting the parsed type tree.
     *
     * <p>JavaParser splits scopes, simple names, annotations, and type arguments differently across source shapes. The
     * formatter already has compact source-equivalent text for those details, so this method trims that compact text at
     * the first generic argument opener instead of rebuilding the name from AST fields and risking a different spelling.
     */
    String typeNameWithoutArguments(ClassOrInterfaceType type) {
        return typeNameWithoutArguments(compactTypeLike.apply(type));
    }

    private String typeNameWithoutArguments(String text) {
        int argumentsStart = text.indexOf('<');
        return argumentsStart < 0 ? text : text.substring(0, argumentsStart);
    }

    /**
     * Builds flat header-clause text used by line-width estimates before a declaration chooses its final shape.
     */
    <T extends Node> String flatTypeClause(String keyword, NodeList<T> types) {
        if (types.isEmpty()) {
            return "";
        }
        return " " + keyword + " " + compactJoinTypeLike(types);
    }

    /**
     * Builds flat declaration type-parameter text used only for line-width estimates.
     */
    String flatTypeParameters(NodeList<TypeParameter> typeParameters) {
        if (typeParameters.isEmpty()) {
            return "";
        }
        return "<" + compactJoinTypeLike(typeParameters) + ">";
    }

    private int typeArgumentCount(ClassOrInterfaceType type) {
        return type.getTypeArguments().map(NodeList::size).orElse(0);
    }

    private boolean hasNonEmptyTypeArguments(ClassOrInterfaceType type) {
        return type.getTypeArguments().map(arguments -> !arguments.isEmpty()).orElse(false);
    }

    /**
     * Joins type-like nodes with comma spacing using the caller's compact type text policy.
     */
    String compactJoinTypeLike(List<? extends Node> nodes) {
        return nodes.stream().map(compactTypeLike).reduce((left, right) -> left + ", " + right).orElse("");
    }
}
