package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntBiFunction;

/**
 * Owns the width-driven broken-shape emitters for {@code new Type(...)} object-creation variable initializers, branched
 * from the single {@link #variableWithBrokenObjectCreation} entry the initializer cascade reaches once a flat creation
 * overflows.
 *
 * <p>This helper hosts the family that decides how an over-width constructor call lays out under {@code NAME = }: the
 * type-argument break ({@code new LongType<\n ...\n>()}), the constructor-argument break (one argument per line, or the
 * small-constructor flat exception), the commented-creation opener-hug that keeps {@code NAME = new Type(} together while
 * the shared object-creation renderer places the nested comments, and the per-argument binary/logical/string-concat
 * operand break that commits a fanned chain operand instead of an operator-per-line split. The boundary exists so the
 * initializer layout's broken-or-flat cascade can delegate the whole object-creation sub-ladder to one authority instead
 * of carrying every constructor-shape probe inline.
 *
 * <p>The helper claims no ownership of when an initializer is over-width, of the assignment prefix, or of anonymous-class
 * bodies and nested comment placement: it reports {@link Optional#empty()} the moment a creation is out of its remit
 * (anonymous body, missing arguments, an opener that no longer fits) and hands the value back to the caller's cascade.
 * The opener-line width measurement stays owned by the caller and is threaded in as {@code openerLineWidth} so both sides
 * measure the assignment line the same way.
 */
final class InitializerObjectCreationLayout {

    private final FormatterOptions options;

    private final LayoutWidth layoutWidth;

    private final Function<Node, String> compact;

    private final Function<Expression, Doc> expression;

    private final Predicate<Expression> binaryFansChainOperand;

    private final Predicate<BinaryExpr> binaryExpressionHasLineComments;

    private final Function<BinaryExpr, Doc> binaryExpressionLinesWithComments;

    private final BiFunction<Expression, Boolean, Doc> binaryExpressionLines;

    private final Function<ObjectCreationExpr, String> objectCreationPrefix;

    private final Function<ClassOrInterfaceType, String> typeNameWithoutArguments;

    private final Function<ClassOrInterfaceType, Doc> brokenClassOrInterfaceType;

    private final ToIntBiFunction<VariableDeclarator, String> openerLineWidth;

    InitializerObjectCreationLayout(
            FormatterOptions options,
            LayoutWidth layoutWidth,
            Function<Node, String> compact,
            Function<Expression, Doc> expression,
            Predicate<Expression> binaryFansChainOperand,
            Predicate<BinaryExpr> binaryExpressionHasLineComments,
            Function<BinaryExpr, Doc> binaryExpressionLinesWithComments,
            BiFunction<Expression, Boolean, Doc> binaryExpressionLines,
            Function<ObjectCreationExpr, String> objectCreationPrefix,
            Function<ClassOrInterfaceType, String> typeNameWithoutArguments,
            Function<ClassOrInterfaceType, Doc> brokenClassOrInterfaceType,
            ToIntBiFunction<VariableDeclarator, String> openerLineWidth
    ) {
        this.options = options;
        this.layoutWidth = layoutWidth;
        this.compact = compact;
        this.expression = expression;
        this.binaryFansChainOperand = binaryFansChainOperand;
        this.binaryExpressionHasLineComments = binaryExpressionHasLineComments;
        this.binaryExpressionLinesWithComments = binaryExpressionLinesWithComments;
        this.binaryExpressionLines = binaryExpressionLines;
        this.objectCreationPrefix = objectCreationPrefix;
        this.typeNameWithoutArguments = typeNameWithoutArguments;
        this.brokenClassOrInterfaceType = brokenClassOrInterfaceType;
        this.openerLineWidth = openerLineWidth;
    }

    /**
     * Branches object creation between broken type arguments and broken constructor arguments, leaving anonymous-class
     * and commented creations to the shared object-creation formatter.
     */
    Optional<Doc> variableWithBrokenObjectCreation(
            VariableDeclarator variable,
            String name,
            String flatName,
            ObjectCreationExpr objectCreation
    ) {
        if (objectCreation.getAnonymousClassBody().isPresent()) {
            return Optional.empty();
        }
        if (!objectCreation.getAllContainedComments().isEmpty()) {
            return variableWithCommentedObjectCreation(variable, name, flatName, objectCreation);
        }
        Optional<Doc> typeArguments = variableWithBrokenObjectCreationTypeArguments(
            variable,
            name,
            flatName,
            objectCreation
        );
        if (typeArguments.isPresent()) {
            return typeArguments;
        }
        return variableWithBrokenObjectCreationArguments(variable, name, flatName, objectCreation);
    }

    /**
     * Keeps {@code name = new Type(} together for commented constructor calls when that first line still fits, while
     * leaving the nested comment placement to the normal object-creation renderer.
     */
    private Optional<Doc> variableWithCommentedObjectCreation(
            VariableDeclarator variable,
            String name,
            String flatName,
            ObjectCreationExpr objectCreation
    ) {
        if (
            objectCreation.getArguments().isEmpty()
            || objectCreation.getComment().filter(BlockComment.class::isInstance).isPresent()
            || objectCreation.getType().getComment().filter(BlockComment.class::isInstance).isPresent()
        ) {
            return Optional.empty();
        }
        String prefix = objectCreationPrefix.apply(objectCreation);
        if (openerLineWidth.applyAsInt(variable, flatName + " = " + prefix + "(") > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(Doc.text(name + " = "), expression.apply(objectCreation)));
    }

    /**
     * Breaks constructor arguments when the assignment and constructor prefix still fit, so only the argument list moves
     * to hard lines.
     */
    private Optional<Doc> variableWithBrokenObjectCreationArguments(
            VariableDeclarator variable,
            String name,
            String flatName,
            ObjectCreationExpr objectCreation
    ) {
        if (objectCreation.getArguments().isEmpty()) {
            return Optional.empty();
        }
        String prefix = objectCreationPrefix.apply(objectCreation);
        if (openerLineWidth.applyAsInt(variable, flatName + " = " + prefix + "(") > options.lineWidth()) {
            return Optional.empty();
        }
        if (smallConstructorCanStayFlat(variable, flatName, objectCreation)) {
            return Optional.of(Doc.concat(Doc.text(name + " = "), expression.apply(objectCreation)));
        }
        return Optional.of(
            Doc.concat(
                Doc.text(name + " = " + prefix + "("),
                Doc.indent(
                    Doc.concat(
                        Doc.HARD_LINE,
                        Doc.join(
                            Doc.concat(Doc.text(","), Doc.HARD_LINE),
                            objectCreation.getArguments()
                                    .stream()
                                    .map(this::brokenObjectCreationArgument)
                                    .toList()
                        )
                    )
                ),
                Doc.HARD_LINE,
                Doc.text(")")
            )
        );
    }

    private Doc brokenObjectCreationArgument(Expression argument) {
        // Canonical-fan cutover seam (End-state A), the binary/logical/string-concat OPERAND carrier at the broken
        // object-creation argument position (the "G bucket"). When this constructor argument is a binary/ternary whose
        // dispatched flat rendering ({@code expression.apply}) already fans a fluent chain operand by the End-state A rule
        // ({@code new StatusData(summary.percentiles().get(0).value() * step + min, …)}), commit that flat shape and do not
        // take the operand-per-line break below. The flat shape renders the chain fanned with the operator kept on its line
        // and is a pure function of the AST (the chain fans by the width-independent link-count rule on every pass), so
        // committing it is the fixpoint; the width-gated {@code binaryExpressionLines} break below would instead lay the
        // operator on its own line. Chains the rule does not fan and comment / lambda chains are withheld by
        // {@code binaryFansChainOperand}, so those arguments take the width-driven break below.
        if (argument instanceof BinaryExpr binaryExpr && binaryFansChainOperand.test(binaryExpr)) {
            return expression.apply(argument);
        }
        if (
            argument instanceof BinaryExpr binaryExpr
            && layoutWidth.continuationStatement(compact.apply(binaryExpr)) > options.lineWidth()
        ) {
            if (binaryExpressionHasLineComments.test(binaryExpr)) {
                return binaryExpressionLinesWithComments.apply(binaryExpr);
            }
            return binaryExpressionLines.apply(binaryExpr, true);
        }
        return expression.apply(argument);
    }

    private boolean smallConstructorCanStayFlat(
            VariableDeclarator variable,
            String flatName,
            ObjectCreationExpr objectCreation
    ) {
        return objectCreation.getArguments().size() <= 3
            && openerLineWidth.applyAsInt(variable, flatName + " = " + compact.apply(objectCreation) + ";")
                <= options.lineWidth();
    }

    /**
     * Breaks constructor type arguments for {@code new SomeVeryLongType<...>()} only when there are no constructor
     * arguments or scopes that need a different object-creation layout.
     */
    private Optional<Doc> variableWithBrokenObjectCreationTypeArguments(
            VariableDeclarator variable,
            String name,
            String flatName,
            ObjectCreationExpr objectCreation
    ) {
        if (
            !objectCreation.getArguments().isEmpty()
            || objectCreation.getScope().isPresent()
            || objectCreation.getTypeArguments().isPresent()
            || !objectCreation.getType().isClassOrInterfaceType()
        ) {
            return Optional.empty();
        }
        ClassOrInterfaceType type = objectCreation.getType().asClassOrInterfaceType();
        if (
            !hasNonEmptyTypeArguments(type)
            || openerLineWidth.applyAsInt(
                variable,
                flatName + " = new " + typeNameWithoutArguments.apply(type) + "<"
            ) > options.lineWidth()
        ) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(name + " = new "),
                brokenClassOrInterfaceType.apply(type),
                Doc.text("()")
            )
        );
    }

    private boolean hasNonEmptyTypeArguments(ClassOrInterfaceType type) {
        return type.getTypeArguments().map(arguments -> !arguments.isEmpty()).orElse(false);
    }
}
