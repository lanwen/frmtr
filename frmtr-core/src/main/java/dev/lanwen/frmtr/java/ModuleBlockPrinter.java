package dev.lanwen.frmtr.java;

import com.github.javaparser.GeneratedJavaParserConstants;
import com.github.javaparser.JavaToken;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.modules.ModuleDeclaration;
import com.github.javaparser.ast.modules.ModuleDirective;
import com.github.javaparser.ast.modules.ModuleExportsDirective;
import com.github.javaparser.ast.modules.ModuleOpensDirective;
import com.github.javaparser.ast.modules.ModuleProvidesDirective;
import com.github.javaparser.ast.modules.ModuleRequiresDirective;
import com.github.javaparser.ast.modules.ModuleUsesDirective;
import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Prints structured {@code module-info.java} blocks and directives after the module header has already been assembled.
 *
 * <p>This helper owns the normal parsed module body shape: empty module blocks, source blank lines between directives,
 * directive-type dispatch, and the flat-versus-broken layout forks for {@code to} and {@code with} target lists. The
 * boundary exists so {@link JavaPrinter} can keep comments, annotations, {@code open module ...}, and raw commented
 * module fallbacks in their current owners while this class keeps the structured directive rules together.
 *
 * <p>Multi-target {@code exports}, {@code opens}, and {@code provides} directives use continuation indentation when a
 * target list no longer fits on the declaration line. If the keyword plus list still fits, that continuation stays on
 * one indented line; otherwise each target is placed on its own deeper continuation line so the target keyword remains
 * visually attached to the package or service name.
 *
 * <p>The fixture pair {@code format/module-declarations-directives/input.java} and {@code
 * format/module-declarations-directives/frmtr-default.output.java} shows the expected structured module behavior.
 */
final class ModuleBlockPrinter {

    private static final String MODULE_DIRECTIVE_LIST_RECOVERY_FAILURE =
        "Unable to recover Java parse error inside module directive list: ";

    private final CommentTracker comments;

    private final FormatterOptions options;

    private final SourceText sourceText;

    private final RecoveredListPlanner recoveredListPlanner;

    private final RecoveredRawGapPrinter rawGaps;

    private final boolean recoverParseProblems;

    private final Function<Node, String> compact;

    private final Function<List<? extends Node>, String> compactJoin;

    private final Function<ModuleRequiresDirective, String> requiresModifiers;

    ModuleBlockPrinter(
            JavaFormatContext context,
            Function<Node, String> compact,
            Function<List<? extends Node>, String> compactJoin,
            Function<ModuleRequiresDirective, String> requiresModifiers
    ) {
        this.comments = context.comments;
        this.options = context.options;
        this.sourceText = context.sourceText;
        this.recoveredListPlanner = context.recoveredListPlanner;
        this.rawGaps = new RecoveredRawGapPrinter(context, ModuleBlockPrinter::moduleDirectiveListRecoveryFailure);
        this.recoverParseProblems = context.recoverParseProblems;
        this.compact = compact;
        this.compactJoin = compactJoin;
        this.requiresModifiers = requiresModifiers;
    }

    /**
     * Prints the brace-delimited module body while leaving the declaration header and commented raw-source fallback to
     * the caller.
     *
     * <p>Empty modules stay on one line because there is no directive or orphan-comment sequencing to preserve.
     */
    Doc moduleBlock(ModuleDeclaration declaration) {
        Optional<RecoveredListPlanner.Plan<ModuleDirective>> recoveryPlan = recoveryPlan(declaration);
        if (recoveryPlan.isPresent() && hasRawGap(recoveryPlan.orElseThrow())) {
            return recoveredModuleBlock(
                declaration,
                recoveryPlan.orElseThrow(),
                recoverableRawGapRegions(declaration, recoveryPlan.orElseThrow())
            );
        }
        if (declaration.getDirectives().isEmpty()) {
            return Doc.text("{}");
        }
        return Doc.concat(
            Doc.text("{"),
            Doc.indent(Doc.concat(Doc.HARD_LINE, moduleContents(declaration.getDirectives()))),
            Doc.HARD_LINE,
            Doc.text("}")
        );
    }

    /**
     * Sequences structured module directives and preserves intentional source blank lines between neighboring
     * directives.
     */
    private Doc moduleContents(NodeList<ModuleDirective> directives) {
        List<Doc> contents = new ArrayList<>();
        for (int i = 0; i < directives.size(); i++) {
            if (i > 0) {
                contents.add(moduleDirectiveSeparator(directives.get(i - 1), directives.get(i)));
            }
            contents.add(moduleDirective(directives.get(i)));
        }
        return Doc.concat(contents);
    }

    /**
     * Chooses a single line break or a blank line from the directives' original source ranges.
     */
    private Doc moduleDirectiveSeparator(ModuleDirective previous, ModuleDirective current) {
        boolean hasBlankLineBetween = previous.getRange()
                .flatMap(previousRange -> current.getRange().map(
                        currentRange -> currentRange.begin.line > previousRange.end.line + 1
                ))
                .orElse(false);
        return hasBlankLineBetween ? Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE) : Doc.HARD_LINE;
    }

    /**
     * Emits a recovered module directive body while keeping valid directive siblings on their normal renderer.
     *
     * <p>The module header and braces remain formatter-owned. Raw gaps are limited to the brace interior selected from
     * the declaration token range, so malformed directive source cannot consume the module declaration boundary.
     */
    private Doc recoveredModuleBlock(
            ModuleDeclaration declaration,
            RecoveredListPlanner.Plan<ModuleDirective> plan,
            List<RecoveredRawGapPrinter.RawGapRegion> rawGapRegions
    ) {
        List<Doc> contents = new ArrayList<>();
        EntryKind previousEntry = EntryKind.NONE;
        ModuleDirective previousDirective = null;
        int rawGapIndex = 0;
        for (RecoveredListPlanner.Entry<ModuleDirective> entry : plan.entries()) {
            switch (entry) {
                case RecoveredListPlanner.ValidSibling<?> valid -> {
                    ModuleDirective currentDirective = (ModuleDirective) valid.sibling();
                    appendSeparatorBeforeRecoveredDirective(
                        contents,
                        previousEntry,
                        previousDirective,
                        currentDirective
                    );
                    contents.add(moduleDirective(currentDirective));
                    previousDirective = currentDirective;
                    previousEntry = EntryKind.VALID_DIRECTIVE;
                }
                case RecoveredListPlanner.RawGap<?> ignored -> {
                    RecoveredRawGapPrinter.RawGapRegion rawRegion = rawGapRegions.get(rawGapIndex++);
                    if (rawRegion.region().beginOffset() < rawRegion.region().endOffset()) {
                        contents.add(rawGaps.raw(declaration, rawRegion, "moduleDirectiveList"));
                    }
                    previousEntry = rawRegion.trailingBreakReplaced()
                        ? EntryKind.RAW_GAP_WITH_TRAILING_BREAK
                        : EntryKind.RAW_GAP;
                }
            }
        }
        if (contents.isEmpty()) {
            return Doc.text("{}");
        }
        Doc closingBreak = switch (previousEntry) {
            case RAW_GAP -> Doc.EMPTY;
            case NONE, VALID_DIRECTIVE, RAW_GAP_WITH_TRAILING_BREAK -> Doc.HARD_LINE;
        };
        return Doc.concat(Doc.text("{"), Doc.indent(Doc.concat(contents)), closingBreak, Doc.text("}"));
    }

    /**
     * Reports whether structured recovery can safely replace the commented-module raw fallback.
     *
     * <p>That is only true when every comment marker inside the module declaration source sits inside a recovered raw
     * directive-list gap. Comments in the module header or normally formatted directive siblings still belong to the
     * whole-module raw fallback because the structured module path does not preserve those source-only comment slots.
     */
    boolean canUseStructuredRecoveryForCommentedModule(ModuleDeclaration declaration) {
        Optional<RecoveredListPlanner.Plan<ModuleDirective>> recoveryPlan = recoveryPlan(declaration);
        if (recoveryPlan.isEmpty() || !hasRawGap(recoveryPlan.orElseThrow())) {
            return false;
        }
        List<RecoveredRawGapPrinter.RawGapRegion> rawGapRegions = rawGaps.rawGapRegions(recoveryPlan.orElseThrow());
        if (hasCommentOutsideRawGaps(declaration, rawGapRegions)) {
            return false;
        }
        rawGaps.requireRecoverableRawRegions(declaration, rawGapRegions);
        return true;
    }

    private void appendSeparatorBeforeRecoveredDirective(
            List<Doc> contents,
            EntryKind previousEntry,
            ModuleDirective previousDirective,
            ModuleDirective currentDirective
    ) {
        if (contents.isEmpty()) {
            contents.add(Doc.HARD_LINE);
            return;
        }
        switch (previousEntry) {
            case VALID_DIRECTIVE -> contents.add(moduleDirectiveSeparator(previousDirective, currentDirective));
            case RAW_GAP_WITH_TRAILING_BREAK -> contents.add(Doc.HARD_LINE);
            case NONE, RAW_GAP -> {
                // Raw source already owns the separation before this directive.
            }
        }
    }

    private Optional<RecoveredListPlanner.Plan<ModuleDirective>> recoveryPlan(ModuleDeclaration declaration) {
        if (!recoverParseProblems || !hasRecoverableModuleDirectiveListProblem(declaration)) {
            return Optional.empty();
        }
        RecoveredListPlanner.Plan<ModuleDirective> plan = recoveredListPlanner.plan(
            declaration,
            requireModuleBlockInteriorRegion(declaration),
            declaration.getDirectives(),
            directive -> directive.getParsed() == Node.Parsedness.PARSED
        );
        if (!plan.isSafe()) {
            throw moduleDirectiveListRecoveryFailure(plan.unsafe().orElseThrow().reason());
        }
        return Optional.of(plan);
    }

    private List<RecoveredRawGapPrinter.RawGapRegion> recoverableRawGapRegions(
            ModuleDeclaration declaration,
            RecoveredListPlanner.Plan<ModuleDirective> plan
    ) {
        List<RecoveredRawGapPrinter.RawGapRegion> rawGapRegions = rawGaps.rawGapRegions(plan);
        rawGaps.requireRecoverableRawRegions(declaration, rawGapRegions);
        return rawGapRegions;
    }

    static boolean hasRecoverableModuleDirectiveListProblem(ModuleDeclaration declaration) {
        return declaration.getDirectives().stream().anyMatch(directive -> !isFullyParsed(directive));
    }

    private SourceRegion requireModuleBlockInteriorRegion(ModuleDeclaration declaration) {
        try {
            return moduleBlockInteriorRegion(declaration);
        } catch (IllegalArgumentException exception) {
            throw moduleDirectiveListRecoveryFailure(exception.getMessage(), exception);
        }
    }

    private SourceRegion moduleBlockInteriorRegion(ModuleDeclaration declaration) {
        List<JavaToken> tokens = declaration.getTokenRange()
                .map(tokenRange -> {
                    List<JavaToken> collected = new ArrayList<>();
                    tokenRange.forEach(collected::add);
                    return collected;
                })
                .orElseThrow(() -> new IllegalArgumentException("module declaration is missing a token range"));
        JavaToken openingBrace = tokens.stream()
                .filter(token -> token.getKind() == GeneratedJavaParserConstants.LBRACE)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("module body source range is missing an opening brace"));
        JavaToken closingBrace = null;
        for (int i = tokens.size() - 1; i >= 0; i--) {
            JavaToken token = tokens.get(i);
            if (token.getKind() == GeneratedJavaParserConstants.RBRACE) {
                closingBrace = token;
                break;
            }
        }
        if (closingBrace == null) {
            throw new IllegalArgumentException("module body source range is missing a closing brace");
        }
        SourceRegion openingRegion = tokenRegion(openingBrace, "opening brace");
        SourceRegion closingRegion = tokenRegion(closingBrace, "closing brace");
        if (closingRegion.beginOffset() < openingRegion.endOffset()) {
            throw new IllegalArgumentException("module body braces are not ordered");
        }
        return sourceText.region(openingRegion.endOffset(), closingRegion.beginOffset());
    }

    private SourceRegion tokenRegion(JavaToken token, String description) {
        return token.getRange()
                .map(sourceText::region)
                .orElseThrow(() -> new IllegalArgumentException(
                        "module body " + description + " is missing a source range"
                ));
    }

    private static boolean hasRawGap(RecoveredListPlanner.Plan<ModuleDirective> plan) {
        return plan.entries().stream().anyMatch(RecoveredListPlanner.RawGap.class::isInstance);
    }

    private boolean hasCommentOutsideRawGaps(
            ModuleDeclaration declaration,
            List<RecoveredRawGapPrinter.RawGapRegion> rawGapRegions
    ) {
        SourceRegion moduleRegion = declaration.getRange()
                .map(sourceText::region)
                .orElseThrow(() -> moduleDirectiveListRecoveryFailure("module declaration is missing a source range"));
        List<SourceRegion> rawRegions = rawGaps.regions(rawGapRegions)
                .stream()
                .filter(region -> region.beginOffset() < region.endOffset())
                .sorted((left, right) -> Integer.compare(left.beginOffset(), right.beginOffset()))
                .toList();
        int cursor = moduleRegion.beginOffset();
        for (SourceRegion rawRegion : rawRegions) {
            if (containsCommentMarker(sourceText.slice(sourceText.region(cursor, rawRegion.beginOffset())))) {
                return true;
            }
            cursor = rawRegion.endOffset();
        }
        return containsCommentMarker(sourceText.slice(sourceText.region(cursor, moduleRegion.endOffset())));
    }

    private static boolean containsCommentMarker(String source) {
        return source.contains("/*") || source.contains("//");
    }

    private static boolean isFullyParsed(Node node) {
        return node.stream().allMatch(descendant -> descendant.getParsed() == Node.Parsedness.PARSED);
    }

    private static FormatterException moduleDirectiveListRecoveryFailure(String reason) {
        return new FormatterException(MODULE_DIRECTIVE_LIST_RECOVERY_FAILURE + reason);
    }

    private static FormatterException moduleDirectiveListRecoveryFailure(String reason, Throwable cause) {
        return new FormatterException(MODULE_DIRECTIVE_LIST_RECOVERY_FAILURE + reason, cause);
    }

    /**
     * Dispatches the Java module directive variants that have normal structured formatting rules.
     *
     * <p>Unknown directive nodes fall back to compact source text so future JavaParser directive kinds are still
     * printable without this helper claiming a layout policy for them.
     */
    private Doc moduleDirective(ModuleDirective directive) {
        Doc body = switch (directive) {
            case ModuleRequiresDirective requiresDirective -> moduleRequiresDirective(requiresDirective);
            case ModuleExportsDirective exportsDirective -> moduleAccessDirective(
                "exports",
                exportsDirective.getName(),
                "to",
                exportsDirective.getModuleNames()
            );
            case ModuleOpensDirective opensDirective -> moduleAccessDirective(
                "opens",
                opensDirective.getName(),
                "to",
                opensDirective.getModuleNames()
            );
            case ModuleUsesDirective usesDirective -> Doc.text("uses " + compact.apply(usesDirective.getName()) + ";");
            case ModuleProvidesDirective providesDirective -> moduleAccessDirective(
                "provides",
                providesDirective.getName(),
                "with",
                providesDirective.getWith()
            );
            default -> Doc.text(compact.apply(directive));
        };
        return Doc.concat(comments.leading(directive), body);
    }

    /**
     * Prints {@code requires}, preserving the caller's modifier ordering for {@code transitive} and other modifiers.
     */
    private Doc moduleRequiresDirective(ModuleRequiresDirective directive) {
        return Doc.text("requires " + requiresModifiers.apply(directive) + compact.apply(directive.getName()) + ";");
    }

    /**
     * Prints target-bearing module directives using the widest layout that fits within the configured line width.
     *
     * <p>The first broken form keeps {@code to ...;} or {@code with ...;} on one continuation line. The deeper broken
     * form is reserved for long multi-target lists, where each target gets its own continuation line under the target
     * keyword.
     */
    private Doc moduleAccessDirective(
            String keyword,
            Name name,
            String targetKeyword,
            NodeList<Name> targets
    ) {
        String prefix = keyword + " " + compact.apply(name);
        if (targets.isEmpty()) {
            return Doc.text(prefix + ";");
        }
        String flatTargets = compactJoin.apply(targets);
        String flat = prefix + " " + targetKeyword + " " + flatTargets + ";";
        if (currentIndentedWidth(flat) <= options.lineWidth()) {
            return Doc.text(flat);
        }
        String targetLine = targetKeyword + " " + flatTargets + ";";
        if (currentIndentedWidth(targetLine) <= options.lineWidth()) {
            return Doc.concat(Doc.text(prefix), Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(targetLine))));
        }
        return Doc.concat(
            Doc.text(prefix),
            Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    Doc.text(targetKeyword),
                    Doc.indent(
                        Doc.concat(
                            Doc.HARD_LINE,
                            Doc.join(
                                Doc.concat(Doc.text(","), Doc.HARD_LINE),
                                targets.stream().map(target -> Doc.text(compact.apply(target))).toList()
                            ),
                            Doc.text(";")
                        )
                    )
                )
            )
        );
    }

    private int currentIndentedWidth(String text) {
        return options.indentUnit().length() + text.length();
    }

    private enum EntryKind {
        /**
         * No recovered module directive entry has been emitted yet.
         */
        NONE,

        /**
         * The previous entry was a normally formatted valid module directive.
         */
        VALID_DIRECTIVE,

        /**
         * The previous entry was raw source whose final line break is still present in that source.
         */
        RAW_GAP,

        /**
         * The previous entry was raw source whose final line break moved to formatter-owned output.
         */
        RAW_GAP_WITH_TRAILING_BREAK,
    }
}
