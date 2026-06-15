package dev.lanwen.frmtr.cli;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return discoverDirectory(selectorScope(path), excludes);
        }
        if (Files.isRegularFile(path) && isJavaFile(path)) {
            Path normalized = path.toAbsolutePath().normalize();
            GitIgnoreContext ignores = selectorScope(normalized).ignoreContextForFile(normalized);
            if (excludes.matches(normalized)) {
                return new Selection(List.of(), List.of(), List.of(normalized));
            }
            if (ignores.isIgnored(normalized, EntryKind.FILE)) {
                return new Selection(List.of(), List.of(normalized), List.of());
            }
            return new Selection(List.of(normalized), List.of(), List.of());
        }
        return new Selection(List.of(), List.of(), List.of());
    }

    private SelectorScope selectorScope(Path traversalBase) {
        Path absolute = traversalBase.toAbsolutePath().normalize();
        Path ignoreRoot = ignoreRootFor(absolute);
        Path matchRoot = absolute.startsWith(root) ? root : ignoreRoot;
        return new SelectorScope(absolute, ignoreRoot, matchRoot);
    }

    private Path ignoreRootFor(Path traversalBase) {
        Path anchor = ignoreAnchor(traversalBase);
        if (anchor.startsWith(root)) {
            return root;
        }
        Path gitRoot = gitRootFor(anchor);
        if (gitRoot != null) {
            return gitRoot;
        }
        return highestGitignoreAncestor(anchor);
    }

    private static Path ignoreAnchor(Path traversalBase) {
        if (Files.isRegularFile(traversalBase)) {
            Path parent = traversalBase.getParent();
            return parent == null ? traversalBase : parent;
        }
        return traversalBase;
    }

    private static Path gitRootFor(Path path) {
        Path current = path;
        while (current != null) {
            Path gitDirectory = current.resolve(".git");
            if (Files.isDirectory(gitDirectory) || Files.isRegularFile(gitDirectory)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private Path highestGitignoreAncestor(Path path) {
        Path boundary = commonAncestor(root, path);
        Path current = path;
        Path gitignoreRoot = null;
        while (current != null && !current.equals(boundary)) {
            if (Files.isRegularFile(current.resolve(".gitignore"))) {
                gitignoreRoot = current;
            }
            current = current.getParent();
        }
        return gitignoreRoot == null ? path : gitignoreRoot;
    }

    private static Path commonAncestor(Path first, Path second) {
        if (!Objects.equals(first.getRoot(), second.getRoot())) {
            return null;
        }
        Path common = first.getRoot();
        int parts = Math.min(first.getNameCount(), second.getNameCount());
        for (int index = 0; index < parts; index++) {
            Path firstPart = first.getName(index);
            if (!firstPart.equals(second.getName(index))) {
                break;
            }
            common = common == null ? firstPart : common.resolve(firstPart);
        }
        return common;
    }

    private Selection discoverDirectory(SelectorScope scope, ExcludeMatcher excludes) throws IOException {
        return discoverCandidates(scope, path -> true, excludes);
    }

    private Selection discoverGlob(String selector, ExcludeMatcher excludes) throws IOException {
        Path base = globBase(selector);
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
            return new Selection(List.of(), List.of(), List.of());
        }
        SelectorScope scope = selectorScope(base);
        List<PathMatcher> matchers = globMatchers(selector, scope.matchRoot());
        return discoverCandidates(scope, path -> matches(scope.matchRoot(), matchers, path), excludes);
    }

    private Selection discoverCandidates(
            SelectorScope scope, Predicate<Path> candidateMatches, ExcludeMatcher excludes
    ) throws IOException {
        SelectionBuilder selection = new SelectionBuilder();
        discoverCandidatesInDirectory(scope.directoryContext(), candidateMatches, excludes, selection);
        return selection.toSelection();
    }

    private static void discoverCandidatesInDirectory(
            DirectoryContext context,
            Predicate<Path> candidateMatches,
            ExcludeMatcher excludes,
            SelectionBuilder selection
    ) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(context.directory())) {
            for (Path entry : entries) {
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    discoverCandidatesInDirectory(
                        context.forChildDirectory(entry),
                        candidateMatches,
                        excludes,
                        selection
                    );
                } else if (Files.isRegularFile(entry) && isJavaFile(entry) && candidateMatches.test(entry)) {
                    selectCandidate(entry.toAbsolutePath().normalize(), context.ignores(), excludes, selection);
                }
            }
        }
    }

    private static void selectCandidate(
            Path candidate, GitIgnoreContext ignores, ExcludeMatcher excludes, SelectionBuilder selection
    ) {
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

    private static boolean matches(Path matchRoot, List<PathMatcher> matchers, Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path relative = absolute.startsWith(matchRoot) ? matchRoot.relativize(absolute) : absolute;
        return matchers
                .stream()
                .anyMatch(matcher -> matcher.matches(relative)
                    || matcher.matches(Path.of(".").resolve(relative))
                    || matcher.matches(absolute));
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

    private static List<PathMatcher> globMatchers(String selector, Path matchRoot) {
        return pathMatchers(globPattern(selector, matchRoot));
    }

    private static List<PathMatcher> globMatchers(String selector) {
        return pathMatchers(normalizeSelector(selector));
    }

    private static List<PathMatcher> pathMatchers(String normalized) {
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

    private static String globPattern(String selector, Path matchRoot) {
        Path selectorPath = Path.of(selector);
        if (selectorPath.isAbsolute()) {
            Path absolute = selectorPath.toAbsolutePath().normalize();
            if (absolute.startsWith(matchRoot)) {
                return slash(matchRoot.relativize(absolute));
            }
        }
        return normalizeSelector(selector);
    }

    private static String slash(Path path) {
        return path.toString().replace('\\', '/');
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

    private record SelectorScope(Path traversalBase, Path ignoreRoot, Path matchRoot) {
        private SelectorScope {
            traversalBase = traversalBase.toAbsolutePath().normalize();
            ignoreRoot = ignoreRoot.toAbsolutePath().normalize();
            matchRoot = matchRoot.toAbsolutePath().normalize();
        }

        private DirectoryContext directoryContext() throws IOException {
            return new DirectoryContext(
                traversalBase,
                GitIgnoreContext.forDirectoryContents(ignoreRoot, traversalBase)
            );
        }

        private GitIgnoreContext ignoreContextForFile(Path file) throws IOException {
            Path parent = file.toAbsolutePath().normalize().getParent();
            if (parent == null) {
                return GitIgnoreContext.empty(ignoreRoot);
            }
            return GitIgnoreContext.forDirectoryContents(ignoreRoot, parent);
        }
    }

    private record DirectoryContext(Path directory, GitIgnoreContext ignores) {
        private DirectoryContext {
            directory = directory.toAbsolutePath().normalize();
        }

        private DirectoryContext forChildDirectory(Path childDirectory) throws IOException {
            Path absolute = childDirectory.toAbsolutePath().normalize();
            return new DirectoryContext(absolute, ignores.forChildDirectoryContents(absolute));
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

    private record GitIgnoreContext(Path root, List<IgnoreRules> rules) {
        private GitIgnoreContext {
            root = root.toAbsolutePath().normalize();
            rules = List.copyOf(rules);
        }

        private static GitIgnoreContext empty(Path root) {
            return new GitIgnoreContext(root, List.of());
        }

        private static GitIgnoreContext forDirectoryContents(Path root, Path directory) throws IOException {
            GitIgnoreContext context = empty(root);
            Path absolute = directory.toAbsolutePath().normalize();
            if (!absolute.startsWith(context.root)) {
                return context;
            }
            for (Path current : context.directoriesThrough(absolute)) {
                context = context.appendDirectoryRules(current);
            }
            return context;
        }

        private GitIgnoreContext forChildDirectoryContents(Path childDirectory) throws IOException {
            Path absolute = childDirectory.toAbsolutePath().normalize();
            if (!absolute.startsWith(root)) {
                return this;
            }
            return appendDirectoryRules(absolute);
        }

        private GitIgnoreContext appendDirectoryRules(Path directory) throws IOException {
            Optional<IgnoreRules> directoryRules = readRules(directory);
            if (directoryRules.isEmpty()) {
                return this;
            }
            List<IgnoreRules> nextRules = new ArrayList<>(rules);
            nextRules.add(directoryRules.orElseThrow());
            return new GitIgnoreContext(root, nextRules);
        }

        boolean isIgnored(Path path, EntryKind kind) {
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

        private MatchResult update(MatchResult result, Path absolute, EntryKind kind) {
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

        private List<IgnoreRules> rulesFor(Path absolute, EntryKind kind) {
            Path lastRuleDirectory = switch (kind) {
                case FILE -> absolute.getParent();
                case DIRECTORY -> absolute.getParent();
            };
            if (lastRuleDirectory == null || !lastRuleDirectory.startsWith(root)) {
                return List.of();
            }
            return rules.stream()
                    .filter(rule -> lastRuleDirectory.startsWith(rule.directory()))
                    .toList();
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

        private Optional<IgnoreRules> readRules(Path directory) throws IOException {
            Path ignoreFile = directory.resolve(".gitignore");
            if (!Files.isRegularFile(ignoreFile)) {
                return Optional.empty();
            }
            IgnoreNode node = new IgnoreNode();
            try (var input = Files.newInputStream(ignoreFile)) {
                node.parse(input);
            }
            return Optional.of(new IgnoreRules(directory, node));
        }

        private static String slash(Path path) {
            return FileDiscovery.slash(path);
        }

        private record IgnoreRules(Path directory, IgnoreNode ignoreNode) {}
    }
}
