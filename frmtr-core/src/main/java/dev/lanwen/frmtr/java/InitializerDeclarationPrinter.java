package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import dev.lanwen.frmtr.doc.Doc;
import java.util.function.Function;

/**
 * Prints initializer declarations after body dispatch has selected the initializer branch.
 *
 * <p>This helper owns initializer-level leading comments and the declaration prefix that distinguishes static
 * initialization blocks from instance initialization blocks. It intentionally delegates brace and statement rendering
 * back through {@link JavaPrinter} so initializer bodies share the same block sequencing, orphan-comment handling, and
 * statement formatting as method and constructor bodies.
 *
 * <p>There is no dedicated static-or-instance initializer fixture yet. The closest block/comment behavior examples live
 * at {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/comments/comments-blocks-and-statements/end-of-block/input.java}
 * and {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/comments/comments-blocks-and-statements/end-of-block/frmtr.output.java}.
 */
final class InitializerDeclarationPrinter {
    private final JavaFormatter.CommentTracker comments;
    private final Function<BlockStmt, Doc> block;

    InitializerDeclarationPrinter(JavaFormatter.CommentTracker comments, Function<BlockStmt, Doc> block) {
        this.comments = comments;
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
                comments.leading(declaration),
                declaration.isStatic() ? Doc.text("static ") : Doc.EMPTY,
                block.apply(declaration.getBody()));
    }
}
