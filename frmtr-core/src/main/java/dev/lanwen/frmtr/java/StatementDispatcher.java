package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchStmt;
import dev.lanwen.frmtr.doc.Doc;

/**
 * Routes formatted statement content to the helper that owns the selected statement grammar.
 *
 * <p>This helper owns only the switch-vs-non-switch decision after {@link StatementRuleEnvelope} has decided that a
 * statement should be formatted structurally. The boundary keeps {@link StatementPrinter} free of switch routing while
 * also keeping formatter pragma state, raw-source recovery, and statement comment attachment out of the content
 * dispatcher.
 *
 * <p>Callers still choose when statement content rendering is allowed and provide the switch and ordinary statement
 * rules. Switch labels, guards, entries, and switch bodies stay with {@link SwitchPrinter}; all other statement bodies
 * stay with {@link StatementPrinter}.
 */
final class StatementDispatcher {
    private final JavaFormatRule<Statement> statements;
    private final JavaFormatRule<SwitchStmt> switches;

    StatementDispatcher(
            JavaFormatRule<Statement> statements,
            JavaFormatRule<SwitchStmt> switches) {
        this.statements = statements;
        this.switches = switches;
    }

    /**
     * Chooses the structured content rule for a statement whose envelope has already allowed formatting.
     *
     * <p>{@link SwitchStmt} routes to the switch grammar because statement and expression switches share labels,
     * guards, entries, and source-only fallback cases. Every other statement routes to the non-switch statement printer.
     */
    Doc statementContent(Statement statement) {
        return statement instanceof SwitchStmt switchStmt
                ? switches.format(switchStmt)
                : statements.format(statement);
    }
}
