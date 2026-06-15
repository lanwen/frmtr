package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;

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
            this::statement,
            this::block,
            this::methodChainLambdaBlock,
            this::body,
            this::switchExpression,
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
    java.util.List<dev.lanwen.frmtr.doc.PrinterWrap> layoutDecisions() {
        return context.layoutDecisions.wraps();
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
