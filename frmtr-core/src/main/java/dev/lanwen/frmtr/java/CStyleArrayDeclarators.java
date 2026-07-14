package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.Type;

/**
 * Owns the legacy C-style array declarator decision: how a declaration written as {@code String filters[]} (brackets
 * after the variable name) is rendered as valid Java without duplicating the variable name. The same shape appears on
 * method and constructor parameters ({@code void write(byte b[])}), which this helper also covers.
 *
 * <p>JavaParser models such a declarator with an {@link ArrayType} built from {@link ArrayType.Origin#NAME} bracket
 * pairs whose tokens sit after the name, so the type's token range spans the name. A naive token-range type prefix then
 * re-emits the name — non-compiling {@code String filters[] filters}, or {@code byte b[] b} for a parameter.
 *
 * <p>The brackets stay in their after-name position rather than being promoted to {@code String[] filters}, because the
 * AST-equivalence guardrail ({@link AstEquivalence}, via {@code EqualsVisitor}) treats an {@link ArrayType.Origin#NAME}
 * declarator and an {@link ArrayType.Origin#TYPE} declarator as different, so re-positioning would trip it. Preserving
 * the source position is also the only correct rendering for a mixed-level declaration ({@code int rowSpan[], columnCount}),
 * where promotion would change the non-array declarator's type.
 *
 * <p>This helper centralizes the two coupled pieces field and local-variable printers need: the shared prefix type (the
 * common element type, so brackets are not double-counted) and the per-declarator after-name bracket suffix. It leaves
 * line-width accounting, comment attachment, and break selection to the callers.
 */
final class CStyleArrayDeclarators {

    private CStyleArrayDeclarators() {
    }

    /**
     * Returns the shared prefix type for the declaration: the element type shared by all declarators when any declarator
     * carries C-style after-name brackets (each declarator then re-emits its own brackets), otherwise the first
     * declarator's type unchanged.
     *
     * <p>Using the common element type as the prefix is what keeps a C-style declaration from emitting brackets both in
     * the shared type and after the name, and it stays correct when declarators differ in array level.
     */
    static Type sharedPrefixType(NodeList<VariableDeclarator> variables) {
        Type firstType = variables.get(0).getType();
        if (variables.stream().anyMatch(CStyleArrayDeclarators::hasCStyleBrackets)) {
            return elementType(firstType);
        }
        return firstType;
    }

    /**
     * Returns the bracket text to append after a declarator name, preserving the C-style after-name position and any
     * bracket-attached type-use annotations (for example {@code segments @Nullable []}). Returns an empty string for
     * declarators that are not C-style arrays, so canonical {@code Type[] name} declarators are left untouched.
     */
    static String declaratorBracketsAfterName(VariableDeclarator variable) {
        if (!hasCStyleBrackets(variable)) {
            return "";
        }
        return bracketsAfterName((ArrayType) variable.getType());
    }

    /**
     * Reports whether {@code parameter} carries C-style after-name brackets ({@code byte b[]}) that the parameter
     * printer must render as an element-type prefix plus an after-name bracket suffix instead of from the type's
     * name-spanning token range. Varargs are never C-style: their brackets are modeled with a non-{@code NAME} origin
     * and render as {@code ...}, so the caller keeps its existing varargs path.
     */
    static boolean parameterHasCStyleBrackets(Parameter parameter) {
        return !parameter.isVarArgs()
            && parameter.getType() instanceof ArrayType arrayType
            && hasCStyleBrackets(arrayType);
    }

    /**
     * Returns the element type to print before a C-style parameter name, so the after-name brackets are emitted once
     * (by {@link #parameterBracketsAfterName(Parameter)}) rather than baked into a name-spanning type prefix. Callers
     * must first confirm {@link #parameterHasCStyleBrackets(Parameter)}; the method assumes an {@link ArrayType}.
     */
    static Type parameterElementType(Parameter parameter) {
        return elementType(parameter.getType());
    }

    /**
     * Returns the bracket text to append after a C-style parameter name, preserving the after-name position required by
     * the formatter's AST-equivalence guardrail and any bracket-attached type-use annotations (for example
     * {@code name @Nullable []}). Callers must first confirm {@link #parameterHasCStyleBrackets(Parameter)}.
     */
    static String parameterBracketsAfterName(Parameter parameter) {
        return bracketsAfterName((ArrayType) parameter.getType());
    }

    private static String bracketsAfterName(ArrayType arrayType) {
        StringBuilder text = new StringBuilder();
        for (ArrayType.ArrayBracketPair bracket : ArrayType.unwrapArrayTypes(arrayType).b) {
            for (AnnotationExpr annotation : bracket.getAnnotations()) {
                text.append(' ').append(annotation).append(' ');
            }
            text.append("[]");
        }
        return text.toString();
    }

    private static boolean hasCStyleBrackets(VariableDeclarator variable) {
        return variable.getType() instanceof ArrayType arrayType && hasCStyleBrackets(arrayType);
    }

    private static boolean hasCStyleBrackets(ArrayType arrayType) {
        return ArrayType.unwrapArrayTypes(arrayType)
                .b
                .stream()
                .anyMatch(bracket -> bracket.getOrigin() == ArrayType.Origin.NAME);
    }

    private static Type elementType(Type type) {
        return type instanceof ArrayType arrayType ? ArrayType.unwrapArrayTypes(arrayType).a : type;
    }
}
