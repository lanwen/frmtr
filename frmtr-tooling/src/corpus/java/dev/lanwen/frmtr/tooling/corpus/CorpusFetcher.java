package dev.lanwen.frmtr.tooling.corpus;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.zip.GZIPInputStream;

/**
 * Fetches and caches the pinned corpus tarball, returning the extracted source root.
 *
 * <p>This type owns the corpus acquisition concern only: download the {@link CorpusPin#TARBALL_URL} once, extract it
 * into the work directory, and mark completion so subsequent runs are offline-fast. It deliberately does NOT decide
 * which files are in scope or assert any invariant; that belongs to {@link CorpusCheckRunner}.
 *
 * <p>Extraction uses a minimal hand-rolled ustar tar reader rather than adding a compression dependency: GitHub source
 * tarballs are plain ustar/pax archives containing only regular files and directories under a single top-level folder,
 * so the reader only needs to handle the {@code '0'}/{@code '\0'} (regular file) and {@code '5'} (directory) type flags
 * plus the GNU/pax long-name extensions, skipping any other entry types.
 */
public final class CorpusFetcher {

    /** Caps a single archive entry so a malformed/oversized tarball cannot exhaust memory while extracting. */
    private static final long MAX_ENTRY_BYTES = 64L * 1024 * 1024;

    private final Path workDir;

    public CorpusFetcher(Path workDir) {
        this.workDir = workDir;
    }

    /**
     * Returns the extracted corpus source root, downloading and extracting the pinned tarball if not already cached.
     *
     * <p>Caching is keyed on a {@code .complete} marker written only after a fully successful extraction, so a partial
     * extraction (e.g. interrupted download) is never mistaken for a cache hit.
     *
     * @throws IOException on any download or extraction failure; the caller treats this as a legitimate SKIP (no
     *     network), never a harness failure.
     */
    public Path ensureCorpus() throws IOException, InterruptedException {
        Path extractedRoot = workDir.resolve(CorpusPin.EXTRACTED_DIR_NAME);
        Path marker = workDir.resolve(CorpusPin.SHA + ".complete");
        if (Files.isRegularFile(marker) && Files.isDirectory(extractedRoot)) {
            return extractedRoot;
        }

        Files.createDirectories(workDir);

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(CorpusPin.TARBALL_URL))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close(); // Release the connection.
            throw new IOException("Corpus tarball fetch returned HTTP " + response.statusCode() + " for "
                    + CorpusPin.TARBALL_URL);
        }

        try (InputStream body = response.body();
                GZIPInputStream gz = new GZIPInputStream(body)) {
            extractTar(gz, workDir);
        }

        if (!Files.isDirectory(extractedRoot)) {
            throw new IOException("Corpus tarball did not contain expected directory " + CorpusPin.EXTRACTED_DIR_NAME);
        }
        Files.writeString(marker, CorpusPin.SHA);
        return extractedRoot;
    }

    /**
     * Extracts a ustar/pax tar stream into {@code destRoot}, handling regular files, directories, and GNU/pax long-name
     * entries; any other entry type is skipped. Paths are validated to stay within {@code destRoot} (tar-slip guard).
     */
    private static void extractTar(InputStream in, Path destRoot) throws IOException {
        byte[] header = new byte[512];
        String pendingLongName = null;
        Path normalizedRoot = destRoot.toAbsolutePath().normalize();

        while (true) {
            readFully(in, header, 0, 512);
            if (isAllZero(header)) {
                return; // Two zero blocks mark the end; one is enough to stop.
            }

            String rawName = cString(header, 0, 100);
            String prefix = cString(header, 345, 155);
            String name = pendingLongName != null
                    ? pendingLongName
                    : (prefix.isEmpty() ? rawName : prefix + "/" + rawName);
            pendingLongName = null;

            long size = parseOctal(header, 124, 12);
            char typeFlag = (char) (header[156] & 0xff);

            if (typeFlag == 'L' || typeFlag == 'x' || typeFlag == 'g') {
                // GNU long name ('L') or pax extended header ('x'/'g'): read its payload to recover the real path.
                byte[] payload = readBlocks(in, size);
                if (typeFlag == 'L') {
                    pendingLongName = stripTrailingNul(new String(payload, java.nio.charset.StandardCharsets.UTF_8));
                } else {
                    String paxPath = parsePaxPath(payload);
                    if (paxPath != null) {
                        pendingLongName = paxPath;
                    }
                }
                continue;
            }

            if (size > MAX_ENTRY_BYTES) {
                throw new IOException("Corpus tar entry exceeds size cap: " + name + " (" + size + " bytes)");
            }

            boolean isDir = typeFlag == '5' || name.endsWith("/");
            boolean isRegular = typeFlag == '0' || typeFlag == '\0';

            if (isDir) {
                Path target = resolveSafely(normalizedRoot, name);
                Files.createDirectories(target);
                skipPadding(in, size);
                continue;
            }

            if (isRegular) {
                Path target = resolveSafely(normalizedRoot, name);
                Files.createDirectories(target.getParent());
                byte[] content = readBlocks(in, size);
                Files.write(target, java.util.Arrays.copyOf(content, (int) size));
                continue;
            }

            // Symlinks, char/block devices, etc.: skip payload (most tar entries carry none, but be safe).
            skipPayload(in, size);
        }
    }

    private static Path resolveSafely(Path normalizedRoot, String entryName) throws IOException {
        Path target = normalizedRoot.resolve(entryName).normalize();
        if (!target.startsWith(normalizedRoot)) {
            throw new IOException("Corpus tar entry escapes destination (tar-slip): " + entryName);
        }
        return target;
    }

    private static byte[] readBlocks(InputStream in, long size) throws IOException {
        int intSize = (int) size;
        byte[] data = new byte[intSize];
        readFully(in, data, 0, intSize);
        skipPadding(in, size);
        return data;
    }

    private static void skipPayload(InputStream in, long size) throws IOException {
        long remaining = roundUpToBlock(size);
        byte[] scratch = new byte[8192];
        while (remaining > 0) {
            int toRead = (int) Math.min(scratch.length, remaining);
            int read = in.read(scratch, 0, toRead);
            if (read < 0) {
                throw new IOException("Unexpected end of tar stream while skipping payload");
            }
            remaining -= read;
        }
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long padding = roundUpToBlock(size) - size;
        if (padding > 0) {
            byte[] scratch = new byte[(int) padding];
            readFully(in, scratch, 0, (int) padding);
        }
    }

    private static long roundUpToBlock(long size) {
        return ((size + 511) / 512) * 512;
    }

    private static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = in.read(buf, off + read, len - read);
            if (n < 0) {
                throw new IOException("Unexpected end of tar stream (wanted " + len + ", got " + read + ")");
            }
            read += n;
        }
    }

    private static boolean isAllZero(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String cString(byte[] buf, int off, int max) {
        int end = off;
        int limit = off + max;
        while (end < limit && buf[end] != 0) {
            end++;
        }
        return new String(buf, off, end - off, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String stripTrailingNul(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '\0') {
            end--;
        }
        return s.substring(0, end);
    }

    private static long parseOctal(byte[] buf, int off, int len) {
        long value = 0;
        int i = off;
        int limit = off + len;
        // Leading spaces/NULs are padding.
        while (i < limit && (buf[i] == ' ' || buf[i] == 0)) {
            i++;
        }
        while (i < limit) {
            int c = buf[i] & 0xff;
            if (c < '0' || c > '7') {
                break;
            }
            value = (value << 3) + (c - '0');
            i++;
        }
        return value;
    }

    /** Extracts the {@code path=} record from a pax extended header payload, if present. */
    private static String parsePaxPath(byte[] payload) {
        String text = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
        for (String record : text.split("\n")) {
            int space = record.indexOf(' ');
            if (space < 0) {
                continue;
            }
            String keyValue = record.substring(space + 1);
            if (keyValue.startsWith("path=")) {
                return keyValue.substring("path=".length());
            }
        }
        return null;
    }
}
