package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalizes Java import declarations into deterministic formatter order before rendering.
 *
 * <p>This transform owns only static-versus-ordinary grouping and stable name ordering inside safe chunks selected by
 * {@link ImportChunks}. The boundary exists so {@link JavaFormatter} can expose import ordering as a parse-to-print
 * transform while {@link CompilationUnitPrinter} receives already-ordered chunks for section layout. It intentionally
 * leaves package, module, and type ordering, blank lines around import chunks, and all {@code Doc} rendering decisions
 * to callers.
 */
final class ImportSortTransform implements JavaFormatTransform {

    /**
     * The deterministic order this transform imposes on imports: static imports first, then by fully-qualified name.
     *
     * <p>Package-visible so the AST-equivalence verifier ({@link AstEquivalence}) can canonicalize import order on both
     * the input and the formatted output before comparing them. Without this, the verifier would flag the deliberate
     * reorder this transform performs as a semantic difference. Import order is not semantically meaningful in Java, so
     * canonicalizing it on both sides is sound; a dropped or duplicated import survives that canonicalization and is
     * still reported as a real difference.
     */
    static final Comparator<ImportDeclaration> FORMATTER_IMPORT_ORDER = Comparator.comparing(
        (ImportDeclaration declaration) -> !declaration.isStatic()
    ).thenComparing(ImportDeclaration::getNameAsString);

    /**
     * Reorders imports in place without cloning JavaParser nodes.
     *
     * <p>Leading comments stay attached to their original {@link ImportDeclaration} nodes, so this transform sorts only
     * the compilation unit's import list rather than creating replacement declarations. Java list sorting is stable,
     * which keeps duplicate import names in source order while still making static/name ordering deterministic inside
     * each chunk.
     */
    @Override
    public JavaTransformResult transform(CompilationUnit unit) {
        List<ImportDeclaration> sortedImports = ImportChunks.orderedChunks(unit)
                .stream()
                .flatMap(chunk -> chunk.imports().stream().sorted(FORMATTER_IMPORT_ORDER))
                .toList();
        Map<ImportDeclaration, Integer> sortedIndex = new IdentityHashMap<>();
        for (int index = 0; index < sortedImports.size(); index++) {
            sortedIndex.put(sortedImports.get(index), index);
        }
        unit.getImports().sort(Comparator.comparingInt(sortedIndex::get));
        return JavaTransformResult.completed(this, unit);
    }
}
