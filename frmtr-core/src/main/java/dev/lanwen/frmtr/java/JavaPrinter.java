package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import dev.lanwen.frmtr.doc.PrinterWrap;
import java.util.List;

final class JavaPrinter {

    private final JavaFormatContext context;

    private final ExpressionPrinters expressions;

    private final DeclarationPrinters declarations;

    private final StatementPrinters statements;

    JavaPrinter(FormatterOptions options, SourceText sourceText, boolean recoverParseProblems) {
        this.context = new JavaFormatContext(options, sourceText, recoverParseProblems);
        TypePrinter types = new TypePrinter(options, context.compactSource::compactTypeLike);
        this.expressions = new ExpressionPrinters(
            context,
            types,
            (statement, layout) -> statement(statement),
            (block, layout) -> block(block),
            (block, layout) -> methodChainLambdaBlock(block),
            (declaration, layout) -> body(declaration),
            (expression, layout) -> switchExpression(expression),
            this::commentText
        );
        this.declarations = new DeclarationPrinters(
            context,
            types,
            expressions,
            this::block,
            this::commentText
        );
        this.statements = new StatementPrinters(context, types, expressions, declarations);
    }

    Doc print(CompilationUnit unit) {
        context.startCommentRun(unit);
        // Record-only dry-run: run the real print traversal once with comment claims recorded (not committed) so each
        // comment's first claimant is captured as its (node, slot) owner. The produced document and its width-decision
        // log are scratch and discarded; only the ownership map survives. The placement policy, comment map, and width
        // caches were built once in startCommentRun above and are reused below, so this is ~2x print, not 2x parse.
        context.comments.beginRecording();
        declarations.compilationUnit(unit);
        context.comments.endRecordingAndReset(context.layoutDecisions, context.formatterPragmas);
        Doc doc = declarations.compilationUnit(unit);
        context.comments.assertAllCommentsAccounted(unit);
        return doc;
    }

    /**
     * Returns the width decisions the printers recorded while building the document for this run.
     *
     * <p>Valid only after {@link #print(CompilationUnit)}; it lets explain recover the printer-side width arithmetic
     * the renderer never sees, without rebuilding the document.
     */
    List<PrinterWrap> layoutDecisions() {
        return context.layoutDecisions.wraps();
    }

    /**
     * Exposes the method-call ladder's exploded-argument-list builder so a test can call it directly against a node
     * from a compilation unit this printer already formatted, without rebuilding the whole printer graph by hand.
     */
    Doc explodedArgumentList(String prefix, NodeList<Expression> arguments, String tailText, MethodCallBreakMode breakMode) {
        return expressions.explodedArgumentList(prefix, arguments, tailText, breakMode);
    }

    private Doc body(BodyDeclaration<?> declaration) {
        return declarations.body(declaration);
    }

    private Doc block(BlockStmt block) {
        return statements.block(block);
    }

    private Doc methodChainLambdaBlock(BlockStmt block) {
        return statements.methodChainLambdaBlock(block);
    }

    private Doc statement(Statement statement) {
        return statements.statement(statement);
    }

    private Doc switchExpression(SwitchExpr expression) {
        return statements.switchExpression(expression);
    }

    private String commentText(Doc comment) {
        if (comment instanceof Doc.Text(String value)) {
            return value;
        }
        return "";
    }
}
