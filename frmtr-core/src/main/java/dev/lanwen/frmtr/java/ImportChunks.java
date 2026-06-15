package dev.lanwen.frmtr.java;

import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.Comment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Models import declarations as source chunks that are safe to reorder independently.
 *
 * <p>This helper owns the boundary between import sorting and source trivia: blank/comment gaps and detached leading
 * comments become hard chunk boundaries before {@link ImportSortTransform} sorts declarations, but only blank source
 * gaps become render separators. The boundary exists because JavaParser keeps comments attached to import nodes, so
 * callers need one source-position model that can keep comment-bearing imports anchored while still allowing
 * comment-free runs to sort deterministically.
 *
 * <p>Callers still decide the actual sort key, how import chunks are separated in rendered output, and how each
 * individual {@link ImportDeclaration} is printed.
 */
final class ImportChunks {

    private static final Comparator<ImportDeclaration> SOURCE_ORDER = Comparator.comparing(ImportChunks::beginPosition);

    private ImportChunks() {}

    /**
     * Returns source chunks whose declarations keep the current compilation-unit order inside each chunk.
     *
     * <p>{@link ImportSortTransform} uses this before reordering, and {@link CompilationUnitPrinter} uses it after
     * reordering. Rebuilding the chunk membership from source ranges keeps the transform free to sort inside a chunk
     * without losing the original hard boundaries or the render separators that came from real source gaps.
     */
    static List<ImportChunk> orderedChunks(CompilationUnit unit) {
        List<ImportChunk> sourceChunks = sourceChunks(unit);
        if (sourceChunks.isEmpty()) {
            return List.of();
        }
        List<ImportDeclaration> currentImports = List.copyOf(unit.getImports());
        return sourceChunks.stream()
                .map(chunk -> chunk.inCurrentOrder(currentImports))
                .toList();
    }

    private static List<ImportChunk> sourceChunks(CompilationUnit unit) {
        List<ImportDeclaration> sourceImports = sourceOrderedImports(unit);
        List<ImportChunk> chunks = new ArrayList<>();
        List<ImportDeclaration> current = new ArrayList<>();
        ImportDeclaration previous = null;
        boolean separatorBeforeCurrent = false;
        for (ImportDeclaration importDeclaration : sourceImports) {
            boolean separatorBeforeImport = hasSeparatorBefore(previous, importDeclaration);
            boolean sortBoundaryBeforeImport = separatorBeforeImport || hasDetachedLeadingComment(importDeclaration);
            if (!current.isEmpty() && sortBoundaryBeforeImport) {
                chunks.add(new ImportChunk(current, separatorBeforeCurrent));
                current = new ArrayList<>();
                separatorBeforeCurrent = separatorBeforeImport;
            }
            current.add(importDeclaration);
            if (hasDetachedLeadingComment(importDeclaration)) {
                chunks.add(new ImportChunk(current, separatorBeforeCurrent));
                current = new ArrayList<>();
                separatorBeforeCurrent = false;
            }
            previous = importDeclaration;
        }
        if (!current.isEmpty()) {
            chunks.add(new ImportChunk(current, separatorBeforeCurrent));
        }
        return chunks;
    }

    private static List<ImportDeclaration> sourceOrderedImports(CompilationUnit unit) {
        Map<ImportDeclaration, Integer> sourceIndex = new IdentityHashMap<>();
        List<ImportDeclaration> imports = List.copyOf(unit.getImports());
        for (int index = 0; index < imports.size(); index++) {
            sourceIndex.put(imports.get(index), index);
        }
        return imports.stream()
                .sorted(SOURCE_ORDER.thenComparingInt(sourceIndex::get))
                .toList();
    }

    private static boolean hasSeparatorBefore(ImportDeclaration previous, ImportDeclaration current) {
        if (previous == null) {
            return false;
        }
        int previousEndLine = CommentIndex.endLine(previous, Integer.MIN_VALUE);
        int boundaryBeginLine = boundaryBeginLine(current);
        if (previousEndLine == Integer.MIN_VALUE || boundaryBeginLine == Integer.MAX_VALUE) {
            return false;
        }
        return boundaryBeginLine - previousEndLine > 1;
    }

    static boolean hasSourceSeparatorBefore(ImportDeclaration previous, ImportDeclaration current) {
        return hasSeparatorBefore(previous, current);
    }

    private static boolean hasDetachedLeadingComment(ImportDeclaration importDeclaration) {
        int importBeginLine = CommentIndex.beginLine(importDeclaration, Integer.MAX_VALUE);
        if (importBeginLine == Integer.MAX_VALUE) {
            return false;
        }
        return importDeclaration.getComment()
                .map(comment -> endsBeforeImportLine(comment, importBeginLine))
                .orElse(false);
    }

    private static boolean endsBeforeImportLine(Comment comment, int importBeginLine) {
        return CommentIndex.endLine(comment, Integer.MAX_VALUE) < importBeginLine;
    }

    private static int boundaryBeginLine(ImportDeclaration importDeclaration) {
        return importDeclaration.getComment()
                .map(comment -> CommentIndex.beginLine(comment, Integer.MAX_VALUE))
                .orElseGet(() -> CommentIndex.beginLine(importDeclaration, Integer.MAX_VALUE));
    }

    private static Position beginPosition(Node node) {
        return node.getRange().map(range -> range.begin).orElse(Position.HOME);
    }

    record ImportChunk(List<ImportDeclaration> imports, boolean separatorBefore) {
        ImportChunk {
            imports = List.copyOf(imports);
        }

        private ImportChunk inCurrentOrder(List<ImportDeclaration> currentImports) {
            Set<ImportDeclaration> chunkImports = Collections.newSetFromMap(new IdentityHashMap<>());
            chunkImports.addAll(imports);
            return new ImportChunk(
                currentImports.stream()
                        .filter(chunkImports::contains)
                        .toList(),
                separatorBefore
            );
        }
    }
}
