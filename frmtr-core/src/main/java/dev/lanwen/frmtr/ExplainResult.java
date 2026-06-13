package dev.lanwen.frmtr;

import dev.lanwen.frmtr.doc.DocExplanation;

/**
 * Pairs the formatter's actual output with an explanation of the layout decisions that produced it.
 *
 * <p>This is the public result of {@link Frmtr#explain(String)}. It exists so a single parse and print can serve both
 * "what did the formatter produce" and "why" without callers re-parsing. The {@code formatted} string is exactly what
 * {@link Frmtr#format(String)} would return for the same input and options, so explain never diverges from formatting:
 * it observes the same render, it does not influence it.
 *
 * @param formatted the formatted source, identical to {@link Frmtr#format(String)} output for the same input
 * @param explanation the per-group break/flat decisions and rule-label tree behind that output
 */
public record ExplainResult(String formatted, DocExplanation explanation) {}
