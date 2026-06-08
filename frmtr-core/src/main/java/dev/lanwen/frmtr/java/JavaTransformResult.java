package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.CompilationUnit;
import java.util.Objects;

/**
 * Carries the output of one Java AST transform together with the transform identity that produced it.
 *
 * <p>This package-private boundary owns only transform-result metadata for the parse-to-print normalization stage. It
 * exists so transforms can keep returning source-equivalent JavaParser trees while the pipeline has a stable place to
 * attach later diagnostics or provenance. It intentionally leaves source maps, node-level provenance, transform
 * sequencing, and all rendering decisions to callers.
 */
record JavaTransformResult(CompilationUnit unit, String transformName) {
    JavaTransformResult {
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(transformName, "transformName");
        if (transformName.isBlank()) {
            throw new IllegalArgumentException("transformName must not be blank");
        }
    }

    /**
     * Creates result metadata for a completed transform without changing the transform's JavaParser identity contract.
     */
    static JavaTransformResult completed(JavaFormatTransform transform, CompilationUnit unit) {
        return new JavaTransformResult(unit, transformName(transform));
    }

    private static String transformName(JavaFormatTransform transform) {
        Objects.requireNonNull(transform, "transform");
        String simpleName = transform.getClass().getSimpleName();
        return simpleName.isBlank() ? transform.getClass().getName() : simpleName;
    }
}
