package dev.lanwen.frmtr.java;

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
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
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
 * <p>The fixture pair {@code format/prettier-java/unit-test/modules/input.java} and {@code
 * format/prettier-java/unit-test/modules/frmtr.output.java} shows the expected structured module behavior.
 */
final class ModuleBlockPrinter {
    private final CommentTracker comments;
    private final FormatterOptions options;
    private final Function<Node, String> compact;
    private final Function<List<? extends Node>, String> compactJoin;
    private final Function<ModuleRequiresDirective, String> requiresModifiers;

    ModuleBlockPrinter(
            CommentTracker comments,
            FormatterOptions options,
            Function<Node, String> compact,
            Function<List<? extends Node>, String> compactJoin,
            Function<ModuleRequiresDirective, String> requiresModifiers) {
        this.comments = comments;
        this.options = options;
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
        if (declaration.getDirectives().isEmpty()) {
            return Doc.text("{}");
        }
        return Doc.concat(
                Doc.text("{"),
                Doc.indent(Doc.concat(Doc.HARD_LINE, moduleContents(declaration.getDirectives()))),
                Doc.HARD_LINE,
                Doc.text("}"));
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
                .flatMap(previousRange -> current.getRange()
                        .map(currentRange -> currentRange.begin.line > previousRange.end.line + 1))
                .orElse(false);
        return hasBlankLineBetween ? Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE) : Doc.HARD_LINE;
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
                    "exports", exportsDirective.getName(), "to", exportsDirective.getModuleNames());
            case ModuleOpensDirective opensDirective -> moduleAccessDirective(
                    "opens", opensDirective.getName(), "to", opensDirective.getModuleNames());
            case ModuleUsesDirective usesDirective -> Doc.text("uses " + compact.apply(usesDirective.getName()) + ";");
            case ModuleProvidesDirective providesDirective -> moduleAccessDirective(
                    "provides", providesDirective.getName(), "with", providesDirective.getWith());
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
            NodeList<Name> targets) {
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
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        Doc.text(targetKeyword),
                        Doc.indent(Doc.concat(
                                Doc.HARD_LINE,
                                Doc.join(
                                        Doc.concat(Doc.text(","), Doc.HARD_LINE),
                                        targets.stream().map(target -> Doc.text(compact.apply(target))).toList()),
                                Doc.text(";"))))));
    }

    private int currentIndentedWidth(String text) {
        return options.indentUnit().length() + text.length();
    }
}
