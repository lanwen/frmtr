package dev.lanwen.frmtr.java;

import dev.lanwen.frmtr.doc.Doc;

/**
 * Carries a packed expression-lambda body with the call-closing placement selected by the body planner.
 *
 * <p>This helper exists so {@link ExpressionLambdaArgumentLayout} can decide which body shape wins without also owning
 * the repeated suffix rendering branches. The caller still chooses the body doc and closing suffix; this value only
 * remembers whether the closing token attaches to the body line or moves to its own line.
 */
record PackedLambdaBody(Doc doc, String closingSuffix, Placement placement) {
    static PackedLambdaBody closingOnOwnLine(Doc doc, String closingSuffix) {
        return new PackedLambdaBody(doc, closingSuffix, Placement.CLOSING_ON_OWN_LINE);
    }

    static PackedLambdaBody attachedClosing(Doc doc, String closingSuffix) {
        return new PackedLambdaBody(doc, closingSuffix, Placement.ATTACHED_CLOSING);
    }

    Doc render(String firstLine) {
        return switch (placement) {
            case CLOSING_ON_OWN_LINE -> Doc.concat(
                Doc.text(firstLine + " "),
                Doc.indent(doc),
                Doc.HARD_LINE,
                Doc.text(closingSuffix)
            );
            case ATTACHED_CLOSING -> Doc.concat(Doc.text(firstLine + " "), doc, Doc.text(closingSuffix));
        };
    }

    enum Placement {
        /**
         * Places the call-closing suffix on its own line after an indented lambda body.
         */
        CLOSING_ON_OWN_LINE,

        /**
         * Keeps the call-closing suffix attached to the lambda body line.
         */
        ATTACHED_CLOSING,
    }
}
