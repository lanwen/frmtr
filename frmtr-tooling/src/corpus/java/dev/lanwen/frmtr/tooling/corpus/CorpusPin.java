package dev.lanwen.frmtr.tooling.corpus;

/**
 * Single source of truth for the real-world corpus the Layer-3 harness formats.
 *
 * <p>The corpus is pinned to one immutable commit SHA so the harness is reproducible: the same commit always yields the
 * same set of source files and therefore the same per-file invariant results, independent of upstream branch movement.
 * Bumping the corpus is a deliberate, reviewable change to the {@link #SHA} constant here (and the cache key in the CI
 * workflow), never an implicit drift to a moving branch.
 *
 * <p>This type intentionally owns only the pin coordinates and the derived download URL; it leaves fetching, caching,
 * extraction, and per-file checking to the other corpus types so the pin can be reviewed in isolation.
 */
public final class CorpusPin {

    /** GitHub {@code owner/repo} slug of the pinned corpus. */
    public static final String REPO = "testcontainers/testcontainers-java";

    /** Immutable commit SHA the corpus is pinned to. Bumping the corpus means changing this constant. */
    public static final String SHA = "deb78e1e6e47675f56e8d7b3f4747093d66f1baa";

    /**
     * GitHub source tarball URL for {@link #SHA}. GitHub serves {@code /archive/<sha>.tar.gz} as a gzip-compressed tar
     * whose single top-level directory is {@code <repo-name>-<sha>/}.
     */
    public static final String TARBALL_URL = "https://github.com/" + REPO + "/archive/" + SHA + ".tar.gz";

    /** Top-level directory name inside the tarball: {@code testcontainers-java-<sha>}. */
    public static final String EXTRACTED_DIR_NAME = repoName() + "-" + SHA;

    private CorpusPin() {}

    private static String repoName() {
        return REPO.substring(REPO.indexOf('/') + 1);
    }
}
