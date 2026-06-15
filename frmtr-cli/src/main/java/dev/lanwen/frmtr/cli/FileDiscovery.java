package dev.lanwen.frmtr.cli;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.eclipse.jgit.ignore.IgnoreNode;
import org.eclipse.jgit.ignore.IgnoreNode.MatchResult;

final class FileDiscovery {

    private final Path root;

    FileDiscovery(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    Result discover(List<String> selectorArgs, List<String> excludeArgs) throws IOException {
        Set<Path> files = new LinkedHashSet<>();
        Set<Path> ignoredFiles = new LinkedHashSet<>();
        Set<Path> excludedFiles = new LinkedHashSet<>();
        List<String> missingFileSelectors = new ArrayList<>();
        ExcludeMatcher excludes = new ExcludeMatcher(root, selectors(excludeArgs));
        for (String selector : selectors(selectorArgs)) {
            if (missingExplicitJavaFileSelector(selector)) {
                missingFileSelectors.add(selector);
                continue;
            }
            Selection selection = discoverSelector(selector, excludes);
            files.addAll(selection.files());
            ignoredFiles.addAll(selection.ignoredFiles());
            excludedFiles.addAll(selection.excludedFiles());
        }
        return new Result(
            files.stream().sorted(Comparator.naturalOrder()).toList(),
            ignoredFiles.stream().sorted(Comparator.naturalOrder()).toList(),
            excludedFiles.stream().sorted(Comparator.naturalOrder()).toList(),
            missingFileSelectors
        );
    }

    private Selection discoverSelector(String selector, ExcludeMatcher excludes) throws IOException {
        if (hasGlobSyntax(selector)) {
            return discoverGlob(selector, excludes);
        }

        Path path = root.resolve(selector).normalize();
        if (Files.isDirectory(path)) {
            return discoverDirectory(path, ignoresForDirectorySelector(path), excludes);
        }
        if (Files.isRegularFile(path) && isJavaFile(path)) {
            Path normalized = path.toAbsolutePath().normalize();
            GitIgnoreMatcher ignores = ignoresForFileSelector(normalized);
            if (excludes.matches(normalized)) {
                return new Selection(List.of(), List.of(), List.of(normalized));
            }
            if (ignores.isIgnored(path, EntryKind.FILE)) {
                return new Selection(List.of(), List.of(normalized), List.of());
            }
            return new Selection(List.of(normalized), List.of(), List.of());
        }
        return new Selection(List.of(), List.of(), List.of());
    }

    private GitIgnoreMatcher ignoresForDirectorySelector(Path directory) {
        Path absolute = directory.toAbsolutePath().normalize();
        if (absolute.startsWith(root)) {
            return new GitIgnoreMatcher(root);
        }
        return new GitIgnoreMatcher(absolute);
    }

    private GitIgnoreMatcher ignoresForFileSelector(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        if (absolute.startsWith(root)) {
            return new GitIgnoreMatcher(root);
        }
        Path parent = absolute.getParent();
        return new GitIgnoreMatcher(parent == null ? absolute : parent);
    }

    private Selection discoverDirectory(
            Path directory, GitIgnoreMatcher ignores, ExcludeMatcher excludes
    ) throws IOException {
        return discoverCandidates(directory, path -> true, ignores, excludes);
    }

    private Selection discoverGlob(String selector, ExcludeMatcher excludes) throws IOException {
        Path base = globBase(selector);
        if (!Files.exists(base)) {
            return new Selection(List.of(), List.of(), List.of());
        }
        GitIgnoreMatcher ignores = ignoresForDirectorySelector(base);
        List<PathMatcher> matchers = globMatchers(selector);
        return discoverCandidates(base, path -> matches(matchers, path), ignores, excludes);
    }

    private Selection discoverCandidates(
            Path base, Predicate<Path> candidateMatches, GitIgnoreMatcher ignores, ExcludeMatcher excludes
    ) throws IOException {
        SelectionBuilder selection = new SelectionBuilder();
        Files.walkFileTree(
            base,
            new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs)
                    throws IOException {
                    ignores.loadRulesForDirectoryContents(directory);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Files.isRegularFile(file) && isJavaFile(file) && candidateMatches.test(file)) {
                        selectCandidate(file.toAbsolutePath().normalize(), ignores, excludes, selection);
                    }
                    return FileVisitResult.CONTINUE;
                }
            }
        );
        return selection.toSelection();
    }

    private static void selectCandidate(
            Path candidate, GitIgnoreMatcher ignores, ExcludeMatcher excludes, SelectionBuilder selection
    ) throws IOException {
        if (excludes.matches(candidate)) {
            selection.addExcluded(candidate);
        } else if (ignores.isIgnored(candidate, EntryKind.FILE)) {
            selection.addIgnored(candidate);
        } else {
            selection.add(candidate);
        }
    }

    private static final class SelectionBuilder {

        private final List<Path> files = new ArrayList<>();

        private final List<Path> ignoredFiles = new ArrayList<>();

        private final List<Path> excludedFiles = new ArrayList<>();

        void add(Path path) {
            files.add(path);
        }

        void addIgnored(Path path) {
            ignoredFiles.add(path);
        }

        void addExcluded(Path path) {
            excludedFiles.add(path);
        }

        Selection toSelection() {
            return new Selection(files, ignoredFiles, excludedFiles);
        }
    }

    private boolean matches(List<PathMatcher> matchers, Path path) {
        Path relative = root.relativize(path.toAbsolutePath().normalize());
        return matchers
                .stream()
                .anyMatch(matcher -> matcher.matches(relative) || matcher.matches(Path.of(".").resolve(relative)));
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
        return (
            !hasGlobSyntax(selector)
            && selector.endsWith(".java")
            && Files.notExists(root.resolve(selector).normalize())
        );
    }

    record Result(
        List<Path> files,
        List<Path> ignoredFiles,
        List<Path> excludedFiles,
        List<String> missingFileSelectors
    ) {
        Result {
            files = List.copyOf(files);
            ignoredFiles = List.copyOf(ignoredFiles);
            excludedFiles = List.copyOf(excludedFiles);
            missingFileSelectors = List.copyOf(missingFileSelectors);
        }

        boolean hasMissingFileSelectors() {
            return !missingFileSelectors.isEmpty();
        }

        long ignoredCount() {
            return ignoredFiles.size();
        }

        long excludedCount() {
            return excludedFiles.size();
        }

        long skippedCount() {
            return ignoredFiles.size() + excludedFiles.size();
        }
    }

    private record Selection(List<Path> files, List<Path> ignoredFiles, List<Path> excludedFiles) {
        private Selection {
            files = List.copyOf(files);
            ignoredFiles = List.copyOf(ignoredFiles);
            excludedFiles = List.copyOf(excludedFiles);
        }
    }

    private enum EntryKind {
        /** A regular file whose own name and parent directory ignore rules decide whether it is selected. */
        FILE,

        /** A directory entry whose own `.gitignore` affects descendants, not the directory entry itself. */
        DIRECTORY,
    }

    private static final class ExcludeMatcher {

        private final Path root;

        private final List<ExcludeRule> rules;

        ExcludeMatcher(Path root, List<String> patterns) {
            this.root = root.toAbsolutePath().normalize();
            this.rules = patterns.stream().map(pattern -> ExcludeRule.create(this.root, pattern)).toList();
        }

        boolean matches(Path path) {
            Path absolute = path.toAbsolutePath().normalize();
            Path relative = absolute.startsWith(root) ? root.relativize(absolute) : absolute;
            return rules.stream().anyMatch(rule -> rule.matches(absolute, relative));
        }
    }

    private sealed interface ExcludeRule permits PathExcludeRule, GlobExcludeRule {
        static ExcludeRule create(Path root, String pattern) {
            if (hasGlobSyntax(pattern)) {
                return new GlobExcludeRule(globMatchers(pattern));
            }
            return new PathExcludeRule(root.resolve(pattern).toAbsolutePath().normalize());
        }

        boolean matches(Path absolute, Path relative);
    }

    private record PathExcludeRule(Path path) implements ExcludeRule {
        @Override
        public boolean matches(Path absolute, Path relative) {
            return absolute.equals(path) || absolute.startsWith(path);
        }
    }

    private record GlobExcludeRule(List<PathMatcher> matchers) implements ExcludeRule {
        private GlobExcludeRule {
            matchers = List.copyOf(matchers);
        }

        @Override
        public boolean matches(Path absolute, Path relative) {
            return matchers.stream()
                    .anyMatch(matcher -> matcher.matches(relative) || matcher.matches(Path.of(".").resolve(relative)));
        }
    }

    private static final class GitIgnoreMatcher {

        private final Path root;

        private final Map<Path, IgnoreRules> rulesByDirectory = new HashMap<>();

        private final Set<Path> loadedDirectories = new HashSet<>();

        GitIgnoreMatcher(Path root) {
            this.root = root.toAbsolutePath().normalize();
        }

        void loadRulesForDirectoryContents(Path directory) throws IOException {
            Path absolute = directory.toAbsolutePath().normalize();
            loadRulesThrough(absolute);
        }

        boolean isIgnored(Path path, EntryKind kind) throws IOException {
            Path absolute = path.toAbsolutePath().normalize();
            if (!absolute.startsWith(root)) {
                return false;
            }
            MatchResult result = MatchResult.CHECK_PARENT;
            Path relativePath = root.relativize(absolute);
            Path current = root;
            for (Path part : relativePath) {
                current = current.resolve(part);
                if (current.equals(absolute) && kind == EntryKind.FILE) {
                    break;
                }
                result = update(result, current, EntryKind.DIRECTORY);
            }
            result = update(result, absolute, kind);
            return result == MatchResult.IGNORED;
        }

        private MatchResult update(MatchResult result, Path absolute, EntryKind kind) throws IOException {
            for (IgnoreRules rule : rulesFor(absolute, kind)) {
                String relative = slash(rule.directory().relativize(absolute));
                if (relative.isEmpty()) {
                    continue;
                }
                MatchResult match = rule.ignoreNode().isIgnored(relative, kind == EntryKind.DIRECTORY);
                if (match != MatchResult.CHECK_PARENT) {
                    result = match;
                }
            }
            return result;
        }

        private List<IgnoreRules> rulesFor(Path absolute, EntryKind kind) throws IOException {
            Path lastRuleDirectory = switch (kind) {
                case FILE -> absolute.getParent();
                case DIRECTORY -> absolute.getParent();
            };
            if (lastRuleDirectory == null || !lastRuleDirectory.startsWith(root)) {
                return List.of();
            }
            List<Path> directories = directoriesThrough(lastRuleDirectory);
            for (Path directory : directories) {
                loadRules(directory);
            }
            return directories.stream()
                    .map(rulesByDirectory::get)
                    .filter(rule -> rule != null)
                    .toList();
        }

        private void loadRulesThrough(Path directory) throws IOException {
            if (!directory.startsWith(root)) {
                return;
            }
            for (Path current : directoriesThrough(directory)) {
                loadRules(current);
            }
        }

        private List<Path> directoriesThrough(Path directory) {
            List<Path> directories = new ArrayList<>();
            directories.add(root);
            Path current = root;
            Path relative = root.relativize(directory);
            for (Path part : relative) {
                current = current.resolve(part);
                directories.add(current);
            }
            return directories;
        }

        private void loadRules(Path directory) throws IOException {
            Path absolute = directory.toAbsolutePath().normalize();
            if (!loadedDirectories.add(absolute)) {
                return;
            }
            Path ignoreFile = absolute.resolve(".gitignore");
            if (!Files.isRegularFile(ignoreFile)) {
                return;
            }
            IgnoreNode node = new IgnoreNode();
            try (var input = Files.newInputStream(ignoreFile)) {
                node.parse(input);
            }
            rulesByDirectory.put(absolute, new IgnoreRules(absolute, node));
        }

        private static String slash(Path path) {
            return path.toString().replace('\\', '/');
        }

        private record IgnoreRules(Path directory, IgnoreNode ignoreNode) {}
    }
}
