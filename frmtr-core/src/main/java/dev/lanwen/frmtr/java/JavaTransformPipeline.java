package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.CompilationUnit;
import java.util.List;

/**
 * Applies the formatter's declared AST transforms between parsing and printing.
 *
 * <p>This helper owns only transform sequencing: it makes the normalization stage explicit without letting individual
 * printers decide when source-equivalent AST edits happen. It intentionally leaves transform implementations to small
 * focused helpers, parsing and error handling to {@link JavaFormatter}, and all {@code Doc} rendering decisions to
 * {@link JavaPrinter} and the printer layer.
 */
final class JavaTransformPipeline {
    private final List<JavaFormatTransform> transforms;

    JavaTransformPipeline(List<JavaFormatTransform> transforms) {
        this.transforms = List.copyOf(transforms);
    }

    /**
     * Runs every transform over the same parsed compilation unit.
     *
     * <p>The pipeline passes the transformed unit from one step to the next so future transforms can build on earlier
     * source-equivalent normalization while preserving a single JavaParser tree for comment and node identity tracking.
     * Per-transform result metadata stays at this boundary because the formatter only needs the final unit today.
     */
    CompilationUnit transform(CompilationUnit unit) {
        CompilationUnit transformed = unit;
        for (JavaFormatTransform transform : transforms) {
            FormatterGuardrails.TransformSnapshot guardrails =
                    FormatterGuardrails.beforeTransform(transform, transformed);
            JavaTransformResult result = transform.transform(transformed);
            FormatterGuardrails.assertTransformInvariants(guardrails, result);
            transformed = result.unit();
        }
        return transformed;
    }
}
