package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import dev.lanwen.frmtr.doc.Doc;
import java.util.function.Function;

/**
 * Prints initializer declarations after body dispatch has selected the initializer branch.
 *
 * <p>This helper owns the declaration prefix that distinguishes static initialization blocks from instance
 * initialization blocks. It intentionally delegates brace and statement rendering back through {@link JavaPrinter} so
 * initializer bodies share the same block sequencing, orphan-comment handling, and statement formatting as method and
 * constructor bodies.
 *
 * <p>There is no dedicated static-or-instance initializer fixture yet. The closest block/comment behavior examples live
 * at {@code frmtr-core/src/test/resources/format/comment-preservation-block-end-comments/input.java}
 * and {@code frmtr-core/src/test/resources/format/comment-preservation-block-end-comments/frmtr-default.output.java}.
 */
final class InitializerDeclarationPrinter {
    private final Function<BlockStmt, Doc> block;

    InitializerDeclarationPrinter(Function<BlockStmt, Doc> block) {
        this.block = block;
    }

    /**
     * Prints a static or instance initializer declaration.
     *
     * <p>JavaParser uses {@link InitializerDeclaration} for both forms: static initializers need the {@code static}
     * keyword before the shared block, while instance initializers are represented by the block alone.
     */
    Doc initializer(InitializerDeclaration declaration) {
        return Doc.concat(
                declaration.isStatic() ? Doc.text("static ") : Doc.EMPTY,
                block.apply(declaration.getBody()));
    }
}
