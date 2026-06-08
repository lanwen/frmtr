package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import java.util.Comparator;

/**
 * Normalizes Java import declarations into deterministic formatter order before rendering.
 *
 * <p>This transform owns only static-versus-ordinary grouping and stable name ordering inside each group. The boundary
 * exists so {@link JavaFormatter} can expose import ordering as a parse-to-print transform while {@link
 * CompilationUnitPrinter} receives already-ordered imports for section layout. It intentionally leaves package, module,
 * and type ordering, the blank line between import groups, and all {@code Doc} rendering decisions to callers.
 */
final class ImportSortTransform implements JavaFormatTransform {
    private static final Comparator<ImportDeclaration> FORMATTER_IMPORT_ORDER = Comparator
            .comparing((ImportDeclaration declaration) -> !declaration.isStatic())
            .thenComparing(ImportDeclaration::getNameAsString);

    /**
     * Reorders imports in place without cloning JavaParser nodes.
     *
     * <p>Leading comments stay attached to their original {@link ImportDeclaration} nodes, so this transform sorts the
     * compilation unit's import list itself rather than creating replacement declarations. Java list sorting is stable,
     * which keeps duplicate import names in source order while still making static/name ordering deterministic.
     */
    @Override
    public JavaTransformResult transform(CompilationUnit unit) {
        unit.getImports().sort(FORMATTER_IMPORT_ORDER);
        return JavaTransformResult.completed(this, unit);
    }
}
