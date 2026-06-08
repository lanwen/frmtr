package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.modules.ModuleDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import dev.lanwen.frmtr.doc.Doc;
import java.util.function.Function;

/**
 * Prints module declaration headers after compilation-unit ordering has selected the optional module position.
 *
 * <p>This helper owns the fork between the raw commented module fallback and the structured module header. The boundary
 * exists because JavaParser exposes ordinary module headers cleanly, but inline comments inside {@code module-info.java}
 * header or directive syntax need raw-source reconstruction before the normal structured directive printer can be used.
 * It intentionally delegates comment reconstruction to {@link CommentedModulePrinter}, declaration annotation rendering
 * and compact module-name text back to {@link JavaPrinter}, and brace-delimited directive rendering to
 * {@link ModuleBlockPrinter}. It also receives the existing block-comment text conversion as a callback so the raw
 * fallback can preserve the old leading-block-comment behavior without creating a second comment-text policy.
 * Compilation-unit sequencing stays in {@link CompilationUnitPrinter}.
 *
 * <p>Representative fixture pairs live at
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/modules/input.java} and
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/modules/frmtr.output.java}. The raw commented
 * module fallback is covered by {@code
 * frmtr-core/src/test/resources/format/prettier-java/unit-test/comments/package/input.java} and {@code
 * frmtr-core/src/test/resources/format/prettier-java/unit-test/comments/package/frmtr.output.java}.
 */
final class ModuleDeclarationPrinter {
    private final CommentTracker comments;
    private final RawSource rawSource;
    private final RawPreservedSource rawPreservedSource;
    private final CommentedModulePrinter commentedModules;
    private final Function<NodeWithAnnotations<?>, Doc> annotations;
    private final Function<Doc, String> commentText;
    private final Function<Node, String> compact;
    private final Function<ModuleDeclaration, Doc> moduleBlock;

    ModuleDeclarationPrinter(
            CommentTracker comments,
            RawSource rawSource,
            RawPreservedSource rawPreservedSource,
            CommentedModulePrinter commentedModules,
            Function<NodeWithAnnotations<?>, Doc> annotations,
            Function<Doc, String> commentText,
            Function<Node, String> compact,
            Function<ModuleDeclaration, Doc> moduleBlock) {
        this.comments = comments;
        this.rawSource = rawSource;
        this.rawPreservedSource = rawPreservedSource;
        this.commentedModules = commentedModules;
        this.annotations = annotations;
        this.commentText = commentText;
        this.compact = compact;
        this.moduleBlock = moduleBlock;
    }

    /**
     * Prints one module declaration, using raw reconstruction when comments appear inside the module syntax and the
     * structured header path otherwise.
     *
     * <p>{@code open module} and plain {@code module} share the same structured path because {@link ModuleDeclaration}
     * exposes the {@code open} keyword as a boolean; the fallback path is only for comment placement that the structured
     * module AST cannot represent without losing source nuance.
     */
    Doc moduleDeclaration(ModuleDeclaration declaration) {
        String raw = rawSource.raw(declaration);
        if (raw.contains("/*") || raw.contains("//")) {
            Doc leadingBlock = comments.ownComment(declaration, BlockComment.class::isInstance);
            String leadingText = commentText.apply(leadingBlock);
            String commentedRaw = leadingText.isEmpty() ? raw : leadingText + raw;
            return rawPreservedSource.rawWithoutOwnComment(
                    declaration,
                    commentedModules.formatCommentedModule(commentedRaw));
        }
        return Doc.concat(
                comments.leading(declaration),
                annotations.apply(declaration),
                Doc.text(moduleKeyword(declaration) + compact.apply(declaration.getName()) + " "),
                moduleBlock.apply(declaration));
    }

    private String moduleKeyword(ModuleDeclaration declaration) {
        return declaration.isOpen() ? "open module " : "module ";
    }
}
