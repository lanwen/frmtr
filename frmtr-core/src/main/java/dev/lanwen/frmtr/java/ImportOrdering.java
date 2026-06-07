package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import java.util.Comparator;
import java.util.List;

/**
 * Normalizes Java import declarations into deterministic formatter order before rendering.
 *
 * <p>This helper owns only the import transform: static-versus-ordinary grouping and stable name ordering inside each
 * group. The boundary exists so {@link CompilationUnitPrinter} can sequence already-ordered compilation-unit parts while
 * {@link ImportDeclarationPrinter} keeps rendering one import declaration at a time. It intentionally leaves package,
 * module, and type ordering, the blank line between import groups, and all {@code Doc} rendering decisions to callers.
 */
final class ImportOrdering {
    private final List<ImportDeclaration> statics;
    private final List<ImportDeclaration> normal;

    private ImportOrdering(List<ImportDeclaration> statics, List<ImportDeclaration> normal) {
        this.statics = statics;
        this.normal = normal;
    }

    /**
     * Builds ordered import groups without cloning the JavaParser nodes.
     *
     * <p>Leading comments stay attached to the original {@link ImportDeclaration} nodes, so this transform reorders the
     * declarations but preserves the nodes that the import printer later renders. Java stream sorting is stable, which
     * keeps duplicate import names in source order while still making the formatter's ordinary static/name ordering
     * deterministic.
     */
    static ImportOrdering order(NodeList<ImportDeclaration> declarations) {
        List<ImportDeclaration> normal = declarations.stream()
                .filter(importDeclaration -> !importDeclaration.isStatic())
                .sorted(Comparator.comparing(ImportDeclaration::getNameAsString))
                .toList();
        List<ImportDeclaration> statics = declarations.stream()
                .filter(ImportDeclaration::isStatic)
                .sorted(Comparator.comparing(ImportDeclaration::getNameAsString))
                .toList();
        return new ImportOrdering(statics, normal);
    }

    boolean isEmpty() {
        return statics.isEmpty() && normal.isEmpty();
    }

    List<ImportDeclaration> staticImports() {
        return statics;
    }

    List<ImportDeclaration> normalImports() {
        return normal;
    }
}
