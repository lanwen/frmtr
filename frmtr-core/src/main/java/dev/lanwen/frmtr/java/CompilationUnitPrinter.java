package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.modules.ModuleDeclaration;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Sequences the layout of a Java compilation unit after the parser has exposed package, import, module, and type nodes.
 *
 * <p>This helper owns only whole-file ordering: source-leading package comments, orphan comments before the first type,
 * the package line, ordered import blocks, optional module declarations, top-level declarations, compact unnamed-class
 * member expansion, and trailing orphan comments. It intentionally delegates package declaration text to
 * {@link PackageDeclarationPrinter}, import grouping and sorting to {@link ImportOrdering}, individual imports to {@link
 * ImportDeclarationPrinter}, module declaration formatting to {@link JavaPrinter}, and body declaration formatting back
 * to {@link JavaPrinter}. It does not print statements, expressions, raw body preservation, or any single-node
 * package/import behavior itself.
 *
 * <p>Representative fixture pairs live at
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/package_and_imports/classWithMixedImports/input.java}
 * and
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/package_and_imports/classWithMixedImports/frmtr.output.java}.
 * Module placement with comments is covered by
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/comments/package/input.java} and
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/comments/package/frmtr.output.java}; compact
 * unnamed-class expansion is covered by
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/unnamed-class-compilation-unit/input.java} and
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/unnamed-class-compilation-unit/frmtr.output.java}.
 */
final class CompilationUnitPrinter {
    private final JavaFormatter.CommentTracker comments;
    private final PackageDeclarationPrinter packageDeclarations;
    private final ImportDeclarationPrinter importDeclarations;
    private final Function<ModuleDeclaration, Doc> moduleDeclarations;
    private final Function<BodyDeclaration<?>, Doc> bodyDeclarations;

    CompilationUnitPrinter(
            JavaFormatter.CommentTracker comments,
            PackageDeclarationPrinter packageDeclarations,
            ImportDeclarationPrinter importDeclarations,
            Function<ModuleDeclaration, Doc> moduleDeclarations,
            Function<BodyDeclaration<?>, Doc> bodyDeclarations) {
        this.comments = comments;
        this.packageDeclarations = packageDeclarations;
        this.importDeclarations = importDeclarations;
        this.moduleDeclarations = moduleDeclarations;
        this.bodyDeclarations = bodyDeclarations;
    }

    /**
     * Prints a whole compilation unit in the formatter's fixed top-level order.
     *
     * <p>The sequence first preserves comments that raw source placed before {@code package}, then emits parser orphan
     * comments that appear before the first type, then package/import/module structure, then top-level types or compact
     * unnamed-class members. Comments after the last type are appended as trailing orphan comments so file-level footer
     * comments remain outside declaration rendering.
     */
    Doc print(CompilationUnit unit) {
        List<Doc> parts = new ArrayList<>();
        boolean hasStructuralParts = false;
        Doc sourceLeadingComments = packageDeclarations.sourceLeadingCommentsBeforePackage(unit);
        if (sourceLeadingComments != Doc.EMPTY) {
            parts.add(sourceLeadingComments);
            parts.add(Doc.HARD_LINE);
            parts.add(Doc.HARD_LINE);
        }
        int firstTypeLine = firstTypeLine(unit);
        Doc orphanComments = comments.orphanComments(unit, comment -> commentBeginLine(comment) < firstTypeLine);
        if (orphanComments != Doc.EMPTY) {
            if (!parts.isEmpty()) {
                parts.add(Doc.HARD_LINE);
                parts.add(Doc.HARD_LINE);
            }
            parts.add(orphanComments);
        }
        unit.getPackageDeclaration().ifPresent(packageDeclaration -> {
            parts.add(packageDeclarations.packageDeclaration(packageDeclaration));
        });
        hasStructuralParts = unit.getPackageDeclaration().isPresent();
        Optional<Doc> imports = imports(unit);
        if (imports.isPresent()) {
            if (!parts.isEmpty()) {
                parts.add(Doc.HARD_LINE);
                parts.add(Doc.HARD_LINE);
            }
            parts.add(imports.orElseThrow());
            hasStructuralParts = true;
        }
        Optional<ModuleDeclaration> module = unit.getModule();
        module.ifPresent(moduleDeclaration -> {
            if (!parts.isEmpty()) {
                parts.add(Doc.HARD_LINE);
                parts.add(Doc.HARD_LINE);
            }
            parts.add(moduleDeclarations.apply(moduleDeclaration));
        });
        hasStructuralParts = hasStructuralParts || module.isPresent();
        List<Doc> topLevelDeclarations = topLevelDeclarations(unit);
        if (!topLevelDeclarations.isEmpty()) {
            if (hasStructuralParts) {
                parts.add(Doc.HARD_LINE);
                parts.add(Doc.HARD_LINE);
            }
            parts.add(Doc.join(Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE), topLevelDeclarations));
        }
        Doc trailingOrphanComments = comments.orphanComments(unit, comment -> commentBeginLine(comment) > lastTypeLine(unit));
        if (trailingOrphanComments != Doc.EMPTY) {
            if (!parts.isEmpty()) {
                parts.add(Doc.HARD_LINE);
            }
            parts.add(trailingOrphanComments);
        }
        return Doc.concat(parts);
    }

    private List<Doc> topLevelDeclarations(CompilationUnit unit) {
        Optional<ClassOrInterfaceDeclaration> compactClass = compactClass(unit);
        if (compactClass.isPresent()) {
            return compactClass.orElseThrow().getMembers().stream().map(bodyDeclarations).toList();
        }
        return unit.getTypes().stream().map(bodyDeclarations).toList();
    }

    /**
     * Detects JavaParser's compact compilation-unit wrapper for unnamed classes.
     *
     * <p>When the compilation unit is exactly one compact class, the source-level declarations are the wrapper's
     * members rather than the wrapper itself. Normal classes keep the wrapper and are dispatched as ordinary top-level
     * body declarations.
     */
    private Optional<ClassOrInterfaceDeclaration> compactClass(CompilationUnit unit) {
        if (unit.getTypes().size() != 1 || !(unit.getTypes().get(0) instanceof ClassOrInterfaceDeclaration declaration)) {
            return Optional.empty();
        }
        return declaration.isCompact() ? Optional.of(declaration) : Optional.empty();
    }

    private int firstTypeLine(CompilationUnit unit) {
        return unit.getTypes().stream()
                .flatMap(type -> type.getRange().stream())
                .mapToInt(range -> range.begin.line)
                .min()
                .orElse(Integer.MAX_VALUE);
    }

    private int lastTypeLine(CompilationUnit unit) {
        return unit.getTypes().stream()
                .flatMap(type -> type.getRange().stream())
                .mapToInt(range -> range.end.line)
                .max()
                .orElse(Integer.MAX_VALUE);
    }

    private int commentBeginLine(Comment comment) {
        return comment.getRange().map(range -> range.begin.line).orElse(Integer.MAX_VALUE);
    }

    /**
     * Builds the import section from ordered static and ordinary import groups.
     *
     * <p>The section-level blank line between static and ordinary imports belongs here because it depends on both import
     * groups being present. Import grouping and ordering belongs to {@link ImportOrdering}, and rendering each individual
     * import line remains with {@link ImportDeclarationPrinter}.
     */
    private Optional<Doc> imports(CompilationUnit unit) {
        ImportOrdering orderedImports = ImportOrdering.order(unit.getImports());
        if (orderedImports.isEmpty()) {
            return Optional.empty();
        }
        List<Doc> blocks = new ArrayList<>();
        if (!orderedImports.staticImports().isEmpty()) {
            blocks.add(Doc.join(
                    Doc.HARD_LINE,
                    orderedImports.staticImports().stream().map(importDeclarations::importDeclaration).toList()));
        }
        if (!orderedImports.normalImports().isEmpty() && !orderedImports.staticImports().isEmpty()) {
            blocks.add(Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE));
        }
        if (!orderedImports.normalImports().isEmpty()) {
            blocks.add(Doc.join(
                    Doc.HARD_LINE,
                    orderedImports.normalImports().stream().map(importDeclarations::importDeclaration).toList()));
        }
        return Optional.of(Doc.concat(blocks));
    }
}
