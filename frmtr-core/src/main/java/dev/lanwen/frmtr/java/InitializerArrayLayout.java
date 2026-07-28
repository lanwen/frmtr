package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Owns the width-driven broken-shape emitter for {@code new Type[] {...}} array-creation variable initializers, and
 * the own-break classification the surrounding assignment consults to avoid a second outer break.
 *
 * <p>This helper hosts the family reached once a flat array creation overflows: the opener-hug that keeps
 * {@code NAME = new T[] {} on the assignment line, the compact-continuation fallback for a short list of empty
 * constructor calls, and the element-per-line last resort ({@link #variableWithBrokenArrayCreation}); and the
 * own-break predicate ({@link #arrayCreationHasOwnBreak}) that reports whether an array creation already claims its
 * assignment continuation shape.
 *
 * <p>The helper claims no ownership of when an initializer is over-width or of the assignment prefix: it reports
 * {@link Optional#empty()} the moment a creation is out of its remit (own type-argument break, contained comments, no
 * initializer) and hands the value back to the caller's cascade.
 */
final class InitializerArrayLayout {

    private final SourceShapePolicy sourceShapePolicy;

    private final FormatterOptions options;

    private final LayoutWidth layoutWidth;

    private final BiPredicate<ArrayCreationExpr, ToIntFunction<String>> arrayCreationTypeBreaks;

    private final Function<ArrayCreationExpr, String> arrayCreationPrefix;

    private final BiFunction<ArrayInitializerExpr, Boolean, Doc> arrayInitializer;

    private final BiFunction<ArrayInitializerExpr, String, String> compactArrayInitializerWithSourceSpacing;

    private final Function<List<? extends Node>, String> compactJoin;

    InitializerArrayLayout(
            SourceShapePolicy sourceShapePolicy,
            FormatterOptions options,
            LayoutWidth layoutWidth,
            BiPredicate<ArrayCreationExpr, ToIntFunction<String>> arrayCreationTypeBreaks,
            Function<ArrayCreationExpr, String> arrayCreationPrefix,
            BiFunction<ArrayInitializerExpr, Boolean, Doc> arrayInitializer,
            BiFunction<ArrayInitializerExpr, String, String> compactArrayInitializerWithSourceSpacing,
            Function<List<? extends Node>, String> compactJoin
    ) {
        this.sourceShapePolicy = sourceShapePolicy;
        this.options = options;
        this.layoutWidth = layoutWidth;
        this.arrayCreationTypeBreaks = arrayCreationTypeBreaks;
        this.arrayCreationPrefix = arrayCreationPrefix;
        this.arrayInitializer = arrayInitializer;
        this.compactArrayInitializerWithSourceSpacing = compactArrayInitializerWithSourceSpacing;
        this.compactJoin = compactJoin;
    }

    /**
     * Breaks an over-width array-creation initializer between the opener-hug, the compact-continuation fallback, and
     * the element-per-line last resort, leaving own-breaking type arguments and commented creations to the caller's
     * {@code new Type<...>[]} type-argument break.
     */
    Optional<Doc> variableWithBrokenArrayCreation(
            String name,
            String flatName,
            ArrayCreationExpr arrayCreation
    ) {
        ToIntFunction<String> continuationPrefixWidth = layoutWidth::continuationStatement;
        if (
            arrayCreation.getInitializer().isEmpty()
            || arrayCreationTypeBreaks.test(arrayCreation, continuationPrefixWidth)
            || sourceShapePolicy.hasContainedComments(arrayCreation)
        ) {
            return Optional.empty();
        }
        String prefix = arrayCreationPrefix.apply(arrayCreation);
        ArrayInitializerExpr initializer = arrayCreation.getInitializer().orElseThrow();
        // Measure the {@code NAME = new T[] {} opener on the assignment line at the initializer's true rendered
        // block/type depth ({@link LayoutWidth#nodeLine}) rather than a fixed current-column baseline.
        if (layoutWidth.nodeLine(arrayCreation, flatName + " = " + prefix + " {") <= options.lineWidth()) {
            return Optional.of(
                Doc.concat(Doc.text(name + " = " + prefix + " "), arrayInitializer.apply(initializer, true))
            );
        }
        Optional<String> compactContinuation = compactObjectCreationArrayInitializer(initializer);
        if (
            compactContinuation.isPresent()
            && layoutWidth.currentIndented(prefix + " " + compactContinuation.orElseThrow()) <= options.lineWidth()
        ) {
            return Optional.of(
                Doc.concat(
                    Doc.text(name + " ="),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(prefix + " " + compactContinuation.orElseThrow())))
                )
            );
        }
        return Optional.of(
            Doc.concat(
                Doc.text(name + " ="),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(prefix + " "), arrayInitializer.apply(initializer, true)))
            )
        );
    }

    /**
     * Keeps an array initializer compact only for the narrow object-creation list that reads better as one continuation.
     */
    private Optional<String> compactObjectCreationArrayInitializer(ArrayInitializerExpr initializer) {
        if (
            sourceShapePolicy.hasContainedComments(initializer)
            || initializer.getValues().isEmpty()
            || initializer.getValues().stream().anyMatch(value -> !compactObjectCreationArrayValue(value))
        ) {
            return Optional.empty();
        }
        String values = compactJoin.apply(initializer.getValues());
        return Optional.of(compactArrayInitializerWithSourceSpacing.apply(initializer, values));
    }

    /**
     * Allows the compact array continuation only for empty constructor calls, where each value stays readable without
     * its own argument or anonymous-body layout.
     */
    private boolean compactObjectCreationArrayValue(Expression value) {
        return value instanceof ObjectCreationExpr objectCreation
            && objectCreation.getScope().isEmpty()
            && objectCreation.getTypeArguments().isEmpty()
            && objectCreation.getArguments().isEmpty()
            && objectCreation.getAnonymousClassBody().isEmpty();
    }

    /**
     * Treats array initializers and genuinely-overflowing generic array types as already owning the assignment
     * continuation shape.
     *
     * <p>An array with an initializer always owns its break (the initializer drives the layout). An array without an
     * initializer only owns a break when its generic type arguments overflow at the continuation baseline and therefore
     * take the width-driven last-resort break; a short generic array type whose compact prefix fits its continuation line
     * does not claim an own-break and lets the surrounding assignment decide where to break. The continuation baseline is
     * used so this stays consistent with {@link #variableWithBrokenArrayCreation}: a generic array type only reports an
     * own-break when it would still overflow after the assignment cleanly broke at {@code =}.
     */
    boolean arrayCreationHasOwnBreak(ArrayCreationExpr expression) {
        return expression.getInitializer().isPresent()
            || arrayCreationTypeBreaks.test(expression, layoutWidth::continuationStatement);
    }
}
