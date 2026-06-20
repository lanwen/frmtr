package dev.lanwen.frmtr.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.Providers;
import com.github.javaparser.TokenMgrException;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.modules.ModuleDeclaration;
import com.github.javaparser.ast.modules.ModuleDirective;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import dev.lanwen.frmtr.ExplainResult;
import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import dev.lanwen.frmtr.doc.DocDebugRenderer;
import dev.lanwen.frmtr.doc.DocExplainRenderer;
import dev.lanwen.frmtr.doc.DocExplanation;
import dev.lanwen.frmtr.doc.DocRenderer;
import java.util.List;
import java.util.Optional;

/**
 * JavaParser-backed formatter engine for one formatter policy.
 *
 * <p>This type owns a JavaParser instance so parser configuration and parser allocation can be reused across sequential
 * formatting calls. JavaParser is stateful and not thread-safe; callers that format concurrently must create one
 * formatter, or a public {@code FrmtrSession}, per worker thread.
 */
public final class JavaFormatter {

    private static final JavaTransformPipeline TRANSFORMS = new JavaTransformPipeline(
        List.of(new ImportSortTransform())
    );

    private final FormatterOptions options;

    private final JavaParser parser;

    public JavaFormatter(FormatterOptions options) {
        this.options = options;
        var configuration = new ParserConfiguration()
                .setLanguageLevel(javaParserLanguageLevel(options.javaLanguageLevel()))
                .setStoreTokens(true)
                .setAttributeComments(true);
        this.parser = new JavaParser(configuration);
    }

    public String format(String source) {
        FormattedSource formatted = formatSource(source);
        formatted.parseResult().ifPresent(parseResult -> verifyAstEquivalent(parseResult, formatted.output()));
        return formatted.output();
    }

    /**
     * Formats {@code source} and, for cleanly-parsed input, always verifies the result is AST-equivalent before
     * returning it (the write-time {@code --verify} safety valve).
     *
     * <p>Unlike {@link #format(String)}, verification here is independent of the {@code dev.lanwen.frmtr.debug.verify}
     * toggle: a caller who asks for {@code formatVerified} is opting in to the re-parse cost and the graceful refusal in
     * exchange for the guarantee that the returned output means the same program as the input. On a mismatch this throws
     * a <em>non-internal</em> {@link FormatterException} (see {@link #assertOutputEquivalentOrThrow}) so the failure
     * renders as a deliberate refusal to overwrite, not an internal formatter bug.
     *
     * <p>Verification is skipped for recovered (partially-parsed) inputs, mirroring {@link #verifyAstEquivalent}: the
     * formatter only round-trips a best-effort tree there, so AST-equivalence is ill-defined and would false-fail. In
     * that case the formatted recovered output is returned without the equivalence guarantee. Pragma gating is honored
     * identically to {@link #format(String)}: a require-pragma input without the pragma is returned unchanged and is not
     * verified.
     */
    public String formatVerified(String source) {
        FormattedSource formatted = formatSource(source);
        formatted.parseResult()
                .filter(parseResult -> !parseResult.hasParseProblems())
                .ifPresent(
                    parseResult -> assertOutputEquivalentOrThrow(parseResult.compilationUnit(), formatted.output())
                );
        return formatted.output();
    }

    private FormattedSource formatSource(String source) {
        if (options.requirePragma() && !hasFormatPragma(source)) {
            return new FormattedSource(Optional.empty(), source);
        }
        JavaParseResult parseResult = parse(source);
        Doc doc = printDoc(source, parseResult);
        String formatted = new DocRenderer(options).render(doc);
        return new FormattedSource(Optional.of(parseResult), formatted);
    }

    private record FormattedSource(Optional<JavaParseResult> parseResult, String output) {}

    /**
     * Throws a non-internal {@link FormatterException} when {@code formatted} is not AST-equivalent to {@code inputUnit}.
     *
     * <p>This is the verify safety valve's decision seam, kept package-private so the refusal logic and its non-internal
     * failure type can be unit-tested directly without round-tripping the whole formatter (the real formatter does not
     * produce non-equivalent output on its own). The re-parse reuses {@code this.parser}, i.e. the exact same
     * {@link ParserConfiguration} used for the input. The equivalence contract — which differences are trivia and which
     * are genuine — lives entirely in {@link AstEquivalence#describeDifference}, reused here as-is; this method only
     * routes a mismatch into a {@link FormatterException} whose {@link FormatterException#internal()} is {@code false}
     * so it surfaces as a clean refusal rather than an internal bug.
     */
    void assertOutputEquivalentOrThrow(CompilationUnit inputUnit, String formatted) {
        JavaParseResult outputResult = parse(formatted);
        if (outputResult.hasParseProblems()) {
            throw new FormatterException(
                "frmtr verify: formatted output did not parse under the input's parser configuration"
            );
        }
        AstEquivalence.describeDifference(inputUnit, outputResult.compilationUnit()).ifPresent(difference -> {
            throw new FormatterException(
                "frmtr verify: formatted output is not AST-equivalent to the input — " + difference
            );
        });
    }

    /**
     * Re-parses the formatted output and asserts it represents the same program as the input (roadmap B3, layer 1).
     *
     * <p>Gated entirely by {@link FormatterGuardrails#verifyEnabled()}, so a normal run does no extra work and output
     * stays byte-identical regardless of the toggle. Verification is skipped for recovered (partially-parsed) inputs:
     * the formatter only round-trips a best-effort tree there, so AST-equivalence is ill-defined and would false-fail.
     * The re-parse reuses {@code this.parser}, i.e. the exact same {@link ParserConfiguration} (stored tokens,
     * attributed comments, language level) used for the input, so the two trees are produced under identical settings.
     */
    private void verifyAstEquivalent(JavaParseResult inputResult, String formatted) {
        if (!FormatterGuardrails.verifyEnabled() || inputResult.hasParseProblems()) {
            return;
        }
        JavaParseResult outputResult = parse(formatted);
        if (outputResult.hasParseProblems()) {
            throw new AssertionError(
                "Formatter AST-equivalence verify failed: formatted output did not parse cleanly under the "
                    + "input's parser configuration"
            );
        }
        FormatterGuardrails.assertAstEquivalent(inputResult.compilationUnit(), outputResult.compilationUnit());
    }

    /**
     * Returns the structural document tree produced after parsing, transforms, and Java printing.
     */
    public String debugDoc(String source) {
        return DocDebugRenderer.render(printDoc(source));
    }

    /**
     * Formats {@code source} and, from the same document, traces the renderer's break/flat decisions.
     *
     * <p>The formatted string is produced by the same {@link DocRenderer} path as {@link #format(String)} so explain
     * output can never disagree with formatting. The explanation is an independent observing pass over the same
     * document, so producing it does not perturb the rendered result. Unlike {@link #format(String)}, this always
     * builds and renders the document even under a require-pragma gate, because explaining an unformatted file would be
     * meaningless; pragma gating only controls whether {@link #format(String)} rewrites source.
     */
    public ExplainResult explain(String source) {
        PrintedDoc printed = printDocWithPrinter(source);
        String formatted = new DocRenderer(options).render(printed.doc());
        DocExplanation explanation = new DocExplainRenderer(options).explain(
            printed.doc(),
            printed.printer().layoutDecisions()
        );
        return new ExplainResult(formatted, explanation);
    }

    private Doc printDoc(String source) {
        return printDocWithPrinter(source).doc();
    }

    private Doc printDoc(String source, JavaParseResult parseResult) {
        return printDocWithPrinter(source, parseResult).doc();
    }

    /**
     * Builds the document and returns it together with the printer that built it, so explain can read the printer's
     * recorded width decisions. {@link #format(String)} discards the printer and only renders the document, so the
     * recording stays a pure observer with no effect on formatted output.
     */
    private PrintedDoc printDocWithPrinter(String source) {
        return printDocWithPrinter(source, parse(source));
    }

    private PrintedDoc printDocWithPrinter(String source, JavaParseResult parseResult) {
        // TODO: Expose parseResult.problems() through a future diagnostics/debug result API.
        SourceText sourceText = new SourceText(source);
        if (parseResult.hasParseProblems()) {
            unsupportedRecoveryReason(parseResult.compilationUnit(), sourceText).ifPresent(reason -> {
                throw parseFailure(
                    source,
                    parseResult.problems(),
                    new ParseProblemException(parseResult.problems()),
                    Optional.of(reason)
                );
            });
        }
        CompilationUnit printableUnit = parseResult.hasParseProblems()
            ? parseResult.compilationUnit()
            : TRANSFORMS.transform(parseResult.compilationUnit());
        JavaPrinter printer = new JavaPrinter(options, sourceText, parseResult.hasParseProblems());
        return new PrintedDoc(printer.print(printableUnit), printer);
    }

    private record PrintedDoc(Doc doc, JavaPrinter printer) {}

    private boolean hasFormatPragma(String source) {
        String stripped = source.stripLeading();
        if (!stripped.startsWith("/**")) {
            return false;
        }
        int end = stripped.indexOf("*/");
        if (end < 0) {
            return false;
        }
        String leadingDocComment = stripped.substring(0, end + 2);
        return leadingDocComment.contains("@format");
    }

    private JavaParseResult parse(String source) {
        try {
            ParseResult<CompilationUnit> result = parser.parse(ParseStart.COMPILATION_UNIT, Providers.provider(source));
            return parseResult(source, result);
        } catch (TokenMgrException exception) {
            throw new FormatterException(
                "Unable to parse Java source",
                exception,
                ParseErrorSourceContext.from(source, exception)
            );
        } catch (ParseProblemException exception) {
            throw parseFailure(source, exception.getProblems(), exception, thrownBeforeRecoveredCompilationUnit());
        }
    }

    private JavaParseResult parseResult(String source, ParseResult<CompilationUnit> result) {
        if (result.getResult().isEmpty()) {
            throw parseFailure(
                source,
                result.getProblems(),
                new ParseProblemException(result.getProblems()),
                noRecoveredCompilationUnit()
            );
        }
        var parseResult = new JavaParseResult(
            result.getResult().orElseThrow(),
            result.getProblems(),
            !result.isSuccessful() || !result.getProblems().isEmpty()
        );
        if (
            parseResult.hasParseProblems()
            && options.parseErrorBehavior() == FormatterOptions.ParseErrorBehavior.FAIL
        ) {
            throw parseFailure(
                source,
                parseResult.problems(),
                new ParseProblemException(parseResult.problems()),
                Optional.empty()
            );
        }
        return parseResult;
    }

    private FormatterException parseFailure(
            String source,
            List<Problem> problems,
            ParseProblemException cause,
            Optional<String> recoveryFailureReason
    ) {
        List<FormatterException.SourceProblem> sourceProblems = ParseErrorSourceContext.from(source, problems);
        if (
            options.parseErrorBehavior() == FormatterOptions.ParseErrorBehavior.RECOVER
            && recoveryFailureReason.isPresent()
        ) {
            sourceProblems = withRecoveryFailureReason(sourceProblems, recoveryFailureReason.orElseThrow());
        }
        return new FormatterException("Unable to parse Java source", cause, sourceProblems);
    }

    private static List<FormatterException.SourceProblem> withRecoveryFailureReason(
            List<FormatterException.SourceProblem> sourceProblems,
            String reason
    ) {
        if (sourceProblems.isEmpty()) {
            return List.of(
                new FormatterException.SourceProblem(reason, Optional.empty(), Optional.empty(), List.of())
            );
        }
        FormatterException.SourceProblem first = sourceProblems.getFirst();
        List<FormatterException.SourceProblem> withReason = new java.util.ArrayList<>();
        withReason.add(
            new FormatterException.SourceProblem(
                reason + System.lineSeparator() + System.lineSeparator() + first.message(),
                first.location(),
                first.enclosingUnitLine(),
                first.contextLines()
            )
        );
        withReason.addAll(sourceProblems.subList(1, sourceProblems.size()));
        return List.copyOf(withReason);
    }

    private Optional<String> parseProblemsUnsupportedByCurrentPrinters() {
        if (options.parseErrorBehavior() == FormatterOptions.ParseErrorBehavior.FAIL) {
            return Optional.empty();
        }
        return Optional.of(
            "Parse-error recovery is configured, but this recovery slice only supports malformed block statement lists, class/interface/record member declaration lists, import declaration lists, top-level declaration lists, module directive lists, switch entry lists, enum constant lists, and annotation declaration member lists."
        );
    }

    private Optional<String> unsupportedRecoveryReason(CompilationUnit unit, SourceText sourceText) {
        if (options.parseErrorBehavior() == FormatterOptions.ParseErrorBehavior.FAIL) {
            return Optional.empty();
        }
        List<Node> recoveredNodes = unit.stream()
                .filter(node -> node.getParsed() != Node.Parsedness.PARSED)
                .toList();
        if (recoveredNodes.isEmpty()) {
            return parseProblemsUnsupportedByCurrentPrinters();
        }
        return recoveredNodes.stream()
                .filter(node -> !isSupportedRecovery(node, Optional.of(sourceText)))
                .findFirst()
                .map(
                    node -> parseProblemsUnsupportedByCurrentPrinters().orElseThrow()
                            + " Unsupported recovered node: "
                            + node.getClass().getSimpleName()
                            + node.getRange().map(range -> " at " + range).orElse(".")
                );
    }

    static boolean isSupportedRecovery(Node recoveredNode) {
        return isSupportedRecovery(recoveredNode, Optional.empty());
    }

    private static boolean isSupportedRecovery(Node recoveredNode, Optional<SourceText> sourceText) {
        return isSupportedSwitchEntryListRecovery(recoveredNode)
            || isSupportedEnumConstantListRecovery(recoveredNode)
            || isSupportedAnnotationMemberListRecovery(recoveredNode)
            || isSupportedBlockStatementListRecovery(recoveredNode, sourceText)
            || isSupportedMemberDeclarationListRecovery(recoveredNode)
            || isSupportedImportDeclarationListRecovery(recoveredNode)
            || isSupportedTopLevelDeclarationListRecovery(recoveredNode)
            || isSupportedModuleDirectiveListRecovery(recoveredNode);
    }

    private static boolean isSupportedSwitchEntryListRecovery(Node recoveredNode) {
        return SwitchPrinter.nearestSwitchEntryListSibling(recoveredNode)
                .filter(SwitchPrinter::isRecoverableSwitchEntryListSibling)
                .isPresent();
    }

    private static boolean isSupportedEnumConstantListRecovery(Node recoveredNode) {
        return EnumDeclarationPrinter.nearestEnumConstantListSibling(recoveredNode)
                .filter(EnumDeclarationPrinter::isRecoverableEnumConstantListSibling)
                .isPresent();
    }

    private static boolean isSupportedAnnotationMemberListRecovery(Node recoveredNode) {
        return AnnotationDeclarationPrinter.nearestAnnotationMemberListSibling(recoveredNode)
                .filter(AnnotationDeclarationPrinter::isRecoverableAnnotationMemberListSibling)
                .isPresent();
    }

    private static boolean isSupportedBlockStatementListRecovery(
            Node recoveredNode,
            Optional<SourceText> sourceText
    ) {
        if (
            SwitchPrinter.nearestSwitchEntryListSibling(recoveredNode).isPresent()
            || EnumDeclarationPrinter.nearestEnumConstantListSibling(recoveredNode).isPresent()
            || AnnotationDeclarationPrinter.nearestAnnotationMemberListSibling(recoveredNode).isPresent()
            || isCollapsedMalformedSwitchStatement(recoveredNode, sourceText)
        ) {
            return false;
        }
        if (recoveredNode instanceof BlockStmt) {
            return true;
        }
        return nearestBlockStatementListSibling(recoveredNode).isPresent();
    }

    private static boolean isCollapsedMalformedSwitchStatement(
            Node recoveredNode,
            Optional<SourceText> sourceText
    ) {
        return sourceText
                .filter(source -> recoveredNode instanceof Statement statement
                        && SwitchPrinter.isCollapsedMalformedSwitchStatement(statement, source)
                )
                .isPresent();
    }

    private static Optional<Statement> nearestBlockStatementListSibling(Node recoveredNode) {
        Optional<Node> current = Optional.of(recoveredNode);
        while (current.isPresent()) {
            Node node = current.orElseThrow();
            if (node instanceof Statement statement && isBlockStatementListSibling(statement)) {
                return Optional.of(statement);
            }
            current = node.getParentNode();
        }
        return Optional.empty();
    }

    private static boolean isBlockStatementListSibling(Statement statement) {
        return statement.getParentNode()
                .filter(BlockStmt.class::isInstance)
                .map(BlockStmt.class::cast)
                .filter(block -> block.getStatements().contains(statement))
                .isPresent();
    }

    private static boolean isSupportedMemberDeclarationListRecovery(Node recoveredNode) {
        return nearestClassInterfaceOrRecordMemberSibling(recoveredNode)
                .filter(member -> member.getParsed() != Node.Parsedness.PARSED)
                .isPresent();
    }

    private static Optional<BodyDeclaration<?>> nearestClassInterfaceOrRecordMemberSibling(Node recoveredNode) {
        Optional<Node> current = Optional.of(recoveredNode);
        while (current.isPresent()) {
            Node node = current.orElseThrow();
            if (node instanceof BodyDeclaration<?> member && isClassInterfaceOrRecordMember(member)) {
                return Optional.of(member);
            }
            current = node.getParentNode();
        }
        return Optional.empty();
    }

    private static boolean isClassInterfaceOrRecordMember(BodyDeclaration<?> member) {
        return member.getParentNode()
                .filter(parent -> {
                    if (parent instanceof ClassOrInterfaceDeclaration declaration) {
                        return declaration.getMembers().contains(member);
                    }
                    if (parent instanceof RecordDeclaration declaration) {
                        return declaration.getMembers().contains(member);
                    }
                    return false;
                })
                .isPresent();
    }

    private static boolean isSupportedImportDeclarationListRecovery(Node recoveredNode) {
        if (recoveredNode instanceof CompilationUnit unit) {
            return CompilationUnitPrinter.hasRecoverableImportDeclarationListProblem(unit.getImports());
        }
        return nearestImportDeclarationListSibling(recoveredNode)
                .filter(JavaFormatter::hasRecoverableImportDeclarationListProblem)
                .isPresent();
    }

    private static Optional<ImportDeclaration> nearestImportDeclarationListSibling(Node recoveredNode) {
        Optional<Node> current = Optional.of(recoveredNode);
        while (current.isPresent()) {
            Node node = current.orElseThrow();
            if (node instanceof ImportDeclaration importDeclaration && isCompilationUnitImport(importDeclaration)) {
                return Optional.of(importDeclaration);
            }
            current = node.getParentNode();
        }
        return Optional.empty();
    }

    private static boolean isCompilationUnitImport(ImportDeclaration importDeclaration) {
        return importDeclaration.getParentNode()
                .filter(CompilationUnit.class::isInstance)
                .map(CompilationUnit.class::cast)
                .filter(unit -> unit.getImports().contains(importDeclaration))
                .isPresent();
    }

    private static boolean hasRecoverableImportDeclarationListProblem(ImportDeclaration importDeclaration) {
        return importDeclaration.getParentNode()
                .filter(CompilationUnit.class::isInstance)
                .map(CompilationUnit.class::cast)
                .map(CompilationUnit::getImports)
                .map(CompilationUnitPrinter::hasRecoverableImportDeclarationListProblem)
                .orElse(false);
    }

    private static boolean isSupportedTopLevelDeclarationListRecovery(Node recoveredNode) {
        if (
            !(recoveredNode instanceof TypeDeclaration<?> type)
            || type.getParsed() == Node.Parsedness.PARSED
        ) {
            return false;
        }
        return type.getParentNode()
                .filter(CompilationUnit.class::isInstance)
                .map(CompilationUnit.class::cast)
                .filter(unit -> unit.getTypes().contains(type))
                .map(unit -> unit.getTypes().stream().anyMatch(
                        sibling -> sibling != type && sibling.getParsed() == Node.Parsedness.PARSED
                ))
                .orElse(false);
    }

    private static boolean isSupportedModuleDirectiveListRecovery(Node recoveredNode) {
        return nearestModuleDirectiveListSibling(recoveredNode)
                .filter(JavaFormatter::hasRecoverableModuleDirectiveListProblem)
                .isPresent();
    }

    private static Optional<ModuleDirective> nearestModuleDirectiveListSibling(Node recoveredNode) {
        Optional<Node> current = Optional.of(recoveredNode);
        while (current.isPresent()) {
            Node node = current.orElseThrow();
            if (node instanceof ModuleDirective directive && isModuleDeclarationDirective(directive)) {
                return Optional.of(directive);
            }
            current = node.getParentNode();
        }
        return Optional.empty();
    }

    private static boolean isModuleDeclarationDirective(ModuleDirective directive) {
        return directive.getParentNode()
                .filter(ModuleDeclaration.class::isInstance)
                .map(ModuleDeclaration.class::cast)
                .filter(module -> module.getDirectives().contains(directive))
                .isPresent();
    }

    private static boolean hasRecoverableModuleDirectiveListProblem(ModuleDirective directive) {
        return directive.getParentNode()
                .filter(ModuleDeclaration.class::isInstance)
                .map(ModuleDeclaration.class::cast)
                .map(ModuleBlockPrinter::hasRecoverableModuleDirectiveListProblem)
                .orElse(false);
    }

    private Optional<String> noRecoveredCompilationUnit() {
        if (options.parseErrorBehavior() == FormatterOptions.ParseErrorBehavior.FAIL) {
            return Optional.empty();
        }
        return Optional.of(
            "Parse-error recovery is configured, but JavaParser did not return a compilation unit to recover."
        );
    }

    private Optional<String> thrownBeforeRecoveredCompilationUnit() {
        if (options.parseErrorBehavior() == FormatterOptions.ParseErrorBehavior.FAIL) {
            return Optional.empty();
        }
        return Optional.of(
            "Parse-error recovery is configured, but JavaParser threw before returning a recovered compilation unit."
        );
    }

    private static ParserConfiguration.LanguageLevel javaParserLanguageLevel(
            FormatterOptions.JavaLanguageLevel languageLevel
    ) {
        return switch (languageLevel) {
            case UNSET -> null;
            case LATEST_AVAILABLE -> ParserConfiguration.LanguageLevel.BLEEDING_EDGE;
            case JAVA_8 -> ParserConfiguration.LanguageLevel.JAVA_8;
            case JAVA_9 -> ParserConfiguration.LanguageLevel.JAVA_9;
            case JAVA_10 -> ParserConfiguration.LanguageLevel.JAVA_10;
            case JAVA_11 -> ParserConfiguration.LanguageLevel.JAVA_11;
            case JAVA_12 -> ParserConfiguration.LanguageLevel.JAVA_12;
            case JAVA_13 -> ParserConfiguration.LanguageLevel.JAVA_13;
            case JAVA_14 -> ParserConfiguration.LanguageLevel.JAVA_14;
            case JAVA_15 -> ParserConfiguration.LanguageLevel.JAVA_15;
            case JAVA_16 -> ParserConfiguration.LanguageLevel.JAVA_16;
            case JAVA_17 -> ParserConfiguration.LanguageLevel.JAVA_17;
            case JAVA_18 -> ParserConfiguration.LanguageLevel.JAVA_18;
            case JAVA_19 -> ParserConfiguration.LanguageLevel.JAVA_19;
            case JAVA_20 -> ParserConfiguration.LanguageLevel.JAVA_20;
            case JAVA_21 -> ParserConfiguration.LanguageLevel.JAVA_21;
            case JAVA_22 -> ParserConfiguration.LanguageLevel.JAVA_22;
            case JAVA_23 -> ParserConfiguration.LanguageLevel.JAVA_23;
            case JAVA_24 -> ParserConfiguration.LanguageLevel.JAVA_24;
            case JAVA_25 -> ParserConfiguration.LanguageLevel.JAVA_25;
        };
    }

    private record JavaParseResult(CompilationUnit compilationUnit, List<Problem> problems, boolean hasParseProblems) {
        private JavaParseResult {
            problems = List.copyOf(problems);
        }
    }

    static Doc commentDoc(Comment comment) {
        return commentDoc(JavaCommentTrivia.from(comment));
    }

    static Doc commentDoc(JavaCommentTrivia trivia) {
        Comment comment = trivia.comment();
        if (trivia.isLine()) {
            LineComment lineComment = (LineComment) comment;
            String text = lineComment.toString().stripTrailing();
            if (text.contains("\n")) {
                return lineDoc(text);
            }
            return Doc.text("//" + lineComment.getContent().stripTrailing());
        }
        if (trivia.isJavadoc()) {
            JavadocComment javadocComment = (JavadocComment) comment;
            String raw = comment.getTokenRange().map(Object::toString).orElseGet(javadocComment::toString).strip();
            if (raw.lines().count() == 1) {
                return Doc.text(raw);
            }
            return lineDoc(javadocComment.toString().stripTrailing());
        }
        if (trivia.isBlock()) {
            BlockComment blockComment = (BlockComment) comment;
            String text = blockComment.toString();
            String normalized = normalizeBlockComment(text);
            return normalized.equals(text.stripTrailing()) ? Doc.text(normalized) : lineDoc(normalized);
        }
        return lineDoc(comment.toString().stripTrailing());
    }

    private static Doc lineDoc(String value) {
        List<Doc> lines = value.lines().map(Doc::text).toList();
        return Doc.join(Doc.HARD_LINE, lines);
    }

    private static String normalizeBlockComment(String value) {
        String text = value.stripTrailing();
        List<String> lines = text.lines().toList();
        if (
            lines.size() < 3
            || lines.stream()
                    .skip(1)
                    .limit(lines.size() - 2)
                    .map(String::stripLeading)
                    .anyMatch(line -> !line.startsWith("*"))
        ) {
            return text;
        }
        List<String> normalized = new java.util.ArrayList<>();
        normalized.add(lines.getFirst());
        lines.stream()
                .skip(1)
                .limit(lines.size() - 2)
                .map(String::stripLeading)
                .map(line -> " " + line)
                .forEach(normalized::add);
        normalized.add(" */");
        return String.join(System.lineSeparator(), normalized);
    }
}
