package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.function.ToIntFunction;

/**
 * Wires statement-formatting helpers behind the statement rule envelope.
 *
 * <p>This composer owns the construction order for block sequencing, reusable switch formatting, control-condition
 * rendering, structured statement dispatch, and the statement-level pragma/comment/raw envelope. The boundary exists so
 * statement grammar remains local while the top-level printer coordinates only the three broad formatter groups.
 *
 * <p>Callers still provide expression rendering, declaration-body rendering, local-variable rendering, and shared type
 * compacting callbacks. This composer leaves expression and declaration layout decisions with their existing owners and
 * only assembles the statement-side graph that calls those decisions.
 */
final class StatementPrinters {

    private final JavaFormatContext context;

    private final BlockPrinter blocks;

    private final StatementPrinter statements;

    private final SwitchPrinter switches;

    private final ControlConditionPrinter controlConditions;

    private final StatementRuleEnvelope statementRules;

    StatementPrinters(
            JavaFormatContext context,
            TypePrinter types,
            ExpressionPrinters expressions,
            DeclarationPrinters declarations
    ) {
        this.context = context;
        FormatterOptions options = context.options;
        CommentTracker comments = context.comments;
        JavaCommentPlacementPolicy commentPlacementPolicy = context.commentPlacementPolicy;
        FormatterPragmas formatterPragmas = context.formatterPragmas;
        RawSource rawSource = context.rawSource;
        CompactSourceText compactSource = context.compactSource;
        CommentPlacement commentPlacement = context.commentPlacement;
        this.blocks = new BlockPrinter(context, (statement, layout) -> statement(statement), formatterPragmas::hasPragma);
        this.controlConditions = new ControlConditionPrinter(
            comments,
            commentPlacementPolicy,
            context.sourceText,
            context.sourceShapePolicy,
            options,
            expressions::expression,
            compactSource::compact,
            compactSource::compactJoin,
            compactSource::compactWithoutOwnComment,
            expressions::expressionHasParenthesizedNestedBinary,
            expression ->
                expression instanceof BinaryExpr binaryExpr && expressions.binaryHasLineComments(binaryExpr)
                    ? expressions.binaryLinesWithComments(binaryExpr)
                    : expressions.binaryConditionLines(expression, true),
            expressions::forcedMethodCallChain,
            this::currentIndentedWidth,
            this::blockStatementWidth,
            context.layoutWidth,
            context.layoutDecisions
        );
        this.switches = new SwitchPrinter(
            context,
            (statement, layout) -> statement(statement),
            expressions::expressionWithTail,
            (block, layout) -> block(block),
            blocks::statementSeparator,
            controlConditions,
            expressions::binaryLines,
            declarations::modifiers,
            this::currentIndentedWidth,
            this::blockStatementWidth
        );
        this.statements = new StatementPrinter(
            comments,
            commentPlacementPolicy,
            rawSource,
            context.sourceShapePolicy,
            options,
            context.layoutWidth,
            (statement, layout) -> statement(statement),
            (switchStatement, layout) -> switches.switchStatement(switchStatement),
            (block, layout) -> block(block),
            blocks::blockWithLeading,
            (declaration, layout) -> declarations.body(declaration),
            (expression, layout) -> expressions.expression(expression),
            expressions::expressionWithTail,
            expressions::assignmentStatement,
            expressions::returnStatement,
            expressions::objectCreationWithSuffix,
            (declaration, layout) -> declarations.variableDeclaration(declaration),
            (declaration, layout) -> declarations.variableDeclarationStatement(declaration),
            declarations::parameterText,
            compactSource::compact,
            compactSource::compactWithoutOwnComment,
            compactSource::compactJoin,
            compactSource::compactTypeLike,
            types::compactJoinTypeLike,
            declarations::modifiers,
            expressions::annotationFlatText,
            expressions::huggableBlockLambdaArguments,
            expressions::statementChain,
            expressions::brokenMethodCall,
            controlConditions::ifCondition,
            controlConditions,
            controlConditions::compactWithOwnBlockComment,
            commentPlacement::ownSameLineBlockCommentBeforeNode,
            expressions::methodCallArgumentList,
            new CommentedExpressionListPrinter(context, expressions::expression),
            this::currentIndentedWidth
        );
        this.statementRules = new StatementRuleEnvelope(
            comments,
            commentPlacementPolicy,
            formatterPragmas,
            context.rawPreservedSource,
            (statement, layout) -> statements.statement(statement)
        );
    }

    Doc statement(Statement statement) {
        return statementRules.statement(statement);
    }

    Doc statement(Statement statement, ToIntFunction<String> lineWidth) {
        return statementRules.statement(statement, (current, layout) -> statements.statement(current, lineWidth));
    }

    Doc block(BlockStmt block) {
        return blocks.block(block);
    }

    Doc methodChainLambdaBlock(BlockStmt block) {
        // Load-bearing depth signal: a statement inside a block-lambda argument nested under a broken method chain
        // renders about five indentation units deep, which the block/type-only nodeLine cannot see. Threading the
        // methodChainLambdaBody width measure keeps the statement's flat-gate probes measuring at that true column.
        return blocks.block(
            block,
            (statement, layout) -> statement(statement, context.layoutWidth::methodChainLambdaBody)
        );
    }

    Doc switchExpression(SwitchExpr expression) {
        return switches.switchExpression(expression);
    }

    private int currentIndentedWidth(String text) {
        return context.layoutWidth.currentIndented(text);
    }

    private int blockStatementWidth(String text) {
        return context.layoutWidth.blockStatement(text);
    }
}
