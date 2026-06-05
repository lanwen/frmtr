package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.CompilationUnit;
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
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.stmt.AssertStmt;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
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
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.Optional;

final class JavaPrinter {
    private final JavaFormatter.CommentTracker comments = new JavaFormatter.CommentTracker();
    private final FormatterOptions options;
    private final RawSource rawSource;
    private final CompactSourceText compactSource;
    private final CommentPlacement commentPlacement;
    private final TypePrinter types;
    private final FormatterPragmas formatterPragmas = new FormatterPragmas();
    private final ModuleBlockPrinter moduleBlocks;
    private final ModuleDeclarationPrinter moduleDeclarations;
    private final MemberBlockPrinter memberBlocks;
    private final BlockPrinter blocks;
    private final StatementPrinter statements;
    private final SwitchPrinter switches;
    private final ControlConditionPrinter controlConditions;
    private final BinaryExpressionPrinter binaries;
    private final AnnotationExpressionPrinter annotationExpressions;
    private final DeclarationPrefixPrinter declarationPrefixes;
    private final ConditionalExpressionPrinter conditionals;
    private final LambdaExpressionPrinter lambdas;
    private final ArrayExpressionPrinter arrays;
    private final ObjectCreationPrinter objectCreations;
    private final TextBlockPrinter textBlocks;
    private final CastExpressionPrinter casts;
    private final EnclosedExpressionPrinter enclosedExpressions;
    private final InstanceOfExpressionPrinter instanceOfExpressions;
    private final FieldAccessPrinter fieldAccesses;
    private final MethodReferencePrinter methodReferences;
    private final MethodCallPrinter methodCalls;
    private final AssignmentExpressionPrinter assignments;
    private final ReturnExpressionPrinter returnExpressions;
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
    private final VariableDeclarationPrinter variableDeclarations;

    JavaPrinter(FormatterOptions options) {
        this.options = options;
        this.rawSource = new RawSource(options);
        this.compactSource = new CompactSourceText(rawSource);
        this.commentPlacement = new CommentPlacement(comments);
        this.types = new TypePrinter(options, compactSource::compactTypeLike);
        this.blocks = new BlockPrinter(comments, this::statement, formatterPragmas::hasPragma);
        this.binaries = new BinaryExpressionPrinter(
                comments,
                options,
                this::expression,
                this::brokenMethodCall,
                compactSource::compact,
                compactSource::compactWithoutOwnComment,
                this::continuationStatementWidth,
                this::blockStatementWidth);
        this.annotationExpressions = new AnnotationExpressionPrinter(
                comments,
                options,
                this::expression,
                binaries::nestedLines,
                compactSource::compact,
                this::currentIndentedWidth);
        this.declarationPrefixes = new DeclarationPrefixPrinter(
                annotationExpressions::annotation,
                annotationExpressions::annotationFlatText);
        this.moduleBlocks = new ModuleBlockPrinter(
                comments,
                options,
                compactSource::compact,
                compactSource::compactJoin,
                declarationPrefixes::modifiers);
        this.moduleDeclarations = new ModuleDeclarationPrinter(
                comments,
                rawSource,
                new CommentedModulePrinter(),
                declarationPrefixes::annotations,
                this::commentText,
                compactSource::compact,
                moduleBlocks::moduleBlock);
        this.memberBlocks = new MemberBlockPrinter(rawSource, comments, declarationPrefixes::hasDeclarationAnnotations);
        this.controlConditions = new ControlConditionPrinter(
                comments,
                options,
                compactSource::compact,
                compactSource::compactWithoutOwnComment,
                binaries::lines,
                this::currentIndentedWidth);
        this.switches = new SwitchPrinter(
                comments,
                rawSource,
                options,
                this::statement,
                this::expression,
                this::block,
                blocks::statementSeparator,
                controlConditions::controlCondition,
                binaries::lines,
                compactSource::compact,
                compactSource::compactTypeLike,
                declarationPrefixes::modifiers,
                this::currentIndentedWidth,
                commentPlacement::ownSameLineBlockCommentBeforeNode);
        this.conditionals = new ConditionalExpressionPrinter(
                comments,
                options,
                this::expression,
                this::expressionWithoutOwnComment,
                compactSource::compact,
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
                compactSource::compact,
                compactSource::compactWithoutOwnComment,
                compactSource::compactJoin,
                this::currentIndentedWidth,
                this::blockStatementWidth,
                commentPlacement::startsBefore,
                commentPlacement::startsOnSameLine);
        this.casts = new CastExpressionPrinter(
                options,
                this::expression,
                compactSource::compactTypeLike,
                this::currentIndentedWidth);
        this.enclosedExpressions = new EnclosedExpressionPrinter(
                options,
                this::expression,
                (expression, forceBreak) -> binaries.lines(expression, forceBreak),
                compactSource::compact,
                this::currentIndentedWidth,
                this::continuationStatementWidth,
                casts::nestedCastDepth,
                lambdas::parenthesizedLambdaBreak,
                (expression, forceBreak) -> conditionals.conditionalExpression(expression, forceBreak));
        this.arrays = new ArrayExpressionPrinter(
                comments,
                options,
                this::expression,
                enclosedExpressions::brokenEnclosedForSuffix,
                compactSource::compactTypeLike,
                compactSource::compact,
                this::currentIndentedWidth,
                commentPlacement::startsBefore,
                commentPlacement::startsAfterNodeOnSameLine);
        this.objectCreations = new ObjectCreationPrinter(
                comments,
                types,
                this::expression,
                lambdas::huggableBlockLambdaArguments,
                this::body,
                compactSource::compact,
                compactSource::compactJoin,
                compactSource::compactTypeLike,
                compactSource::compactTypeLikeWithoutOwnComment,
                this::commentText);
        this.textBlocks = new TextBlockPrinter(rawSource, options);
        this.instanceOfExpressions = new InstanceOfExpressionPrinter(
                options,
                this::expression,
                compactSource::compact,
                compactSource::compactTypeLike,
                this::currentIndentedWidth);
        this.fieldAccesses = new FieldAccessPrinter(comments, this::expression);
        this.methodReferences = new MethodReferencePrinter(
                options,
                compactSource::compact,
                types::compactJoinTypeLike,
                enclosedExpressions::brokenEnclosedForSuffix,
                this::blockStatementWidth);
        this.methodCalls = new MethodCallPrinter(
                comments,
                options,
                types,
                this::expression,
                enclosedExpressions::brokenEnclosedForSuffix,
                objectCreations::brokenObjectCreation,
                lambdas::huggableBlockLambdaArguments,
                lambdas::commentedExpressionLambdaArgument,
                lambdas::huggableMethodCallExpressionLambdaArguments,
                textBlocks::renderUnformattedTextBlock,
                binaryExpr -> binaries.nestedLines(binaryExpr, true),
                compactSource::compact,
                compactSource::compactJoin,
                this::currentIndentedWidth,
                this::blockStatementWidth);
        this.assignments = new AssignmentExpressionPrinter(
                options,
                this::expression,
                compactSource::compact,
                this::blockStatementWidth,
                (expression, leadingBreak) -> suffixedEnclosedExpression(expression, leadingBreak),
                binaries::shouldKeepCastDivisionContinuationFlat,
                (expression, forceBreak) -> binaries.lines(expression, forceBreak),
                objectCreations::brokenObjectCreation,
                methodCalls::assignmentWithBrokenMethodCallArguments,
                conditionals::assignmentWithConditionalValue);
        this.returnExpressions = new ReturnExpressionPrinter(
                options,
                this::expression,
                compactSource::compact,
                this::currentIndentedWidth,
                methodCalls::forcedMethodCallChain,
                (expression, forceBreak) -> conditionals.conditionalExpression(expression, forceBreak),
                enclosedExpressions::parenthesizedBreak);
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
                declarationPrefixes::declarationAnnotations,
                declarationPrefixes::modifiers,
                declarationPrefixes::inlineAnnotations,
                compactSource::compactTypeLike,
                compactSource::compact,
                compactSource::compactWithoutOwnComment,
                compactSource::compactJoin,
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
                casts::castType,
                conditional -> conditionals.conditionalExpression(conditional, true),
                conditionals::shouldBreakBeforeConditionalInitializer,
                arrays::arrayCreationTypeBreaks,
                arrays::arrayCreationPrefix,
                arrays::arrayInitializer,
                objectCreations::objectCreationPrefix,
                types::typeNameWithoutArguments,
                types::brokenClassOrInterfaceType,
                methodCalls::shouldPrintScopeAsDoc,
                methodCalls::methodCallPrefix,
                lambdas::lambdaParameters,
                lambdas::lambdaParametersShouldBreak,
                lambdas::lambdaExpression);
        this.variableDeclarations = new VariableDeclarationPrinter(
                options,
                declarationPrefixes::annotations,
                declarationPrefixes::modifiers,
                compactSource::compactTypeLike,
                types::typeBody,
                types::typeCanBreak,
                fields::variable,
                this::currentIndentedWidth);
        this.callableSignatures = new CallableSignaturePrinter(
                comments,
                rawSource,
                options,
                compactSource::compact,
                compactSource::compactTypeLike,
                types::typeBody,
                declarationPrefixes::modifier,
                types::typeCanBreak,
                commentPlacement::unattachedTrailingBlockComment,
                commentPlacement::startsAfterNodeOnSameLine,
                this::commentText);
        this.classOrInterfaces = new ClassOrInterfaceDeclarationPrinter(
                comments,
                rawSource,
                options,
                new CommentedInterfacePrinter(),
                callableSignatures,
                declarationPrefixes::annotations,
                declarationPrefixes::modifiers,
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
                declarationPrefixes::annotations,
                declarationPrefixes::modifiers,
                compactSource::compactJoin,
                this::throwsClause,
                this::block);
        this.methods = new MethodDeclarationPrinter(
                comments,
                rawSource,
                commentedMethodSignatures,
                callableSignatures,
                declarationPrefixes::declarationAnnotations,
                declarationPrefixes::modifiers,
                types::flatTypeParameters,
                declarationPrefixes::inlineAnnotations,
                compactSource::compact,
                this::throwsClause,
                this::block);
        this.initializers = new InitializerDeclarationPrinter(comments, this::block);
        this.enums = new EnumDeclarationPrinter(
                comments,
                rawSource,
                options,
                declarationPrefixes::annotations,
                declarationPrefixes::modifiers,
                enumTypes -> types.typeClause("implements", enumTypes),
                types::implementsTypes,
                enumTypes -> types.flatTypeClause("implements", enumTypes),
                compactSource::compactJoin,
                this::expression,
                this::currentIndentedWidth,
                commentPlacement::startsAfterNodeOnSameLine,
                this::body);
        this.records = new RecordDeclarationPrinter(
                comments,
                options,
                declarationPrefixes::annotations,
                declarationPrefixes::modifiers,
                callableSignatures::typeParameters,
                types::flatTypeParameters,
                compactSource::compact,
                compactSource::compactJoin,
                types::compactJoinTypeLike,
                compactSource::compactTypeLike,
                annotationExpressions::annotation,
                annotationExpressions::annotationFlatText,
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
                returnExpressions::returnExpression,
                variableDeclarations::variableDeclaration,
                compactSource::compact,
                compactSource::compactWithoutOwnComment,
                compactSource::compactJoin,
                compactSource::compactTypeLike,
                types::compactJoinTypeLike,
                lambdas::huggableBlockLambdaArguments,
                methodCalls::forcedMethodCallChain,
                methodCalls::brokenMethodCall,
                methodCalls::methodCallChainHasComments,
                methodCalls::methodCallChainRootIsObjectCreation,
                methodCalls::methodCallChainRootIsFieldAccess,
                binaries::expressionHasParenthesizedNestedBinary,
                binaries::lines,
                controlConditions::controlCondition,
                controlConditions::compactWithOwnBlockComment,
                commentPlacement::ownSameLineBlockCommentBeforeNode,
                this::currentIndentedWidth);
        this.annotationDeclarations = new AnnotationDeclarationPrinter(
                comments,
                declarationPrefixes::annotations,
                declarationPrefixes::modifiers,
                compactSource::compactTypeLike,
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
        String exceptions = compactSource.compactJoin(thrownExceptions);
        String flatParameters = "(" + parameters.stream()
                .map(compactSource::compact)
                .reduce((left, right) -> left + ", " + right)
                .orElse("") + ")";
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

    private Doc brokenMethodCall(MethodCallExpr expression) {
        return methodCalls.brokenMethodCall(expression);
    }

    private Doc expression(Expression expression) {
        if (expression instanceof AssignExpr assignExpr) {
            return assignments.assignment(assignExpr);
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
            return annotationExpressions.annotation(annotationExpr);
        }
        if (expression instanceof BinaryExpr binaryExpr) {
            return binaries.binaryExpression(binaryExpr);
        }
        if (expression instanceof CastExpr castExpr) {
            return casts.castExpression(castExpr);
        }
        if (expression instanceof ConditionalExpr conditionalExpr) {
            return conditionals.conditionalExpression(conditionalExpr);
        }
        if (expression instanceof EnclosedExpr enclosedExpr) {
            return enclosedExpressions.enclosedExpression(enclosedExpr);
        }
        if (expression instanceof FieldAccessExpr fieldAccessExpr) {
            return fieldAccesses.fieldAccess(fieldAccessExpr);
        }
        if (expression instanceof InstanceOfExpr instanceOfExpr) {
            return instanceOfExpressions.instanceOfExpression(instanceOfExpr);
        }
        if (expression instanceof LambdaExpr lambdaExpr) {
            return lambdas.lambdaExpression(lambdaExpr);
        }
        if (expression instanceof MethodCallExpr methodCallExpr) {
            return methodCalls.methodCall(methodCallExpr);
        }
        if (expression instanceof MethodReferenceExpr methodReferenceExpr) {
            return methodReferences.methodReference(methodReferenceExpr);
        }
        if (expression instanceof ObjectCreationExpr objectCreationExpr) {
            return objectCreations.objectCreation(objectCreationExpr);
        }
        if (expression instanceof SwitchExpr switchExpr) {
            return switches.switchExpression(switchExpr);
        }
        if (expression instanceof TextBlockLiteralExpr textBlockLiteralExpr) {
            return textBlocks.textBlockLiteral(textBlockLiteralExpr);
        }
        return Doc.text(compactSource.compact(expression));
    }

    private Optional<Doc> suffixedEnclosedExpression(Expression expression, boolean leadingBreak) {
        if (expression instanceof MethodCallExpr methodCallExpr) {
            return methodCalls.suffixedEnclosedMethodCall(methodCallExpr, leadingBreak);
        }
        if (expression instanceof MethodReferenceExpr methodReferenceExpr) {
            return methodReferences.suffixedEnclosedMethodReference(methodReferenceExpr, leadingBreak);
        }
        return Optional.empty();
    }

    private String commentText(Doc comment) {
        if (comment instanceof Doc.Text text) {
            return text.value();
        }
        return "";
    }

    private Doc rawDeclaration(BodyDeclaration<?> declaration) {
        return Doc.concat(comments.leading(declaration), Doc.text(compactSource.compact(declaration)));
    }

}
