package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.Comment;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Hosts opt-in formatter pipeline checks for mistakes that should be noisy during formatter development.
 *
 * <p>This helper owns debug-only invariant activation, shared failure messages, and the small bits of bookkeeping needed
 * to turn silent formatter accounting misses into actionable failures. The boundary exists so printers and trackers can
 * keep their normal output behavior while still having one place for development guardrails.
 *
 * <p>Callers still decide which pipeline events are meaningful enough to guard, what recovery or fallback behavior is
 * appropriate when guardrails are disabled, and how rendered output should be assembled.
 */
final class FormatterGuardrails {
    static final String ENABLED_PROPERTY = "dev.lanwen.frmtr.debug.guardrails";
    private static final int COMMENT_SNIPPET_LENGTH = 80;

    private FormatterGuardrails() {}

    /**
     * Records that a comment has been claimed for rendering and rejects duplicate claims when debug guardrails are on.
     *
     * <p>Normal formatter runs keep the existing best-effort behavior: a duplicate claim simply returns {@code false} so
     * callers can skip rendering a second copy. Enabling {@value #ENABLED_PROPERTY} makes the same duplicate claim fail
     * fast, which exposes comment-accounting bugs without changing default formatting output.
     */
    static boolean claimComment(JavaCommentTrivia trivia, Set<Comment> claimedComments) {
        boolean claimed = trivia.claim(claimedComments);
        if (!claimed && enabled()) {
            throw new AssertionError("Formatter comment guardrail failed: duplicate claim for "
                    + describe(trivia.comment()));
        }
        return claimed;
    }

    /**
     * Records comments that reached the output through an explicit raw-source preservation path.
     */
    static void accountRawComments(Node node, Set<Comment> rawRenderedComments) {
        rawRenderedComments.addAll(node.getAllContainedComments());
    }

    /**
     * Records raw-preserved comments while excluding the node's own attached comment.
     */
    static void accountRawCommentsWithoutOwnComment(Node node, Set<Comment> rawRenderedComments) {
        Optional<Comment> ownComment = node.getComment();
        node.getAllContainedComments().stream()
                .filter(comment -> ownComment.stream().noneMatch(own -> own == comment))
                .forEach(rawRenderedComments::add);
    }

    /**
     * Asserts that every JavaParser-exposed comment reached either structured rendering or raw preservation.
     */
    static void assertAllCommentsAccounted(
            Node root,
            Set<Comment> claimedComments,
            Set<Comment> rawRenderedComments) {
        if (!enabled()) {
            return;
        }
        List<Comment> missedComments = root.getAllContainedComments().stream()
                .filter(comment -> !claimedComments.contains(comment))
                .filter(comment -> !rawRenderedComments.contains(comment))
                .sorted(CommentIndex.sourceOrderComparator())
                .toList();
        if (!missedComments.isEmpty()) {
            throw new AssertionError("Formatter comment guardrail failed: unclaimed comment "
                    + describe(missedComments.getFirst())
                    + " was exposed by JavaParser but was not printed or raw-accounted before formatting completed");
        }
    }

    /**
     * Captures JavaParser identity state before one transform runs.
     *
     * <p>The snapshot is intentionally empty unless debug guardrails are enabled. Transform implementations keep their
     * normal source-equivalent mutation behavior, while the pipeline gets one canonical place to validate assumptions it
     * relies on before printing starts.
     */
    static TransformSnapshot beforeTransform(JavaFormatTransform transform, CompilationUnit unit) {
        return enabled() ? TransformSnapshot.capture(transformName(transform), unit) : TransformSnapshot.disabled();
    }

    /**
     * Asserts that a transform kept the formatter's current JavaParser identity contract.
     */
    static void assertTransformInvariants(TransformSnapshot before, JavaTransformResult result) {
        before.assertPreserved(result);
    }

    static boolean enabled() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }

    /**
     * Stores debug-only identity snapshots for one transform invocation.
     *
     * <p>The formatter currently treats transforms as in-place source-equivalent normalization over one JavaParser tree.
     * This snapshot makes that boundary explicit: transforms may reorder existing nodes, but replacing the compilation
     * unit, cloning declarations, or swapping JavaParser-visible comments should fail fast during formatter development.
     */
    static final class TransformSnapshot {
        private final boolean enabled;
        private final String transformName;
        private final CompilationUnit unit;
        private final List<Node> nodes;
        private final Set<Node> nodeIdentities;
        private final List<Comment> comments;
        private final Set<Comment> commentIdentities;
        private final List<ImportDeclaration> imports;
        private final Set<ImportDeclaration> importIdentities;
        private final Map<ImportDeclaration, Optional<Comment>> importComments;

        private TransformSnapshot(
                boolean enabled,
                String transformName,
                CompilationUnit unit,
                List<Node> nodes,
                Set<Node> nodeIdentities,
                List<Comment> comments,
                Set<Comment> commentIdentities,
                List<ImportDeclaration> imports,
                Set<ImportDeclaration> importIdentities,
                Map<ImportDeclaration, Optional<Comment>> importComments) {
            this.enabled = enabled;
            this.transformName = transformName;
            this.unit = unit;
            this.nodes = nodes;
            this.nodeIdentities = nodeIdentities;
            this.comments = comments;
            this.commentIdentities = commentIdentities;
            this.imports = imports;
            this.importIdentities = importIdentities;
            this.importComments = importComments;
        }

        private static TransformSnapshot disabled() {
            return new TransformSnapshot(
                    false,
                    "",
                    null,
                    List.of(),
                    Set.of(),
                    List.of(),
                    Set.of(),
                    List.of(),
                    Set.of(),
                    Map.of());
        }

        private static TransformSnapshot capture(String transformName, CompilationUnit unit) {
            List<Node> nodes = unit.stream().toList();
            List<Comment> comments = unit.getAllContainedComments();
            List<ImportDeclaration> imports = List.copyOf(unit.getImports());
            Map<ImportDeclaration, Optional<Comment>> importComments = new IdentityHashMap<>();
            imports.forEach(importDeclaration -> importComments.put(importDeclaration, importDeclaration.getComment()));
            return new TransformSnapshot(
                    true,
                    transformName,
                    unit,
                    nodes,
                    identitySet(nodes),
                    comments,
                    identitySet(comments),
                    imports,
                    identitySet(imports),
                    importComments);
        }

        private void assertPreserved(JavaTransformResult result) {
            if (!enabled) {
                return;
            }
            if (!transformName.equals(result.transformName())) {
                fail("returned result metadata for "
                        + result.transformName()
                        + " after the pipeline invoked "
                        + transformName);
            }
            CompilationUnit transformed = result.unit();
            if (transformed != unit) {
                fail("returned a different CompilationUnit instance; transforms must keep the original JavaParser tree "
                        + "unless the transform pipeline is redesigned");
            }
            assertIdentitySetPreserved(
                    comments,
                    commentIdentities,
                    transformed.getAllContainedComments(),
                    "JavaParser-visible comment was lost or replaced by identity",
                    "introduced a new JavaParser-visible comment identity",
                    FormatterGuardrails::describe);
            assertImportDeclarationsPreserved(transformed);
            assertIdentitySetPreserved(
                    nodes,
                    nodeIdentities,
                    transformed.stream().toList(),
                    "JavaParser tree node was lost or replaced by identity",
                    "introduced a new JavaParser tree node identity",
                    FormatterGuardrails::describe);
        }

        private void assertImportDeclarationsPreserved(CompilationUnit transformed) {
            List<ImportDeclaration> transformedImports = List.copyOf(transformed.getImports());
            assertIdentitySetPreserved(
                    imports,
                    importIdentities,
                    transformedImports,
                    "import declaration node was lost or replaced instead of being reordered in place",
                    "introduced a new import declaration node instead of reordering existing imports in place",
                    FormatterGuardrails::describeImport);
            for (ImportDeclaration importDeclaration : imports) {
                Optional<Comment> beforeComment = importComments.get(importDeclaration);
                Optional<Comment> afterComment = importDeclaration.getComment();
                if (commentsDiffer(beforeComment, afterComment)) {
                    fail("comment attachment changed for "
                            + describeImport(importDeclaration)
                            + "; comments attached to import declarations must remain on their original import nodes");
                }
            }
        }

        private <T> void assertIdentitySetPreserved(
                List<T> before,
                Set<T> beforeIdentities,
                List<T> after,
                String missingMessage,
                String addedMessage,
                Function<T, String> describe) {
            Set<T> afterIdentities = identitySet(after);
            before.stream()
                    .filter(item -> !afterIdentities.contains(item))
                    .findFirst()
                    .ifPresent(item -> fail(missingMessage + ": " + describe.apply(item)));
            after.stream()
                    .filter(item -> !beforeIdentities.contains(item))
                    .findFirst()
                    .ifPresent(item -> fail(addedMessage + ": " + describe.apply(item)));
        }

        private void fail(String invariant) {
            throw new AssertionError("Formatter transform guardrail failed for "
                    + transformName
                    + ": "
                    + invariant);
        }
    }

    private static String transformName(JavaFormatTransform transform) {
        String simpleName = transform.getClass().getSimpleName();
        return simpleName.isBlank() ? transform.getClass().getName() : simpleName;
    }

    private static boolean commentsDiffer(Optional<Comment> before, Optional<Comment> after) {
        if (before.isEmpty() || after.isEmpty()) {
            return before.isPresent() != after.isPresent();
        }
        return before.orElseThrow() != after.orElseThrow();
    }

    private static <T> Set<T> identitySet(List<T> values) {
        Set<T> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        identities.addAll(values);
        return identities;
    }

    private static String describeImport(ImportDeclaration declaration) {
        String kind = declaration.isStatic() ? "static import " : "import ";
        return "ImportDeclaration "
                + kind
                + declaration.getNameAsString()
                + " at "
                + range(declaration);
    }

    private static String describe(Node node) {
        return node.getClass().getSimpleName() + " at " + range(node);
    }

    private static String describe(Comment comment) {
        String text = snippet(comment.toString());
        return comment.getClass().getSimpleName() + " at " + range(comment) + " [" + text + "]";
    }

    private static String range(Node node) {
        return node.getRange().map(Object::toString).orElse("unknown range");
    }

    private static String snippet(String text) {
        String singleLine = text.strip().replaceAll("\\R", "\\\\n");
        if (singleLine.length() <= COMMENT_SNIPPET_LENGTH) {
            return singleLine;
        }
        return singleLine.substring(0, COMMENT_SNIPPET_LENGTH) + "...";
    }
}
