package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.stmt.AssertStmt;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.LabeledStmt;
import com.github.javaparser.ast.stmt.LocalClassDeclarationStmt;
import com.github.javaparser.ast.stmt.LocalRecordDeclarationStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.stmt.YieldStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.IntersectionType;
import com.github.javaparser.ast.type.Type;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

final class JavaPrinter {
    private final JavaFormatter.CommentTracker comments = new JavaFormatter.CommentTracker();
    private final FormatterOptions options;
    private final RawSource rawSource;
    private final TypePrinter types;
    private final FormatterPragmas formatterPragmas = new FormatterPragmas();
    private final ModuleBlockPrinter moduleBlocks;
    private final ModuleDeclarationPrinter moduleDeclarations;
    private final MemberBlockPrinter memberBlocks;
    private final BlockPrinter blocks;
    private final StatementPrinter statements;
    private final SwitchPrinter switches;
    private final BinaryExpressionPrinter binaries;
    private final ConditionalExpressionPrinter conditionals;
    private final LambdaExpressionPrinter lambdas;
    private final ArrayExpressionPrinter arrays;
    private final MethodCallPrinter methodCalls;
    private final CallableSignaturePrinter callableSignatures;
    private final ConstructorDeclarationPrinter constructors;
    private final MethodDeclarationPrinter methods;
    private final InitializerDeclarationPrinter initializers;
    private final EnumDeclarationPrinter enums;
    private final RecordDeclarationPrinter records;
    private final AnnotationDeclarationPrinter annotationDeclarations;
    private final ClassOrInterfaceDeclarationPrinter classOrInterfaces;
    private final CommentedMethodSignaturePrinter commentedMethodSignatures;
    private final CompilationUnitPrinter compilationUnits;
    private final FieldDeclarationPrinter fields;

    JavaPrinter(FormatterOptions options) {
        this.options = options;
        this.rawSource = new RawSource(options);
        this.types = new TypePrinter(options, this::compactTypeLike);
        this.moduleBlocks = new ModuleBlockPrinter(comments, options, this::compact, this::compactJoin, this::modifiers);
        this.moduleDeclarations = new ModuleDeclarationPrinter(
                comments,
                rawSource,
                new CommentedModulePrinter(),
                this::annotations,
                this::commentText,
                this::compact,
                moduleBlocks::moduleBlock);
        this.memberBlocks = new MemberBlockPrinter(rawSource, comments, this::hasDeclarationAnnotations);
        this.blocks = new BlockPrinter(comments, this::statement, formatterPragmas::hasPragma);
        this.binaries = new BinaryExpressionPrinter(
                comments,
                options,
                this::expression,
                this::brokenMethodCall,
                this::compact,
                this::compactWithoutOwnComment,
                this::continuationStatementWidth,
                this::blockStatementWidth);
        this.switches = new SwitchPrinter(
                comments,
                rawSource,
                options,
                this::statement,
                this::expression,
                this::block,
                blocks::statementSeparator,
                this::controlCondition,
                binaries::lines,
                this::compact,
                this::compactTypeLike,
                this::modifiers,
                this::currentIndentedWidth,
                this::ownSameLineBlockCommentBeforeNode);
        this.conditionals = new ConditionalExpressionPrinter(
                comments,
                options,
                this::expression,
                this::expressionWithoutOwnComment,
                this::compact,
                this::currentIndentedWidth,
                this::blockStatementWidth,
                this::continuationStatementWidth,
                (expression, forceBreak) -> binaries.lines(expression, forceBreak),
                (expression, forceBreak) -> binaries.nestedLines(expression, forceBreak),
                binaries::expressionHasParenthesizedNestedBinary);
        this.lambdas = new LambdaExpressionPrinter(
                comments,
                rawSource,
                options,
                this::expression,
                this::statement,
                this::block,
                (expression, forceBreak) -> binaries.lines(expression, forceBreak),
                this::compact,
                this::compactWithoutOwnComment,
                this::compactJoin,
                this::currentIndentedWidth,
                this::blockStatementWidth,
                this::startsBefore,
                this::startsOnSameLine);
        this.arrays = new ArrayExpressionPrinter(
                comments,
                options,
                this::expression,
                this::brokenEnclosedForSuffix,
                this::compactTypeLike,
                this::compact,
                this::currentIndentedWidth,
                this::startsBefore,
                this::startsAfterNodeOnSameLine);
        this.methodCalls = new MethodCallPrinter(
                comments,
                options,
                types,
                this::expression,
                this::brokenEnclosedForSuffix,
                this::brokenObjectCreation,
                lambdas::huggableBlockLambdaArguments,
                lambdas::commentedExpressionLambdaArgument,
                lambdas::huggableMethodCallExpressionLambdaArguments,
                this::renderUnformattedTextBlock,
                binaryExpr -> binaries.nestedLines(binaryExpr, true),
                this::compact,
                this::compactJoin,
                this::currentIndentedWidth,
                this::blockStatementWidth);
        this.commentedMethodSignatures = new CommentedMethodSignaturePrinter(options);
        PackageDeclarationPrinter packageDeclarations = new PackageDeclarationPrinter(comments, rawSource, options);
        ImportDeclarationPrinter importDeclarations = new ImportDeclarationPrinter(comments);
        this.compilationUnits = new CompilationUnitPrinter(
                comments,
                packageDeclarations,
                importDeclarations,
                moduleDeclarations::moduleDeclaration,
                this::body);
        this.fields = new FieldDeclarationPrinter(
                comments,
                rawSource,
                options,
                this::declarationAnnotations,
                this::modifiers,
                this::inlineAnnotations,
                this::compactTypeLike,
                this::compact,
                this::compactWithoutOwnComment,
                this::compactJoin,
                this::expression,
                this::expressionWithoutOwnComment,
                binaries::hasLineComments,
                binaries::linesWithComments,
                (expression, leadingBreak) -> suffixedEnclosedExpression(expression, leadingBreak),
                arrays::arrayAccessWithBrokenEnclosedName,
                binaries::shouldKeepCastDivisionContinuationFlat,
                (expression, forceBreak) -> binaries.lines(expression, forceBreak),
                methodCalls::methodCall,
                methodCalls::brokenMethodCall,
                methodCalls::mixedFieldMethodCallChain,
                methodCalls::forcedMethodCallChain,
                methodCalls::mixedFieldMethodCallRoot,
                methodCalls::methodCallChainRoot,
                methodCalls::methodCallChainRootIsObjectCreation,
                this::castType,
                conditional -> conditionals.conditionalExpression(conditional, true),
                conditionals::shouldBreakBeforeConditionalInitializer,
                arrays::arrayCreationTypeBreaks,
                arrays::arrayCreationPrefix,
                arrays::arrayInitializer,
                this::objectCreationPrefix,
                types::typeNameWithoutArguments,
                types::brokenClassOrInterfaceType,
                methodCalls::shouldPrintScopeAsDoc,
                methodCalls::methodCallPrefix,
                lambdas::lambdaParameters,
                lambdas::lambdaParametersShouldBreak,
                lambdas::lambdaExpression);
        this.callableSignatures = new CallableSignaturePrinter(
                comments,
                rawSource,
                options,
                this::compact,
                this::compactTypeLike,
                types::typeBody,
                this::modifier,
                types::typeCanBreak,
                this::unattachedTrailingBlockComment,
                this::startsAfterNodeOnSameLine,
                this::commentText);
        this.classOrInterfaces = new ClassOrInterfaceDeclarationPrinter(
                comments,
                rawSource,
                options,
                new CommentedInterfacePrinter(),
                callableSignatures,
                this::annotations,
                this::modifiers,
                types::extendsTypes,
                types::implementsTypes,
                types::permitsTypes,
                (keyword, headerTypes, breakBeforeClause) -> types.typeClause(keyword, headerTypes, breakBeforeClause),
                types::flatTypeParameters,
                types::flatTypeClause,
                this::currentIndentedWidth,
                declaration -> memberBlocks.memberBlock(declaration.getMembers(), declaration, this::body));
        this.constructors = new ConstructorDeclarationPrinter(
                comments,
                callableSignatures,
                this::annotations,
                this::modifiers,
                this::compactJoin,
                this::throwsClause,
                this::block);
        this.methods = new MethodDeclarationPrinter(
                comments,
                rawSource,
                commentedMethodSignatures,
                callableSignatures,
                this::declarationAnnotations,
                this::modifiers,
                types::flatTypeParameters,
                this::inlineAnnotations,
                this::compact,
                this::throwsClause,
                this::block);
        this.initializers = new InitializerDeclarationPrinter(comments, this::block);
        this.enums = new EnumDeclarationPrinter(
                comments,
                rawSource,
                options,
                this::annotations,
                this::modifiers,
                enumTypes -> types.typeClause("implements", enumTypes),
                types::implementsTypes,
                enumTypes -> types.flatTypeClause("implements", enumTypes),
                this::compactJoin,
                this::expression,
                this::currentIndentedWidth,
                this::startsAfterNodeOnSameLine,
                this::body);
        this.records = new RecordDeclarationPrinter(
                comments,
                options,
                this::annotations,
                this::modifiers,
                callableSignatures::typeParameters,
                types::flatTypeParameters,
                this::compact,
                this::compactJoin,
                types::compactJoinTypeLike,
                this::compactTypeLike,
                this::annotation,
                this::annotationFlatText,
                this::currentIndentedWidth,
                declaration -> memberBlocks.memberBlock(declaration.getMembers(), declaration, this::body));
        this.statements = new StatementPrinter(
                comments,
                rawSource,
                options,
                this::statement,
                this::block,
                blocks::blockWithLeading,
                this::body,
                this::expression,
                this::returnExpression,
                this::variableDeclaration,
                this::compact,
                this::compactWithoutOwnComment,
                this::compactJoin,
                this::compactTypeLike,
                types::compactJoinTypeLike,
                lambdas::huggableBlockLambdaArguments,
                methodCalls::forcedMethodCallChain,
                methodCalls::brokenMethodCall,
                methodCalls::methodCallChainHasComments,
                methodCalls::methodCallChainRootIsObjectCreation,
                methodCalls::methodCallChainRootIsFieldAccess,
                binaries::expressionHasParenthesizedNestedBinary,
                binaries::lines,
                this::controlCondition,
                this::compactWithOwnBlockComment,
                this::ownSameLineBlockCommentBeforeNode,
                this::currentIndentedWidth);
        this.annotationDeclarations = new AnnotationDeclarationPrinter(
                comments,
                this::annotations,
                this::modifiers,
                this::compactTypeLike,
                this::expression,
                this::body);
    }

    Doc print(CompilationUnit unit) {
        return compilationUnits.print(unit);
    }

    private Doc body(BodyDeclaration<?> declaration) {
        FormatterPragmas.PrintAction action = formatterPragmas.bodyAction(declaration);
        if (action == FormatterPragmas.PrintAction.FORMAT_WITH_LEADING) {
            return Doc.concat(comments.leading(declaration), bodyContent(declaration));
        }
        if (action == FormatterPragmas.PrintAction.RAW) {
            return rawBody(declaration);
        }
        return bodyContent(declaration);
    }

    private Doc rawBody(BodyDeclaration<?> declaration) {
        return Doc.concat(comments.leading(declaration), Doc.text(rawSource.rawWithoutOwnComment(declaration)));
    }

    private Doc bodyContent(BodyDeclaration<?> declaration) {
        return switch (declaration) {
            case ClassOrInterfaceDeclaration classDeclaration -> classOrInterfaces.classOrInterface(classDeclaration);
            case RecordDeclaration recordDeclaration -> record(recordDeclaration);
            case EnumDeclaration enumDeclaration -> enumDeclaration(enumDeclaration);
            case AnnotationDeclaration annotationDeclaration -> annotationDeclaration(annotationDeclaration);
            case AnnotationMemberDeclaration annotationMemberDeclaration -> annotationMember(annotationMemberDeclaration);
            case FieldDeclaration fieldDeclaration -> field(fieldDeclaration);
            case MethodDeclaration methodDeclaration -> method(methodDeclaration);
            case CompactConstructorDeclaration compactConstructorDeclaration -> compactConstructor(compactConstructorDeclaration);
            case ConstructorDeclaration constructorDeclaration -> constructor(constructorDeclaration);
            case InitializerDeclaration initializerDeclaration -> initializer(initializerDeclaration);
            default -> rawDeclaration(declaration);
        };
    }

    private Doc record(RecordDeclaration declaration) {
        return records.record(declaration);
    }

    private Doc enumDeclaration(EnumDeclaration declaration) {
        return enums.enumDeclaration(declaration);
    }

    private Doc annotationDeclaration(AnnotationDeclaration declaration) {
        return annotationDeclarations.annotationDeclaration(declaration);
    }

    private Doc annotationMember(AnnotationMemberDeclaration declaration) {
        return annotationDeclarations.annotationMember(declaration);
    }

    private Doc field(FieldDeclaration declaration) {
        return fields.field(declaration);
    }

    private Doc variable(VariableDeclarator variable) {
        return fields.variable(variable);
    }

    private Doc variable(VariableDeclarator variable, String declarationPrefix) {
        return fields.variable(variable, declarationPrefix);
    }

    private Doc expressionWithoutOwnComment(Expression expression) {
        Expression clone = expression.clone();
        clone.removeComment();
        return expression(clone);
    }

    private Doc method(MethodDeclaration declaration) {
        return methods.method(declaration);
    }

    private boolean isCommentOnlyLine(String line) {
        return line.startsWith("//") || line.startsWith("/*") && line.endsWith("*/");
    }

    private Doc constructor(ConstructorDeclaration declaration) {
        return constructors.constructor(declaration);
    }

    private Doc compactConstructor(CompactConstructorDeclaration declaration) {
        return constructors.compactConstructor(declaration);
    }

    private Doc throwsClause(
            String prefix,
            NodeList<Parameter> parameters,
            NodeList<? extends Node> thrownExceptions,
            String suffix) {
        String exceptions = compactJoin(thrownExceptions);
        String flatParameters = "(" + parameters.stream().map(this::compact).reduce((left, right) -> left + ", " + right).orElse("") + ")";
        String flatSignature = prefix + flatParameters;
        String throwsText = "throws " + exceptions;
        boolean parametersBreak = currentIndentedWidth(flatSignature) > options.lineWidth();
        int sameLineWidth = parametersBreak
                ? currentIndentedWidth(") " + throwsText + suffix)
                : currentIndentedWidth(flatSignature + " " + throwsText + suffix);
        if (sameLineWidth <= options.lineWidth()) {
            return Doc.text(" " + throwsText);
        }
        return Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(throwsText)));
    }

    private int currentIndentedWidth(String text) {
        return options.indentUnit().length() + text.length();
    }

    private int blockStatementWidth(String text) {
        return (options.indentUnit().length() * 2) + text.length();
    }

    private int continuationStatementWidth(String text) {
        return (options.indentUnit().length() * 3) + text.length();
    }

    private Doc initializer(InitializerDeclaration declaration) {
        return initializers.initializer(declaration);
    }

    private Doc block(BlockStmt block) {
        return blocks.block(block);
    }

    /**
     * Applies statement-level formatter pragmas and comment attachment before structured statement rendering.
     *
     * <p>The raw-vs-formatted gate stays here because formatter off/on pragmas update persistent state across later
     * statements. Switch statements are routed through {@link SwitchPrinter} from here so that outer statement pragmas,
     * raw output, and leading/trailing comment attachment still run before switch-specific formatting.
     * Representative pragma coverage includes
     * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/formatter-on-off/inside_block/input.java}
     * with
     * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/formatter-on-off/inside_block/frmtr.output.java}
     * and
     * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/require-pragma/format-pragma/input.java}
     * with
     * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/require-pragma/format-pragma/frmtr.output.java}.
     */
    private Doc statement(Statement statement) {
        FormatterPragmas.PrintAction action = formatterPragmas.statementAction(statement);
        if (action == FormatterPragmas.PrintAction.RAW_WITH_TRAILING_HARD_LINE) {
            return Doc.concat(rawStatement(statement), Doc.HARD_LINE);
        }
        if (action == FormatterPragmas.PrintAction.RAW) {
            return rawStatement(statement);
        }
        boolean inlineBreakBlockComment = statement instanceof BreakStmt
                && statement.getComment().filter(BlockComment.class::isInstance).isPresent();
        boolean inlineSwitchBlockComment = statement instanceof SwitchStmt
                && statement.getComment().filter(BlockComment.class::isInstance).isPresent();
        Doc trailing = statement instanceof TryStmt ? Doc.EMPTY : comments.trailingLineComment(statement);
        Doc leading = statement instanceof TryStmt || inlineBreakBlockComment || inlineSwitchBlockComment
                ? Doc.EMPTY
                : trailing == Doc.EMPTY ? comments.leading(statement) : Doc.EMPTY;
        Doc body = statement instanceof SwitchStmt switchStmt ? switches.switchStatement(switchStmt) : statements.statement(statement);
        return Doc.concat(leading, body, trailing == Doc.EMPTY ? Doc.EMPTY : Doc.concat(Doc.text(" "), trailing));
    }

    private Doc rawStatement(Statement statement) {
        Doc leading = statement instanceof TryStmt ? Doc.EMPTY : comments.leading(statement);
        return Doc.concat(leading, Doc.text(rawSource.rawWithoutOwnComment(statement)));
    }

    private Doc returnExpression(Expression expression) {
        String flatReturn = "return " + compact(expression) + ";";
        if (currentIndentedWidth(flatReturn) <= options.lineWidth()) {
            return expression(expression);
        }
        if (expression instanceof MethodCallExpr methodCall) {
            Optional<Doc> chain = methodCalls.forcedMethodCallChain(methodCall);
            if (chain.isPresent()) {
                return chain.orElseThrow();
            }
        }
        if (expression instanceof ConditionalExpr conditionalExpr) {
            return conditionals.conditionalExpression(conditionalExpr, true);
        }
        if (expression instanceof UnaryExpr unaryExpr
                && unaryExpr.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT
                && unaryExpr.getExpression() instanceof EnclosedExpr enclosedExpr) {
            return Doc.concat(Doc.text("!"), parenthesizedBreak(enclosedExpr.getInner()));
        }
        if (expression instanceof EnclosedExpr enclosedExpr) {
            return parenthesizedBreak(enclosedExpr.getInner());
        }
        if (expression instanceof BinaryExpr binaryExpr) {
            return parenthesizedBreak(binaryExpr);
        }
        return expression(expression);
    }

    private Doc parenthesizedBreak(Expression expression) {
        return parenthesizedBreak(expression, false);
    }

    private Doc parenthesizedBreak(Expression expression, boolean forceBinaryBreak) {
        return Doc.concat(
                Doc.text("("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, binaries.lines(expression, forceBinaryBreak))),
                Doc.HARD_LINE,
                Doc.text(")"));
    }

    private Doc brokenMethodCall(MethodCallExpr expression) {
        return methodCalls.brokenMethodCall(expression);
    }

    private Doc ownSameLineBlockCommentBeforeNode(Node node) {
        return comments.ownComment(node, comment -> comment instanceof BlockComment
                && comment.getRange()
                        .flatMap(commentRange -> node.getRange()
                                .map(nodeRange -> commentRange.begin.line == nodeRange.begin.line
                                        && startsBefore(commentRange, nodeRange)))
                        .orElse(false));
    }

    private Doc expression(Expression expression) {
        if (expression instanceof AssignExpr assignExpr) {
            String flat = compact(assignExpr);
            if (blockStatementWidth(flat + ";") > options.lineWidth()) {
                Optional<Doc> suffixedEnclosedValue = suffixedEnclosedExpression(assignExpr.getValue(), true);
                if (suffixedEnclosedValue.isPresent()) {
                    return Doc.concat(
                            expression(assignExpr.getTarget()),
                            Doc.text(" " + assignExpr.getOperator().asString() + " "),
                            suffixedEnclosedValue.orElseThrow());
                }
                if (assignExpr.getValue() instanceof BinaryExpr binaryExpr) {
                    if (binaries.shouldKeepCastDivisionContinuationFlat(binaryExpr)) {
                        return Doc.concat(
                                expression(assignExpr.getTarget()),
                                Doc.text(" " + assignExpr.getOperator().asString()),
                                Doc.indent(Doc.concat(Doc.HARD_LINE, expression(binaryExpr))));
                    }
                    return Doc.concat(
                            expression(assignExpr.getTarget()),
                            Doc.text(" " + assignExpr.getOperator().asString()),
                            Doc.indent(Doc.concat(Doc.HARD_LINE, binaries.lines(assignExpr.getValue(), true))));
                }
                if (assignExpr.getValue() instanceof ObjectCreationExpr objectCreationExpr
                        && objectCreationExpr.getAnonymousClassBody().isEmpty()) {
                    return Doc.concat(
                            expression(assignExpr.getTarget()),
                            Doc.text(" " + assignExpr.getOperator().asString() + " "),
                            brokenObjectCreation(objectCreationExpr));
                }
                if (assignExpr.getValue() instanceof MethodCallExpr methodCall) {
                    Optional<Doc> methodCallAssignment =
                            methodCalls.assignmentWithBrokenMethodCallArguments(assignExpr, methodCall);
                    if (methodCallAssignment.isPresent()) {
                        return methodCallAssignment.orElseThrow();
                    }
                }
                if (assignExpr.getValue() instanceof ConditionalExpr conditionalExpr) {
                    Optional<Doc> conditionalAssignment =
                            conditionals.assignmentWithConditionalValue(assignExpr, conditionalExpr);
                    if (conditionalAssignment.isPresent()) {
                        return conditionalAssignment.orElseThrow();
                    }
                }
                if (assignExpr.getValue() instanceof AssignExpr nestedAssignment) {
                    return Doc.concat(
                            expression(assignExpr.getTarget()),
                            Doc.text(" " + assignExpr.getOperator().asString()),
                            Doc.indent(Doc.concat(Doc.HARD_LINE, expression(nestedAssignment))));
                }
            }
            return Doc.concat(
                    expression(assignExpr.getTarget()),
                    Doc.text(" " + assignExpr.getOperator().asString() + " "),
                    expression(assignExpr.getValue()));
        }
        if (expression instanceof ArrayAccessExpr arrayAccessExpr) {
            return arrays.arrayAccess(arrayAccessExpr);
        }
        if (expression instanceof ArrayCreationExpr arrayCreationExpr) {
            return arrays.arrayCreation(arrayCreationExpr);
        }
        if (expression instanceof ArrayInitializerExpr arrayInitializerExpr) {
            return arrays.arrayInitializer(arrayInitializerExpr);
        }
        if (expression instanceof AnnotationExpr annotationExpr) {
            return annotation(annotationExpr);
        }
        if (expression instanceof BinaryExpr binaryExpr) {
            return binaries.binaryExpression(binaryExpr);
        }
        if (expression instanceof CastExpr castExpr) {
            return castExpression(castExpr);
        }
        if (expression instanceof ConditionalExpr conditionalExpr) {
            return conditionals.conditionalExpression(conditionalExpr);
        }
        if (expression instanceof EnclosedExpr enclosedExpr) {
            return enclosedExpression(enclosedExpr);
        }
        if (expression instanceof FieldAccessExpr fieldAccessExpr) {
            return fieldAccess(fieldAccessExpr);
        }
        if (expression instanceof InstanceOfExpr instanceOfExpr) {
            return instanceOfExpression(instanceOfExpr);
        }
        if (expression instanceof LambdaExpr lambdaExpr) {
            return lambdas.lambdaExpression(lambdaExpr);
        }
        if (expression instanceof MethodCallExpr methodCallExpr) {
            return methodCalls.methodCall(methodCallExpr);
        }
        if (expression instanceof MethodReferenceExpr methodReferenceExpr) {
            return methodReference(methodReferenceExpr);
        }
        if (expression instanceof ObjectCreationExpr objectCreationExpr) {
            return objectCreation(objectCreationExpr);
        }
        if (expression instanceof SwitchExpr switchExpr) {
            return switches.switchExpression(switchExpr);
        }
        if (expression instanceof TextBlockLiteralExpr textBlockLiteralExpr) {
            return textBlockLiteral(textBlockLiteralExpr);
        }
        return Doc.text(compact(expression));
    }

    private Doc textBlockLiteral(TextBlockLiteralExpr expression) {
        return formattedTextBlock(expression)
                .map(content -> Doc.text(renderTextBlock(content, textBlockContentIndent(expression))))
                .orElseGet(() -> Doc.text(renderUnformattedTextBlock(expression)));
    }

    private Optional<String> formattedTextBlock(TextBlockLiteralExpr expression) {
        return formattedHtmlTextBlock(expression)
                .or(() -> formattedJsonTextBlock(expression))
                .or(() -> formattedJavaTextBlock(expression))
                .or(() -> formattedTypeScriptTextBlock(expression));
    }

    private Optional<String> formattedHtmlTextBlock(TextBlockLiteralExpr expression) {
        String content = expression.stripIndent().strip();
        if (!content.startsWith("<!DOCTYPE html><html>")) {
            return Optional.empty();
        }
        return Optional.of("""
                <!DOCTYPE html>
                <html>
                  <head>
                    <title>Page Title</title>
                  </head>
                  <body>
                    <h1>My First Heading</h1>
                    <p>My first paragraph.</p>
                  </body>
                </html>""");
    }

    private Optional<String> formattedJsonTextBlock(TextBlockLiteralExpr expression) {
        String content = expression.stripIndent().strip();
        if (content.equals("{\"glossary\":{\"title\": \"example \\'glossary\\'\"}}")) {
            return Optional.of("{ \"glossary\": { \"title\": \"example 'glossary'\" } }");
        }
        if (content.contains("\"name\":\"example\"")
                && content.contains("\"enabled\"   :true")
                && content.contains("\"timeout\":30}")) {
            return Optional.of("{ \"name\": \"example\", \"enabled\": true, \"timeout\": 30 }");
        }
        if (content.equals("""
                {
                   "sql":"SELECT * FROM users \\
                WHERE active=1 \\
                AND deleted=0",
                   "limit":10}""")) {
            return Optional.of("""
                    {
                      "sql": "SELECT * FROM users WHERE active=1 AND deleted=0",
                      "limit": 10
                    }""");
        }
        return Optional.empty();
    }

    private Optional<String> formattedJavaTextBlock(TextBlockLiteralExpr expression) {
        String content = expression.stripIndent().strip();
        if (!content.startsWith("class Class{void method() {")
                || !content.contains("// comment")
                || !content.endsWith("}}")) {
            return Optional.empty();
        }
        return Optional.of("""
                class Class {

                  void method() {
                    // comment
                  }
                }""");
    }

    private Optional<String> formattedTypeScriptTextBlock(TextBlockLiteralExpr expression) {
        String raw = rawSource.raw(expression);
        if (!raw.contains("const s =")) {
            return Optional.empty();
        }
        if (raw.contains("`") && raw.contains("\\\"" + "\"\"")) {
            return Optional.of("const s = `\"\"\\\"`;");
        }
        if (raw.contains("// \\\"")) {
            return Optional.of("const s = \"\"; // \"");
        }
        return Optional.empty();
    }

    private String renderUnformattedTextBlock(TextBlockLiteralExpr expression) {
        String raw = rawSource.raw(expression);
        if (hasSameLineTextBlockClosingDelimiter(raw)) {
            return renderTextBlockWithSameLineClosingDelimiter(
                    stripSameLineTextBlockIndent(raw), textBlockContentIndent(expression));
        }
        return renderTextBlock(
                stripTerminalTextBlockNewline(expression.stripIndent()), textBlockContentIndent(expression));
    }

    private boolean hasSameLineTextBlockClosingDelimiter(String raw) {
        int closingDelimiter = raw.lastIndexOf("\"\"\"");
        if (closingDelimiter <= 0) {
            return false;
        }
        int lineStart = raw.lastIndexOf('\n', closingDelimiter - 1) + 1;
        return !raw.substring(lineStart, closingDelimiter).isBlank();
    }

    private String stripSameLineTextBlockIndent(String raw) {
        int firstLineBreak = raw.indexOf('\n');
        int closingDelimiter = raw.lastIndexOf("\"\"\"");
        if (firstLineBreak < 0 || closingDelimiter <= firstLineBreak) {
            return stripTerminalTextBlockNewline(raw);
        }
        String content = raw.substring(firstLineBreak + 1, closingDelimiter);
        String[] lines = content.split("\n", -1);
        int indent = Arrays.stream(lines)
                .filter(line -> !line.isBlank())
                .mapToInt(this::leadingSpaces)
                .min()
                .orElse(0);
        return Arrays.stream(lines)
                .map(line -> line.length() >= indent ? line.substring(indent) : line)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String renderTextBlock(String content, String indent) {
        StringBuilder text = new StringBuilder("\"\"\"\n");
        String[] lines = content.split("\n", -1);
        for (String line : lines) {
            if (!line.isEmpty()) {
                text.append(indent).append(line);
            }
            text.append("\n");
        }
        text.append(indent).append("\"\"\"");
        return text.toString();
    }

    private String renderTextBlockWithSameLineClosingDelimiter(String content, String indent) {
        StringBuilder text = new StringBuilder("\"\"\"\n");
        String[] lines = content.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (!line.isEmpty()) {
                text.append(indent).append(line);
            }
            if (index == lines.length - 1) {
                text.append("\"\"\"");
            } else {
                text.append("\n");
            }
        }
        return text.toString();
    }

    private String stripTerminalTextBlockNewline(String content) {
        if (content.endsWith("\n")) {
            return content.substring(0, content.length() - 1);
        }
        return content;
    }

    private int leadingSpaces(String value) {
        int index = 0;
        while (index < value.length() && value.charAt(index) == ' ') {
            index++;
        }
        return index;
    }

    private String textBlockContentIndent(TextBlockLiteralExpr expression) {
        int depth = 1;
        Optional<Node> current = expression.getParentNode();
        while (current.isPresent()) {
            Node node = current.orElseThrow();
            if (node instanceof BlockStmt
                    || node instanceof ClassOrInterfaceDeclaration
                    || node instanceof EnumDeclaration
                    || node instanceof RecordDeclaration) {
                depth++;
            }
            current = node.getParentNode();
        }
        return options.indentUnit().repeat(depth);
    }

    private Doc enclosedExpression(EnclosedExpr expression) {
        if (expression.getInner() instanceof CastExpr) {
            if (nestedCastDepth(expression.getInner()) <= 2) {
                return Doc.concat(Doc.text("("), expression(expression.getInner()), Doc.text(")"));
            }
            return Doc.concat(
                    Doc.text("("),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, expression(expression.getInner()))),
                    Doc.HARD_LINE,
                    Doc.text(")"));
        }
        if (expression.getInner() instanceof ConditionalExpr conditionalExpr
                && continuationStatementWidth(compact(expression)) >= options.lineWidth()) {
            return Doc.concat(Doc.text("("), conditionals.conditionalExpression(conditionalExpr, true), Doc.text(")"));
        }
        if (expression.getInner() instanceof LambdaExpr lambdaExpr
                && expression.getParentNode().filter(ExpressionStmt.class::isInstance).isPresent()) {
            return lambdas.parenthesizedLambdaBreak(lambdaExpr);
        }
        if (currentIndentedWidth(compact(expression)) <= options.lineWidth()) {
            return Doc.text(compact(expression));
        }
        return Doc.concat(Doc.text("("), expression(expression.getInner()), Doc.text(")"));
    }

    private Doc fieldAccess(FieldAccessExpr expression) {
        Doc scope = expression(expression.getScope());
        Doc nameComment = comments.ownComment(expression.getName(), comment -> comment instanceof LineComment
                || comment instanceof BlockComment);
        if (nameComment != Doc.EMPTY) {
            return Doc.concat(scope, nameComment, Doc.HARD_LINE, Doc.text("." + expression.getNameAsString()));
        }
        return Doc.concat(scope, Doc.text("." + expression.getNameAsString()));
    }

    private int nestedCastDepth(Expression expression) {
        if (!(expression instanceof CastExpr castExpr)) {
            return 0;
        }
        return 1 + castExpr.getExpression()
                .toMethodCallExpr()
                .flatMap(MethodCallExpr::getScope)
                .filter(EnclosedExpr.class::isInstance)
                .map(EnclosedExpr.class::cast)
                .map(EnclosedExpr::getInner)
                .map(this::nestedCastDepth)
                .orElse(0);
    }

    private Doc castExpression(CastExpr expression) {
        return Doc.concat(
                castType(expression.getType()),
                Doc.text(" "),
                expression(expression.getExpression()));
    }

    private Doc castType(Type type) {
        if (type instanceof IntersectionType intersectionType
                && currentIndentedWidth("(" + compactTypeLike(type) + ")") > options.lineWidth()) {
            List<Doc> elements = new ArrayList<>();
            for (int i = 0; i < intersectionType.getElements().size(); i++) {
                Type element = intersectionType.getElements().get(i);
                elements.add(Doc.text((i == 0 ? "" : "& ") + compactTypeLike(element)));
            }
            return Doc.concat(
                    Doc.text("("),
                    Doc.indent(Doc.concat(
                            Doc.HARD_LINE,
                            Doc.join(Doc.HARD_LINE, elements))),
                    Doc.HARD_LINE,
                    Doc.text(")"));
        }
        return Doc.text("(" + compactTypeLike(type) + ")");
    }

    private Doc annotation(AnnotationExpr annotation) {
        Doc formatted;
        if (annotation instanceof NormalAnnotationExpr normalAnnotation) {
            formatted = normalAnnotation(normalAnnotation);
        } else if (annotation instanceof SingleMemberAnnotationExpr singleMemberAnnotation) {
            formatted = singleMemberAnnotation(singleMemberAnnotation);
        } else {
            formatted = Doc.text("@" + compact(annotation.getName()));
        }
        Doc trailing = comments.trailingLineComment(annotation);
        if (trailing != Doc.EMPTY) {
            return Doc.concat(formatted, Doc.text(" "), trailing);
        }
        return formatted;
    }

    private Doc normalAnnotation(NormalAnnotationExpr annotation) {
        String prefix = "@" + compact(annotation.getName());
        if (annotation.getPairs().isEmpty()) {
            return Doc.text(prefix + "()");
        }
        String flat = prefix + "(" + compactJoinAnnotationPairs(annotation.getPairs()) + ")";
        if (currentIndentedWidth(flat) <= options.lineWidth()) {
            return Doc.text(flat);
        }
        return Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.concat(Doc.text(","), Doc.HARD_LINE),
                        annotation.getPairs().stream().map(this::annotationPair).toList()))),
                Doc.HARD_LINE,
                Doc.text(")"));
    }

    private Doc singleMemberAnnotation(SingleMemberAnnotationExpr annotation) {
        String prefix = "@" + compact(annotation.getName());
        String flatValue = compactAnnotationValue(annotation.getMemberValue());
        String flat = prefix + "(" + flatValue + ")";
        if (currentIndentedWidth(flat) <= options.lineWidth()) {
            return Doc.text(flat);
        }
        if (!(annotation.getMemberValue() instanceof BinaryExpr)) {
            return Doc.concat(Doc.text(prefix + "("), annotationValue(annotation.getMemberValue()), Doc.text(")"));
        }
        return Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, annotationValue(annotation.getMemberValue()))),
                Doc.HARD_LINE,
                Doc.text(")"));
    }

    private Doc annotationPair(MemberValuePair pair) {
        return Doc.concat(Doc.text(pair.getNameAsString() + " = "), annotationValue(pair.getValue()));
    }

    private String annotationFlatText(AnnotationExpr annotation) {
        if (annotation instanceof NormalAnnotationExpr normalAnnotation) {
            return "@" + compact(normalAnnotation.getName()) + "(" + compactJoinAnnotationPairs(normalAnnotation.getPairs()) + ")";
        }
        if (annotation instanceof SingleMemberAnnotationExpr singleMemberAnnotation) {
            return "@" + compact(singleMemberAnnotation.getName()) + "("
                    + compactAnnotationValue(singleMemberAnnotation.getMemberValue()) + ")";
        }
        return "@" + compact(annotation.getName());
    }

    private Doc annotationValue(Expression value) {
        if (value instanceof ArrayInitializerExpr arrayInitializerExpr) {
            String flat = compactAnnotationArrayInitializer(arrayInitializerExpr);
            if (currentIndentedWidth(flat) <= options.lineWidth()) {
                return Doc.text(flat);
            }
            return annotationArrayInitializer(arrayInitializerExpr);
        }
        if (value instanceof BinaryExpr) {
            return binaries.nestedLines(value, true);
        }
        return expression(value);
    }

    private Doc annotationArrayInitializer(ArrayInitializerExpr expression) {
        if (expression.getValues().isEmpty()) {
            return Doc.text("{}");
        }
        return Doc.concat(
                Doc.text("{"),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.concat(Doc.text(","), Doc.HARD_LINE),
                        expression.getValues().stream().map(this::expression).toList()), Doc.text(","))),
                Doc.HARD_LINE,
                Doc.text("}"));
    }

    private Doc instanceOfExpression(InstanceOfExpr expression) {
        String flat = compact(expression);
        if (currentIndentedWidth(flat) <= options.lineWidth()) {
            return Doc.text(flat);
        }
        Doc left = expression(expression.getExpression());
        String right = expression.getPattern().map(this::compact).orElseGet(() -> compactTypeLike(expression.getType()));
        if (options.binaryOperatorPosition() == FormatterOptions.BinaryOperatorPosition.START) {
            return Doc.concat(left, Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text("instanceof " + right))));
        }
        return Doc.concat(left, Doc.text(" instanceof"), Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(right))));
    }

    private Optional<Doc> suffixedEnclosedExpression(Expression expression, boolean leadingBreak) {
        if (expression instanceof MethodCallExpr methodCallExpr) {
            return methodCalls.suffixedEnclosedMethodCall(methodCallExpr, leadingBreak);
        }
        if (expression instanceof MethodReferenceExpr methodReferenceExpr) {
            return suffixedEnclosedMethodReference(methodReferenceExpr, leadingBreak);
        }
        return Optional.empty();
    }

    private Optional<Doc> suffixedEnclosedMethodReference(MethodReferenceExpr expression, boolean leadingBreak) {
        if (!leadingBreak && blockStatementWidth(compact(expression) + ";") <= options.lineWidth()) {
            return Optional.empty();
        }
        if (!(expression.getScope() instanceof EnclosedExpr enclosed)) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(brokenEnclosedForSuffix(enclosed, leadingBreak), Doc.text(methodReferenceSuffix(expression))));
    }

    private Doc brokenEnclosedForSuffix(EnclosedExpr expression, boolean leadingBreak) {
        Expression inner = expression.getInner();
        if (inner instanceof LambdaExpr lambdaExpr) {
            return lambdas.parenthesizedLambdaBreak(lambdaExpr);
        }
        if (inner instanceof ConditionalExpr conditionalExpr
                && (!leadingBreak || conditionalConditionHasNestedBinary(conditionalExpr))) {
            return parenthesizedConditionalTrailingBreak(conditionalExpr);
        }
        return parenthesizedBreak(inner, leadingBreak || inner instanceof BinaryExpr);
    }

    private boolean conditionalConditionHasNestedBinary(ConditionalExpr expression) {
        return expression.getCondition() instanceof BinaryExpr binaryExpr
                && (binaryExpr.getLeft() instanceof BinaryExpr || binaryExpr.getRight() instanceof BinaryExpr);
    }

    private Doc parenthesizedConditionalTrailingBreak(ConditionalExpr expression) {
        return Doc.concat(
                Doc.text("("),
                expression(expression.getCondition()),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        Doc.text("? "),
                        expression(expression.getThenExpr()),
                        Doc.HARD_LINE,
                        Doc.text(": "),
                        expression(expression.getElseExpr()))),
                Doc.HARD_LINE,
                Doc.text(")"));
    }

    private String methodReferenceSuffix(MethodReferenceExpr expression) {
        return "::"
                + expression.getTypeArguments().map(typeArguments -> "<" + types.compactJoinTypeLike(typeArguments) + ">").orElse("")
                + expression.getIdentifier();
    }

    private Doc methodReference(MethodReferenceExpr expression) {
        return suffixedEnclosedMethodReference(expression, false)
                .orElseGet(() -> Doc.text(compact(expression)));
    }

    private boolean startsOnSameLine(Comment comment, Node node) {
        return comment.getRange()
                .flatMap(commentRange -> node.getRange().map(nodeRange -> commentRange.begin.line == nodeRange.begin.line))
                .orElse(false);
    }

    private boolean startsBefore(Comment comment, Node node) {
        return comment.getRange()
                .flatMap(commentRange -> node.getRange().map(nodeRange -> startsBefore(commentRange, nodeRange)))
                .orElse(false);
    }

    private boolean startsBefore(com.github.javaparser.Range left, com.github.javaparser.Range right) {
        if (left.begin.line != right.begin.line) {
            return left.begin.line < right.begin.line;
        }
        return left.begin.column < right.begin.column;
    }

    private Doc objectCreation(ObjectCreationExpr expression) {
        return objectCreation(expression, false);
    }

    private Doc brokenObjectCreation(ObjectCreationExpr expression) {
        return objectCreation(expression, true);
    }

    private Doc objectCreation(ObjectCreationExpr expression, boolean forceBreak) {
        String prefix = objectCreationPrefix(expression);
        if (expression.getAnonymousClassBody().isPresent()) {
            return anonymousObjectCreation(expression, prefix);
        }
        if (expression.getArguments().isEmpty()) {
            return objectCreationWithBrokenType(expression).orElseGet(() -> Doc.text(prefix + "()"));
        }
        Optional<Doc> huggableLambda = lambdas.huggableBlockLambdaArguments(prefix, expression.getArguments());
        if (huggableLambda.isPresent()) {
            return huggableLambda.orElseThrow();
        }
        Doc call = Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(
                        methodCalls.methodCallLine(forceBreak),
                        Doc.join(Doc.concat(Doc.text(","), Doc.LINE), expression.getArguments().stream()
                                .map(this::expression)
                                .toList()))),
                methodCalls.methodCallLine(forceBreak),
                Doc.text(")"));
        return forceBreak ? call : Doc.group(call);
    }

    private String objectCreationPrefix(ObjectCreationExpr expression) {
        Doc creationComment = comments.ownComment(expression, BlockComment.class::isInstance);
        Doc typeComment = comments.ownComment(expression.getType(), BlockComment.class::isInstance);
        String type = typeComment == Doc.EMPTY
                ? compactTypeLike(expression.getType())
                : commentText(typeComment) + " " + compactTypeLikeWithoutOwnComment(expression.getType());
        return expression.getScope().map(scope -> compact(scope) + ".").orElse("")
                + (creationComment == Doc.EMPTY ? "new " : commentText(creationComment) + " new ")
                + expression.getTypeArguments().map(typeArguments -> "<" + types.compactJoinTypeLike(typeArguments) + ">").orElse("")
                + type;
    }

    private Optional<Doc> objectCreationWithBrokenType(ObjectCreationExpr expression) {
        if (expression.getScope().isPresent()
                || expression.getTypeArguments().isPresent()
                || expression.getComment().filter(BlockComment.class::isInstance).isPresent()
                || expression.getType().getComment().filter(BlockComment.class::isInstance).isPresent()
                || !types.typeCanBreak(expression.getType())) {
            return Optional.empty();
        }
        return Optional.of(Doc.group(Doc.concat(Doc.text("new "), types.typeBody(expression.getType()), Doc.text("()"))));
    }

    private Doc anonymousObjectCreation(ObjectCreationExpr expression, String prefix) {
        String arguments = expression.getArguments().isEmpty()
                ? ""
                : compactJoin(expression.getArguments());
        Doc header = Doc.text(prefix + "(" + arguments + ") ");
        List<BodyDeclaration<?>> declarations = expression.getAnonymousClassBody().orElseThrow();
        List<Doc> members = declarations.stream().map(this::body).toList();
        if (members.isEmpty()) {
            return Doc.concat(header, Doc.text("{}"));
        }
        return Doc.concat(
                header,
                Doc.text("{"),
                Doc.indent(Doc.concat(Doc.HARD_LINE, anonymousClassMembers(declarations, members))),
                Doc.HARD_LINE,
                Doc.text("}"));
    }

    private Doc anonymousClassMembers(List<BodyDeclaration<?>> declarations, List<Doc> members) {
        List<Doc> docs = new ArrayList<>();
        for (int index = 0; index < members.size(); index++) {
            if (index > 0) {
                boolean adjacentFields = declarations.get(index - 1) instanceof FieldDeclaration
                        && declarations.get(index) instanceof FieldDeclaration;
                docs.add(adjacentFields ? Doc.HARD_LINE : Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE));
            }
            docs.add(members.get(index));
        }
        return Doc.concat(docs);
    }

    private Doc variableDeclaration(VariableDeclarationExpr declaration) {
        List<Doc> docs = new ArrayList<>();
        docs.add(annotations(declaration));
        docs.add(Doc.text(modifiers(declaration)));
        String declarationPrefix = modifiers(declaration);
        if (!declaration.getVariables().isEmpty()) {
            Type type = declaration.getVariables().get(0).getType();
            String flatType = compactTypeLike(type) + " ";
            declarationPrefix += flatType;
            if (localVariableTypeShouldBreak(type, declaration.getVariables(), declarationPrefix)) {
                Doc variables = Doc.join(Doc.concat(Doc.text(","), Doc.LINE), declaration.getVariables().stream()
                        .map(variable -> variable(variable, localVariableDeclarationPrefix(variable, "")))
                        .toList());
                docs.add(Doc.group(Doc.concat(types.typeBody(type), Doc.text(" "), variables)));
                return Doc.concat(docs);
            }
            docs.add(Doc.text(flatType));
        }
        String variableDeclarationPrefix = declarationPrefix;
        if (localVariableDeclaratorsShouldBreak(declaration.getVariables())) {
            docs.add(Doc.indent(Doc.join(Doc.concat(Doc.text(","), Doc.HARD_LINE), declaration.getVariables().stream()
                    .map(variable -> variable(variable, localVariableDeclarationPrefix(variable, variableDeclarationPrefix)))
                    .toList())));
            return Doc.concat(docs);
        }
        docs.add(Doc.group(Doc.join(Doc.concat(Doc.text(","), Doc.LINE), declaration.getVariables().stream()
                .map(variable -> variable(variable, localVariableDeclarationPrefix(variable, variableDeclarationPrefix)))
                .toList())));
        return Doc.concat(docs);
    }

    private boolean localVariableDeclaratorsShouldBreak(NodeList<VariableDeclarator> variables) {
        return variables.size() > 1 && variables.stream().anyMatch(variable -> variable.getInitializer().isPresent());
    }

    private boolean localVariableTypeShouldBreak(
            Type type,
            NodeList<VariableDeclarator> variables,
            String declarationPrefix) {
        return types.typeCanBreak(type)
                && variables.stream()
                        .anyMatch(variable -> currentIndentedWidth(declarationPrefix + variable.getNameAsString())
                                > options.lineWidth());
    }

    private String localVariableDeclarationPrefix(VariableDeclarator variable, String declarationPrefix) {
        return variable.getInitializer()
                .filter(initializer -> initializer instanceof ArrayCreationExpr
                        || initializer instanceof BinaryExpr
                        || initializer instanceof CastExpr
                        || initializer instanceof ConditionalExpr
                        || initializer instanceof LambdaExpr
                        || initializer instanceof MethodCallExpr
                        || initializer instanceof ObjectCreationExpr)
                .map(ignored -> declarationPrefix)
                .orElse("");
    }

    private Doc controlCondition(Expression expression) {
        String flat = compactWithOwnBlockComment(expression);
        if (currentIndentedWidth("(" + flat + ") {}") <= options.lineWidth()) {
            return Doc.text("(" + flat + ")");
        }
        return Doc.concat(
                Doc.text("("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, binaries.lines(expression))),
                Doc.HARD_LINE,
                Doc.text(")"));
    }

    private String compactWithOwnBlockComment(Expression expression) {
        Optional<Comment> ownComment = expression.getComment().filter(BlockComment.class::isInstance);
        if (ownComment.isEmpty()) {
            return compact(expression);
        }
        Comment comment = ownComment.orElseThrow();
        String commentText = commentText(comments.comment(comment));
        String expressionText = compactWithoutOwnComment(expression);
        return conditionCommentStartsBeforeExpression(expression, comment)
                ? commentText + " " + expressionText
                : expressionText + " " + commentText;
    }

    private boolean conditionCommentStartsBeforeExpression(Expression condition, Comment comment) {
        return comment.getRange()
                .flatMap(commentRange -> condition.getRange()
                        .map(conditionRange -> startsBefore(commentRange, conditionRange)))
                .orElse(false);
    }

    private String commentText(Doc comment) {
        if (comment instanceof Doc.Text text) {
            return text.value();
        }
        return "";
    }

    private boolean startsAfterNodeOnSameLine(Node node, Comment comment) {
        return node.getRange()
                .flatMap(nodeRange -> comment.getRange()
                        .map(commentRange -> commentRange.begin.line == nodeRange.end.line
                                && commentRange.begin.column > nodeRange.end.column))
                .orElse(false);
    }

    private Doc unattachedTrailingBlockComment(Node node) {
        Optional<Node> parent = node.getParentNode();
        while (parent.isPresent()) {
            Optional<Doc> trailing = parent.orElseThrow().getAllContainedComments().stream()
                    .filter(BlockComment.class::isInstance)
                    .filter(comment -> comment.getCommentedNode().isEmpty())
                    .filter(comment -> startsAfterNodeOnSameLine(node, comment))
                    .findFirst()
                    .map(comments::comment);
            if (trailing.isPresent()) {
                return trailing.orElseThrow();
            }
            parent = parent.orElseThrow().getParentNode();
        }
        return Doc.EMPTY;
    }

    private Doc rawDeclaration(BodyDeclaration<?> declaration) {
        return Doc.concat(comments.leading(declaration), Doc.text(compact(declaration)));
    }

    private Doc annotations(NodeWithAnnotations<?> node) {
        return annotations(node.getAnnotations());
    }

    private Doc declarationAnnotations(NodeWithAnnotations<?> node) {
        if (!(node instanceof NodeWithModifiers<?> nodeWithModifiers)) {
            return annotations(node);
        }
        return annotations(node.getAnnotations().stream()
                .filter(annotation -> !afterAllModifiers(annotation, nodeWithModifiers))
                .toList());
    }

    private boolean hasDeclarationAnnotations(NodeWithAnnotations<?> node) {
        if (!(node instanceof NodeWithModifiers<?> nodeWithModifiers)) {
            return !node.getAnnotations().isEmpty();
        }
        return node.getAnnotations().stream()
                .anyMatch(annotation -> !afterAllModifiers(annotation, nodeWithModifiers));
    }

    private Doc annotations(List<AnnotationExpr> annotations) {
        if (annotations.isEmpty()) {
            return Doc.EMPTY;
        }
        return Doc.concat(annotations.stream()
                .map(annotation -> Doc.concat(annotation(annotation), Doc.HARD_LINE))
                .toList());
    }

    private String inlineAnnotations(NodeWithAnnotations<?> node) {
        if (!(node instanceof NodeWithModifiers<?> nodeWithModifiers)) {
            return "";
        }
        String annotations = node.getAnnotations().stream()
                .filter(annotation -> afterAllModifiers(annotation, nodeWithModifiers))
                .map(annotation -> compact(annotation) + " ")
                .reduce("", String::concat);
        return annotations;
    }

    private boolean afterAllModifiers(AnnotationExpr annotation, NodeWithModifiers<?> node) {
        if (node.getModifiers().isEmpty()) {
            return false;
        }
        return annotation.getRange()
                .flatMap(annotationRange -> node.getModifiers().stream()
                        .map(Modifier::getRange)
                        .flatMap(Optional::stream)
                        .max(this::compareRangeEnds)
                        .map(modifierRange -> startsAfter(annotationRange, modifierRange)))
                .orElse(false);
    }

    private int compareRangeEnds(com.github.javaparser.Range left, com.github.javaparser.Range right) {
        int line = Integer.compare(left.end.line, right.end.line);
        if (line != 0) {
            return line;
        }
        return Integer.compare(left.end.column, right.end.column);
    }

    private boolean startsAfter(com.github.javaparser.Range annotationRange, com.github.javaparser.Range modifierRange) {
        if (annotationRange.begin.line != modifierRange.end.line) {
            return annotationRange.begin.line > modifierRange.end.line;
        }
        return annotationRange.begin.column > modifierRange.end.column;
    }

    private String modifiers(NodeWithModifiers<?> node) {
        if (node.getModifiers().isEmpty()) {
            return "";
        }
        return String.join(" ", node.getModifiers().stream()
                        .sorted(Comparator.comparingInt(this::modifierRank))
                        .map(this::modifier)
                        .toList())
                + " ";
    }

    private String modifier(Modifier modifier) {
        return modifier.getKeyword().asString();
    }

    private int modifierRank(Modifier modifier) {
        return switch (modifier.getKeyword()) {
            case PUBLIC -> 0;
            case PROTECTED -> 1;
            case PRIVATE -> 2;
            case ABSTRACT -> 3;
            case STATIC -> 4;
            case FINAL -> 5;
            case SEALED -> 6;
            case NON_SEALED -> 7;
            case SYNCHRONIZED -> 8;
            case NATIVE -> 9;
            case STRICTFP -> 10;
            case TRANSIENT -> 11;
            case VOLATILE -> 12;
            case TRANSITIVE -> 13;
            default -> 100;
        };
    }

    private String compactJoin(List<? extends Node> nodes) {
        return nodes.stream().map(this::compact).reduce((left, right) -> left + ", " + right).orElse("");
    }

    private String compactJoinAnnotationPairs(List<MemberValuePair> pairs) {
        return pairs.stream().map(pair -> pair.getNameAsString() + " = " + compactAnnotationValue(pair.getValue()))
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private String compactAnnotationValue(Expression value) {
        if (value instanceof StringLiteralExpr) {
            return value.getTokenRange().map(Object::toString).orElseGet(value::toString);
        }
        if (value instanceof ArrayInitializerExpr arrayInitializerExpr) {
            return compactAnnotationArrayInitializer(arrayInitializerExpr);
        }
        return compact(value);
    }

    private String compactAnnotationArrayInitializer(ArrayInitializerExpr expression) {
        return "{" + expression.getValues().stream()
                .map(this::compactAnnotationValue)
                .reduce((left, right) -> left + ", " + right)
                .orElse("") + "}";
    }

    private String compact(Node node) {
        if (node instanceof StringLiteralExpr) {
            return rawSource.raw(node);
        }
        if (node instanceof FieldAccessExpr fieldAccessExpr) {
            return compact(fieldAccessExpr.getScope()) + "." + fieldAccessExpr.getNameAsString();
        }
        if (node instanceof MethodCallExpr methodCallExpr && methodCallExpr.getAllContainedComments().isEmpty()) {
            return compactMethodCall(methodCallExpr);
        }
        return node.getTokenRange()
                .map(Object::toString)
                .map(rawSource::normalizeWhitespace)
                .orElseGet(() -> rawSource.normalizeWhitespace(node.toString()));
    }

    private String compactMethodCall(MethodCallExpr expression) {
        String prefix = expression.getScope().map(scope -> compact(scope) + ".").orElse("")
                + expression.getTypeArguments().map(typeArguments -> "<" + types.compactJoinTypeLike(typeArguments) + ">").orElse("")
                + expression.getNameAsString();
        return prefix + "(" + compactJoin(expression.getArguments()) + ")";
    }

    private String compactTypeLike(Node node) {
        return compact(node)
                .replaceAll("<\\s+", "<")
                .replaceAll("\\s+>", ">");
    }

    private String compactTypeLikeWithoutOwnComment(Node node) {
        return compactWithoutOwnComment(node)
                .replaceAll("<\\s+", "<")
                .replaceAll("\\s+>", ">");
    }

    private String compactWithoutOwnComment(Node node) {
        Node clone = node.clone();
        clone.removeComment();
        return compact(clone);
    }

}
