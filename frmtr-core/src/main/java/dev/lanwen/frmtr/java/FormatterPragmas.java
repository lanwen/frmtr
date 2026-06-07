package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;

/**
 * Tracks formatter pragmas that are attached to syntax nodes and translates them into raw-vs-formatted print
 * actions.
 *
 * <p>This class owns the persistent enabled/disabled state created by range pragmas and single-node ignore pragmas. It
 * deliberately does not decide how raw source is recovered, how leading/trailing comments are printed, or which
 * JavaPrinter dispatch path renders a node when formatting remains enabled.
 */
final class FormatterPragmas {
    private boolean formattingDisabled;

    /**
     * Returns the print action for a body declaration after applying any pragma carried by that declaration.
     *
     * <p>Single-node ignore pragmas raw-pass only the declaration that carries them, while range pragmas update
     * persistent formatter state for later declarations.
     */
    PrintAction bodyAction(Node node) {
        return switch (pragma(node)) {
            case ON, END -> {
                formattingDisabled = false;
                yield PrintAction.FORMAT_WITH_LEADING;
            }
            case OFF, START -> {
                formattingDisabled = true;
                yield PrintAction.RAW;
            }
            case IGNORE -> PrintAction.RAW;
            case NONE -> formattingDisabled ? PrintAction.RAW : PrintAction.FORMAT;
        };
    }

    /**
     * Returns the print action for a statement after applying any pragma carried by that statement.
     *
     * <p>Single-node ignore pragmas have a statement-specific newline side effect in the existing printer, so the
     * returned action preserves that distinction without making this class responsible for constructing docs.
     */
    PrintAction statementAction(Node node) {
        return switch (pragma(node)) {
            case ON, END -> {
                formattingDisabled = false;
                yield PrintAction.FORMAT;
            }
            case OFF, START -> {
                formattingDisabled = true;
                yield PrintAction.RAW;
            }
            case IGNORE -> PrintAction.RAW_WITH_TRAILING_HARD_LINE;
            case NONE -> formattingDisabled ? PrintAction.RAW : PrintAction.FORMAT;
        };
    }

    /**
     * Reports whether a node carries any recognized pragma, without changing the persistent formatter state.
     *
     * <p>Separators use this as a source-layout hint; they must not toggle formatting while looking ahead or back.
     */
    boolean hasPragma(Node node) {
        return pragma(node) != Pragma.NONE;
    }

    private Pragma pragma(Node node) {
        return node.getComment()
                .map(comment -> {
                    String content = comment.getContent();
                    if (content.contains("@formatter:off")) {
                        return Pragma.OFF;
                    }
                    if (content.contains("@formatter:on")) {
                        return Pragma.ON;
                    }
                    if (content.contains("prettier-ignore-start")) {
                        return Pragma.START;
                    }
                    if (content.contains("prettier-ignore-end")) {
                        return Pragma.END;
                    }
                    if (content.contains("prettier-ignore")) {
                        return Pragma.IGNORE;
                    }
                    return Pragma.NONE;
                })
                .orElse(Pragma.NONE);
    }

    enum PrintAction {
        /** Format the current node normally because formatting is enabled after applying its pragma state. */
        FORMAT,

        /** Format a declaration after preserving the legacy explicit leading-comment prefix for enable pragmas. */
        FORMAT_WITH_LEADING,

        /** Emit the current node from raw source because a pragma or active disabled range suppresses formatting. */
        RAW,

        /** Emit the current statement from raw source and preserve the legacy extra hard line after an ignore pragma. */
        RAW_WITH_TRAILING_HARD_LINE
    }

    private enum Pragma {
        /** Disables formatter output from this node onward and raw-passes the node that carries the pragma. */
        OFF,

        /** Re-enables formatter output from this node onward and formats the node that carries the pragma. */
        ON,

        /** Starts an ignore range and raw-passes the node that carries the pragma. */
        START,

        /** Ends an ignore range and formats the node that carries the pragma. */
        END,

        /** Raw-passes only the node that carries the pragma without changing later formatter state. */
        IGNORE,

        /** Marks nodes with no recognized formatter pragma so the current persistent state decides the action. */
        NONE
    }
}
