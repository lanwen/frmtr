package dev.lanwen.frmtr;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.support.AnnotationConsumer;
import org.junit.jupiter.params.support.ParameterDeclarations;

/**
 * JUnit arguments source for formatter resource fixtures discovered by glob instead of hard-coded lists.
 *
 * <p>The source owns fixture discovery and filename convention parsing so tests only declare which resource tree they
 * want to exercise. Normal formatter fixtures use {@code input.java} plus {@code frmtr-<variant>.output.java};
 * non-default variant options are read from a {@code frmtr-<variant>.options.properties} sidecar. The {@code default}
 * variant starts from {@link FormatterOptions#defaults()} without requiring a sidecar. Unsupported fixtures use {@code
 * input.java} plus {@code error.txt}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@ArgumentsSource(ResourceFixtureSource.Provider.class)
public @interface ResourceFixtureSource {
    String glob();

    final class Provider implements ArgumentsProvider, AnnotationConsumer<ResourceFixtureSource> {

        private static final Pattern OUTPUT_FILE = Pattern.compile("frmtr-([A-Za-z0-9][A-Za-z0-9_-]*)\\.output\\.java");

        private ResourceFixtureSource source;

        @Override
        public void accept(ResourceFixtureSource source) {
            this.source = source;
        }

        @Override
        public Stream<? extends Arguments> provideArguments(
                ParameterDeclarations parameterDeclarations, ExtensionContext context
        ) throws Exception {
            MatchedResources resources = matchedResources(
                source.glob(),
                context.getRequiredTestClass().getClassLoader()
            );
            Class<?> fixtureType = fixtureParameterType(context);
            if (fixtureType.equals(FixtureInput.class)) {
                return fixtureInputs(resources).map(Arguments::of);
            }
            if (fixtureType.equals(FormatFixture.class)) {
                return formatFixtures(resources).map(Arguments::of);
            }
            if (fixtureType.equals(UnsupportedFixture.class)) {
                return unsupportedFixtures(resources).map(Arguments::of);
            }
            throw new IllegalStateException(
                "Unsupported fixture parameter type `%s` for resource glob `%s`."
                        .formatted(fixtureType.getName(), source.glob())
            );
        }

        /**
         * Discovers fixture inputs for {@code glob} without requiring any output companion, for callers that only
         * consume inputs (e.g. {@code @MethodSource} property tests that perturb the source). This is the same
         * discovery the {@link FixtureInput} {@code @ArgumentsSource} mode uses, exposed as a callable so
         * transform-heavy tests can reuse it instead of re-walking the resource tree.
         *
         * <p>The glob follows the one-directory-per-fixture convention (e.g. {@code root/**}{@code /input.java}); a
         * {@code **} segment does not match an input placed directly in the glob root, which no fixture does.
         */
        public static List<FixtureInput> inputs(String glob) {
            try {
                return fixtureInputs(matchedResources(glob, ResourceFixtureSource.class.getClassLoader())).toList();
            } catch (IOException | URISyntaxException exception) {
                throw new IllegalStateException(
                    "Unable to discover fixture inputs for glob `%s`.".formatted(glob),
                    exception
                );
            }
        }

        /**
         * Discovers formatter output resource paths recognized by the normal format fixture conventions for {@code
         * glob}. This lets fixture-level audits validate companion metadata against the same resource root, input glob,
         * and {@code frmtr-<variant>.output.java} filename contract used by the {@link FormatFixture} argument source.
         */
        public static List<String> outputResources(String glob) {
            try {
                return outputResources(matchedResources(glob, ResourceFixtureSource.class.getClassLoader())).toList();
            } catch (IOException | URISyntaxException exception) {
                throw new IllegalStateException(
                    "Unable to discover formatter output resources for glob `%s`.".formatted(glob),
                    exception
                );
            }
        }

        private static Stream<FixtureInput> fixtureInputs(MatchedResources resources) {
            return resources.inputs()
                    .stream()
                    .map(input -> new FixtureInput(fixtureName(resources, input.getParent()), readString(input)))
                    .sorted(Comparator.comparing(FixtureInput::name));
        }

        private static Stream<String> outputResources(MatchedResources resources) {
            return resources.inputs()
                    .stream()
                    .flatMap(input -> outputResources(resources, input.getParent()))
                    .sorted();
        }

        private static Stream<String> outputResources(MatchedResources resources, Path directory) {
            try (var stream = Files.list(directory)) {
                return stream.filter(Files::isRegularFile)
                        .filter(output -> OUTPUT_FILE.matcher(output.getFileName().toString()).matches())
                        .map(output -> resourcePath(resources.rootName(), resources.root(), output)
                                    .toString()
                                    .replace(File.separatorChar, '/')
                        )
                        .toList()
                        .stream();
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to discover formatter outputs in " + directory, exception);
            }
        }

        private Stream<FormatFixture> formatFixtures(MatchedResources resources) {
            return resources.inputs()
                    .stream()
                    .flatMap(input -> formatFixtures(resources, input))
                    .sorted(Comparator.comparing(FormatFixture::name));
        }

        private Stream<FormatFixture> formatFixtures(MatchedResources resources, Path input) {
            Path directory = input.getParent();
            String fixtureName = fixtureName(resources, directory);
            try (var stream = Files.list(directory)) {
                List<FormatFixture> fixtures = stream.filter(Files::isRegularFile)
                        .flatMap(output -> formatFixture(fixtureName, resources, input, output))
                        .toList();
                if (fixtures.isEmpty()) {
                    throw new IllegalStateException(
                        "Missing formatter output for fixture `%s`. Expected `frmtr-<variant>.output.java` next to %s."
                                .formatted(fixtureName, input)
                    );
                }
                return fixtures.stream();
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to discover formatter outputs in " + directory, exception);
            }
        }

        private Stream<FormatFixture> formatFixture(
                String fixtureName,
                MatchedResources resources,
                Path input,
                Path output
        ) {
            String fileName = output.getFileName().toString();
            var outputFile = OUTPUT_FILE.matcher(fileName);
            if (outputFile.matches()) {
                String variant = outputFile.group(1);
                Path optionsFile = output.getParent().resolve("frmtr-" + variant + ".options.properties");
                FormatterOptions options = FixtureOptionsProperties.forVariant(variant, optionsFile);
                return Stream.of(
                    new FormatFixture(
                        fixtureName + " @ " + variant,
                        readString(input),
                        readString(output),
                        options,
                        resourcePath(resources.rootName(), resources.root(), output)
                                .toString()
                                .replace(File.separatorChar, '/')
                    )
                );
            }
            return Stream.empty();
        }

        private Stream<UnsupportedFixture> unsupportedFixtures(MatchedResources resources) {
            return resources.inputs()
                    .stream()
                    .map(input -> unsupportedFixture(resources, input))
                    .sorted(Comparator.comparing(UnsupportedFixture::name));
        }

        private UnsupportedFixture unsupportedFixture(MatchedResources resources, Path input) {
            Path error = input.getParent().resolve("error.txt");
            String fixtureName = fixtureName(resources, input.getParent());
            if (!Files.isRegularFile(error)) {
                throw new IllegalStateException(
                    "Missing unsupported fixture error companion for `%s`. Expected `error.txt` next to %s."
                            .formatted(fixtureName, input)
                );
            }
            return new UnsupportedFixture(fixtureName, readString(input), readLines(error));
        }

        private static String readString(Path path) {
            try {
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to read fixture resource " + path, exception);
            }
        }

        private static List<String> readLines(Path path) {
            try {
                return Files.readAllLines(path, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to read fixture resource " + path, exception);
            }
        }

        private static MatchedResources matchedResources(
                String glob, ClassLoader classLoader
        ) throws IOException, URISyntaxException {
            String rootName = resourceRootName(glob);
            Path root = resourceRoot(classLoader, rootName);
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
            try (var stream = Files.walk(root)) {
                List<Path> inputs = stream.filter(Files::isRegularFile)
                        .filter(path -> matcher.matches(resourcePath(rootName, root, path)))
                        .sorted()
                        .toList();
                if (inputs.isEmpty()) {
                    throw new IllegalStateException("No resource fixtures matched glob `%s`.".formatted(glob));
                }
                return new MatchedResources(rootName, root, inputs);
            }
        }

        private static Path resourceRoot(ClassLoader classLoader, String name) throws URISyntaxException {
            return Path.of(Objects.requireNonNull(classLoader.getResource(name), name).toURI())
                    .toAbsolutePath()
                    .normalize();
        }

        private static String resourceRootName(String glob) {
            int firstGlob = firstGlobCharacter(glob);
            String prefix = firstGlob == -1 ? glob : glob.substring(0, firstGlob);
            int slash = prefix.lastIndexOf('/');
            if (slash == -1) {
                return "";
            }
            return prefix.substring(0, slash);
        }

        private static int firstGlobCharacter(String glob) {
            int result = -1;
            for (char marker : List.of('*', '?', '[', '{')) {
                int index = glob.indexOf(marker);
                if (index != -1 && (result == -1 || index < result)) {
                    result = index;
                }
            }
            return result;
        }

        private static Path resourcePath(String rootName, Path root, Path path) {
            Path relative = root.relativize(path);
            if (rootName.isBlank()) {
                return relative;
            }
            return Path.of(rootName).resolve(relative);
        }

        private static String fixtureName(MatchedResources resources, Path fixtureDirectory) {
            String resourcePath = resourcePath(resources.rootName(), resources.root(), fixtureDirectory)
                    .toString()
                    .replace(File.separatorChar, '/');
            String rootPrefix = resources.rootName().isBlank() ? "" : resources.rootName() + "/";
            if (!rootPrefix.isBlank() && resourcePath.startsWith(rootPrefix)) {
                return resourcePath.substring(rootPrefix.length());
            }
            return resourcePath;
        }

        private static Class<?> fixtureParameterType(ExtensionContext context) {
            Class<?>[] parameterTypes = context.getRequiredTestMethod().getParameterTypes();
            if (parameterTypes.length != 1) {
                throw new IllegalStateException(
                    "@ResourceFixtureSource supports exactly one fixture parameter on %s."
                            .formatted(context.getRequiredTestMethod().getName())
                );
            }
            return parameterTypes[0];
        }

        private record MatchedResources(String rootName, Path root, List<Path> inputs) {}
    }
}
