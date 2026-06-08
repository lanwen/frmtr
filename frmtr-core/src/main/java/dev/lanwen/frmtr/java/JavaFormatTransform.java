package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.CompilationUnit;

/**
 * Mutates a parsed compilation unit before any formatter printer renders docs.
 *
 * <p>This package-private boundary owns source-equivalent AST normalization that must be visible in the formatter
 * pipeline but should not be hidden inside rendering helpers. It intentionally leaves parsing, comment tracking, doc
 * construction, and output rendering to {@link JavaFormatter}, {@link JavaPrinter}, and their printer collaborators.
 */
interface JavaFormatTransform {
    /**
     * Applies this transform to the parsed compilation unit and returns the same unit for pipeline chaining.
     *
     * <p>Transforms may reorder existing JavaParser nodes when that preserves formatter behavior, but callers remain
     * responsible for deciding which transforms belong in the pipeline and when printing starts.
     */
    CompilationUnit transform(CompilationUnit unit);
}
