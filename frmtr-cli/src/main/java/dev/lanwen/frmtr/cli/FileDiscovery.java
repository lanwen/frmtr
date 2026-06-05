package dev.lanwen.frmtr.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.ignore.IgnoreNode;
import org.eclipse.jgit.ignore.IgnoreNode.MatchResult;

final class FileDiscovery {
    private final Path root;

    FileDiscovery(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    Result discover(List<String> selectorArgs) throws IOException {
        Set<Path> files = new LinkedHashSet<>();
        Set<Path> ignoredFiles = new LinkedHashSet<>();
        List<String> missingFileSelectors = new ArrayList<>();
        GitIgnoreMatcher ignores = new GitIgnoreMatcher(root);
        for (String selector : selectors(selectorArgs)) {
            if (missingExplicitJavaFileSelector(selector)) {
                missingFileSelectors.add(selector);
                continue;
            }
            Selection selection = discoverSelector(selector, ignores);
            files.addAll(selection.files());
            ignoredFiles.addAll(selection.ignoredFiles());
        }
        return new Result(
                files.stream().sorted(Comparator.naturalOrder()).toList(),
                ignoredFiles.stream().sorted(Comparator.naturalOrder()).toList(),
                missingFileSelectors);
    }

    private Selection discoverSelector(String selector, GitIgnoreMatcher ignores) throws IOException {
        if (hasGlobSyntax(selector)) {
            return discoverGlob(selector, ignores);
        }

        Path path = root.resolve(selector).normalize();
        if (Files.isDirectory(path)) {
            return discoverDirectory(path, ignores);
        }
        if (Files.isRegularFile(path) && isJavaFile(path)) {
            Path normalized = path.toAbsolutePath().normalize();
            if (ignores.isIgnored(path, false)) {
                return new Selection(List.of(), List.of(normalized));
            }
            return new Selection(List.of(normalized), List.of());
        }
        return new Selection(List.of(), List.of());
    }

    private Selection discoverDirectory(Path directory, GitIgnoreMatcher ignores) throws IOException {
        try (var stream = Files.walk(directory)) {
            List<Path> candidates = stream.filter(Files::isRegularFile)
                    .filter(FileDiscovery::isJavaFile)
                    .map(path -> path.toAbsolutePath().normalize())
                    .toList();
            return selectCandidates(candidates, ignores);
        }
    }

    private Selection discoverGlob(String selector, GitIgnoreMatcher ignores) throws IOException {
        Path base = globBase(selector);
        if (!Files.exists(base)) {
            return new Selection(List.of(), List.of());
        }
        List<PathMatcher> matchers = globMatchers(selector);
        try (var stream = Files.walk(base)) {
            List<Path> candidates = stream.filter(Files::isRegularFile)
                    .filter(FileDiscovery::isJavaFile)
                    .filter(path -> matches(matchers, path))
                    .map(path -> path.toAbsolutePath().normalize())
                    .toList();
            return selectCandidates(candidates, ignores);
        }
    }

    private static Selection selectCandidates(List<Path> candidates, GitIgnoreMatcher ignores) {
        List<Path> files = new ArrayList<>();
        List<Path> ignoredFiles = new ArrayList<>();
        for (Path candidate : candidates) {
            if (ignores.isIgnored(candidate, false)) {
                ignoredFiles.add(candidate);
            } else {
                files.add(candidate);
            }
        }
        return new Selection(files, ignoredFiles);
    }

    private boolean matches(List<PathMatcher> matchers, Path path) {
        Path relative = root.relativize(path.toAbsolutePath().normalize());
        return matchers.stream().anyMatch(matcher -> matcher.matches(relative) || matcher.matches(Path.of(".").resolve(relative)));
    }

    private Path globBase(String selector) {
        String normalized = normalizeSelector(selector);
        int firstGlob = firstGlobIndex(normalized);
        int slash = normalized.substring(0, firstGlob).lastIndexOf('/');
        if (slash < 0) {
            return root;
        }
        String base = normalized.substring(0, slash);
        if (base.isBlank()) {
            return root;
        }
        return root.resolve(base).normalize();
    }

    private static List<String> selectors(List<String> args) {
        List<String> selectors = new ArrayList<>();
        for (String arg : args) {
            for (String selector : arg.split(",")) {
                String trimmed = selector.trim();
                if (!trimmed.isEmpty()) {
                    selectors.add(trimmed);
                }
            }
        }
        return selectors;
    }

    private static boolean hasGlobSyntax(String selector) {
        return firstGlobIndex(normalizeSelector(selector)) >= 0;
    }

    private static int firstGlobIndex(String selector) {
        int first = -1;
        for (char glob : new char[] {'*', '?', '[', '{'}) {
            int index = selector.indexOf(glob);
            if (index >= 0 && (first < 0 || index < first)) {
                first = index;
            }
        }
        return first;
    }

    private static String normalizeSelector(String selector) {
        return selector.replace('\\', '/');
    }

    private static List<PathMatcher> globMatchers(String selector) {
        String normalized = normalizeSelector(selector);
        List<String> patterns = new ArrayList<>();
        patterns.add(normalized);
        if (normalized.contains("**/")) {
            patterns.add(normalized.replace("**/", ""));
        }
        return patterns.stream()
                .distinct()
                .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
                .toList();
    }

    private static boolean isJavaFile(Path path) {
        return path.getFileName().toString().endsWith(".java");
    }

    private boolean missingExplicitJavaFileSelector(String selector) {
        return !hasGlobSyntax(selector)
                && selector.endsWith(".java")
                && Files.notExists(root.resolve(selector).normalize());
    }

    record Result(List<Path> files, List<Path> ignoredFiles, List<String> missingFileSelectors) {
        Result {
            files = List.copyOf(files);
            ignoredFiles = List.copyOf(ignoredFiles);
            missingFileSelectors = List.copyOf(missingFileSelectors);
        }

        boolean hasMissingFileSelectors() {
            return !missingFileSelectors.isEmpty();
        }

        long ignoredCount() {
            return ignoredFiles.size();
        }
    }

    private record Selection(List<Path> files, List<Path> ignoredFiles) {
        private Selection {
            files = List.copyOf(files);
            ignoredFiles = List.copyOf(ignoredFiles);
        }
    }

    private static final class GitIgnoreMatcher {
        private final Path root;
        private final List<IgnoreRules> rules;

        GitIgnoreMatcher(Path root) throws IOException {
            this.root = root;
            this.rules = loadRules(root);
        }

        boolean isIgnored(Path path, boolean directory) {
            Path absolute = path.toAbsolutePath().normalize();
            MatchResult result = MatchResult.CHECK_PARENT;
            Path relativePath = root.relativize(absolute);
            Path current = root;
            for (Path part : relativePath) {
                current = current.resolve(part);
                if (current.equals(absolute) && !directory) {
                    break;
                }
                result = update(result, current, true);
            }
            result = update(result, absolute, directory);
            return result == MatchResult.IGNORED;
        }

        private MatchResult update(MatchResult result, Path absolute, boolean directory) {
            for (IgnoreRules rule : rules) {
                if (!absolute.startsWith(rule.directory())) {
                    continue;
                }
                String relative = slash(rule.directory().relativize(absolute));
                if (relative.isEmpty()) {
                    continue;
                }
                MatchResult match = rule.ignoreNode().isIgnored(relative, directory);
                if (match != MatchResult.CHECK_PARENT) {
                    result = match;
                }
            }
            return result;
        }

        private static List<IgnoreRules> loadRules(Path root) throws IOException {
            List<IgnoreRules> loaded = new ArrayList<>();
            try (var stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().equals(".gitignore"))
                        .sorted()
                        .forEach(path -> {
                            IgnoreNode node = new IgnoreNode();
                            try (var input = Files.newInputStream(path)) {
                                node.parse(input);
                                loaded.add(new IgnoreRules(path.getParent().toAbsolutePath().normalize(), node));
                            } catch (IOException exception) {
                                throw new UncheckedIOException(exception);
                            }
                        });
            } catch (UncheckedIOException exception) {
                throw exception.getCause();
            }
            loaded.sort(Comparator.comparing(rule -> root.relativize(rule.directory()).getNameCount()));
            return loaded;
        }

        private static String slash(Path path) {
            return path.toString().replace('\\', '/');
        }

        private record IgnoreRules(Path directory, IgnoreNode ignoreNode) {}
    }
}
