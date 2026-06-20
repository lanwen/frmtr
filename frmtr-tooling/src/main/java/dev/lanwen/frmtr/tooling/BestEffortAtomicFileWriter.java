package dev.lanwen.frmtr.tooling;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Replaces formatted source files through a sibling temp file.
 *
 * <p>This helper owns the low-level file-system choreography for in-place formatting writes: write complete contents to
 * a temp file in the target directory, copy the target's POSIX mode when available, then rename the staged file over the
 * target. It prefers an atomic same-filesystem move, but deliberately falls back to a plain replacing move when the
 * platform reports that atomic moves are unsupported. That keeps the common path crash-safe while still formatting files
 * on file systems that cannot provide {@link StandardCopyOption#ATOMIC_MOVE}.
 *
 * <p>Symlinked inputs are resolved with {@link Path#toRealPath()} so the link target is rewritten, matching the old
 * write-through-symlink behavior. The helper leaves formatter status mapping and per-file orchestration to callers.
 */
final class BestEffortAtomicFileWriter {

    static final String TEMP_SUFFIX = ".frmtr.tmp";

    private BestEffortAtomicFileWriter() {}

    static void writeString(Path file, String contents) throws IOException {
        Path target = Files.exists(file) ? file.toRealPath() : file.toAbsolutePath().normalize();
        Path dir = target.getParent();
        if (dir == null) {
            throw new IOException("Cannot replace a path without a parent: " + target);
        }
        Path tmp = Files.createTempFile(dir, target.getFileName().toString(), TEMP_SUFFIX);
        try {
            Files.writeString(tmp, contents, StandardCharsets.UTF_8);
            copyPosixModeIfAvailable(target, tmp);
            moveReplacingBestEffortAtomic(tmp, target);
        } catch (IOException | RuntimeException | Error failure) {
            Files.deleteIfExists(tmp);
            throw failure;
        }
    }

    private static void copyPosixModeIfAvailable(Path target, Path tmp) throws IOException {
        if (Files.exists(target)
                && Files.getFileStore(target).supportsFileAttributeView(PosixFileAttributeView.class)) {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(target);
            Files.setPosixFilePermissions(tmp, perms);
        }
    }

    private static void moveReplacingBestEffortAtomic(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
