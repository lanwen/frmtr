package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;

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
        this.blocks = new BlockPrinter(context, this::statement, formatterPragmas::hasPragma);
        this.controlConditions = new ControlConditionPrinter(
            comments,
            context.sourceShape,
            options,
            expressions::expression,
            compactSource::compact,
            compactSource::compactJoin,
            compactSource::compactWithoutOwnComment,
            expressions::expressionHasParenthesizedNestedBinary,
            expression -> expressions.binaryConditionLines(expression, true),
            expressions::forcedMethodCallChain,
            this::currentIndentedWidth,
            this::blockStatementWidth,
            context.layoutDecisions
        );
        this.switches = new SwitchPrinter(
            context,
            this::statement,
            expressions::expressionWithTail,
            this::block,
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
            context.sourceShape,
            options,
            context.layoutWidth,
            this::statement,
            switches::switchStatement,
            this::block,
            blocks::blockWithLeading,
            declarations::body,
            expressions::expression,
            expressions::expressionWithTail,
            expressions::assignmentStatement,
            expressions::returnStatement,
            expressions::objectCreationWithSuffix,
            declarations::variableDeclaration,
            declarations::variableDeclarationStatement,
            declarations::parameterText,
            compactSource::compact,
            compactSource::compactWithoutOwnComment,
            compactSource::compactJoin,
            compactSource::compactTypeLike,
            types::compactJoinTypeLike,
            expressions::huggableBlockLambdaArguments,
            expressions::sourceMultilineMethodCallStatement,
            expressions::forcedMethodCallChain,
            (methodCall, lineBudget) -> expressions.forcedMethodCallWithTail(
                methodCall,
                ExpressionTail.SEMICOLON,
                lineBudget
            ),
            expressions::brokenMethodCall,
            expressions::methodCallChainHasComments,
            expressions::methodCallChainHasFinalTrailingLineComment,
            expressions::methodCallChainIsSourceMultiline,
            expressions::methodCallChainRootIsObjectCreation,
            expressions::methodCallChainRootIsFieldAccess,
            controlConditions::ifCondition,
            controlConditions,
            controlConditions::compactWithOwnBlockComment,
            commentPlacement::ownSameLineBlockCommentBeforeNode,
            expressions::methodCallArgumentList,
            this::currentIndentedWidth
        );
        this.statementRules = new StatementRuleEnvelope(
            comments,
            commentPlacementPolicy,
            formatterPragmas,
            context.rawPreservedSource,
            statements::statement
        );
    }

    Doc statement(Statement statement) {
        return statementRules.statement(statement);
    }

    Doc statement(Statement statement, LayoutWidth.LineBudget lineBudget) {
        return statementRules.statement(statement, current -> statements.statement(current, lineBudget));
    }

    Doc block(BlockStmt block) {
        return blocks.block(block);
    }

    Doc methodChainLambdaBlock(BlockStmt block) {
        return blocks.block(
            block,
            statement -> statement(statement, LayoutWidth.LineBudget.METHOD_CHAIN_LAMBDA_BODY)
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
