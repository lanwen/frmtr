package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.JavaParser;
import com.github.javaparser.JavaToken;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.TokenRange;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import dev.lanwen.frmtr.FixtureInput;
import dev.lanwen.frmtr.ResourceFixtureSource;
import dev.lanwen.frmtr.SourceShapePerturbation;
import dev.lanwen.frmtr.SourceShapePerturbation.Shape;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins the whitespace-invariance of the canonical comment binding: containment and the preceding/following/enclosing
 * skeleton are pure functions of the code-token stream, so they do not change when a pass re-wraps the source. The
 * role-gated owner is deliberately not asserted invariant here — it is preserved from source layout, which an arbitrary
 * whitespace perturbation may change (moving a trailing comment to its own line), so only the skeleton is invariant.
 */
final class CanonicalCommentBindingTest {

    @Test
    void trailingStatementCommentIsNotContainedInTheChainRegardlessOfLayout() {
        // The #395 non-idempotence root: JavaParser attaches `// note` to the statement when flat but to an inner
        // operand when broken, flipping getAllContainedComments(chain). The canonical binding keeps it outside the chain
        // in both layouts because its token gap `(; , })` is past the chain's last code token.
        String flat = "class Demo { void run() { when(x).thenReturn(list(a, b)); // note\n } }";
        String broken = "class Demo { void run() { when(x)\n.thenReturn(list(a,\n b)); // note\n } }";

        CompilationUnit flatUnit = parse(flat);
        CompilationUnit brokenUnit = parse(broken);
        MethodCallExpr flatChain = outermostChain(flatUnit);
        MethodCallExpr brokenChain = outermostChain(brokenUnit);

        assertThat(flatChain.getAllContainedComments()).isEmpty();
        assertThat(brokenChain.getAllContainedComments()).isNotEmpty();

        assertThat(CanonicalCommentBinding.from(flatUnit).hasContainedComments(flatChain)).isFalse();
        assertThat(CanonicalCommentBinding.from(brokenUnit).hasContainedComments(brokenChain)).isFalse();
    }

    @Test
    void containmentFollowsTokenSpanNotJavaParserAttachment() {
        CompilationUnit unit = parse("class Demo { void run() { if (ready) { call(); /* here */ } } }");
        CanonicalCommentBinding binding = CanonicalCommentBinding.from(unit);
        Comment comment = onlyComment(unit);

        Node ifStatement = unit.findFirst(com.github.javaparser.ast.stmt.IfStmt.class).orElseThrow();
        Node thenBlock = ifStatement.findFirst(com.github.javaparser.ast.stmt.BlockStmt.class).orElseThrow();
        MethodCallExpr call = unit.findFirst(MethodCallExpr.class).orElseThrow();

        assertThat(binding.contains(thenBlock, comment)).isTrue();
        assertThat(binding.contains(ifStatement, comment)).isTrue();
        // The comment trails the call's last token, so it is not inside the call even though it shares the block.
        assertThat(binding.contains(call, comment)).isFalse();
    }

    @Test
    void fileHeaderBlockCommentBindsToTheCompilationUnitNotThePackage() {
        CompilationUnit unit = parse("/* license */\npackage demo;\nclass Demo {}");
        CanonicalCommentBinding binding = CanonicalCommentBinding.from(unit);
        Comment header = unit.getComment().orElseThrow();

        assertThat(binding.owner(header)).contains(unit);
    }

    @Test
    void commaTrailingCommentBindsToThePrecedingArgumentNotTheNextOne() {
        // "closed nearest before": the `,` separator must not null out preceding, so the comment stays on the argument
        // it trails (user story 9), not the following one.
        CompilationUnit unit = parse("class Demo { void run() { call(firstArgument, // note\n secondArgument); } }");
        CanonicalCommentBinding binding = CanonicalCommentBinding.from(unit);
        Comment comment = boundComment(unit, binding, "note");

        Node owner = binding.owner(comment).orElseThrow();
        assertThat(owner).isInstanceOf(NameExpr.class);
        assertThat(((NameExpr) owner).getNameAsString()).isEqualTo("firstArgument");
    }

    @Test
    void operatorTrailingCommentBindsToThePrecedingOperand() {
        CompilationUnit unit = parse("class Demo { boolean ready = open && // note\n active; }");
        CanonicalCommentBinding binding = CanonicalCommentBinding.from(unit);
        Comment comment = boundComment(unit, binding, "note");

        Node owner = binding.owner(comment).orElseThrow();
        assertThat(owner).isInstanceOf(NameExpr.class);
        assertThat(((NameExpr) owner).getNameAsString()).isEqualTo("open");
    }

    @Test
    void interSegmentTrailingCommentReAnchorsToTheSegmentCall() {
        CompilationUnit unit = parse("class Demo { void run() { source.merge(x) // note\n .groupBy(y); } }");
        CanonicalCommentBinding binding = CanonicalCommentBinding.from(unit);
        Comment comment = onlyComment(unit);

        Node owner = binding.owner(comment).orElseThrow();
        assertThat(owner).isInstanceOf(MethodCallExpr.class);
        assertThat(((MethodCallExpr) owner).getNameAsString()).isEqualTo("merge");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "class Demo { void run() { when(x).thenReturn(list(a, b)); // trailing\n } }",
        "class Demo { void run() { if (ready && open) { work(); } // after if\n } }",
        "class Demo { void run() { source.merge(x) // segment\n .filter(y).groupBy(z); } }",
        "class Demo { void run() { while (ready && /* gap */ (open || busy)) { step(); } } }",
        "class Demo { void run() { call(first, // arg\n second); } }",
        "class Demo { int total = base // operand\n + extra; }",
        "/* header */\npackage demo;\nclass Demo { /* body */ int value; }",
        "class Demo {\n  // leading\n  void run() {\n    step(); // trailing\n    // standalone\n    stop();\n  }\n}",
    })
    void containmentAndSkeletonAreWhitespaceInvariant(String source) {
        Snapshot original = snapshot(source);
        Snapshot collapsed = snapshot(SourceShapePerturbation.perturb(source, Shape.COLLAPSE));
        Snapshot expanded = snapshot(SourceShapePerturbation.perturb(source, Shape.EXPAND));

        Map<Integer, Comment> originalByOrdinal = original.commentsByCodeOrdinal();
        for (Map.Entry<Integer, Comment> entry : originalByOrdinal.entrySet()) {
            int ordinal = entry.getKey();
            Comment collapsedComment = collapsed.commentsByCodeOrdinal().get(ordinal);
            Comment expandedComment = expanded.commentsByCodeOrdinal().get(ordinal);
            assertThat(collapsedComment).as("collapsed keeps comment at code-ordinal %s", ordinal).isNotNull();
            assertThat(expandedComment).as("expanded keeps comment at code-ordinal %s", ordinal).isNotNull();

            String label = entry.getValue().getContent().strip();
            assertThat(collapsed.containingSignatures(collapsedComment))
                    .as("containment for '%s' under collapse", label)
                    .isEqualTo(original.containingSignatures(entry.getValue()));
            assertThat(expanded.containingSignatures(expandedComment))
                    .as("containment for '%s' under expand", label)
                    .isEqualTo(original.containingSignatures(entry.getValue()));
            assertThat(collapsed.skeletonSignature(collapsedComment))
                    .as("skeleton for '%s' under collapse", label)
                    .isEqualTo(original.skeletonSignature(entry.getValue()));
            assertThat(expanded.skeletonSignature(expandedComment))
                    .as("skeleton for '%s' under expand", label)
                    .isEqualTo(original.skeletonSignature(entry.getValue()));
        }
    }

    /**
     * The corpus-wide ratchet: for every comment-bearing fixture input, the canonical skeleton must survive both
     * whitespace re-shapes unchanged, so a future rule that reintroduces whitespace-sensitivity fails here immediately.
     * Containment invariance follows from skeleton invariance — node spans nest along the ancestor chain in the
     * (perturbation-preserved) AST, so the set of nodes containing a comment is exactly its enclosing node's ancestry.
     */
    @ParameterizedTest(name = "{0}")
    @ResourceFixtureSource(glob = "format/**/input.java")
    void skeletonIsWhitespaceInvariantAcrossTheFixtureCorpus(FixtureInput fixture) {
        Optional<Snapshot> original = tryParse(fixture.source()).map(this::snapshotOf);
        if (original.isEmpty() || original.orElseThrow().commentsByCodeOrdinal().isEmpty()) {
            return;
        }
        Map<Integer, Comment> originalByOrdinal = original.orElseThrow().commentsByCodeOrdinal();
        for (Shape shape : Shape.values()) {
            String perturbedSource = SourceShapePerturbation.perturb(fixture.source(), shape);
            Optional<Snapshot> perturbed = perturbedSource == null
                ? Optional.empty()
                : tryParse(perturbedSource).map(this::snapshotOf);
            if (perturbed.isEmpty()) {
                continue;
            }
            Map<Integer, Comment> perturbedByOrdinal = perturbed.orElseThrow().commentsByCodeOrdinal();
            for (Map.Entry<Integer, Comment> entry : originalByOrdinal.entrySet()) {
                Comment perturbedComment = perturbedByOrdinal.get(entry.getKey());
                if (perturbedComment == null) {
                    continue;
                }
                assertThat(perturbed.orElseThrow().skeletonSignature(perturbedComment))
                        .as("skeleton for '%s' under %s in %s",
                            entry.getValue().getContent().strip(), shape, fixture.name())
                        .isEqualTo(original.orElseThrow().skeletonSignature(entry.getValue()));
            }
        }
    }

    private Snapshot snapshot(String source) {
        return snapshotOf(parse(source));
    }

    private Snapshot snapshotOf(CompilationUnit unit) {
        return new Snapshot(unit, CanonicalCommentBinding.from(unit), codeOrdinals(unit));
    }

    private static Optional<CompilationUnit> tryParse(String source) {
        return newParser().parse(ParseStart.COMPILATION_UNIT, Providers.provider(source)).getResult();
    }

    /** One parsed layout with its binding and a code-only token numbering that lines up across whitespace re-shapes. */
    private record Snapshot(CompilationUnit unit, CanonicalCommentBinding binding, Map<JavaToken, Integer> codeOrdinal) {

        Map<Integer, Comment> commentsByCodeOrdinal() {
            Map<Integer, Comment> byOrdinal = new java.util.TreeMap<>();
            List<Comment> comments = new ArrayList<>(unit.getAllContainedComments());
            unit.getComment().ifPresent(comments::add);
            for (Comment comment : comments) {
                Optional<JavaToken> token = comment.getTokenRange().map(TokenRange::getBegin);
                if (token.isPresent() && codeOrdinal.containsKey(token.orElseThrow())) {
                    byOrdinal.put(codeOrdinal.get(token.orElseThrow()), comment);
                }
            }
            return byOrdinal;
        }

        List<String> containingSignatures(Comment comment) {
            TreeSet<String> signatures = new TreeSet<>();
            for (Node node : unit.stream().toList()) {
                if (!(node instanceof Comment) && binding.contains(node, comment)) {
                    signatures.add(signature(node));
                }
            }
            return List.copyOf(signatures);
        }

        String skeletonSignature(Comment comment) {
            CanonicalCommentBinding.Skeleton skeleton = binding.skeleton(comment).orElseThrow();
            return signature(skeleton.preceding()) + " | " + signature(skeleton.following())
                    + " | " + signature(skeleton.enclosing());
        }

        private String signature(Node node) {
            if (node == null) {
                return "none";
            }
            JavaToken first = CanonicalCommentBinding.firstCodeToken(node);
            JavaToken last = CanonicalCommentBinding.lastCodeToken(node);
            return node.getClass().getSimpleName()
                    + ":" + codeOrdinal.getOrDefault(first, -1)
                    + "-" + codeOrdinal.getOrDefault(last, -1);
        }
    }

    private static Map<JavaToken, Integer> codeOrdinals(CompilationUnit unit) {
        Map<JavaToken, Integer> ordinals = new IdentityHashMap<>();
        Optional<TokenRange> range = unit.getTokenRange();
        if (range.isEmpty()) {
            return ordinals;
        }
        JavaToken token = range.orElseThrow().getBegin();
        JavaToken end = range.orElseThrow().getEnd();
        int ordinal = 0;
        while (token != null) {
            if (!token.getCategory().isWhitespace()) {
                ordinals.put(token, ordinal++);
            }
            if (token == end) {
                break;
            }
            token = token.getNextToken().orElse(null);
        }
        return ordinals;
    }

    private static MethodCallExpr outermostChain(CompilationUnit unit) {
        return unit.findAll(MethodCallExpr.class)
                .stream()
                .filter(call -> call.getNameAsString().equals("thenReturn"))
                .findFirst()
                .orElseThrow();
    }

    private static Comment boundComment(CompilationUnit unit, CanonicalCommentBinding binding, String content) {
        return unit.getAllContainedComments()
                .stream()
                .filter(comment -> binding.owner(comment).isPresent())
                .filter(comment -> comment.getContent().strip().equals(content))
                .findFirst()
                .orElseThrow();
    }

    private static Comment onlyComment(CompilationUnit unit) {
        List<Comment> comments = unit.getAllContainedComments();
        assertThat(comments).hasSize(1);
        return comments.getFirst();
    }

    private static CompilationUnit parse(String source) {
        return newParser().parse(ParseStart.COMPILATION_UNIT, Providers.provider(source))
                .getResult()
                .orElseThrow();
    }

    private static JavaParser newParser() {
        return new JavaParser(
            new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                    .setStoreTokens(true)
                    .setAttributeComments(true)
        );
    }
}
