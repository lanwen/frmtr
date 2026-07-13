package dev.lanwen.frmtr.java;

import com.github.javaparser.GeneratedJavaParserConstants;
import com.github.javaparser.JavaToken;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Owns the per-constant rendering for {@link EnumDeclarationPrinter}: given one {@link EnumConstantDeclaration} it emits
 * that constant's leading comment, annotations, name, argument list, optional explicit class body, and deferred
 * trailing-comment suffix as a single {@link Doc}.
 *
 * <p>This helper hosts the family that decides how a constant's arguments lay out: compact raw text when no argument
 * needs formatter-owned breaking, a flat expression list while the rendered constant still fits, and one argument per
 * line once it overflows — together with the lambda-argument probe that routes a constant carrying a lambda body through
 * the expression callback, and the token scan that appends a class body only when the source actually wrote one
 * ({@code VALUE { ... }}). The boundary exists so the enum printer's entry-list sequencing can delegate "render this one
 * constant" to a single authority instead of carrying the argument-width probe, the class-body token scan, and the
 * leading / tail comment wiring inline.
 *
 * <p>The helper claims no ownership of entry sequencing, inter-constant separators, blank-line preservation, or parse
 * recovery: it renders the constant it is handed and reads that constant's own comments through the shared {@link
 * EnumConstantComments}, but never decides where a constant lands relative to its siblings — that stays with the caller.
 * Expression formatting and the per-constant class-body member block are likewise left to {@link EnumDeclarationPrinter}'s
 * callbacks, threaded in as the {@code expression} and {@code memberBlockRenderer} handles so an enum constant body keeps
 * the same member sequencing as other body blocks.
 */
final class EnumConstantLayout {

    private final EnumConstantComments enumConstantComments;

    private final SourceText sourceText;

    private final FormatterOptions options;

    private final Function<NodeWithAnnotations<?>, Doc> annotations;

    private final Function<List<? extends Node>, String> compactJoin;

    private final Function<Expression, Doc> expression;

    private final ToIntFunction<String> currentIndentedWidth;

    private final BiFunction<NodeList<BodyDeclaration<?>>, Node, Doc> memberBlockRenderer;

    EnumConstantLayout(
            EnumConstantComments enumConstantComments,
            SourceText sourceText,
            FormatterOptions options,
            Function<NodeWithAnnotations<?>, Doc> annotations,
            Function<List<? extends Node>, String> compactJoin,
            Function<Expression, Doc> expression,
            ToIntFunction<String> currentIndentedWidth,
            BiFunction<NodeList<BodyDeclaration<?>>, Node, Doc> memberBlockRenderer
    ) {
        this.enumConstantComments = enumConstantComments;
        this.sourceText = sourceText;
        this.options = options;
        this.annotations = annotations;
        this.compactJoin = compactJoin;
        this.expression = expression;
        this.currentIndentedWidth = currentIndentedWidth;
        this.memberBlockRenderer = memberBlockRenderer;
    }

    /**
     * Prints one enum constant, including leading comments, arguments, and comments attached to the constant's tail.
     */
    Doc enumConstant(
            EnumDeclaration owner,
            EnumConstantDeclaration declaration,
            EnumConstantDeclaration next
    ) {
        return enumConstant(declaration, enumConstantComments.tail(owner, declaration, next));
    }

    private Doc enumConstant(EnumConstantDeclaration declaration, EnumConstantComments.Tail tail) {
        return enumConstant(enumConstantComments.leading(declaration), declaration, tail);
    }

    Doc enumConstant(Doc leading, EnumConstantDeclaration declaration, EnumConstantComments.Tail tail) {
        return Doc.concat(
            leading,
            enumConstantAnnotations(declaration),
            Doc.text(declaration.getNameAsString()),
            enumConstantArguments(declaration),
            enumConstantClassBody(declaration),
            tail.suffix()
        );
    }

    private Doc enumConstantClassBody(EnumConstantDeclaration declaration) {
        if (!hasExplicitEnumConstantClassBody(declaration)) {
            return Doc.EMPTY;
        }
        return Doc.concat(Doc.text(" "), memberBlockRenderer.apply(declaration.getClassBody(), declaration));
    }

    private boolean hasExplicitEnumConstantClassBody(EnumConstantDeclaration declaration) {
        SourceRegion nameRegion = declaration.getName()
                .getRange()
                .map(sourceText::region)
                .orElseThrow(() -> new IllegalArgumentException("enum constant name is missing a source range"));
        return declaration
                .getTokenRange()
                .map(tokenRange -> {
                    boolean afterName = false;
                    int parenDepth = 0;
                    for (JavaToken token : tokenRange) {
                        if (!afterName) {
                            afterName = tokenMatchesRegion(token, nameRegion);
                            continue;
                        }
                        if (token.getKind() == GeneratedJavaParserConstants.LPAREN) {
                            parenDepth++;
                            continue;
                        }
                        if (token.getKind() == GeneratedJavaParserConstants.RPAREN && parenDepth > 0) {
                            parenDepth--;
                            continue;
                        }
                        if (token.getKind() == GeneratedJavaParserConstants.LBRACE && parenDepth == 0) {
                            return true;
                        }
                    }
                    return false;
                })
                .orElse(false);
    }

    private boolean tokenMatchesRegion(JavaToken token, SourceRegion expected) {
        return token.getRange()
                .map(sourceText::region)
                .map(region -> region.beginOffset() == expected.beginOffset()
                    && region.endOffset() == expected.endOffset())
                .orElse(false);
    }

    private Doc enumConstantAnnotations(EnumConstantDeclaration declaration) {
        if (declaration.getAnnotations().isEmpty()) {
            return Doc.EMPTY;
        }
        return Doc.concat(annotations.apply(declaration), Doc.EMPTY);
    }

    /**
     * Prints enum constant arguments compactly unless a lambda argument needs normal expression docs.
     *
     * <p>Lambda arguments can contain bodies that need formatter-owned breaking decisions, so the helper uses the
     * expression callback for those cases and falls back to one-argument-per-line only when the rendered constant no
     * longer fits.
     */
    private Doc enumConstantArguments(EnumConstantDeclaration declaration) {
        if (declaration.getArguments().isEmpty()) {
            return Doc.EMPTY;
        }
        if (declaration.getArguments().stream().noneMatch(this::enumConstantArgumentNeedsDoc)) {
            return Doc.text("(" + compactJoin.apply(declaration.getArguments()) + ")");
        }
        String flat = declaration.getNameAsString() + "(" + compactJoin.apply(declaration.getArguments()) + ")";
        if (currentIndentedWidth.applyAsInt(flat) <= options.lineWidth()) {
            return Doc.concat(
                Doc.text("("),
                Doc.join(Doc.text(", "), declaration.getArguments().stream().map(expression).toList()),
                Doc.text(")")
            );
        }
        return Doc.concat(
            Doc.text("("),
            Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    Doc.join(
                        Doc.concat(Doc.text(","), Doc.HARD_LINE),
                        declaration.getArguments()
                                .stream()
                                .map(expression)
                                .toList()
                    )
                )
            ),
            Doc.HARD_LINE,
            Doc.text(")")
        );
    }

    /**
     * Detects enum constant arguments that need expression docs instead of compact raw text.
     */
    private boolean enumConstantArgumentNeedsDoc(Expression expression) {
        return expression instanceof LambdaExpr || expression.findFirst(LambdaExpr.class).isPresent();
    }
}
