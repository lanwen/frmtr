package dev.lanwen.frmtr.java;

import com.github.javaparser.JavaToken;
import com.github.javaparser.TokenRange;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.comments.Comment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Owns the whitespace-invariant comment→node binding: a token-gap skeleton and token-span containment computed purely
 * from the code-token stream, so comment-driven layout gates converge across passes where JavaParser's line-sensitive
 * attachment flips. It does not render or claim comments — that stays with {@link CommentTracker} and the placement policy.
 */
final class CanonicalCommentBinding {

    /** The comment's own-line/end-of-line character, preserved from source (not part of the invariant skeleton). */
    enum Role { LEADING, TRAILING }

    /**
     * The whitespace-invariant coordinate of a comment: the outermost nodes closing/opening around its token gap and
     * their enclosing node. Two byte-equivalent programs yield the same skeleton however whitespace lays them out.
     */
    record Skeleton(Node preceding, Node following, Node enclosing) {}

    /** A node's inclusive code-token span in stream-ordinal terms, the basis of the containment test. */
    private record Span(int first, int last) {}

    private final Map<Comment, Node> owners;

    private final Map<Comment, Skeleton> skeletons;

    private final Map<Node, Span> codeSpans;

    private final Map<Comment, Integer> commentOrdinal;

    private final int[] commentOrdinals;

    private CanonicalCommentBinding(
            Map<Comment, Node> owners,
            Map<Comment, Skeleton> skeletons,
            Map<Node, Span> codeSpans,
            Map<Comment, Integer> commentOrdinal,
            int[] commentOrdinals
    ) {
        this.owners = owners;
        this.skeletons = skeletons;
        this.codeSpans = codeSpans;
        this.commentOrdinal = commentOrdinal;
        this.commentOrdinals = commentOrdinals;
    }

    /**
     * Computes the canonical binding for one parsed unit from its stored token stream.
     *
     * <p>Requires the parser's {@code setStoreTokens(true)}; a range-less comment (recovered source) with no token gap is
     * left unbound and simply absent from every query, matching the direct-JavaParser fallback callers keep for clones.
     */
    static CanonicalCommentBinding from(CompilationUnit unit) {
        Index index = new Index(unit);
        Map<Comment, Node> owners = new IdentityHashMap<>();
        Map<Comment, Skeleton> skeletons = new IdentityHashMap<>();
        Map<Comment, Integer> commentOrdinal = new IdentityHashMap<>();
        List<Integer> ordinals = new ArrayList<>();

        List<Comment> comments = new ArrayList<>(unit.getAllContainedComments());
        unit.getComment().ifPresent(comments::add);
        for (Comment comment : comments) {
            Optional<JavaToken> token = comment.getTokenRange().map(TokenRange::getBegin);
            if (token.isEmpty() || !index.tokenOrdinal.containsKey(token.orElseThrow())) {
                continue;
            }
            int ordinal = index.tokenOrdinal.get(token.orElseThrow());
            commentOrdinal.put(comment, ordinal);
            ordinals.add(ordinal);
            Skeleton skeleton = index.skeleton(token.orElseThrow(), ordinal);
            skeletons.put(comment, skeleton);
            owners.put(comment, index.owner(comment, token.orElseThrow(), ordinal, skeleton));
        }
        return new CanonicalCommentBinding(owners, skeletons, index.codeSpans, commentOrdinal, sortedInts(ordinals));
    }

    /**
     * Build-time index over one unit's token stream: the stream ordinal of every token, each code node's first/last
     * code-token span, and the outermost node closing/opening at each ordinal. Holds the maps the skeleton and owner
     * computations share so they read fields instead of threading four collections through every call.
     */
    private static final class Index {

        private final CompilationUnit unit;

        private final Map<JavaToken, Integer> tokenOrdinal;

        private final Map<Node, Span> codeSpans = new IdentityHashMap<>();

        private final Map<Integer, Node> outermostEndingAt = new HashMap<>();

        private final Map<Integer, Node> outermostStartingAt = new HashMap<>();

        private int[] endOrdinals = new int[0];

        private int[] startOrdinals = new int[0];

        private final int firstMemberStart;

        Index(CompilationUnit unit) {
            this.unit = unit;
            this.tokenOrdinal = tokenOrdinals(unit);
            indexNodeSpans();
            this.endOrdinals = sortedKeys(outermostEndingAt);
            this.startOrdinals = sortedKeys(outermostStartingAt);
            this.firstMemberStart = firstMemberStartOrdinal();
        }

        /** Walks the whole token stream once, assigning each token a stream ordinal used for span containment tests. */
        private static Map<JavaToken, Integer> tokenOrdinals(CompilationUnit unit) {
            Map<JavaToken, Integer> ordinals = new IdentityHashMap<>();
            Optional<TokenRange> range = unit.getTokenRange();
            if (range.isEmpty()) {
                return ordinals;
            }
            JavaToken token = range.orElseThrow().getBegin();
            JavaToken end = range.orElseThrow().getEnd();
            int ordinal = 0;
            while (token != null) {
                ordinals.put(token, ordinal++);
                if (token == end) {
                    break;
                }
                token = token.getNextToken().orElse(null);
            }
            return ordinals;
        }

        /** Records each code node's span and the outermost node closing/opening at every ordinal. */
        private void indexNodeSpans() {
            for (Node node : unit.stream().toList()) {
                if (node instanceof Comment) {
                    continue;
                }
                JavaToken first = firstCodeToken(node);
                JavaToken last = lastCodeToken(node);
                Integer firstOrd = first == null ? null : tokenOrdinal.get(first);
                Integer lastOrd = last == null ? null : tokenOrdinal.get(last);
                if (firstOrd == null || lastOrd == null) {
                    continue;
                }
                codeSpans.put(node, new Span(firstOrd, lastOrd));
                outermostEndingAt.merge(lastOrd, node, (existing, candidate) ->
                    codeSpans.get(existing).first() <= codeSpans.get(candidate).first() ? existing : candidate);
                outermostStartingAt.merge(firstOrd, node, (existing, candidate) ->
                    codeSpans.get(existing).last() >= codeSpans.get(candidate).last() ? existing : candidate);
            }
        }

        private Skeleton skeleton(JavaToken commentToken, int ordinal) {
            JavaToken prev = skipTrivia(commentToken, true);
            JavaToken next = skipTrivia(commentToken, false);
            Node preceding = precedingNode(prev);
            Node following = followingNode(next);
            return new Skeleton(preceding, following, enclosing(preceding, following, ordinal));
        }

        /**
         * The outermost node that closed nearest before the comment — Biome's {@code preceding}. Keyed on "closed
         * nearest before {@code prevTok}", not "ends exactly at {@code prevTok}", so a separator between the node and the
         * comment (a {@code ,} in an argument list, an operator in a chain) does not null it out.
         */
        private Node precedingNode(JavaToken prev) {
            if (prev == null) {
                return null;
            }
            int key = floorOrdinal(endOrdinals, tokenOrdinal.get(prev));
            return key < 0 ? null : outermostEndingAt.get(key);
        }

        /** The outermost node that opens nearest after the comment — Biome's {@code following}, the mirror of {@link #precedingNode}. */
        private Node followingNode(JavaToken next) {
            if (next == null) {
                return null;
            }
            int key = ceilingOrdinal(startOrdinals, tokenOrdinal.get(next));
            return key < 0 ? null : outermostStartingAt.get(key);
        }

        private Node owner(Comment comment, JavaToken commentToken, int ordinal, Skeleton skeleton) {
            JavaToken prev = skipTrivia(commentToken, true);
            Role role = role(prev, commentToken);
            boolean header = role != Role.TRAILING && firstMemberStart != Integer.MAX_VALUE && ordinal < firstMemberStart;
            if (header) {
                return unit;
            }
            return role == Role.TRAILING
                ? firstNonNull(skeleton.preceding(), skeleton.following(), skeleton.enclosing())
                : firstNonNull(skeleton.following(), skeleton.enclosing());
        }

        /**
         * Finds the enclosing node — the lowest common ancestor of the skeleton ends, or the deepest node whose
         * code-token span brackets the comment when only one end is known.
         */
        private Node enclosing(Node preceding, Node following, int ordinal) {
            if (preceding != null && following != null) {
                return lowestCommonAncestor(preceding, following);
            }
            Node node = following != null ? following : preceding;
            while (node != null) {
                Span span = codeSpans.get(node);
                if (span != null && span.first() < ordinal && ordinal < span.last()) {
                    return node;
                }
                node = node.getParentNode().orElse(null);
            }
            return unit;
        }

        /**
         * Returns the stream ordinal where the first real member (package declaration or first type) opens, marking the
         * end of the detached file-header region.
         */
        private int firstMemberStartOrdinal() {
            Node firstMember = unit.getPackageDeclaration().<Node>map(declaration -> declaration)
                    .or(() -> unit.getTypes().stream().findFirst().map(TypeDeclaration.class::cast))
                    .orElse(null);
            Span span = firstMember == null ? null : codeSpans.get(firstMember);
            return span == null ? Integer.MAX_VALUE : span.first();
        }
    }

    private static int[] sortedKeys(Map<Integer, Node> map) {
        int[] keys = map.keySet().stream().mapToInt(Integer::intValue).toArray();
        Arrays.sort(keys);
        return keys;
    }

    /** Returns the largest ordinal in {@code sorted} that is {@code <= value}, or {@code -1} if none. */
    private static int floorOrdinal(int[] sorted, int value) {
        int low = 0;
        int high = sorted.length - 1;
        int found = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (sorted[mid] <= value) {
                found = sorted[mid];
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return found;
    }

    /** Returns the smallest ordinal in {@code sorted} that is {@code >= value}, or {@code -1} if none. */
    private static int ceilingOrdinal(int[] sorted, int value) {
        int low = 0;
        int high = sorted.length - 1;
        int found = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (sorted[mid] >= value) {
                found = sorted[mid];
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }
        return found;
    }

    private static Node lowestCommonAncestor(Node left, Node right) {
        java.util.Set<Node> ancestors = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Node node = left; node != null; node = node.getParentNode().orElse(null)) {
            ancestors.add(node);
        }
        for (Node node = right; node != null; node = node.getParentNode().orElse(null)) {
            if (ancestors.contains(node)) {
                return node;
            }
        }
        return left;
    }

    /**
     * Derives the preserved leading/trailing role from the comment's source layout — end-of-line when code shares its
     * line, own-line otherwise. Role is preserved, never made part of the invariant skeleton.
     */
    private static Role role(JavaToken prev, JavaToken commentToken) {
        if (prev == null || prev.getRange().isEmpty() || commentToken.getRange().isEmpty()) {
            return Role.LEADING;
        }
        return prev.getRange().orElseThrow().end.line == commentToken.getRange().orElseThrow().begin.line
            ? Role.TRAILING
            : Role.LEADING;
    }

    static JavaToken firstCodeToken(Node node) {
        return node.getTokenRange().map(range -> {
            JavaToken token = range.getBegin();
            while (token != null && isTrivia(token) && token != range.getEnd()) {
                token = token.getNextToken().orElse(null);
            }
            return token != null && isTrivia(token) ? null : token;
        }).orElse(null);
    }

    static JavaToken lastCodeToken(Node node) {
        return node.getTokenRange().map(range -> {
            JavaToken token = range.getEnd();
            while (token != null && isTrivia(token) && token != range.getBegin()) {
                token = token.getPreviousToken().orElse(null);
            }
            return token != null && isTrivia(token) ? null : token;
        }).orElse(null);
    }

    private static JavaToken skipTrivia(JavaToken from, boolean backward) {
        Optional<JavaToken> token = backward ? from.getPreviousToken() : from.getNextToken();
        while (token.isPresent() && isTrivia(token.orElseThrow())) {
            token = backward ? token.orElseThrow().getPreviousToken() : token.orElseThrow().getNextToken();
        }
        return token.orElse(null);
    }

    private static boolean isTrivia(JavaToken token) {
        return token.getCategory().isWhitespaceOrComment();
    }

    private static Node firstNonNull(Node... nodes) {
        for (Node node : nodes) {
            if (node != null) {
                return node;
            }
        }
        return null;
    }

    private static int[] sortedInts(List<Integer> values) {
        int[] array = values.stream().mapToInt(Integer::intValue).toArray();
        Arrays.sort(array);
        return array;
    }

    /**
     * Reports whether {@code comment}'s token gap lies strictly within {@code node}'s code-token span — the pure,
     * whitespace-invariant containment test that replaces JavaParser's attachment-derived answer.
     */
    boolean contains(Node node, Comment comment) {
        Span span = codeSpans.get(node);
        Integer ordinal = commentOrdinal.get(comment);
        return span != null && ordinal != null && span.first() < ordinal && ordinal < span.last();
    }

    /** Reports whether any comment is canonically contained in {@code node}. */
    boolean hasContainedComments(Node node) {
        Span span = codeSpans.get(node);
        if (span == null) {
            return false;
        }
        int index = firstIndexAbove(commentOrdinals, span.first());
        return index < commentOrdinals.length && commentOrdinals[index] < span.last();
    }

    private static int firstIndexAbove(int[] sorted, int value) {
        int low = 0;
        int high = sorted.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (sorted[mid] <= value) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /**
     * Reports whether {@code node} has a computed code-token span in this run, i.e. the canonical queries have an answer
     * for it (an unknown detached or cloned node returns {@code false}, mirroring the run-snapshot boundary).
     */
    boolean knows(Node node) {
        return codeSpans.containsKey(node);
    }

    /** Returns the canonical owner node for {@code comment}, if the comment was bound in this run. */
    Optional<Node> owner(Comment comment) {
        return Optional.ofNullable(owners.get(comment));
    }

    /** Returns the whitespace-invariant skeleton for {@code comment}, if bound. */
    Optional<Skeleton> skeleton(Comment comment) {
        return Optional.ofNullable(skeletons.get(comment));
    }
}
