package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Plans recovered raw gaps inside formatter-owned ordered sibling lists.
 *
 * <p>This helper owns only source-boundary selection for lists whose ordering and syntax shape are already owned by a
 * formatter caller: it accepts the caller's list boundary, walks the caller-provided sibling list in that order, keeps
 * siblings that are both caller-approved and fully parsed as structured entries, and widens unsafe siblings into raw
 * source gaps between the nearest valid sibling or list boundaries.
 *
 * <p>Callers still decide which source span is the real formatter-owned list interior, which sibling kinds are safe to
 * format normally, how valid siblings render, how raw regions are emitted and comment-accounted, and which separators
 * or indentation rules apply around the returned entries.
 */
final class RecoveredListPlanner {
    private final SourceText sourceText;

    RecoveredListPlanner(SourceText sourceText) {
        this.sourceText = Objects.requireNonNull(sourceText, "sourceText");
    }

    /**
     * Plans recovery entries using {@code owner}'s full source range as the list boundary.
     *
     * <p>This overload is only appropriate when the caller's formatter-owned list really covers the whole owner range.
     * Brace-delimited and token-delimited lists should pass an explicit interior {@link SourceRegion} instead, because
     * this planner does not synthesize opening or closing tokens and cannot infer token-level list boundaries.
     */
    <N extends Node> Plan<N> plan(Node owner, List<N> siblings, Predicate<? super N> validSibling) {
        Objects.requireNonNull(owner, "owner");
        if (owner.getRange().isEmpty()) {
            return Plan.unsafe("list owner is missing a source range");
        }
        SourceRegion ownerRegion;
        try {
            ownerRegion = sourceText.region(owner.getRange().orElseThrow());
        } catch (IllegalArgumentException exception) {
            return Plan.unsafe("list owner source range cannot be mapped: " + exception.getMessage());
        }
        return plan(owner, ownerRegion, siblings, validSibling);
    }

    /**
     * Plans ordered valid sibling and raw gap entries inside {@code listRegion}.
     *
     * <p>Whitespace between two valid siblings is not emitted as a raw gap; that ordinary separator policy stays with
     * the caller that owns the list's rendering. A raw gap is emitted only after at least one sibling in that span is
     * unsafe because the caller rejected it or because its subtree contains a JavaParser {@code UNPARSABLE} node.
     */
    <N extends Node> Plan<N> plan(
            Node owner,
            SourceRegion listRegion,
            List<N> siblings,
            Predicate<? super N> validSibling) {
        return plan(owner, listRegion, siblings, validSibling, this::siblingRegion, true);
    }

    /**
     * Plans ordered valid sibling and raw gap entries using caller-supplied source regions for each sibling.
     *
     * <p>Most recovered lists can use the node's own JavaParser range, but some source lists have caller-owned trivia
     * that must move with a sibling to keep raw gaps from claiming comments that structured rendering will print. The
     * source-region hook lets those callers widen or narrow sibling boundaries while keeping the same ordered-list
     * recovery checks.
     */
    <N extends Node> Plan<N> plan(
            Node owner,
            SourceRegion listRegion,
            List<N> siblings,
            Predicate<? super N> validSibling,
            Function<? super N, Optional<SourceRegion>> regionForSibling) {
        return plan(owner, listRegion, siblings, validSibling, regionForSibling, true);
    }

    /**
     * Plans ordered entries using a caller-owned safe-sibling predicate.
     *
     * <p>Unlike {@link #plan(Node, SourceRegion, List, Predicate)}, this variant does not add an implicit full-subtree
     * parsedness check after the predicate succeeds. Callers use it only when a sibling can safely render through a
     * nested recovery owner, and the predicate must reject every sibling the structured renderer cannot handle.
     */
    <N extends Node> Plan<N> planWithCallerOwnedSafety(
            Node owner,
            SourceRegion listRegion,
            List<N> siblings,
            Predicate<? super N> safeSibling) {
        return plan(owner, listRegion, siblings, safeSibling, this::siblingRegion, false);
    }

    private <N extends Node> Plan<N> plan(
            Node owner,
            SourceRegion listRegion,
            List<N> siblings,
            Predicate<? super N> safeSibling,
            Function<? super N, Optional<SourceRegion>> regionForSibling,
            boolean requireFullyParsedSibling) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(listRegion, "listRegion");
        Objects.requireNonNull(siblings, "siblings");
        Objects.requireNonNull(safeSibling, "safeSibling");
        Objects.requireNonNull(regionForSibling, "regionForSibling");

        SourceRegion boundary;
        try {
            boundary = validatedBoundary(listRegion);
        } catch (IllegalArgumentException exception) {
            return Plan.unsafe("list boundary cannot be mapped: " + exception.getMessage());
        }
        if (owner.getParsed() != Node.Parsedness.PARSED) {
            return wholeListRawGap(boundary);
        }

        List<SiblingRegion<N>> siblingRegions = new ArrayList<>(siblings.size());
        int previousEndOffset = boundary.beginOffset();
        for (N sibling : siblings) {
            Objects.requireNonNull(sibling, "sibling");
            Optional<SourceRegion> maybeRegion = regionForSibling.apply(sibling);
            if (maybeRegion.isEmpty()) {
                return Plan.unsafe("sibling %s is missing a source range"
                        .formatted(sibling.getClass().getSimpleName()));
            }
            SourceRegion region = maybeRegion.orElseThrow();
            if (!contains(boundary, region)) {
                return Plan.unsafe("sibling %s has a source range outside the list boundary"
                        .formatted(sibling.getClass().getSimpleName()));
            }
            if (region.beginOffset() < previousEndOffset) {
                return Plan.unsafe("sibling source ranges are not ordered and non-overlapping");
            }
            previousEndOffset = region.endOffset();
            boolean safeToFormat = safeSibling.test(sibling)
                    && (!requireFullyParsedSibling || fullyParsed(sibling));
            siblingRegions.add(new SiblingRegion<>(sibling, region, safeToFormat));
        }

        List<Entry<N>> entries = new ArrayList<>();
        int cursor = boundary.beginOffset();
        boolean sawValidSibling = false;
        boolean pendingRawGap = false;
        RawGapKind pendingRawGapKind = RawGapKind.PREFIX;
        for (SiblingRegion<N> siblingRegion : siblingRegions) {
            if (siblingRegion.safeToFormat()) {
                if (pendingRawGap) {
                    Optional<RawGap<N>> gap = rawGap(pendingRawGapKind, cursor, siblingRegion.region().beginOffset());
                    if (gap.isEmpty()) {
                        return Plan.unsafe("unsafe sibling source range cannot be represented as a raw gap");
                    }
                    entries.add(gap.orElseThrow());
                    pendingRawGap = false;
                }
                entries.add(new ValidSibling<>(siblingRegion.sibling(), siblingRegion.region()));
                cursor = siblingRegion.region().endOffset();
                sawValidSibling = true;
                continue;
            }
            if (!pendingRawGap) {
                pendingRawGap = true;
                pendingRawGapKind = sawValidSibling ? RawGapKind.BETWEEN : RawGapKind.PREFIX;
            }
        }
        if (pendingRawGap) {
            RawGapKind kind = sawValidSibling ? RawGapKind.SUFFIX : RawGapKind.PREFIX;
            Optional<RawGap<N>> gap = rawGap(kind, cursor, boundary.endOffset());
            if (gap.isEmpty()) {
                return Plan.unsafe("unsafe sibling source range cannot be represented as a raw gap");
            }
            entries.add(gap.orElseThrow());
        }
        return Plan.safe(entries);
    }

    /**
     * Preserves the whole list interior when JavaParser marked the owner itself as unparseable.
     *
     * <p>An unparseable owner can expose an empty recovered child list even though the original list interior contains
     * source. In that case there are no sibling anchors to reason from, so the only safe structured plan is one raw gap
     * for the caller-owned list boundary.
     */
    private <N extends Node> Plan<N> wholeListRawGap(SourceRegion boundary) {
        Optional<RawGap<N>> gap = rawGap(RawGapKind.PREFIX, boundary.beginOffset(), boundary.endOffset());
        if (gap.isEmpty()) {
            return Plan.unsafe("unparseable list owner has no recoverable list source");
        }
        return Plan.safe(List.of(gap.orElseThrow()));
    }

    private SourceRegion validatedBoundary(SourceRegion listRegion) {
        return sourceText.region(listRegion.beginOffset(), listRegion.endOffset());
    }

    private Optional<SourceRegion> siblingRegion(Node sibling) {
        try {
            return sibling.getRange()
                    .map(sourceText::region)
                    .filter(region -> region.beginOffset() < region.endOffset());
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Treats a sibling as unsafe if JavaParser marked any node in its subtree as unparseable.
     *
     * <p>JavaParser can leave the sibling itself marked {@code PARSED} while one of its children is {@code UNPARSABLE}.
     * The planner therefore checks the whole subtree before allowing a sibling to become a structured boundary.
     */
    private static boolean fullyParsed(Node sibling) {
        return sibling.stream().allMatch(node -> node.getParsed() == Node.Parsedness.PARSED);
    }

    private <N extends Node> Optional<RawGap<N>> rawGap(RawGapKind kind, int beginOffset, int endOffset) {
        if (beginOffset >= endOffset) {
            return Optional.empty();
        }
        try {
            return Optional.of(new RawGap<>(kind, sourceText.region(beginOffset, endOffset)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static boolean contains(SourceRegion boundary, SourceRegion region) {
        return boundary.beginOffset() <= region.beginOffset() && region.endOffset() <= boundary.endOffset();
    }

    /**
     * Names where a raw gap sits relative to the valid sibling entries in the same plan.
     */
    enum RawGapKind {
        /**
         * Raw source before the first valid sibling, or the whole list when no valid sibling anchors the unsafe source.
         */
        PREFIX,

        /**
         * Raw source between two valid siblings, spanning from the previous valid sibling end to the next valid sibling
         * start.
         */
        BETWEEN,

        /**
         * Raw source after the last valid sibling, spanning from that sibling's end to the list boundary end.
         */
        SUFFIX
    }

    /**
     * Describes a successful or unsafe recovery plan for one formatter-owned sibling list.
     */
    record Plan<N extends Node>(List<Entry<N>> entries, Optional<Unsafe> unsafe) {
        Plan {
            entries = List.copyOf(entries);
            unsafe = Objects.requireNonNull(unsafe, "unsafe");
            if (unsafe.isPresent() && !entries.isEmpty()) {
                throw new IllegalArgumentException("unsafe plans must not carry recovery entries");
            }
        }

        static <N extends Node> Plan<N> safe(List<Entry<N>> entries) {
            return new Plan<>(entries, Optional.empty());
        }

        static <N extends Node> Plan<N> unsafe(String reason) {
            return new Plan<>(List.of(), Optional.of(new Unsafe(reason)));
        }

        boolean isSafe() {
            return unsafe.isEmpty();
        }
    }

    /**
     * Explains why the planner could not prove safe source boundaries for a list.
     */
    record Unsafe(String reason) {
        Unsafe {
            reason = Objects.requireNonNull(reason, "reason").strip();
            if (reason.isEmpty()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
        }
    }

    /**
     * One ordered recovery-plan entry inside a formatter-owned list.
     */
    sealed interface Entry<N extends Node> permits ValidSibling, RawGap {
        SourceRegion region();
    }

    /**
     * A sibling that callers can hand to the ordinary structured formatter.
     */
    record ValidSibling<N extends Node>(N sibling, SourceRegion region) implements Entry<N> {
        ValidSibling {
            sibling = Objects.requireNonNull(sibling, "sibling");
            region = Objects.requireNonNull(region, "region");
        }
    }

    /**
     * A raw source gap that later recovery emission can preserve and account for.
     */
    record RawGap<N extends Node>(RawGapKind kind, SourceRegion region) implements Entry<N> {
        RawGap {
            kind = Objects.requireNonNull(kind, "kind");
            region = Objects.requireNonNull(region, "region");
        }
    }

    private record SiblingRegion<N extends Node>(N sibling, SourceRegion region, boolean safeToFormat) {
        SiblingRegion {
            sibling = Objects.requireNonNull(sibling, "sibling");
            region = Objects.requireNonNull(region, "region");
        }
    }
}
