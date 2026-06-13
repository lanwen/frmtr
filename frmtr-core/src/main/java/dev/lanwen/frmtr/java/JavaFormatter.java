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
import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import dev.lanwen.frmtr.doc.DocDebugRenderer;
import dev.lanwen.frmtr.doc.DocRenderer;
import java.util.List;
import java.util.Optional;

public final class JavaFormatter {
    private static final JavaTransformPipeline TRANSFORMS =
            new JavaTransformPipeline(List.of(new ImportSortTransform()));

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
        if (options.requirePragma() && !hasFormatPragma(source)) {
            return source;
        }
        Doc doc = printDoc(source);
        return new DocRenderer(options).render(doc);
    }

    /**
     * Returns the structural document tree produced after parsing, transforms, and Java printing.
     */
    public String debugDoc(String source) {
        return DocDebugRenderer.render(printDoc(source));
    }

    private Doc printDoc(String source) {
        JavaParseResult parseResult = parse(source);
        // TODO: Expose parseResult.problems() through a future diagnostics/debug result API.
        SourceText sourceText = new SourceText(source);
        if (parseResult.hasParseProblems()) {
            unsupportedRecoveryReason(parseResult.compilationUnit(), sourceText)
                    .ifPresent(reason -> {
                        throw parseFailure(
                                source,
                                parseResult.problems(),
                                new ParseProblemException(parseResult.problems()),
                                Optional.of(reason));
                    });
        }
        CompilationUnit printableUnit = parseResult.hasParseProblems()
                ? parseResult.compilationUnit()
                : TRANSFORMS.transform(parseResult.compilationUnit());
        JavaPrinter printer = new JavaPrinter(options, sourceText, parseResult.hasParseProblems());
        return printer.print(printableUnit);
    }

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
            ParseResult<CompilationUnit> result =
                    parser.parse(ParseStart.COMPILATION_UNIT, Providers.provider(source));
            return parseResult(source, result);
        } catch (TokenMgrException exception) {
            throw new FormatterException(
                    "Unable to parse Java source",
                    exception,
                    ParseErrorSourceContext.from(source, exception));
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
                    noRecoveredCompilationUnit());
        }
        var parseResult = new JavaParseResult(
                result.getResult().orElseThrow(),
                result.getProblems(),
                !result.isSuccessful() || !result.getProblems().isEmpty());
        if (parseResult.hasParseProblems()
                && options.parseErrorBehavior() == FormatterOptions.ParseErrorBehavior.FAIL) {
            throw parseFailure(
                    source,
                    parseResult.problems(),
                    new ParseProblemException(parseResult.problems()),
                    Optional.empty());
        }
        return parseResult;
    }

    private FormatterException parseFailure(
            String source,
            List<Problem> problems,
            ParseProblemException cause,
            Optional<String> recoveryFailureReason) {
        List<FormatterException.SourceProblem> sourceProblems = ParseErrorSourceContext.from(source, problems);
        if (options.parseErrorBehavior() == FormatterOptions.ParseErrorBehavior.RECOVER
                && recoveryFailureReason.isPresent()) {
            sourceProblems = withRecoveryFailureReason(sourceProblems, recoveryFailureReason.orElseThrow());
        }
        return new FormatterException("Unable to parse Java source", cause, sourceProblems);
    }

    private static List<FormatterException.SourceProblem> withRecoveryFailureReason(
            List<FormatterException.SourceProblem> sourceProblems,
            String reason) {
        if (sourceProblems.isEmpty()) {
            return List.of(new FormatterException.SourceProblem(reason, Optional.empty(), Optional.empty(), List.of()));
        }
        FormatterException.SourceProblem first = sourceProblems.getFirst();
        List<FormatterException.SourceProblem> withReason = new java.util.ArrayList<>();
        withReason.add(new FormatterException.SourceProblem(
                reason + System.lineSeparator() + System.lineSeparator() + first.message(),
                first.location(),
                first.enclosingUnitLine(),
                first.contextLines()));
        withReason.addAll(sourceProblems.subList(1, sourceProblems.size()));
        return List.copyOf(withReason);
    }

    private Optional<String> parseProblemsUnsupportedByCurrentPrinters() {
        if (options.parseErrorBehavior() == FormatterOptions.ParseErrorBehavior.FAIL) {
            return Optional.empty();
        }
        return Optional.of(
                "Parse-error recovery is configured, but this recovery slice only supports malformed block statement lists, class/interface/record member declaration lists, import declaration lists, top-level declaration lists, module directive lists, switch entry lists, enum constant lists, and annotation declaration member lists.");
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
                .map(node -> parseProblemsUnsupportedByCurrentPrinters().orElseThrow()
                        + " Unsupported recovered node: "
                        + node.getClass().getSimpleName()
                        + node.getRange().map(range -> " at " + range).orElse("."));
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
            Optional<SourceText> sourceText) {
        if (SwitchPrinter.nearestSwitchEntryListSibling(recoveredNode).isPresent()
                || EnumDeclarationPrinter.nearestEnumConstantListSibling(recoveredNode).isPresent()
                || AnnotationDeclarationPrinter.nearestAnnotationMemberListSibling(recoveredNode).isPresent()
                || isCollapsedMalformedSwitchStatement(recoveredNode, sourceText)) {
            return false;
        }
        if (recoveredNode instanceof BlockStmt) {
            return true;
        }
        return nearestBlockStatementListSibling(recoveredNode).isPresent();
    }

    private static boolean isCollapsedMalformedSwitchStatement(
            Node recoveredNode,
            Optional<SourceText> sourceText) {
        return sourceText
                .filter(source -> recoveredNode instanceof Statement statement
                        && SwitchPrinter.isCollapsedMalformedSwitchStatement(statement, source))
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
        if (!(recoveredNode instanceof TypeDeclaration<?> type)
                || type.getParsed() == Node.Parsedness.PARSED) {
            return false;
        }
        return type.getParentNode()
                .filter(CompilationUnit.class::isInstance)
                .map(CompilationUnit.class::cast)
                .filter(unit -> unit.getTypes().contains(type))
                .map(unit -> unit.getTypes().stream()
                        .anyMatch(sibling -> sibling != type && sibling.getParsed() == Node.Parsedness.PARSED))
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
        return Optional.of("Parse-error recovery is configured, but JavaParser did not return a compilation unit to recover.");
    }

    private Optional<String> thrownBeforeRecoveredCompilationUnit() {
        if (options.parseErrorBehavior() == FormatterOptions.ParseErrorBehavior.FAIL) {
            return Optional.empty();
        }
        return Optional.of(
                "Parse-error recovery is configured, but JavaParser threw before returning a recovered compilation unit.");
    }

    private static ParserConfiguration.LanguageLevel javaParserLanguageLevel(
            FormatterOptions.JavaLanguageLevel languageLevel) {
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

    private record JavaParseResult(
            CompilationUnit compilationUnit,
            List<Problem> problems,
            boolean hasParseProblems) {
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
        if (lines.size() < 3 || lines.stream().skip(1).limit(lines.size() - 2)
                .map(String::stripLeading)
                .anyMatch(line -> !line.startsWith("*"))) {
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
