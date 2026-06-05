package dev.lanwen.frmtr.java;

import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ArrayCreationLevel;
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
import com.github.javaparser.ast.expr.RecordPatternExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.expr.TypePatternExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.modules.ModuleDeclaration;
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
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.stmt.YieldStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.IntersectionType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
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
    private final FormatterPragmas formatterPragmas = new FormatterPragmas();
    private final ModuleBlockPrinter moduleBlocks;
    private final MemberBlockPrinter memberBlocks;
    private final BlockPrinter blocks;
    private final CallableSignaturePrinter callableSignatures;
    private final ConstructorDeclarationPrinter constructors;
    private final MethodDeclarationPrinter methods;
    private final InitializerDeclarationPrinter initializers;
    private final EnumDeclarationPrinter enums;
    private final RecordDeclarationPrinter records;
    private final AnnotationDeclarationPrinter annotationDeclarations;
    private final CommentedMethodSignaturePrinter commentedMethodSignatures;
    private final CommentedModulePrinter commentedModules = new CommentedModulePrinter();
    private final CommentedInterfacePrinter commentedInterfaces = new CommentedInterfacePrinter();
    private final CompilationUnitPrinter compilationUnits;
    private final FieldDeclarationPrinter fields;

    JavaPrinter(FormatterOptions options) {
        this.options = options;
        this.rawSource = new RawSource(options);
        this.moduleBlocks = new ModuleBlockPrinter(comments, options, this::compact, this::compactJoin, this::modifiers);
        this.memberBlocks = new MemberBlockPrinter(rawSource, comments, this::hasDeclarationAnnotations);
        this.blocks = new BlockPrinter(comments, this::statement, formatterPragmas::hasPragma);
        this.commentedMethodSignatures = new CommentedMethodSignaturePrinter(options);
        PackageDeclarationPrinter packageDeclarations = new PackageDeclarationPrinter(comments, rawSource, options);
        ImportDeclarationPrinter importDeclarations = new ImportDeclarationPrinter(comments);
        this.compilationUnits = new CompilationUnitPrinter(
                comments,
                packageDeclarations,
                importDeclarations,
                this::moduleDeclaration,
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
                this::binaryExpressionHasLineComments,
                this::binaryExpressionLinesWithComments,
                (expression, leadingBreak) -> suffixedEnclosedExpression(expression, leadingBreak),
                this::arrayAccessWithBrokenEnclosedName,
                this::shouldKeepCastDivisionContinuationFlat,
                (expression, forceBreak) -> binaryExpressionLines(expression, forceBreak),
                this::methodCall,
                methodCall -> methodCall(methodCall, MethodCallMode.BREAK),
                this::mixedFieldMethodCallChain,
                methodCall -> methodCallChain(methodCall, true),
                this::mixedFieldMethodCallRoot,
                this::methodCallChainRoot,
                this::methodCallChainRootIsObjectCreation,
                this::castType,
                conditional -> conditionalExpression(conditional, true),
                this::shouldBreakBeforeConditionalInitializer,
                this::arrayCreationTypeBreaks,
                this::arrayCreationPrefix,
                (initializer, forceBreak) -> arrayInitializer(initializer, forceBreak),
                this::objectCreationPrefix,
                this::typeNameWithoutArguments,
                this::brokenClassOrInterfaceType,
                this::shouldPrintScopeAsDoc,
                this::methodCallPrefix,
                this::lambdaParameters,
                this::lambdaParametersShouldBreak,
                this::lambdaExpression);
        this.callableSignatures = new CallableSignaturePrinter(
                comments,
                rawSource,
                options,
                this::compact,
                this::compactTypeLike,
                this::typeBody,
                this::modifier,
                this::typeCanBreak,
                this::unattachedTrailingBlockComment,
                this::startsAfterNodeOnSameLine,
                this::commentText);
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
                this::flatTypeParameters,
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
                types -> typeClause("implements", types),
                this::implementsTypes,
                types -> flatTypeClause("implements", types),
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
                this::flatTypeParameters,
                this::compact,
                this::compactJoin,
                this::compactJoinTypeLike,
                this::compactTypeLike,
                this::annotation,
                this::annotationFlatText,
                this::currentIndentedWidth,
                declaration -> memberBlocks.memberBlock(declaration.getMembers(), declaration, this::body));
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

    private Doc moduleDeclaration(ModuleDeclaration declaration) {
        String raw = rawSource.raw(declaration);
        if (raw.contains("/*") || raw.contains("//")) {
            Doc leadingBlock = comments.ownComment(declaration, BlockComment.class::isInstance);
            String leadingText = commentText(leadingBlock);
            String commentedRaw = leadingText.isEmpty() ? raw : leadingText + raw;
            return Doc.text(commentedModules.formatCommentedModule(commentedRaw));
        }
        return Doc.concat(
                comments.leading(declaration),
                annotations(declaration),
                Doc.text((declaration.isOpen() ? "open " : "") + "module " + compact(declaration.getName()) + " "),
                moduleBlocks.moduleBlock(declaration));
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
            case ClassOrInterfaceDeclaration classDeclaration -> classOrInterface(classDeclaration);
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

    private Doc classOrInterface(ClassOrInterfaceDeclaration declaration) {
        String raw = rawSource.raw(declaration);
        if (declaration.isInterface() && commentedInterfaces.hasCommentedHeader(raw)) {
            return Doc.concat(comments.leading(declaration), Doc.text(commentedInterfaces.formatCommentedInterface(raw)));
        }
        if (shouldBreakClassOrInterfaceHeader(declaration)) {
            return brokenClassOrInterface(declaration);
        }
        List<Doc> header = new ArrayList<>();
        header.add(comments.leading(declaration));
        header.add(annotations(declaration));
        header.add(Doc.text(modifiers(declaration)));
        header.add(Doc.text(declaration.isInterface() ? "interface " : "class "));
        header.add(Doc.text(declaration.getNameAsString()));
        if (!declaration.getTypeParameters().isEmpty()) {
            header.add(callableSignatures.typeParameters(declaration.getTypeParameters()));
        }
        extendsTypes(declaration.getExtendedTypes()).ifPresent(header::add);
        implementsTypes(declaration.getImplementedTypes()).ifPresent(header::add);
        permitsTypes(declaration.getPermittedTypes()).ifPresent(header::add);
        header.add(Doc.text(" "));
        header.add(memberBlocks.memberBlock(declaration.getMembers(), declaration, this::body));
        return Doc.concat(header);
    }

    private boolean shouldBreakClassOrInterfaceHeader(ClassOrInterfaceDeclaration declaration) {
        if (declaration.getExtendedTypes().isEmpty()
                && declaration.getImplementedTypes().isEmpty()
                && declaration.getPermittedTypes().isEmpty()) {
            return false;
        }
        String flatHeader = modifiers(declaration)
                + (declaration.isInterface() ? "interface " : "class ")
                + declaration.getNameAsString()
                + flatTypeParameters(declaration.getTypeParameters())
                + flatTypeClause("extends", declaration.getExtendedTypes())
                + flatTypeClause("implements", declaration.getImplementedTypes())
                + flatTypeClause("permits", declaration.getPermittedTypes());
        return flatHeader.length() + 1 + flatMemberBlockWidth(declaration) > options.lineWidth();
    }

    private Doc brokenClassOrInterface(ClassOrInterfaceDeclaration declaration) {
        List<Doc> header = new ArrayList<>();
        header.add(comments.leading(declaration));
        header.add(annotations(declaration));
        header.add(Doc.text(modifiers(declaration)));
        header.add(Doc.text(declaration.isInterface() ? "interface " : "class "));
        header.add(Doc.text(declaration.getNameAsString()));
        boolean breakTypeParameters = classOrInterfaceTypeParametersBreak(declaration);
        if (breakTypeParameters) {
            header.add(callableSignatures.brokenTypeParameters(
                    declaration.getTypeParameters(),
                    classOrInterfaceHeaderClauses(declaration) > 1));
        } else if (!declaration.getTypeParameters().isEmpty()) {
            header.add(callableSignatures.typeParameters(declaration.getTypeParameters()));
        }
        boolean breakClauses = classOrInterfaceHeaderClauses(declaration) > 1 || !breakTypeParameters;
        typeClause("extends", declaration.getExtendedTypes(), breakClauses).ifPresent(header::add);
        typeClause("implements", declaration.getImplementedTypes(), breakClauses).ifPresent(header::add);
        typeClause("permits", declaration.getPermittedTypes(), breakClauses).ifPresent(header::add);
        header.add(emptyMemberBlock(declaration) || !breakClauses ? Doc.text(" ") : Doc.HARD_LINE);
        header.add(memberBlocks.memberBlock(declaration.getMembers(), declaration, this::body));
        return Doc.concat(header);
    }

    private boolean classOrInterfaceTypeParametersBreak(ClassOrInterfaceDeclaration declaration) {
        if (declaration.getTypeParameters().isEmpty()) {
            return false;
        }
        if (classOrInterfaceHeaderClauses(declaration) > 1) {
            String headerHead = modifiers(declaration)
                    + (declaration.isInterface() ? "interface " : "class ")
                    + declaration.getNameAsString()
                    + flatTypeParameters(declaration.getTypeParameters());
            return currentIndentedWidth(headerHead) > options.lineWidth();
        }
        if (declaration.getTypeParameters().size() > 2) {
            return true;
        }
        return classOrInterfaceHeaderClauses(declaration) == 1
                && declaration.getExtendedTypes().stream().anyMatch(this::hasTypeArguments);
    }

    private int classOrInterfaceHeaderClauses(ClassOrInterfaceDeclaration declaration) {
        int clauses = 0;
        if (!declaration.getExtendedTypes().isEmpty()) {
            clauses++;
        }
        if (!declaration.getImplementedTypes().isEmpty()) {
            clauses++;
        }
        if (!declaration.getPermittedTypes().isEmpty()) {
            clauses++;
        }
        return clauses;
    }

    private boolean hasTypeArguments(ClassOrInterfaceType type) {
        return type.getTypeArguments().map(arguments -> !arguments.isEmpty()).orElse(false);
    }

    private int typeArgumentCount(ClassOrInterfaceType type) {
        return type.getTypeArguments().map(NodeList::size).orElse(0);
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

    private boolean shouldBreakBeforeConditionalInitializer(ConditionalExpr initializer) {
        return initializer.getCondition() instanceof BinaryExpr
                && (initializer.getThenExpr() instanceof BinaryExpr || initializer.getElseExpr() instanceof BinaryExpr);
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
        Doc body = switch (statement) {
            case BlockStmt blockStmt -> block(blockStmt);
            case ReturnStmt returnStmt -> returnStatement(returnStmt);
            case ThrowStmt throwStmt -> Doc.concat(Doc.text("throw "), expression(throwStmt.getExpression()), Doc.text(";"));
            case YieldStmt yieldStmt -> yieldStatement(yieldStmt);
            case ExplicitConstructorInvocationStmt constructorInvocation -> Doc.concat(explicitConstructorInvocation(constructorInvocation), Doc.text(";"));
            case ExpressionStmt expressionStmt -> expressionStatement(expressionStmt);
            case EmptyStmt ignored -> Doc.text(";");
            case AssertStmt assertStmt -> assertStatement(assertStmt);
            case BreakStmt breakStmt -> breakStatement(breakStmt);
            case ContinueStmt continueStmt -> continueStatement(continueStmt);
            case LabeledStmt labeledStmt -> labeledStatement(labeledStmt);
            case LocalClassDeclarationStmt localClassDeclaration -> body(localClassDeclaration.getClassDeclaration());
            case LocalRecordDeclarationStmt localRecordDeclaration -> body(localRecordDeclaration.getRecordDeclaration());
            case IfStmt ifStmt -> ifStatement(ifStmt);
            case WhileStmt whileStmt -> whileStatement(whileStmt);
            case DoStmt doStmt -> doStatement(doStmt);
            case TryStmt tryStmt -> tryStatement(tryStmt);
            case SynchronizedStmt synchronizedStmt -> Doc.concat(
                    Doc.text("synchronized "),
                    controlCondition(synchronizedStmt.getExpression()),
                    Doc.text(" "),
                    block(synchronizedStmt.getBody()));
            case SwitchStmt switchStmt -> switchStatement(switchStmt);
            case ForStmt forStmt -> forStatement(forStmt);
            case ForEachStmt forEachStmt -> forEachStatement(forEachStmt);
            default -> Doc.text(compact(statement));
        };
        return Doc.concat(leading, body, trailing == Doc.EMPTY ? Doc.EMPTY : Doc.concat(Doc.text(" "), trailing));
    }

    private Doc rawStatement(Statement statement) {
        Doc leading = statement instanceof TryStmt ? Doc.EMPTY : comments.leading(statement);
        return Doc.concat(leading, Doc.text(rawSource.rawWithoutOwnComment(statement)));
    }

    private Doc breakStatement(BreakStmt statement) {
        Doc leadingBlockComment = comments.ownComment(statement, BlockComment.class::isInstance);
        String prefix = leadingBlockComment == Doc.EMPTY ? "" : commentText(leadingBlockComment) + " ";
        return Doc.text(prefix + "break" + statement.getLabel().map(label -> " " + label.asString()).orElse("") + ";"
                + trailingStatementBlockComment(statement));
    }

    private Doc continueStatement(ContinueStmt statement) {
        return Doc.text("continue" + statement.getLabel().map(this::continueLabel).orElse("") + ";");
    }

    private Doc assertStatement(AssertStmt statement) {
        String message = statement.getMessage().map(expression -> " : " + compact(expression)).orElse("");
        return Doc.text("assert " + compactWithOwnBlockComment(statement.getCheck()) + message + ";");
    }

    private String trailingStatementBlockComment(Statement statement) {
        String raw = rawSource.raw(statement);
        int commentStart = raw.indexOf("/*");
        int semicolon = raw.lastIndexOf(';');
        if (commentStart < 0 || semicolon < commentStart) {
            return "";
        }
        int commentEnd = raw.indexOf("*/", commentStart);
        if (commentEnd < 0 || commentEnd > semicolon) {
            return "";
        }
        return " " + raw.substring(commentStart, commentEnd + 2);
    }

    private String continueLabel(com.github.javaparser.ast.expr.SimpleName label) {
        Doc labelComment = comments.ownComment(label, BlockComment.class::isInstance);
        return labelComment == Doc.EMPTY
                ? " " + label.asString()
                : " " + commentText(labelComment) + " " + label.asString();
    }

    private Doc returnStatement(ReturnStmt statement) {
        return statement.getExpression()
                .map(expression -> Doc.concat(Doc.text("return "), returnExpression(expression), Doc.text(";")))
                .orElse(Doc.text("return;" + trailingStatementBlockComment(statement)));
    }

    private Doc labeledStatement(LabeledStmt statement) {
        Doc label = Doc.text(statement.getLabel().asString() + ": ");
        List<String> leadingComments = labeledStatementLeadingComments(statement);
        if (!leadingComments.isEmpty()) {
            consumeLabeledBodyLeadingLineComment(statement.getStatement());
        }
        Doc labeledBody = labeledStatementBody(statement.getStatement());
        Doc body = Doc.concat(label, labeledBody);
        if (leadingComments.isEmpty()) {
            return body;
        }
        return Doc.concat(
                Doc.join(Doc.HARD_LINE, leadingComments.stream().map(Doc::text).toList()),
                Doc.HARD_LINE,
                body);
    }

    private void consumeLabeledBodyLeadingLineComment(Statement statement) {
        comments.ownComment(statement, LineComment.class::isInstance);
    }

    private Doc labeledStatementBody(Statement statement) {
        if (statement instanceof ForEachStmt forEachStmt
                && forEachStmt.getBody().isBlockStmt()
                && forEachStmt.getBody().asBlockStmt().getStatements().isEmpty()
                && forEachStmt.getBody().asBlockStmt().getOrphanComments().isEmpty()) {
            return Doc.text("for (" + compact(forEachStmt.getVariable()) + " : " + compact(forEachStmt.getIterable()) + ") {}");
        }
        if (statement instanceof BlockStmt blockStmt) {
            return block(blockStmt);
        }
        return statement(statement);
    }

    private List<String> labeledStatementLeadingComments(LabeledStmt statement) {
        String raw = rawSource.raw(statement);
        int colon = raw.indexOf(':');
        if (colon < 0) {
            return List.of();
        }
        String labelBody = raw.substring(colon + 1);
        int statementStart = labeledNestedStatementStart(labelBody);
        if (statementStart < 0) {
            return List.of();
        }
        String comments = labelBody.substring(0, statementStart);
        List<String> lines = new ArrayList<>();
        for (String line : comments.split("\\R", -1)) {
            String stripped = line.strip();
            if (stripped.isEmpty()) {
                if (!lines.isEmpty() && !lines.getLast().isEmpty()) {
                    lines.add("");
                }
                continue;
            }
            if (isCommentOnlyLine(stripped)) {
                lines.add(stripped);
            }
        }
        while (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
        return lines;
    }

    private int labeledNestedStatementStart(String labelBody) {
        int cursor = 0;
        for (String line : labelBody.split("\\R", -1)) {
            String stripped = line.stripLeading();
            if (stripped.startsWith("for") || stripped.startsWith("{")) {
                return cursor + line.indexOf(stripped);
            }
            cursor += line.length() + 1;
        }
        return -1;
    }

    private Doc returnExpression(Expression expression) {
        String flatReturn = "return " + compact(expression) + ";";
        if (currentIndentedWidth(flatReturn) <= options.lineWidth()) {
            return expression(expression);
        }
        if (expression instanceof MethodCallExpr methodCall) {
            Optional<Doc> chain = methodCallChain(methodCall, true);
            if (chain.isPresent()) {
                return chain.orElseThrow();
            }
        }
        if (expression instanceof ConditionalExpr conditionalExpr) {
            return conditionalExpression(conditionalExpr, true);
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
                Doc.indent(Doc.concat(Doc.HARD_LINE, binaryExpressionLines(expression, forceBinaryBreak))),
                Doc.HARD_LINE,
                Doc.text(")"));
    }

    private Doc binaryExpressionLines(Expression expression) {
        return binaryExpressionLines(expression, false);
    }

    private Doc binaryExpressionLines(Expression expression, boolean forceBreak) {
        return binaryExpressionLines(expression, forceBreak, false);
    }

    private Doc nestedBinaryExpressionLines(Expression expression, boolean forceBreak) {
        return binaryExpressionLines(expression, forceBreak, true);
    }

    private Doc binaryExpressionLines(Expression expression, boolean forceBreak, boolean nestedContinuation) {
        if (!(expression instanceof BinaryExpr binaryExpr)) {
            return expression(expression);
        }
        if (!forceBreak && parenthesizedInnerWidth(compact(binaryExpr)) <= options.lineWidth()) {
            return expression(binaryExpr);
        }
        List<Expression> operands = new ArrayList<>();
        flattenBinaryExpression(binaryExpr, binaryExpr.getOperator(), operands);
        if (binaryExpr.getOperator() == BinaryExpr.Operator.AND
                && operands.size() == 2
                && operands.getFirst() instanceof InstanceOfExpr instanceOfExpr
                && parenthesizedInnerWidth(compact(instanceOfExpr)) > options.lineWidth()) {
            return Doc.concat(expression(instanceOfExpr), Doc.text(" && "), expression(operands.getLast()));
        }
        if (options.binaryOperatorPosition() == FormatterOptions.BinaryOperatorPosition.END
                && operands.size() == 2
                && operands.getFirst() instanceof MethodCallExpr methodCall
                && shouldBreakEndPositionMethodCallOperand(binaryExpr.getOperator(), methodCall)
                && continuationStatementWidth(") " + binaryExpr.getOperator().asString() + " " + compact(operands.getLast()))
                        <= options.lineWidth()) {
            return Doc.concat(
                    methodCall(methodCall, MethodCallMode.BREAK),
                    Doc.text(" " + binaryExpr.getOperator().asString() + " "),
                    expression(operands.getLast()));
        }
        List<Doc> lines = new ArrayList<>();
        for (int i = 0; i < operands.size(); i++) {
            Expression operandExpression = operands.get(i);
            Doc operand = binaryExpressionLineOperand(binaryExpr.getOperator(), operandExpression);
            if (options.binaryOperatorPosition() == FormatterOptions.BinaryOperatorPosition.END
                    && i < operands.size() - 1
                    && shouldBreakEndPositionMethodCallOperand(binaryExpr.getOperator(), operandExpression)) {
                MethodCallExpr methodCall = (MethodCallExpr) operandExpression;
                operand = methodCall(methodCall, MethodCallMode.BREAK);
            }
            if (options.binaryOperatorPosition() == FormatterOptions.BinaryOperatorPosition.START && i > 0) {
                operand = Doc.concat(Doc.text(binaryExpr.getOperator().asString() + " "), operand);
            } else if (options.binaryOperatorPosition() == FormatterOptions.BinaryOperatorPosition.END && i < operands.size() - 1) {
                operand = Doc.concat(operand, Doc.text(" " + binaryExpr.getOperator().asString()));
            }
            lines.add(operand);
        }
        if (nestedContinuation) {
            List<Doc> nestedLines = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                Doc line = lines.get(i);
                nestedLines.add(i == 0 ? line : Doc.indent(Doc.concat(Doc.HARD_LINE, line)));
            }
            return Doc.concat(nestedLines);
        }
        return Doc.join(Doc.HARD_LINE, lines);
    }

    private Doc binaryExpressionLineOperand(BinaryExpr.Operator operator, Expression operand) {
        if (operator == BinaryExpr.Operator.OR
                && operand instanceof BinaryExpr binaryOperand
                && binaryOperand.getOperator() == BinaryExpr.Operator.AND) {
            if (parenthesizedInnerWidth(compact(binaryOperand)) > options.lineWidth()) {
                return Doc.concat(Doc.text("("), nestedBinaryExpressionLines(binaryOperand, true), Doc.text(")"));
            }
            return Doc.concat(Doc.text("("), expression(binaryOperand), Doc.text(")"));
        }
        if (operand instanceof BinaryExpr binaryOperand
                && shouldParenthesizeNestedBinary(operator, binaryOperand.getOperator())) {
            return Doc.concat(Doc.text("("), expression(binaryOperand), Doc.text(")"));
        }
        return expression(operand);
    }

    private boolean shouldKeepCastDivisionContinuationFlat(BinaryExpr expression) {
        return expression.getOperator() == BinaryExpr.Operator.DIVIDE
                && expression.getLeft() instanceof CastExpr
                && blockStatementWidth(compact(expression)) <= options.lineWidth();
    }

    private boolean shouldBreakEndPositionMethodCallOperand(BinaryExpr.Operator operator, Expression operand) {
        return operand instanceof MethodCallExpr methodCall
                && !methodCall.getArguments().isEmpty()
                && continuationStatementWidth(compact(methodCall) + " " + operator.asString()) > options.lineWidth();
    }

    private boolean binaryExpressionHasLineComments(BinaryExpr expression) {
        return expression.getAllContainedComments().stream().anyMatch(LineComment.class::isInstance);
    }

    private Doc binaryExpressionLinesWithComments(BinaryExpr expression) {
        List<Expression> operands = new ArrayList<>();
        flattenBinaryExpression(expression, expression.getOperator(), operands);
        List<Doc> lines = new ArrayList<>();
        for (int i = 0; i < operands.size(); i++) {
            Expression operand = operands.get(i);
            Doc line = Doc.text(binaryLineOperandText(expression.getOperator(), operand, i, operands.size()));
            List<Comment> between = i < operands.size() - 1
                    ? binaryCommentsBetween(expression, operand, operands.get(i + 1))
                    : List.of();
            if (options.binaryOperatorPosition() == FormatterOptions.BinaryOperatorPosition.END) {
                List<Comment> sameLineComments = sameLineComments(operand, between);
                for (Comment comment : sameLineComments) {
                    line = Doc.concat(line, Doc.text(" "), comments.comment(comment));
                }
                between = between.stream()
                        .filter(comment -> !sameLineComments.contains(comment))
                        .toList();
            }
            lines.add(line);
            if (i < operands.size() - 1) {
                lines.addAll(commentDocs(between));
            }
        }
        return Doc.join(Doc.HARD_LINE, lines);
    }

    private String binaryLineOperandText(BinaryExpr.Operator operator, Expression operand, int index, int operandCount) {
        String text = compactWithoutOwnComment(operand);
        if (options.binaryOperatorPosition() == FormatterOptions.BinaryOperatorPosition.START) {
            return index == 0 ? text : operator.asString() + " " + text;
        }
        return index < operandCount - 1 ? text + " " + operator.asString() : text;
    }

    private List<Comment> binaryCommentsBetween(BinaryExpr expression, Expression previous, Expression next) {
        int previousLine = previous.getRange().map(range -> range.end.line).orElse(Integer.MIN_VALUE);
        int nextLine = next.getRange().map(range -> range.begin.line).orElse(Integer.MAX_VALUE);
        return expression.getAllContainedComments().stream()
                .filter(LineComment.class::isInstance)
                .filter(comment -> comment.getRange()
                        .map(range -> range.begin.line >= previousLine && range.begin.line < nextLine)
                        .orElse(false))
                .sorted(Comparator.comparing(comment -> comment.getRange()
                        .map(range -> range.begin)
                        .orElse(Position.HOME)))
                .toList();
    }

    private List<Comment> sameLineComments(Expression expression, List<Comment> comments) {
        int expressionEndLine = expression.getRange().map(range -> range.end.line).orElse(Integer.MIN_VALUE);
        return comments.stream()
                .filter(comment -> comment.getRange()
                        .map(range -> range.begin.line == expressionEndLine)
                        .orElse(false))
                .toList();
    }

    private List<Doc> commentDocs(List<Comment> sourceComments) {
        return sourceComments.stream()
                .map(comments::comment)
                .filter(doc -> doc != Doc.EMPTY)
                .toList();
    }

    private int parenthesizedInnerWidth(String text) {
        return (options.indentUnit().length() * 2) + text.length();
    }

    private void flattenBinaryExpression(
            Expression expression,
            BinaryExpr.Operator operator,
            List<Expression> operands) {
        if (expression instanceof BinaryExpr binaryExpr && binaryExpr.getOperator() == operator) {
            flattenBinaryExpression(binaryExpr.getLeft(), operator, operands);
            flattenBinaryExpression(binaryExpr.getRight(), operator, operands);
            return;
        }
        operands.add(expression);
    }

    private Doc yieldStatement(YieldStmt statement) {
        if (compact(statement.getExpression()).equals("()")) {
            return Doc.text("yield();");
        }
        return Doc.concat(Doc.text("yield "), expression(statement.getExpression()), Doc.text(";"));
    }

    private Doc explicitConstructorInvocation(ExplicitConstructorInvocationStmt statement) {
        String prefix = statement.getExpression().map(expression -> compact(expression) + ".").orElse("")
                + statement.getTypeArguments().map(typeArguments -> "<" + compactJoinTypeLike(typeArguments) + ">").orElse("")
                + (statement.isThis() ? "this" : "super");
        if (statement.getArguments().isEmpty()) {
            return Doc.text(prefix + "()");
        }
        Optional<Doc> huggableLambda = huggableBlockLambdaArguments(prefix, statement.getArguments());
        if (huggableLambda.isPresent()) {
            return huggableLambda.orElseThrow();
        }
        return Doc.group(Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(
                        Doc.SOFT_LINE,
                        Doc.join(Doc.concat(Doc.text(","), Doc.LINE), statement.getArguments().stream()
                                .map(this::expression)
                                .toList()))),
                Doc.SOFT_LINE,
                Doc.text(")")));
    }

    private Doc expressionStatement(ExpressionStmt statement) {
        Expression expression = statement.getExpression();
        if (expression instanceof VariableDeclarationExpr variableDeclaration) {
            return Doc.concat(variableDeclaration(variableDeclaration), Doc.text(";"));
        }
        if (expression instanceof MethodCallExpr methodCall
                && blockStatementWidth(compact(expression) + ";") > options.lineWidth()) {
            boolean chainBreak = methodCallChainHasComments(methodCall)
                    || methodCallChainRootIsObjectCreation(methodCall)
                    || !methodCallChainRootIsFieldAccess(methodCall);
            return Doc.concat(
                    chainBreak
                            ? methodCallChain(methodCall, true).orElseGet(() -> methodCall(methodCall, MethodCallMode.BREAK))
                            : methodCall(methodCall, MethodCallMode.BREAK),
                    Doc.text(";"));
        }
        Optional<Comment> trailingConditionalComment = conditionalElseStatementTrailingComment(statement);
        Doc trailing = trailingConditionalComment
                .map(comment -> Doc.concat(Doc.text(" "), comments.comment(comment)))
                .orElse(Doc.EMPTY);
        return Doc.concat(expression(expression), Doc.text(";"), trailing);
    }

    private Optional<Comment> conditionalElseStatementTrailingComment(ExpressionStmt statement) {
        return statement.getExpression().findAll(ConditionalExpr.class).stream()
                .flatMap(conditionalExpr -> conditionalExpr.getElseExpr().getComment()
                        .filter(LineComment.class::isInstance)
                        .filter(comment -> startsAfterNodeOnSameLine(statement, comment))
                        .stream())
                .findFirst();
    }

    private Doc tryStatement(TryStmt statement) {
        List<Doc> docs = new ArrayList<>();
        docs.add(Doc.text("try"));
        docs.add(tryResources(statement));
        docs.add(Doc.text(" "));
        docs.add(tryBlock(statement.getTryBlock()));
        Doc previousBlockTrailingComment = comments.trailingLineComment(statement.getTryBlock());
        for (int i = 0; i < statement.getCatchClauses().size(); i++) {
            CatchClause clause = statement.getCatchClauses().get(i);
            Doc catchPrefixComment = ownBlockCommentBeforeNode(clause);
            docs.add(Doc.text(" "));
            if (catchPrefixComment != Doc.EMPTY) {
                docs.add(catchPrefixComment);
                docs.add(Doc.text(" "));
            }
            docs.add(catchClause(
                    clause,
                    statement.getCatchClauses().size(),
                    statement.getFinallyBlock().isPresent(),
                    Doc.concat(previousBlockTrailingComment, ownLineCommentBeforeNode(clause))));
            previousBlockTrailingComment = trailingCommentAfterClause(clause);
        }
        if (statement.getFinallyBlock().isPresent()) {
            BlockStmt finallyBlock = statement.getFinallyBlock().orElseThrow();
            Doc finallyPrefixComment = ownBlockCommentBeforeNode(finallyBlock);
            docs.add(Doc.text(" "));
            if (finallyPrefixComment != Doc.EMPTY) {
                docs.add(finallyPrefixComment);
                docs.add(Doc.text(" "));
            }
            docs.add(Doc.text("finally "));
            docs.add(tryBlock(finallyBlock, Doc.concat(previousBlockTrailingComment, ownLineCommentBeforeNode(finallyBlock))));
        }
        Doc finalTrailingComment = statement.getFinallyBlock()
                .map(comments::trailingLineComment)
                .orElse(previousBlockTrailingComment);
        if (finalTrailingComment == Doc.EMPTY) {
            finalTrailingComment = rawTrailingLineComment(statement);
        }
        if (finalTrailingComment == Doc.EMPTY) {
            finalTrailingComment = parentOrphanCommentOnEndLine(statement);
        }
        Doc tryStatementTrailingComment = comments.trailingLineComment(statement);
        if (finalTrailingComment != Doc.EMPTY) {
            docs.add(Doc.text(" "));
            docs.add(finalTrailingComment);
        }
        if (tryStatementTrailingComment != Doc.EMPTY) {
            docs.add(Doc.text(" "));
            docs.add(tryStatementTrailingComment);
        }
        return Doc.concat(docs);
    }

    private Doc tryResources(TryStmt statement) {
        if (statement.getResources().isEmpty()) {
            return Doc.EMPTY;
        }
        boolean trailingSemicolon = tryResourcesHaveTrailingSemicolon(statement);
        String flatResources = statement.getResources().stream()
                .map(this::compact)
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
        if (trailingSemicolon) {
            flatResources += ";";
        }
        String flat = "try (" + flatResources + ")";
        if (statement.getResources().size() == 1 && currentIndentedWidth(flat + " {}") <= options.lineWidth()) {
            return Doc.text(" (" + flatResources + ")");
        }
        return Doc.concat(
                Doc.text(" ("),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        Doc.join(
                                Doc.concat(Doc.text(";"), Doc.HARD_LINE),
                                statement.getResources().stream().map(resource -> Doc.text(compact(resource))).toList()),
                        trailingSemicolon ? Doc.text(";") : Doc.EMPTY)),
                Doc.HARD_LINE,
                Doc.text(")"));
    }

    private boolean tryResourcesHaveTrailingSemicolon(TryStmt statement) {
        String raw = rawSource.rawWithoutOwnComment(statement);
        int resourceStart = raw.indexOf('(');
        int blockStart = raw.indexOf('{', resourceStart);
        if (resourceStart < 0 || blockStart < 0) {
            return false;
        }
        int resourceEnd = raw.substring(resourceStart, blockStart).lastIndexOf(')');
        if (resourceEnd < 0) {
            return false;
        }
        return raw.substring(resourceStart + 1, resourceStart + resourceEnd).stripTrailing().endsWith(";");
    }

    private Doc trailingCommentAfterClause(CatchClause clause) {
        Doc bodyTrailing = comments.trailingLineComment(clause.getBody());
        if (bodyTrailing != Doc.EMPTY) {
            return bodyTrailing;
        }
        return comments.trailingLineComment(clause);
    }

    private Doc ownBlockCommentBeforeNode(Node node) {
        return comments.ownComment(node, comment -> comment instanceof BlockComment
                && comment.getRange()
                        .flatMap(commentRange -> node.getRange().map(nodeRange -> startsBefore(commentRange, nodeRange)))
                        .orElse(false));
    }

    private Doc ownSameLineBlockCommentBeforeNode(Node node) {
        return comments.ownComment(node, comment -> comment instanceof BlockComment
                && comment.getRange()
                        .flatMap(commentRange -> node.getRange()
                                .map(nodeRange -> commentRange.begin.line == nodeRange.begin.line
                                        && startsBefore(commentRange, nodeRange)))
                        .orElse(false));
    }

    private Doc ownLineCommentBeforeNode(Node node) {
        return comments.ownComment(node, comment -> comment instanceof LineComment
                && comment.getRange()
                        .flatMap(commentRange -> node.getRange()
                                .map(nodeRange -> commentRange.begin.line < nodeRange.begin.line))
                        .orElse(false));
    }

    private Doc rawTrailingLineComment(Node node) {
        String raw = node.getTokenRange().map(Object::toString).orElse("");
        int lastBrace = raw.lastIndexOf('}');
        if (lastBrace < 0) {
            return Doc.EMPTY;
        }
        int commentStart = raw.indexOf("//", lastBrace);
        if (commentStart < 0 || raw.substring(lastBrace, commentStart).contains("\n")) {
            return Doc.EMPTY;
        }
        int commentEnd = raw.indexOf('\n', commentStart);
        String comment = commentEnd < 0 ? raw.substring(commentStart) : raw.substring(commentStart, commentEnd);
        return Doc.text(comment.stripTrailing());
    }

    private Doc parentOrphanCommentOnEndLine(Node node) {
        return node.getParentNode()
                .filter(BlockStmt.class::isInstance)
                .map(BlockStmt.class::cast)
                .map(parent -> Doc.concat(comments.orphanCommentStatements(parent, comment -> comment.getRange()
                        .flatMap(commentRange -> node.getRange()
                                .map(nodeRange -> commentRange.begin.line == nodeRange.end.line))
                        .orElse(false))))
                .orElse(Doc.EMPTY);
    }

    private Doc tryBlock(BlockStmt block) {
        return tryBlock(block, Doc.EMPTY);
    }

    private Doc tryBlock(BlockStmt block, Doc leadingInside) {
        if (block.getStatements().isEmpty() && block.getOrphanComments().isEmpty() && leadingInside == Doc.EMPTY) {
            return Doc.concat(Doc.text("{"), Doc.HARD_LINE, Doc.text("}"));
        }
        return blocks.blockWithLeading(block, leadingInside);
    }

    private Doc catchClause(CatchClause clause, int catchCount, boolean hasFinally, Doc leadingInside) {
        boolean compactEmptyBody = catchCount == 1
                && !hasFinally
                && clause.getBody().getStatements().isEmpty()
                && clause.getBody().getOrphanComments().isEmpty()
                && leadingInside == Doc.EMPTY;
        return Doc.concat(
                Doc.text("catch ("),
                catchParameter(clause.getParameter()),
                Doc.text(") "),
                compactEmptyBody ? Doc.text("{}") : tryBlock(clause.getBody(), leadingInside));
    }

    private Doc catchParameter(Parameter parameter) {
        if (parameterHasComments(parameter) && compact(parameter).contains("|")) {
            return commentedCatchParameter(parameter);
        }
        String flat = compact(parameter);
        if (!flat.contains("|") || currentIndentedWidth("catch (" + flat + ") {}") <= options.lineWidth()) {
            return Doc.text(flat);
        }
        String type = compactTypeLike(parameter.getType());
        List<String> parts = List.of(type.split("\\s*\\|\\s*"));
        List<Doc> lines = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            String prefix = i == 0 ? "" : "| ";
            String suffix = i == parts.size() - 1 ? " " + parameter.getNameAsString() : "";
            lines.add(Doc.text(prefix + parts.get(i) + suffix));
        }
        return Doc.concat(Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, lines))), Doc.HARD_LINE);
    }

    private boolean parameterHasComments(Parameter parameter) {
        return parameter.getComment().filter(BlockComment.class::isInstance).isPresent()
                || !parameter.getAllContainedComments().isEmpty();
    }

    private Doc commentedCatchParameter(Parameter parameter) {
        String rawType = parameter.getType().getTokenRange()
                .map(Object::toString)
                .orElseGet(() -> compactTypeLike(parameter.getType()));
        List<String> parts = List.of(rawType.split("\\s*\\|\\s*"));
        Doc leading = comments.ownComment(parameter, BlockComment.class::isInstance);
        List<Doc> lines = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            String type = CommentedTokenText.tokenLine(CommentedTokenText.tokens(parts.get(i).strip()));
            if (i == 0 && leading != Doc.EMPTY) {
                type = commentText(leading) + " " + type;
            }
            String prefix = i == 0 ? "" : "| ";
            String suffix = i == parts.size() - 1 ? " " + parameter.getNameAsString() : "";
            lines.add(Doc.text(prefix + type + suffix));
        }
        return Doc.concat(Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, lines))), Doc.HARD_LINE);
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
                    if (shouldKeepCastDivisionContinuationFlat(binaryExpr)) {
                        return Doc.concat(
                                expression(assignExpr.getTarget()),
                                Doc.text(" " + assignExpr.getOperator().asString()),
                                Doc.indent(Doc.concat(Doc.HARD_LINE, expression(binaryExpr))));
                    }
                    return Doc.concat(
                            expression(assignExpr.getTarget()),
                            Doc.text(" " + assignExpr.getOperator().asString()),
                            Doc.indent(Doc.concat(Doc.HARD_LINE, binaryExpressionLines(assignExpr.getValue(), true))));
                }
                if (assignExpr.getValue() instanceof ObjectCreationExpr objectCreationExpr
                        && objectCreationExpr.getAnonymousClassBody().isEmpty()) {
                    return Doc.concat(
                            expression(assignExpr.getTarget()),
                            Doc.text(" " + assignExpr.getOperator().asString() + " "),
                            objectCreation(objectCreationExpr, MethodCallMode.BREAK));
                }
                if (assignExpr.getValue() instanceof MethodCallExpr methodCall) {
                    Optional<Doc> methodCallAssignment = assignmentWithBrokenMethodCallArguments(assignExpr, methodCall);
                    if (methodCallAssignment.isPresent()) {
                        return methodCallAssignment.orElseThrow();
                    }
                }
                if (assignExpr.getValue() instanceof ConditionalExpr conditionalExpr) {
                    Optional<Doc> conditionalAssignment = assignmentWithConditionalValue(assignExpr, conditionalExpr);
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
            return arrayAccess(arrayAccessExpr);
        }
        if (expression instanceof ArrayCreationExpr arrayCreationExpr) {
            return arrayCreation(arrayCreationExpr);
        }
        if (expression instanceof ArrayInitializerExpr arrayInitializerExpr) {
            return arrayInitializer(arrayInitializerExpr);
        }
        if (expression instanceof AnnotationExpr annotationExpr) {
            return annotation(annotationExpr);
        }
        if (expression instanceof BinaryExpr binaryExpr) {
            return binaryExpression(binaryExpr);
        }
        if (expression instanceof CastExpr castExpr) {
            return castExpression(castExpr);
        }
        if (expression instanceof ConditionalExpr conditionalExpr) {
            return conditionalExpression(conditionalExpr);
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
            return lambdaExpression(lambdaExpr);
        }
        if (expression instanceof MethodCallExpr methodCallExpr) {
            return methodCall(methodCallExpr);
        }
        if (expression instanceof MethodReferenceExpr methodReferenceExpr) {
            return methodReference(methodReferenceExpr);
        }
        if (expression instanceof ObjectCreationExpr objectCreationExpr) {
            return objectCreation(objectCreationExpr);
        }
        if (expression instanceof SwitchExpr switchExpr) {
            return switchExpression(switchExpr);
        }
        if (expression instanceof TextBlockLiteralExpr textBlockLiteralExpr) {
            return textBlockLiteral(textBlockLiteralExpr);
        }
        return Doc.text(compact(expression));
    }

    private Optional<Doc> assignmentWithBrokenMethodCallArguments(AssignExpr assignExpr, MethodCallExpr methodCall) {
        if (methodCall.getArguments().isEmpty() || !methodCall.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        String firstLine = compact(assignExpr.getTarget()) + " "
                + assignExpr.getOperator().asString()
                + " "
                + methodCallPrefix(methodCall)
                + "(";
        if (blockStatementWidth(firstLine) > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                expression(assignExpr.getTarget()),
                Doc.text(" " + assignExpr.getOperator().asString() + " "),
                methodCall(methodCall, MethodCallMode.BREAK)));
    }

    private Optional<Doc> assignmentWithConditionalValue(AssignExpr assignExpr, ConditionalExpr conditionalExpr) {
        if (shouldBreakBeforeConditionalInitializer(conditionalExpr)
                || shouldBreakBeforeConditionalAssignment(conditionalExpr)) {
            return Optional.of(Doc.concat(
                    expression(assignExpr.getTarget()),
                    Doc.text(" " + assignExpr.getOperator().asString()),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, conditionalExpression(conditionalExpr, true)))));
        }
        String conditionLine = compact(assignExpr.getTarget()) + " "
                + assignExpr.getOperator().asString()
                + " "
                + compact(conditionalExpr.getCondition())
                + ";";
        if (blockStatementWidth(conditionLine) <= options.lineWidth()) {
            return Optional.of(Doc.concat(
                    expression(assignExpr.getTarget()),
                    Doc.text(" " + assignExpr.getOperator().asString() + " "),
                    conditionalExpression(conditionalExpr, true)));
        }
        return Optional.empty();
    }

    private boolean shouldBreakBeforeConditionalAssignment(ConditionalExpr conditionalExpr) {
        return conditionalExpr.getCondition() instanceof BinaryExpr binaryExpr
                && binaryExpr.findAll(MethodCallExpr.class).stream().findAny().isPresent();
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
            return Doc.concat(Doc.text("("), conditionalExpression(conditionalExpr, true), Doc.text(")"));
        }
        if (expression.getInner() instanceof LambdaExpr lambdaExpr
                && expression.getParentNode().filter(ExpressionStmt.class::isInstance).isPresent()) {
            return parenthesizedLambdaBreak(lambdaExpr);
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

    private Doc conditionalExpression(ConditionalExpr expression) {
        return conditionalExpression(expression, false);
    }

    private Doc conditionalExpression(ConditionalExpr expression, boolean forceBreak) {
        Optional<Doc> commented = commentedConditionalExpression(expression);
        if (commented.isPresent()) {
            return commented.orElseThrow();
        }
        String flat = compact(expression);
        if (!forceBreak && currentIndentedWidth(flat) <= options.lineWidth()) {
            if (expressionHasParenthesizedNestedBinary(expression)) {
                return Doc.concat(
                        conditionalCondition(expression),
                        Doc.text(" ? "),
                        conditionalBranch(expression.getThenExpr()),
                        Doc.text(" : "),
                        conditionalBranch(expression.getElseExpr()));
            }
            return Doc.text(flat);
        }
        return Doc.concat(
                conditionalCondition(expression),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        Doc.text("? "),
                        conditionalBranch(expression.getThenExpr()),
                        Doc.HARD_LINE,
                        Doc.text(": "),
                        conditionalBranch(expression.getElseExpr()))));
    }

    private Optional<Doc> commentedConditionalExpression(ConditionalExpr expression) {
        if (expression.getAllContainedComments().stream().noneMatch(LineComment.class::isInstance)) {
            return Optional.empty();
        }
        Optional<Comment> conditionComment = expression.getCondition().getComment()
                .filter(LineComment.class::isInstance);
        Optional<Comment> thenComment = expression.getThenExpr().getComment()
                .filter(LineComment.class::isInstance);
        Optional<Comment> elseComment = expression.getElseExpr().getComment()
                .filter(LineComment.class::isInstance);
        Optional<Comment> leadingThenComment =
                thenComment.filter(comment -> startsBefore(comment, expression.getThenExpr()));
        Optional<Comment> conditionTrailingComment =
                conditionComment
                        .filter(comment -> conditionalQuestionCommentTrailsCondition(expression, comment))
                        .or(() -> leadingThenComment
                                .filter(comment -> conditionalQuestionCommentTrailsCondition(expression, comment)));
        Optional<Comment> questionComment = conditionComment
                .filter(comment -> !conditionalQuestionCommentTrailsCondition(expression, comment))
                .or(() -> leadingThenComment
                        .filter(comment -> !conditionalQuestionCommentTrailsCondition(expression, comment)));
        Optional<Comment> thenTrailingComment = thenComment
                .filter(comment -> !startsBefore(comment, expression.getThenExpr()))
                .filter(comment -> !commentAppearsAfterColon(expression, comment));
        Optional<Comment> colonComment = thenComment
                .filter(comment -> !questionComment.filter(question -> question == comment).isPresent())
                .filter(comment -> commentAppearsAfterColon(expression, comment))
                .or(() -> elseComment.filter(comment -> startsBefore(comment, expression.getElseExpr())));
        Optional<Comment> elseTrailingComment = elseComment
                .filter(comment -> !colonComment.filter(colon -> colon == comment).isPresent())
                .filter(comment -> !startsBefore(comment, expression.getElseExpr()))
                .filter(comment -> !conditionalElseCommentIsStatementTrailing(expression, comment));
        return Optional.of(Doc.concat(
                conditionalConditionWithTrailingComment(expression.getCondition(), conditionTrailingComment),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        conditionalCommentedBranch("?", expression.getThenExpr(), questionComment, thenTrailingComment),
                        Doc.HARD_LINE,
                        conditionalCommentedBranch(":", expression.getElseExpr(), colonComment, elseTrailingComment)))));
    }

    private Doc conditionalConditionWithTrailingComment(Expression condition, Optional<Comment> trailingComment) {
        Doc trailing = trailingComment
                .map(comment -> Doc.concat(Doc.text(" "), comments.comment(comment)))
                .orElse(Doc.EMPTY);
        return Doc.concat(expressionWithoutOwnComment(condition), trailing);
    }

    private boolean conditionalQuestionCommentTrailsCondition(ConditionalExpr expression, Comment comment) {
        return commentAppearsAfterOperator(expression, comment, "?")
                && startsAfterNodeOnSameLine(expression.getCondition(), comment);
    }

    private Doc conditionalCommentedBranch(
            String operator,
            Expression branch,
            Optional<Comment> leadingComment,
            Optional<Comment> trailingComment) {
        if (leadingComment.isPresent()) {
            return Doc.concat(
                    Doc.text(operator + " "),
                    comments.comment(leadingComment.orElseThrow()),
                    Doc.HARD_LINE,
                    Doc.text("  "),
                    expressionWithoutOwnComment(branch));
        }
        Doc trailing = trailingComment
                .map(comment -> Doc.concat(Doc.text(" "), comments.comment(comment)))
                .orElse(Doc.EMPTY);
        return Doc.concat(Doc.text(operator + " "), expressionWithoutOwnComment(branch), trailing);
    }

    private boolean conditionalElseCommentIsStatementTrailing(ConditionalExpr expression, Comment comment) {
        return expression.getParentNode()
                .stream()
                .flatMap(parent -> findAncestorExpressionStatement(parent).stream())
                .anyMatch(statement -> startsAfterNodeOnSameLine(statement, comment));
    }

    private Optional<ExpressionStmt> findAncestorExpressionStatement(Node node) {
        Optional<Node> parent = node.getParentNode();
        while (parent.isPresent()) {
            Node current = parent.orElseThrow();
            if (current instanceof ExpressionStmt expressionStmt) {
                return Optional.of(expressionStmt);
            }
            parent = current.getParentNode();
        }
        return Optional.empty();
    }

    private boolean commentAppearsAfterColon(ConditionalExpr expression, Comment comment) {
        return commentAppearsAfterOperator(expression, comment, ":");
    }

    private boolean commentAppearsAfterOperator(ConditionalExpr expression, Comment comment, String operator) {
        return expression.getTokenRange()
                .flatMap(tokenRange -> expression.getRange().flatMap(expressionRange -> comment.getRange()
                        .map(commentRange -> {
                            List<String> lines = tokenRange.toString().lines().toList();
                            int lineIndex = commentRange.begin.line - expressionRange.begin.line;
                            if (lineIndex < 0 || lineIndex >= lines.size()) {
                                return false;
                            }
                            int column = lineIndex == 0
                                    ? commentRange.begin.column - expressionRange.begin.column
                                    : commentRange.begin.column - 1;
                            if (column <= 0) {
                                return false;
                            }
                            String prefix = lines.get(lineIndex).substring(0, Math.min(column, lines.get(lineIndex).length()));
                            return prefix.contains(operator);
                        })))
                .orElse(false);
    }

    private Doc conditionalCondition(ConditionalExpr expression) {
        Expression condition = expression.getCondition();
        if (condition instanceof BinaryExpr
                && continuationStatementWidth(compact(condition)) > options.lineWidth()) {
            if (conditionalIsAssignmentValue(expression) || conditionalIsVariableInitializer(expression)) {
                return binaryExpressionLines(condition, true);
            }
            return nestedBinaryExpressionLines(condition, true);
        }
        return expression(condition);
    }

    private boolean conditionalIsAssignmentValue(ConditionalExpr expression) {
        return expression.getParentNode()
                .filter(AssignExpr.class::isInstance)
                .map(AssignExpr.class::cast)
                .filter(assignExpr -> assignExpr.getValue() == expression)
                .isPresent();
    }

    private boolean conditionalIsVariableInitializer(ConditionalExpr expression) {
        return expression.getParentNode()
                .filter(VariableDeclarator.class::isInstance)
                .map(VariableDeclarator.class::cast)
                .flatMap(VariableDeclarator::getInitializer)
                .filter(initializer -> initializer == expression)
                .isPresent();
    }

    private Doc conditionalBranch(Expression branch) {
        if (branch instanceof ConditionalExpr conditionalExpr) {
            return conditionalExpression(conditionalExpr, true);
        }
        return expression(branch);
    }

    private Doc arrayAccess(ArrayAccessExpr expression) {
        return Doc.group(Doc.concat(
                expression(expression.getName()),
                Doc.text("["),
                Doc.indent(Doc.concat(Doc.SOFT_LINE, expression(expression.getIndex()))),
                Doc.SOFT_LINE,
                Doc.text("]")));
    }

    private Doc arrayAccessWithBrokenEnclosedName(ArrayAccessExpr expression) {
        EnclosedExpr enclosed = expression.getName().asEnclosedExpr();
        return Doc.concat(
                brokenEnclosedForSuffix(enclosed, true),
                Doc.text("["),
                expression(expression.getIndex()),
                Doc.text("]"));
    }

    private Doc arrayCreation(ArrayCreationExpr expression) {
        Doc prefix = Doc.concat(
                Doc.text("new "),
                arrayCreationType(expression),
                Doc.text(compactJoinArrayLevels(expression.getLevels())));
        return expression.getInitializer()
                .map(initializer -> compactArrayCreation(expression, initializer)
                        .filter(flat -> currentIndentedWidth(flat) <= options.lineWidth())
                        .map(Doc::text)
                        .orElseGet(() -> Doc.concat(prefix, Doc.text(" "), arrayInitializer(initializer))))
                .orElse(prefix);
    }

    private Optional<String> compactArrayCreation(ArrayCreationExpr expression, ArrayInitializerExpr initializer) {
        if (arrayCreationTypeBreaks(expression) || !initializer.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        if (initializer.getValues().stream().anyMatch(value -> !compactArrayInitializerValue(value))) {
            return Optional.empty();
        }
        return compactArrayInitializer(initializer).map(initializerText -> arrayCreationPrefix(expression) + " " + initializerText);
    }

    private String arrayCreationPrefix(ArrayCreationExpr expression) {
        return "new "
                + compactTypeLike(expression.getElementType())
                + compactJoinArrayLevels(expression.getLevels());
    }

    private Optional<String> compactArrayInitializer(ArrayInitializerExpr initializer) {
        if (!initializer.getAllContainedComments().isEmpty()
                || initializer.getValues().stream().anyMatch(value -> !compactArrayInitializerValue(value))) {
            return Optional.empty();
        }
        String values = initializer.getValues().stream()
                .map(this::compact)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        return Optional.of("{" + values + "}");
    }

    private boolean compactArrayInitializerValue(Expression value) {
        return value.isLiteralExpr();
    }

    private Doc arrayCreationType(ArrayCreationExpr expression) {
        if (!arrayCreationTypeBreaks(expression)) {
            return Doc.text(compactTypeLike(expression.getElementType()));
        }
        ClassOrInterfaceType type = expression.getElementType().asClassOrInterfaceType();
        NodeList<com.github.javaparser.ast.type.Type> typeArguments = type.getTypeArguments().orElse(new NodeList<>());
        return Doc.concat(
                Doc.text(type.getNameWithScope()),
                Doc.text("<"),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.concat(Doc.text(","), Doc.HARD_LINE), typeArguments.stream()
                        .map(argument -> Doc.text(compactTypeLike(argument)))
                        .toList()))),
                Doc.HARD_LINE,
                Doc.text(">"));
    }

    private boolean arrayCreationTypeBreaks(ArrayCreationExpr expression) {
        return expression.getElementType().isClassOrInterfaceType()
                && expression.getElementType().asClassOrInterfaceType().getTypeArguments().isPresent()
                && !expression.getLevels().isEmpty();
    }

    private Doc arrayInitializer(ArrayInitializerExpr expression) {
        return arrayInitializer(expression, false);
    }

    private Doc arrayInitializer(ArrayInitializerExpr expression, boolean forceBreak) {
        List<Doc> comments = JavaPrinter.this.comments.orphanCommentStatements(expression);
        if (expression.getValues().isEmpty() && comments.isEmpty()) {
            return Doc.text("{}");
        }
        Optional<String> compact = compactArrayInitializer(expression);
        if (!forceBreak && compact.isPresent() && currentIndentedWidth(compact.orElseThrow()) <= options.lineWidth()) {
            return Doc.text(compact.orElseThrow());
        }
        List<Doc> values = new ArrayList<>(comments);
        for (int i = 0; i < expression.getValues().size(); i++) {
            Expression value = expression.getValues().get(i);
            Expression next = i + 1 < expression.getValues().size() ? expression.getValues().get(i + 1) : null;
            values.add(Doc.concat(arrayInitializerValue(value, next), Doc.text(",")));
        }
        return Doc.concat(
                Doc.text("{"),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, values))),
                Doc.HARD_LINE,
                Doc.text("}"));
    }

    private Doc arrayInitializerValue(Expression value, Expression next) {
        List<Doc> parts = new ArrayList<>();
        Doc leadingComment = comments.ownComment(value, comment -> comment instanceof BlockComment
                && comment.getRange()
                        .flatMap(commentRange -> value.getRange().map(valueRange -> startsBefore(commentRange, valueRange)))
                        .orElse(false));
        if (leadingComment != Doc.EMPTY) {
            parts.add(leadingComment);
            parts.add(Doc.text(" "));
        }
        parts.add(expression(value));
        if (next != null) {
            Doc trailingComment = next.getComment()
                    .filter(BlockComment.class::isInstance)
                    .filter(comment -> startsAfterNodeOnSameLine(value, comment))
                    .filter(comment -> comment.getRange()
                            .flatMap(commentRange -> next.getRange()
                                    .map(nextRange -> commentRange.begin.line < nextRange.begin.line))
                            .orElse(false))
                    .map(comments::comment)
                    .orElse(Doc.EMPTY);
            if (trailingComment != Doc.EMPTY) {
                parts.add(Doc.text(" "));
                parts.add(trailingComment);
            }
        }
        return Doc.concat(parts);
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
            return nestedBinaryExpressionLines(value, true);
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

    private String compactJoinArrayLevels(NodeList<ArrayCreationLevel> levels) {
        return levels.stream()
                .map(level -> level.getDimension()
                        .map(dimension -> "[" + compact(dimension) + "]")
                        .orElse("[]"))
                .reduce(String::concat)
                .orElse("");
    }

    private Doc binaryExpression(BinaryExpr expression) {
        Optional<LineComment> leftLineComment = expression.getLeft()
                .getComment()
                .filter(LineComment.class::isInstance)
                .map(LineComment.class::cast);
        if (leftLineComment.isEmpty()) {
            return Doc.concat(
                    binaryLeftOperand(expression),
                    Doc.text(" " + expression.getOperator().asString() + " "),
                    binaryRightOperand(expression));
        }
        return Doc.concat(
                Doc.text(compactWithoutOwnComment(expression.getLeft()) + " " + expression.getOperator().asString() + " "),
                JavaFormatter.commentDoc(leftLineComment.orElseThrow()),
                Doc.indent(Doc.concat(Doc.HARD_LINE, binaryRightOperand(expression))));
    }

    private Doc binaryLeftOperand(BinaryExpr expression) {
        if (expression.getLeft() instanceof BinaryExpr leftBinary
                && (shouldParenthesizeLeftBinary(expression.getOperator(), leftBinary.getOperator())
                        || shouldParenthesizeNestedBinary(expression.getOperator(), leftBinary.getOperator()))) {
            return Doc.concat(Doc.text("("), expression(leftBinary), Doc.text(")"));
        }
        return expression(expression.getLeft());
    }

    private Doc binaryRightOperand(BinaryExpr expression) {
        if (expression.getRight() instanceof BinaryExpr rightBinary
                && shouldParenthesizeNestedBinary(expression.getOperator(), rightBinary.getOperator())) {
            return Doc.concat(Doc.text("("), expression(rightBinary), Doc.text(")"));
        }
        return expression(expression.getRight());
    }

    private boolean shouldParenthesizeLeftBinary(BinaryExpr.Operator outer, BinaryExpr.Operator inner) {
        return (outer == BinaryExpr.Operator.DIVIDE || outer == BinaryExpr.Operator.REMAINDER)
                && (inner == BinaryExpr.Operator.MULTIPLY || inner == BinaryExpr.Operator.REMAINDER);
    }

    private boolean shouldParenthesizeNestedBinary(BinaryExpr.Operator outer, BinaryExpr.Operator inner) {
        if (isMultiplicativeOperator(outer)
                && (inner == BinaryExpr.Operator.DIVIDE || inner == BinaryExpr.Operator.REMAINDER)) {
            return true;
        }
        if (isAdditiveOperator(outer) && inner == BinaryExpr.Operator.REMAINDER) {
            return true;
        }
        if (isShiftOperator(outer) && (isArithmeticOperator(inner) || isShiftOperator(inner))) {
            return true;
        }
        if (isBitwiseOperator(outer)
                && (isShiftOperator(inner)
                        || isRelationalOperator(inner)
                        || isEqualityOperator(inner)
                        || outer == BinaryExpr.Operator.BINARY_OR
                                && (inner == BinaryExpr.Operator.BINARY_AND || inner == BinaryExpr.Operator.XOR)
                        || outer == BinaryExpr.Operator.XOR && inner == BinaryExpr.Operator.BINARY_AND)) {
            return true;
        }
        return isEqualityOperator(outer) && isEqualityOperator(inner);
    }

    private boolean isShiftOperator(BinaryExpr.Operator operator) {
        return operator == BinaryExpr.Operator.LEFT_SHIFT
                || operator == BinaryExpr.Operator.SIGNED_RIGHT_SHIFT
                || operator == BinaryExpr.Operator.UNSIGNED_RIGHT_SHIFT;
    }

    private boolean isArithmeticOperator(BinaryExpr.Operator operator) {
        return operator == BinaryExpr.Operator.PLUS
                || operator == BinaryExpr.Operator.MINUS
                || operator == BinaryExpr.Operator.MULTIPLY
                || operator == BinaryExpr.Operator.DIVIDE
                || operator == BinaryExpr.Operator.REMAINDER;
    }

    private boolean isAdditiveOperator(BinaryExpr.Operator operator) {
        return operator == BinaryExpr.Operator.PLUS || operator == BinaryExpr.Operator.MINUS;
    }

    private boolean isMultiplicativeOperator(BinaryExpr.Operator operator) {
        return operator == BinaryExpr.Operator.MULTIPLY
                || operator == BinaryExpr.Operator.DIVIDE
                || operator == BinaryExpr.Operator.REMAINDER;
    }

    private boolean isRelationalOperator(BinaryExpr.Operator operator) {
        return operator == BinaryExpr.Operator.LESS
                || operator == BinaryExpr.Operator.GREATER
                || operator == BinaryExpr.Operator.LESS_EQUALS
                || operator == BinaryExpr.Operator.GREATER_EQUALS;
    }

    private boolean isBitwiseOperator(BinaryExpr.Operator operator) {
        return operator == BinaryExpr.Operator.BINARY_AND
                || operator == BinaryExpr.Operator.XOR
                || operator == BinaryExpr.Operator.BINARY_OR;
    }

    private boolean isEqualityOperator(BinaryExpr.Operator operator) {
        return operator == BinaryExpr.Operator.EQUALS || operator == BinaryExpr.Operator.NOT_EQUALS;
    }

    private boolean expressionHasParenthesizedNestedBinary(Expression expression) {
        return expression.findAll(BinaryExpr.class).stream().anyMatch(binary ->
                binary.getLeft() instanceof BinaryExpr leftBinary
                                && (shouldParenthesizeLeftBinary(binary.getOperator(), leftBinary.getOperator())
                                        || shouldParenthesizeNestedBinary(binary.getOperator(), leftBinary.getOperator()))
                        || binary.getRight() instanceof BinaryExpr rightBinary
                                && shouldParenthesizeNestedBinary(binary.getOperator(), rightBinary.getOperator()));
    }

    private Doc methodCall(MethodCallExpr expression) {
        return methodCall(expression, MethodCallMode.AUTO);
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

    private Doc methodCall(MethodCallExpr expression, MethodCallMode mode) {
        if (expression.getScope().isEmpty()
                && expression.getNameAsString().equals("yield")
                && !expression.getArguments().isEmpty()) {
            return Doc.text("yield (" + compactJoin(expression.getArguments()) + ")");
        }
        if (expression.getScope().filter(this::shouldPrintScopeAsDoc).isPresent()) {
            return Doc.concat(
                    expression(expression.getScope().orElseThrow()),
                    Doc.text("."),
                    methodCallWithoutScope(expression));
        }
        if (mode == MethodCallMode.AUTO) {
            Optional<Doc> chain = methodCallChain(expression);
            if (chain.isPresent()) {
                return chain.orElseThrow();
            }
        }
        Optional<Doc> suffixedEnclosed = suffixedEnclosedMethodCall(expression, false);
        if (suffixedEnclosed.isPresent()) {
            return suffixedEnclosed.orElseThrow();
        }
        String prefix = methodCallPrefix(expression);
        if (expression.getArguments().isEmpty()) {
            Optional<Doc> commentedArguments = emptyMethodCallArguments(prefix, expression);
            if (commentedArguments.isPresent()) {
                return commentedArguments.orElseThrow();
            }
            return Doc.text(prefix + "()");
        }
        Optional<Doc> huggableLambda = huggableBlockLambdaArguments(prefix, expression.getArguments());
        if (huggableLambda.isPresent()) {
            return huggableLambda.orElseThrow();
        }
        Optional<Doc> commentedExpressionLambda = commentedExpressionLambdaArgument(prefix, expression);
        if (commentedExpressionLambda.isPresent()) {
            return commentedExpressionLambda.orElseThrow();
        }
        Optional<Doc> huggableExpressionLambda = huggableMethodCallExpressionLambdaArguments(prefix, expression.getArguments());
        if (huggableExpressionLambda.isPresent()) {
            return huggableExpressionLambda.orElseThrow();
        }
        Optional<Doc> singleTextBlockArgument = singleTextBlockArgument(prefix, expression);
        if (singleTextBlockArgument.isPresent()) {
            return singleTextBlockArgument.orElseThrow();
        }
        Optional<Doc> singleBinaryArgument = singleBinaryArgument(prefix, expression.getArguments(), mode);
        if (singleBinaryArgument.isPresent()) {
            return singleBinaryArgument.orElseThrow();
        }
        Doc call = Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(
                        methodCallLine(mode),
                        Doc.join(Doc.concat(Doc.text(","), Doc.LINE), expression.getArguments().stream()
                                .map(this::expression)
                                .toList()))),
                methodCallLine(mode),
                Doc.text(")"));
        return mode == MethodCallMode.BREAK ? call : Doc.group(call);
    }

    private String methodCallPrefix(MethodCallExpr expression) {
        return expression.getScope().map(scope -> compact(scope) + ".").orElse("")
                + expression.getTypeArguments().map(typeArguments -> "<" + compactJoin(typeArguments) + ">").orElse("")
                + expression.getNameAsString();
    }

    private Optional<Doc> suffixedEnclosedExpression(Expression expression, boolean leadingBreak) {
        if (expression instanceof MethodCallExpr methodCallExpr) {
            return suffixedEnclosedMethodCall(methodCallExpr, leadingBreak);
        }
        if (expression instanceof MethodReferenceExpr methodReferenceExpr) {
            return suffixedEnclosedMethodReference(methodReferenceExpr, leadingBreak);
        }
        return Optional.empty();
    }

    private Optional<Doc> suffixedEnclosedMethodCall(MethodCallExpr expression, boolean leadingBreak) {
        return expression.getScope()
                .filter(EnclosedExpr.class::isInstance)
                .map(EnclosedExpr.class::cast)
                .filter(scope -> leadingBreak || blockStatementWidth(compact(expression) + ";") > options.lineWidth())
                .map(scope -> Doc.concat(
                        brokenEnclosedForSuffix(scope, leadingBreak),
                        Doc.text("."),
                        methodCallWithoutScope(expression)));
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
            return parenthesizedLambdaBreak(lambdaExpr);
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

    private Doc parenthesizedLambdaBreak(LambdaExpr expression) {
        String parameters = lambdaParameters(expression);
        return Doc.concat(
                Doc.text("(" + parameters + " ->"),
                Doc.indent(Doc.concat(Doc.HARD_LINE, lambdaExpressionBody(expression))),
                Doc.text(")"));
    }

    private Doc lambdaExpressionBody(LambdaExpr expression) {
        return expression.getExpressionBody()
                .map(this::expression)
                .orElseGet(() -> statement(expression.getBody()));
    }

    private Doc lambdaExpression(LambdaExpr expression) {
        String parameters = lambdaParameters(expression);
        if (expression.getBody().isBlockStmt()) {
            return Doc.concat(lambdaParametersForHeader(expression, parameters), Doc.text(" -> "), block(expression.getBody().asBlockStmt()));
        }
        boolean parametersHaveComments = lambdaParametersHaveComments(expression);
        if (parametersHaveComments) {
            Optional<String> inlineCommentedLambda = inlineCommentedLambda(expression);
            if (inlineCommentedLambda.filter(lambda -> currentIndentedWidth(lambda) <= options.lineWidth()).isPresent()) {
                return Doc.text(inlineCommentedLambda.orElseThrow());
            }
        }
        String flat = parameters + " -> " + expression.getExpressionBody()
                .map(this::compact)
                .orElseGet(() -> compact(expression.getBody()));
        if (!parametersHaveComments && currentIndentedWidth(flat) <= options.lineWidth()) {
            return Doc.text(flat);
        }
        if (parametersHaveComments && expression.getExpressionBody().isPresent()) {
            Expression body = expression.getExpressionBody().orElseThrow();
            if (currentIndentedWidth(") -> " + compact(body)) <= options.lineWidth()) {
                return Doc.concat(lambdaParametersForHeader(expression, parameters), Doc.text(" -> "), expression(body));
            }
        }
        if (lambdaParametersShouldBreak(expression, parameters)
                && expression.getExpressionBody().filter(this::shouldHugBrokenLambdaBody).isPresent()) {
            return Doc.concat(
                    lambdaParametersForHeader(expression, parameters),
                    Doc.text(" -> "),
                    expression(expression.getExpressionBody().orElseThrow()));
        }
        Doc body = brokenLambdaExpressionBody(expression);
        return Doc.concat(
                lambdaParametersForHeader(expression, parameters),
                Doc.text(" ->"),
                Doc.indent(Doc.concat(Doc.HARD_LINE, body)));
    }

    private boolean shouldHugBrokenLambdaBody(Expression body) {
        return body instanceof MethodCallExpr methodCall
                && methodCall.getArguments().isEmpty()
                && currentIndentedWidth(") -> " + compact(methodCall)) <= options.lineWidth();
    }

    private Optional<String> inlineCommentedLambda(LambdaExpr expression) {
        if (expression.getComment().isPresent() || expression.getExpressionBody().isEmpty()) {
            return Optional.empty();
        }
        return lambdaParameterText(expression)
                .filter(parameterText -> parameterText.contains("/*"))
                .filter(parameterText -> !parameterText.contains("//"))
                .flatMap(this::compactInlineCommentedLambdaParameters)
                .map(parameters -> parameters + " -> " + compact(expression.getExpressionBody().orElseThrow()));
    }

    private Optional<String> compactInlineCommentedLambdaParameters(String parameterText) {
        List<String> lines = parameterText.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).startsWith("/*")) {
                return Optional.empty();
            }
        }
        return Optional.of(rawSource.normalizeWhitespace(String.join(" ", lines))
                .replace("( /*", "(/*")
                .replaceAll(",\\s*", ", ")
                .replaceAll("\\s+\\)", ")"));
    }

    private Doc brokenLambdaExpressionBody(LambdaExpr expression) {
        return expression.getExpressionBody()
                .map(body -> body instanceof BinaryExpr binaryExpr && isLogicalBinaryOperator(binaryExpr)
                        ? binaryExpressionLines(body, true)
                        : expression(body))
                .orElseGet(() -> statement(expression.getBody()));
    }

    private boolean isLogicalBinaryOperator(BinaryExpr expression) {
        return expression.getOperator() == BinaryExpr.Operator.AND
                || expression.getOperator() == BinaryExpr.Operator.OR;
    }

    private Doc lambdaParametersForHeader(LambdaExpr expression, String flatParameters) {
        if (lambdaParametersHaveComments(expression)) {
            return commentedLambdaParametersForHeader(expression);
        }
        if (!lambdaParametersShouldBreak(expression, flatParameters)) {
            return Doc.text(flatParameters);
        }
        return Doc.concat(
                Doc.text("("),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        Doc.join(Doc.concat(Doc.text(","), Doc.HARD_LINE), expression.getParameters().stream()
                                .map(parameter -> Doc.text(compact(parameter)))
                                .toList()))),
                Doc.HARD_LINE,
                Doc.text(")"));
    }

    private Doc commentedLambdaParametersForHeader(LambdaExpr expression) {
        return Doc.concat(
                Doc.text("("),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        Doc.join(Doc.HARD_LINE, commentedLambdaParameterLines(expression).stream()
                                .map(Doc::text)
                                .toList()))),
                Doc.HARD_LINE,
                Doc.text(")"));
    }

    private List<String> commentedLambdaParameterLines(LambdaExpr expression) {
        String parameterText = lambdaParameterText(expression).orElseGet(() -> compactJoin(expression.getParameters()));
        if (parameterText.startsWith("(") && parameterText.endsWith(")")) {
            parameterText = parameterText.substring(1, parameterText.length() - 1);
        }
        List<String> lines = new ArrayList<>();
        for (String rawLine : parameterText.lines().map(String::strip).toList()) {
            if (rawLine.isEmpty()) {
                continue;
            }
            addCommentedLambdaParameterLine(lines, rawLine);
        }
        return lines;
    }

    private void addCommentedLambdaParameterLine(List<String> lines, String rawLine) {
        int lineComment = rawLine.indexOf("//");
        if (lineComment >= 0) {
            String beforeComment = rawLine.substring(0, lineComment).stripTrailing();
            String comment = rawLine.substring(lineComment).stripTrailing();
            if (beforeComment.isBlank()) {
                lines.add(comment);
                return;
            }
            addCommaSeparatedLambdaParameters(lines, beforeComment, comment);
            return;
        }
        if (rawLine.startsWith("/*")) {
            lines.add(rawLine);
            return;
        }
        addCommaSeparatedLambdaParameters(lines, rawLine, "");
    }

    private void addCommaSeparatedLambdaParameters(List<String> lines, String text, String trailingComment) {
        boolean lineEndsWithComma = text.stripTrailing().endsWith(",");
        String[] parameters = text.split(",");
        for (int i = 0; i < parameters.length; i++) {
            String parameter = parameters[i].strip();
            if (parameter.isEmpty()) {
                continue;
            }
            boolean last = i == parameters.length - 1;
            if (!last) {
                lines.add(parameter + ",");
            } else if (!trailingComment.isBlank()) {
                lines.add(parameter + (lineEndsWithComma ? ", " : " ") + trailingComment);
            } else {
                lines.add(parameter + (lineEndsWithComma ? "," : ""));
            }
        }
    }

    private boolean lambdaParametersHaveComments(LambdaExpr expression) {
        return lambdaParameterText(expression)
                .map(parameterText -> parameterText.contains("//") || parameterText.contains("/*"))
                .orElseGet(() -> expression.getParameters().stream()
                        .anyMatch(parameter -> !parameter.getAllContainedComments().isEmpty()));
    }

    private Optional<String> lambdaParameterText(LambdaExpr expression) {
        return expression.getTokenRange()
                .map(Object::toString)
                .filter(raw -> raw.contains("->"))
                .map(raw -> raw.substring(0, raw.indexOf("->")).strip());
    }

    private boolean lambdaParametersShouldBreak(LambdaExpr expression, String flatParameters) {
        return expression.getParameters().size() > 1
                && currentIndentedWidth(flatParameters + " -> {}") > options.lineWidth();
    }

    private String lambdaParameters(LambdaExpr expression) {
        if (expression.getParameters().size() != 1) {
            return "(" + compactJoin(expression.getParameters()) + ")";
        }
        String parameter = compact(expression.getParameters().get(0));
        if (options.lambdaArrowParens() == FormatterOptions.LambdaArrowParens.ALWAYS) {
            return "(" + parameter + ")";
        }
        if (options.lambdaArrowParens() == FormatterOptions.LambdaArrowParens.AVOID && lambdaParameterCanAvoidParens(expression)) {
            return parameter;
        }
        return expression.isEnclosingParameters() ? "(" + parameter + ")" : parameter;
    }

    private boolean lambdaParameterCanAvoidParens(LambdaExpr expression) {
        return expression.getParameters().size() == 1
                && expression.getParameters().get(0).getAnnotations().isEmpty()
                && expression.getParameters().get(0).getModifiers().isEmpty()
                && expression.getParameters().get(0).getType().isUnknownType();
    }

    private Optional<Doc> huggableBlockLambdaArguments(String prefix, NodeList<Expression> arguments) {
        int lambdaIndex = blockLambdaArgumentIndex(arguments);
        if (lambdaIndex < 0) {
            return Optional.empty();
        }
        if (lambdaIndex > 0 && lambdaIndex < arguments.size() - 1) {
            return Optional.empty();
        }
        if (hasOtherLambdaArgument(arguments, lambdaIndex)) {
            return Optional.empty();
        }
        LambdaExpr lambdaExpr = (LambdaExpr) arguments.get(lambdaIndex);
        if (lambdaParametersShouldBreak(lambdaExpr, lambdaParameters(lambdaExpr))) {
            return Optional.empty();
        }
        String leadingArguments = compactJoin(arguments.subList(0, lambdaIndex));
        String firstLine = prefix + "("
                + (leadingArguments.isEmpty() ? "" : leadingArguments + ", ")
                + lambdaParameters(lambdaExpr) + " -> {";
        if (blockStatementWidth(firstLine) > options.lineWidth()) {
            return Optional.empty();
        }
        String trailingArguments = compactJoin(arguments.subList(lambdaIndex + 1, arguments.size()));
        return Optional.of(Doc.concat(
                Doc.text(prefix + "(" + (leadingArguments.isEmpty() ? "" : leadingArguments + ", ")),
                lambdaExpression(lambdaExpr),
                Doc.text((trailingArguments.isEmpty() ? "" : ", " + trailingArguments) + ")")));
    }

    private Optional<Doc> commentedExpressionLambdaArgument(String prefix, MethodCallExpr expression) {
        if (expression.getArguments().size() != 1
                || !(expression.getArgument(0) instanceof LambdaExpr lambdaExpr)
                || lambdaExpr.getExpressionBody().isEmpty()) {
            return Optional.empty();
        }
        List<Comment> commentsAroundLambda = new ArrayList<>();
        expression.getOrphanComments().stream()
                .filter(this::isLineOrBlockComment)
                .forEach(commentsAroundLambda::add);
        lambdaExpr.getComment()
                .filter(this::isLineOrBlockComment)
                .ifPresent(commentsAroundLambda::add);
        expression.getName().getComment()
                .filter(this::isLineOrBlockComment)
                .filter(comment -> startsBefore(comment, lambdaExpr))
                .ifPresent(commentsAroundLambda::add);
        if (commentsAroundLambda.isEmpty()) {
            return Optional.empty();
        }
        commentsAroundLambda.sort(Comparator.comparing(comment -> comment.getRange()
                .map(range -> range.begin)
                .orElse(Position.HOME)));
        Optional<Doc> inlineBlockComment = inlineBlockCommentedExpressionLambdaArgument(prefix, lambdaExpr, commentsAroundLambda);
        if (inlineBlockComment.isPresent()) {
            return inlineBlockComment;
        }
        Optional<Doc> brokenLeadingBlockComment = brokenLeadingBlockCommentedExpressionLambdaArgument(
                prefix,
                lambdaExpr,
                commentsAroundLambda);
        if (brokenLeadingBlockComment.isPresent()) {
            return brokenLeadingBlockComment;
        }
        List<Doc> leading = commentsAroundLambda.stream()
                .filter(comment -> isLeadingExpressionLambdaComment(lambdaExpr, comment))
                .map(comments::comment)
                .filter(comment -> comment != Doc.EMPTY)
                .toList();
        List<Doc> trailing = commentsAroundLambda.stream()
                .filter(comment -> !isLeadingExpressionLambdaComment(lambdaExpr, comment))
                .map(comments::comment)
                .filter(comment -> comment != Doc.EMPTY)
                .toList();
        List<Doc> argumentLines = new ArrayList<>();
        argumentLines.addAll(leading);
        argumentLines.add(lambdaExpression(lambdaExpr));
        argumentLines.addAll(trailing);
        return Optional.of(Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, argumentLines))),
                Doc.HARD_LINE,
                Doc.text(")")));
    }

    private boolean isLineOrBlockComment(Comment comment) {
        return comment instanceof LineComment || comment instanceof BlockComment;
    }

    private Optional<Doc> inlineBlockCommentedExpressionLambdaArgument(
            String prefix,
            LambdaExpr lambdaExpr,
            List<Comment> commentsAroundLambda) {
        if (commentsAroundLambda.size() != 1) {
            return Optional.empty();
        }
        Comment comment = commentsAroundLambda.getFirst();
        if (!(comment instanceof BlockComment) || !isLeadingExpressionLambdaComment(lambdaExpr, comment)
                || !startsOnSameLine(comment, lambdaExpr)) {
            return Optional.empty();
        }
        String call = prefix + "(" + comment.toString().stripTrailing() + " " + compactWithoutOwnComment(lambdaExpr) + ")";
        if (currentIndentedWidth(call) > options.lineWidth()) {
            return Optional.empty();
        }
        comments.comment(comment);
        return Optional.of(Doc.text(call));
    }

    private Optional<Doc> brokenLeadingBlockCommentedExpressionLambdaArgument(
            String prefix,
            LambdaExpr lambdaExpr,
            List<Comment> commentsAroundLambda) {
        if (commentsAroundLambda.size() != 1) {
            return Optional.empty();
        }
        Comment comment = commentsAroundLambda.getFirst();
        if (!(comment instanceof BlockComment) || !isLeadingExpressionLambdaComment(lambdaExpr, comment)
                || !startsOnSameLine(comment, lambdaExpr)) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        comments.comment(comment),
                        Doc.text(" "),
                        lambdaExpression(lambdaExpr))),
                Doc.HARD_LINE,
                Doc.text(")")));
    }

    private boolean isLeadingExpressionLambdaComment(LambdaExpr lambdaExpr, Comment comment) {
        return lambdaExpr.getComment().filter(ownComment -> ownComment == comment).isPresent()
                || startsBefore(comment, lambdaExpr);
    }

    private Optional<Doc> huggableMethodCallExpressionLambdaArguments(String prefix, NodeList<Expression> arguments) {
        int lambdaIndex = expressionLambdaArgumentIndex(arguments);
        if (lambdaIndex < 0 || lambdaIndex < arguments.size() - 1 || hasOtherLambdaArgument(arguments, lambdaIndex)) {
            return Optional.empty();
        }
        LambdaExpr lambdaExpr = (LambdaExpr) arguments.get(lambdaIndex);
        Optional<Expression> body = lambdaExpr.getExpressionBody();
        Optional<LambdaExpr> nestedLambda = body
                .filter(LambdaExpr.class::isInstance)
                .map(LambdaExpr.class::cast);
        if (body.isEmpty()
                || !huggableExpressionLambdaBody(body.orElseThrow())
                || !lambdaExpr.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        String parameters = lambdaParameters(lambdaExpr);
        if (lambdaParametersShouldBreak(lambdaExpr, parameters)) {
            return Optional.empty();
        }
        if (nestedLambda.isPresent()) {
            LambdaExpr nested = nestedLambda.orElseThrow();
            if (!nested.getAllContainedComments().isEmpty()
                    || lambdaParametersShouldBreak(nested, lambdaParameters(nested))) {
                return Optional.empty();
            }
        }
        String leadingArguments = compactJoin(arguments.subList(0, lambdaIndex));
        String firstLine = prefix + "("
                + (leadingArguments.isEmpty() ? "" : leadingArguments + ", ")
                + huggableExpressionLambdaFirstLine(lambdaExpr, parameters);
        String flat = prefix + "(" + compactJoin(arguments) + ")";
        if (blockStatementWidth(flat) < options.lineWidth()
                || blockStatementWidth(firstLine) > options.lineWidth()) {
            return Optional.empty();
        }
        Expression bodyExpression = huggableExpressionLambdaBodyExpression(lambdaExpr).orElseThrow();
        if (nestedLambda.isPresent()) {
            return Optional.of(Doc.concat(
                    Doc.text(prefix + "("),
                    Doc.indent(Doc.concat(
                            Doc.HARD_LINE,
                            Doc.text(huggableExpressionLambdaFirstLine(lambdaExpr, parameters)),
                            Doc.indent(Doc.concat(Doc.HARD_LINE, expression(bodyExpression))))),
                    Doc.HARD_LINE,
                    Doc.text(")")));
        }
        return Optional.of(Doc.concat(
                Doc.text(firstLine),
                Doc.indent(Doc.concat(Doc.HARD_LINE, expression(bodyExpression))),
                Doc.HARD_LINE,
                Doc.text(")")));
    }

    private boolean huggableExpressionLambdaBody(Expression body) {
        if (body instanceof MethodCallExpr methodCall) {
            return !methodCall.getArguments().isEmpty();
        }
        if (body instanceof ConditionalExpr) {
            return true;
        }
        if (body instanceof LambdaExpr lambdaExpr && lambdaExpr.getExpressionBody().isPresent()) {
            return huggableExpressionLambdaBody(lambdaExpr.getExpressionBody().orElseThrow());
        }
        return false;
    }

    private String huggableExpressionLambdaFirstLine(LambdaExpr lambdaExpr, String parameters) {
        return lambdaExpr.getExpressionBody()
                .filter(LambdaExpr.class::isInstance)
                .map(LambdaExpr.class::cast)
                .map(nested -> parameters + " -> " + lambdaParameters(nested) + " ->")
                .orElse(parameters + " ->");
    }

    private Optional<Expression> huggableExpressionLambdaBodyExpression(LambdaExpr lambdaExpr) {
        return lambdaExpr.getExpressionBody().flatMap(body -> {
            if (body instanceof MethodCallExpr || body instanceof ConditionalExpr) {
                return Optional.of(body);
            }
            if (body instanceof LambdaExpr nested) {
                return huggableExpressionLambdaBodyExpression(nested);
            }
            return Optional.empty();
        });
    }

    private int expressionLambdaArgumentIndex(NodeList<Expression> arguments) {
        for (int i = 0; i < arguments.size(); i++) {
            if (arguments.get(i) instanceof LambdaExpr lambdaExpr && lambdaExpr.getExpressionBody().isPresent()) {
                return i;
            }
        }
        return -1;
    }

    private Optional<Doc> singleTextBlockArgument(String prefix, MethodCallExpr expression) {
        if (expression.getArguments().size() != 1
                || !(expression.getArguments().get(0) instanceof TextBlockLiteralExpr textBlockLiteralExpr)) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, textBlockArgument(textBlockLiteralExpr, expression))),
                Doc.HARD_LINE,
                Doc.text(")")));
    }

    private Doc textBlockArgument(TextBlockLiteralExpr textBlockLiteralExpr, MethodCallExpr expression) {
        Doc leading = comments.ownComment(textBlockLiteralExpr, LineComment.class::isInstance);
        Doc literal = Doc.text(renderUnformattedTextBlock(textBlockLiteralExpr));
        Doc trailing = textBlockSameLineTrailingComment(textBlockLiteralExpr, expression);
        if (leading != Doc.EMPTY) {
            return Doc.concat(leading, Doc.HARD_LINE, literal, trailing);
        }
        return Doc.concat(literal, trailing);
    }

    private Doc textBlockSameLineTrailingComment(TextBlockLiteralExpr textBlockLiteralExpr, MethodCallExpr expression) {
        int textBlockEndLine = textBlockLiteralExpr.getRange().map(range -> range.end.line).orElse(Integer.MIN_VALUE);
        return expression.getOrphanComments().stream()
                .filter(LineComment.class::isInstance)
                .filter(comment -> comment.getRange()
                        .map(range -> range.begin.line == textBlockEndLine)
                        .orElse(false))
                .findFirst()
                .map(comment -> Doc.concat(Doc.text(" "), comments.comment(comment)))
                .orElse(Doc.EMPTY);
    }

    private Optional<Doc> singleBinaryArgument(String prefix, NodeList<Expression> arguments, MethodCallMode mode) {
        if (arguments.size() != 1 || !(arguments.get(0) instanceof BinaryExpr binaryExpr)) {
            return Optional.empty();
        }
        if (mode != MethodCallMode.BREAK && currentIndentedWidth(prefix + "(" + compact(binaryExpr) + ")") <= options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, nestedBinaryExpressionLines(binaryExpr, true))),
                Doc.HARD_LINE,
                Doc.text(")")));
    }

    private int blockLambdaArgumentIndex(NodeList<Expression> arguments) {
        int lambdaIndex = -1;
        for (int i = 0; i < arguments.size(); i++) {
            if (arguments.get(i) instanceof LambdaExpr lambdaExpr && lambdaExpr.getBody().isBlockStmt()) {
                if (lambdaIndex >= 0) {
                    return -1;
                }
                lambdaIndex = i;
            }
        }
        return lambdaIndex;
    }

    private boolean hasOtherLambdaArgument(NodeList<Expression> arguments, int lambdaIndex) {
        for (int i = 0; i < arguments.size(); i++) {
            if (i != lambdaIndex && arguments.get(i) instanceof LambdaExpr) {
                return true;
            }
        }
        return false;
    }

    private String methodReferenceSuffix(MethodReferenceExpr expression) {
        return "::"
                + expression.getTypeArguments().map(typeArguments -> "<" + compactJoinTypeLike(typeArguments) + ">").orElse("")
                + expression.getIdentifier();
    }

    private Doc methodCallLine(MethodCallMode mode) {
        return mode == MethodCallMode.BREAK ? Doc.HARD_LINE : Doc.SOFT_LINE;
    }

    private boolean shouldPrintScopeAsDoc(Expression expression) {
        return expression instanceof ArrayCreationExpr
                || expression instanceof ArrayAccessExpr
                || expression instanceof TextBlockLiteralExpr
                || expression instanceof EnclosedExpr enclosedExpr
                        && enclosedExpr.getInner() instanceof CastExpr;
    }

    private Doc methodCallWithoutScope(MethodCallExpr expression) {
        String prefix = expression.getTypeArguments().map(typeArguments -> "<" + compactJoin(typeArguments) + ">").orElse("")
                + expression.getNameAsString();
        if (expression.getArguments().isEmpty()) {
            Optional<Doc> commentedArguments = emptyMethodCallArguments(prefix, expression);
            if (commentedArguments.isPresent()) {
                return commentedArguments.orElseThrow();
            }
            return Doc.text(prefix + "()");
        }
        return Doc.group(Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(
                        Doc.SOFT_LINE,
                        Doc.join(Doc.concat(Doc.text(","), Doc.LINE), expression.getArguments().stream()
                                .map(this::expression)
                                .toList()))),
                Doc.SOFT_LINE,
                Doc.text(")")));
    }

    private Doc methodReference(MethodReferenceExpr expression) {
        return suffixedEnclosedMethodReference(expression, false)
                .orElseGet(() -> Doc.text(compact(expression)));
    }

    private Optional<Doc> methodCallChain(MethodCallExpr expression) {
        return methodCallChain(expression, false);
    }

    private Optional<Doc> methodCallChain(MethodCallExpr expression, boolean force) {
        boolean chainHasComments = methodCallChainHasComments(expression);
        if ((!force && !chainHasComments && compact(expression).length() <= options.lineWidth())
                || expression.getScope().isEmpty()) {
            return Optional.empty();
        }
        List<MethodCallExpr> calls = new ArrayList<>();
        Expression root = methodCallChainRoot(expression, calls);
        boolean singleCommentedSegment = calls.size() == 1 && methodCallSegmentHasComment(calls.getFirst());
        boolean rootHasComments = !root.getAllContainedComments().isEmpty();
        if (calls.isEmpty()
                || (calls.size() < 2
                        && !(root instanceof MethodCallExpr)
                        && !(force && root instanceof ObjectCreationExpr)
                        && !rootHasComments
                        && !singleCommentedSegment)) {
            return Optional.empty();
        }
        if (force
                && calls.size() == 1
                && root.getAllContainedComments().isEmpty()
                && calls.getFirst().getAllContainedComments().isEmpty()
                && !methodCallSegmentHasComment(calls.getFirst())) {
            Optional<Doc> compactRootWithBrokenSegment = compactRootWithBrokenFinalSegment(root, calls.getFirst());
            if (compactRootWithBrokenSegment.isPresent()) {
                return compactRootWithBrokenSegment;
            }
        }
        if (calls.size() == 1 && root instanceof MethodCallExpr) {
            return Optional.of(Doc.concat(expression(root), methodCallChainSegment(calls.getFirst())));
        }
        if (root instanceof FieldAccessExpr fieldAccess
                && fieldAccess.getScope() instanceof MethodCallExpr methodRoot
                && calls.size() == 1) {
            return Optional.of(Doc.concat(
                    expression(methodRoot),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, fieldAccessMethodCallSegment(fieldAccess, calls.getFirst())))));
        }
        boolean inlinePromotedRoot = false;
        if (chainHasComments) {
            int firstCommentedSegment = firstCommentedChainSegment(calls);
            if (firstCommentedSegment > 0 && methodCallChainPromotesFirstCall(root)) {
                root = calls.get(firstCommentedSegment - 1);
                calls = new ArrayList<>(calls.subList(firstCommentedSegment, calls.size()));
            } else if (firstCommentedSegment == 0
                    && root instanceof FieldAccessExpr
                    && !root.getAllContainedComments().isEmpty()
                    && calls.size() > 1) {
                root = calls.removeFirst();
                inlinePromotedRoot = true;
            }
        } else if (methodCallChainShouldPromoteFirstCallForArgumentComments(root, calls)) {
            root = calls.removeFirst();
        } else if (methodCallChainShouldPromoteFirstCall(force, root, calls)) {
            root = calls.removeFirst();
        }
        Doc rootDoc = inlinePromotedRoot && root instanceof MethodCallExpr methodCall
                ? inlineMethodCall(methodCall)
                : force && root instanceof ObjectCreationExpr objectCreation
                ? objectCreation(objectCreation, MethodCallMode.BREAK)
                : expression(root);
        if (force && root instanceof ObjectCreationExpr && calls.size() == 1) {
            return Optional.of(Doc.concat(rootDoc, methodCallChainSegment(calls.getFirst())));
        }
        if (root instanceof MethodCallExpr
                && calls.size() == 1
                && root.getAllContainedComments().isEmpty()
                && calls.getFirst().getAllContainedComments().isEmpty()
                && !methodCallSegmentHasComment(calls.getFirst())) {
            return Optional.of(Doc.concat(rootDoc, methodCallChainSegment(calls.getFirst())));
        }
        return Optional.of(Doc.concat(
                rootDoc,
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, calls.stream()
                        .map(this::methodCallChainSegment)
                        .toList())))));
    }

    private Optional<Doc> compactRootWithBrokenFinalSegment(Expression root, MethodCallExpr call) {
        if (!(root instanceof ObjectCreationExpr || root instanceof MethodCallExpr) || call.getArguments().isEmpty()) {
            return Optional.empty();
        }
        String typeArguments = call.getTypeArguments()
                .map(arguments -> "<" + compactJoinTypeLike(arguments) + ">")
                .orElse("");
        String prefix = compact(root) + "." + typeArguments + call.getNameAsString() + "(";
        if (currentIndentedWidth(prefix + ")") > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                Doc.text(prefix),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        Doc.join(Doc.concat(Doc.text(","), Doc.HARD_LINE), call.getArguments().stream()
                                .map(this::expression)
                                .toList()))),
                Doc.HARD_LINE,
                Doc.text(")")));
    }

    private Optional<Doc> mixedFieldMethodCallChain(MethodCallExpr expression) {
        if (!expression.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        List<Doc> segments = new ArrayList<>();
        Optional<Expression> root = collectMixedFieldMethodCallChain(expression, segments);
        if (root.isEmpty() || segments.size() < 2) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                expression(root.orElseThrow()),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, segments)))));
    }

    private Optional<Expression> mixedFieldMethodCallRoot(MethodCallExpr expression) {
        if (!expression.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        if (mixedFieldMethodCallSegmentCount(expression) < 2) {
            return Optional.empty();
        }
        return mixedFieldMethodCallStructuralRoot(expression);
    }

    private int mixedFieldMethodCallSegmentCount(MethodCallExpr expression) {
        Optional<Expression> scope = expression.getScope();
        if (scope.isEmpty()) {
            return 0;
        }
        Expression scoped = scope.orElseThrow();
        if (scoped instanceof MethodCallExpr methodScope) {
            int segments = mixedFieldMethodCallSegmentCount(methodScope);
            return segments == 0 ? 0 : segments + 1;
        }
        if (scoped instanceof FieldAccessExpr fieldAccess) {
            Optional<MethodCallExpr> methodRoot = fieldAccessMethodRoot(fieldAccess);
            return methodRoot.map(root -> mixedFieldMethodCallSegmentCount(root) + 1).orElse(0);
        }
        return 1;
    }

    private Optional<Expression> mixedFieldMethodCallStructuralRoot(MethodCallExpr expression) {
        Optional<Expression> scope = expression.getScope();
        if (scope.isEmpty()) {
            return Optional.empty();
        }
        Expression scoped = scope.orElseThrow();
        if (scoped instanceof MethodCallExpr methodScope) {
            return mixedFieldMethodCallStructuralRoot(methodScope);
        }
        if (scoped instanceof FieldAccessExpr fieldAccess) {
            return fieldAccessMethodRoot(fieldAccess).flatMap(this::mixedFieldMethodCallStructuralRoot);
        }
        return Optional.of(scoped);
    }

    private Optional<Expression> collectMixedFieldMethodCallChain(MethodCallExpr expression, List<Doc> segments) {
        Optional<Expression> scope = expression.getScope();
        if (scope.isEmpty()) {
            return Optional.empty();
        }
        Expression scoped = scope.orElseThrow();
        if (scoped instanceof MethodCallExpr methodScope) {
            Optional<Expression> root = collectMixedFieldMethodCallChain(methodScope, segments);
            root.ifPresent(ignored -> segments.add(methodCallChainSegment(expression)));
            return root;
        }
        if (scoped instanceof FieldAccessExpr fieldAccess) {
            Optional<MethodCallExpr> methodRoot = fieldAccessMethodRoot(fieldAccess);
            if (methodRoot.isEmpty()) {
                return Optional.empty();
            }
            Optional<Expression> root = collectMixedFieldMethodCallChain(methodRoot.orElseThrow(), segments);
            root.ifPresent(ignored -> segments.add(fieldAccessMethodCallSegment(fieldAccess, expression)));
            return root;
        }
        segments.add(methodCallChainSegment(expression));
        return Optional.of(scoped);
    }

    private Optional<MethodCallExpr> fieldAccessMethodRoot(FieldAccessExpr fieldAccess) {
        Expression scope = fieldAccess.getScope();
        if (scope instanceof MethodCallExpr methodCall) {
            return Optional.of(methodCall);
        }
        if (scope instanceof FieldAccessExpr innerFieldAccess) {
            return fieldAccessMethodRoot(innerFieldAccess);
        }
        return Optional.empty();
    }

    private boolean methodCallChainHasComments(MethodCallExpr expression) {
        List<MethodCallExpr> calls = new ArrayList<>();
        Expression root = methodCallChainRoot(expression, calls);
        return !root.getAllContainedComments().isEmpty() || calls.stream().anyMatch(this::methodCallSegmentHasComment);
    }

    private boolean methodCallChainRootIsObjectCreation(MethodCallExpr expression) {
        return methodCallChainRoot(expression, new ArrayList<>()) instanceof ObjectCreationExpr;
    }

    private boolean methodCallChainRootIsFieldAccess(MethodCallExpr expression) {
        return methodCallChainRoot(expression, new ArrayList<>()) instanceof FieldAccessExpr;
    }

    private int firstCommentedChainSegment(List<MethodCallExpr> calls) {
        for (int i = 0; i < calls.size(); i++) {
            if (methodCallSegmentHasComment(calls.get(i))) {
                return i;
            }
        }
        return 0;
    }

    private boolean methodCallSegmentHasComment(MethodCallExpr expression) {
        return expression.getName().getComment()
                .filter(comment -> startsBefore(comment, expression.getName()))
                .isPresent();
    }

    private boolean methodCallChainPromotesFirstCall(Expression root) {
        return root.isNameExpr() && !root.asNameExpr().getNameAsString().isEmpty()
                && Character.isUpperCase(root.asNameExpr().getNameAsString().charAt(0));
    }

    private boolean methodCallChainShouldPromoteFirstCall(boolean force, Expression root, List<MethodCallExpr> calls) {
        if (!methodCallChainPromotesFirstCall(root) || calls.isEmpty()) {
            return false;
        }
        return force || calls.getFirst().getArguments().stream()
                .anyMatch(argument -> argument instanceof LambdaExpr lambdaExpr && lambdaExpr.getBody().isBlockStmt());
    }

    private boolean methodCallChainShouldPromoteFirstCallForArgumentComments(
            Expression root,
            List<MethodCallExpr> calls) {
        return methodCallChainPromotesFirstCall(root)
                && calls.size() > 1
                && calls.getFirst().getAllContainedComments().isEmpty()
                && calls.stream().skip(1).anyMatch(call -> !call.getAllContainedComments().isEmpty());
    }

    private Doc inlineMethodCall(MethodCallExpr expression) {
        Doc scope = expression.getScope().map(this::expression).orElse(Doc.EMPTY);
        String typeArguments = expression.getTypeArguments()
                .map(arguments -> "<" + compactJoinTypeLike(arguments) + ">")
                .orElse("");
        String arguments = "(" + compactJoin(expression.getArguments()) + ")";
        return Doc.concat(scope, Doc.text("." + typeArguments + expression.getNameAsString() + arguments));
    }

    private Expression methodCallChainRoot(MethodCallExpr expression, List<MethodCallExpr> calls) {
        if (expression.getScope().orElse(null) instanceof MethodCallExpr methodCallExpr) {
            Expression root = methodCallChainRoot(methodCallExpr, calls);
            calls.add(expression);
            return root;
        }
        if (expression.getScope().isEmpty()) {
            return expression;
        }
        calls.add(expression);
        return expression.getScope().orElseThrow();
    }

    private Doc methodCallChainSegment(MethodCallExpr expression) {
        Optional<Comment> rawNameComment = expression.getName().getComment()
                .filter(comment -> comment instanceof LineComment || comment instanceof BlockComment)
                .filter(comment -> startsBefore(comment, expression.getName()));
        Doc nameComment = rawNameComment.map(comments::comment).orElse(Doc.EMPTY);
        String typeArguments = expression.getTypeArguments()
                .map(arguments -> "<" + compactJoinTypeLike(arguments) + ">")
                .orElse("");
        String prefix = "." + typeArguments + expression.getNameAsString();
        Doc segmentPrefix = nameComment == Doc.EMPTY
                ? Doc.EMPTY
                : rawNameComment.filter(comment -> comment instanceof BlockComment && startsOnSameLine(comment, expression.getName()))
                        .map(ignored -> Doc.concat(nameComment, Doc.text(" ")))
                        .orElseGet(() -> Doc.concat(nameComment, Doc.HARD_LINE));
        if (expression.getArguments().isEmpty()) {
            Optional<Doc> commentedArguments = emptyMethodCallArguments(prefix, expression);
            if (commentedArguments.isPresent()) {
                return Doc.concat(segmentPrefix, commentedArguments.orElseThrow());
            }
            return Doc.concat(segmentPrefix, Doc.text(prefix + "()"));
        }
        Optional<Doc> huggableLambda = huggableBlockLambdaArguments(prefix, expression.getArguments());
        if (huggableLambda.isPresent()) {
            return Doc.concat(segmentPrefix, huggableLambda.orElseThrow());
        }
        Optional<Doc> commentedExpressionLambda = commentedExpressionLambdaArgument(prefix, expression);
        if (commentedExpressionLambda.isPresent()) {
            return Doc.concat(segmentPrefix, commentedExpressionLambda.orElseThrow());
        }
        return Doc.concat(segmentPrefix, Doc.group(Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(Doc.SOFT_LINE, Doc.join(Doc.concat(Doc.text(","), Doc.LINE), expression.getArguments().stream()
                        .map(this::expression)
                        .toList()))),
                Doc.SOFT_LINE,
                Doc.text(")"))));
    }

    private Doc fieldAccessMethodCallSegment(FieldAccessExpr fieldAccess, MethodCallExpr methodCall) {
        String typeArguments = methodCall.getTypeArguments()
                .map(arguments -> "<" + compactJoinTypeLike(arguments) + ">")
                .orElse("");
        return Doc.text(fieldAccessSuffixAfterMethodRoot(fieldAccess) + "." + typeArguments + methodCall.getNameAsString()
                + "(" + compactJoin(methodCall.getArguments()) + ")");
    }

    private String fieldAccessSuffixAfterMethodRoot(FieldAccessExpr fieldAccess) {
        Expression scope = fieldAccess.getScope();
        if (scope instanceof MethodCallExpr) {
            return "." + fieldAccess.getNameAsString();
        }
        if (scope instanceof FieldAccessExpr innerFieldAccess) {
            return fieldAccessSuffixAfterMethodRoot(innerFieldAccess) + "." + fieldAccess.getNameAsString();
        }
        return "." + fieldAccess.getNameAsString();
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

    private Optional<Doc> emptyMethodCallArguments(String prefix, MethodCallExpr expression) {
        List<Doc> argumentComments = new ArrayList<>();
        Doc firstArgumentComment = comments.ownComment(expression, comment -> comment instanceof LineComment
                && comment.getRange()
                        .flatMap(commentRange -> expression.getRange()
                                .map(expressionRange -> commentRange.begin.line == expressionRange.begin.line))
                        .orElse(false));
        if (firstArgumentComment != Doc.EMPTY) {
            argumentComments.add(firstArgumentComment);
        }
        expression.getScope()
                .map(scope -> comments.ownComment(scope, comment -> comment instanceof LineComment
                        && comment.getRange()
                                .flatMap(commentRange -> expression.getRange()
                                        .map(expressionRange -> commentRange.begin.line == expressionRange.begin.line))
                                .orElse(false)))
                .filter(comment -> comment != Doc.EMPTY)
                .ifPresent(argumentComments::add);
        argumentComments.addAll(comments.orphanCommentStatements(expression));
        if (argumentComments.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, argumentComments))),
                Doc.HARD_LINE,
                Doc.text(")")));
    }

    private Doc objectCreation(ObjectCreationExpr expression) {
        return objectCreation(expression, MethodCallMode.AUTO);
    }

    private Doc objectCreation(ObjectCreationExpr expression, MethodCallMode mode) {
        String prefix = objectCreationPrefix(expression);
        if (expression.getAnonymousClassBody().isPresent()) {
            return anonymousObjectCreation(expression, prefix);
        }
        if (expression.getArguments().isEmpty()) {
            return objectCreationWithBrokenType(expression).orElseGet(() -> Doc.text(prefix + "()"));
        }
        Optional<Doc> huggableLambda = huggableBlockLambdaArguments(prefix, expression.getArguments());
        if (huggableLambda.isPresent()) {
            return huggableLambda.orElseThrow();
        }
        Doc call = Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(
                        methodCallLine(mode),
                        Doc.join(Doc.concat(Doc.text(","), Doc.LINE), expression.getArguments().stream()
                                .map(this::expression)
                                .toList()))),
                methodCallLine(mode),
                Doc.text(")"));
        return mode == MethodCallMode.BREAK ? call : Doc.group(call);
    }

    private String objectCreationPrefix(ObjectCreationExpr expression) {
        Doc creationComment = comments.ownComment(expression, BlockComment.class::isInstance);
        Doc typeComment = comments.ownComment(expression.getType(), BlockComment.class::isInstance);
        String type = typeComment == Doc.EMPTY
                ? compactTypeLike(expression.getType())
                : commentText(typeComment) + " " + compactTypeLikeWithoutOwnComment(expression.getType());
        return expression.getScope().map(scope -> compact(scope) + ".").orElse("")
                + (creationComment == Doc.EMPTY ? "new " : commentText(creationComment) + " new ")
                + expression.getTypeArguments().map(typeArguments -> "<" + compactJoinTypeLike(typeArguments) + ">").orElse("")
                + type;
    }

    private Optional<Doc> objectCreationWithBrokenType(ObjectCreationExpr expression) {
        if (expression.getScope().isPresent()
                || expression.getTypeArguments().isPresent()
                || expression.getComment().filter(BlockComment.class::isInstance).isPresent()
                || expression.getType().getComment().filter(BlockComment.class::isInstance).isPresent()
                || !typeCanBreak(expression.getType())) {
            return Optional.empty();
        }
        return Optional.of(Doc.group(Doc.concat(Doc.text("new "), typeBody(expression.getType()), Doc.text("()"))));
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

    private Doc switchStatement(SwitchStmt statement) {
        Doc leadingBlockComment = ownSameLineBlockCommentBeforeNode(statement);
        Doc prefix = leadingBlockComment == Doc.EMPTY ? Doc.EMPTY : Doc.concat(leadingBlockComment, Doc.text(" "));
        if (statement.getEntries().isEmpty()) {
            return Doc.concat(
                    prefix,
                    Doc.text("switch "),
                    controlCondition(statement.getSelector()),
                    Doc.text(" {"),
                    Doc.HARD_LINE,
                    Doc.text("}"));
        }
        Doc selectorLineComment = comments.ownComment(statement.getSelector(), LineComment.class::isInstance);
        return Doc.concat(
                prefix,
                Doc.text("switch "),
                controlCondition(statement.getSelector()),
                Doc.text(" "),
                switchBlock(statement.getEntries(), selectorLineComment));
    }

    private Doc switchExpression(SwitchExpr expression) {
        return Doc.concat(
                Doc.text("switch (" + compact(expression.getSelector()) + ") "),
                switchBlock(expression.getEntries()));
    }

    private Doc switchBlock(NodeList<SwitchEntry> entries) {
        return switchBlock(entries, Doc.EMPTY);
    }

    private Doc switchBlock(NodeList<SwitchEntry> entries, Doc leadingInside) {
        if (entries.isEmpty()) {
            return Doc.text("{}");
        }
        List<Doc> entryDocs = new ArrayList<>();
        if (leadingInside != Doc.EMPTY) {
            entryDocs.add(leadingInside);
        }
        entryDocs.addAll(entries.stream().map(this::switchEntry).toList());
        return Doc.concat(
                Doc.text("{"),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, entryDocs))),
                Doc.HARD_LINE,
                Doc.text("}"));
    }

    private Doc switchEntry(SwitchEntry entry) {
        Doc leadingComment = comments.ownComment(entry, commentNode -> commentNode instanceof LineComment
                && commentNode.getRange()
                        .flatMap(commentRange -> entry.getRange()
                                .map(entryRange -> commentRange.begin.line < entryRange.begin.line))
                        .orElse(false));
        if (leadingComment != Doc.EMPTY) {
            leadingComment = Doc.concat(leadingComment, Doc.HARD_LINE);
        }
        Doc trailingComment = comments.ownComment(entry, commentNode -> commentNode instanceof LineComment
                && commentNode.getRange()
                        .flatMap(commentRange -> entry.getRange()
                                .map(entryRange -> commentRange.begin.line == entryRange.begin.line))
                        .orElse(false));
        if (trailingComment == Doc.EMPTY) {
            Optional<Doc> raw = rawSingleLineSwitchEntry(entry);
            if (raw.isPresent()) {
                return Doc.concat(leadingComment, raw.orElseThrow());
            }
        }
        Doc label = switchEntryLabel(entry);
        Doc guard = switchEntryGuard(entry);
        Doc entryDoc;
        if (entry.getType() == SwitchEntry.Type.STATEMENT_GROUP) {
            entryDoc = switchStatementGroupEntry(label, guard, entry.getStatements());
        } else if (entry.getStatements().isEmpty()) {
            entryDoc = Doc.concat(label, guard, Doc.text(" ->"));
        } else {
            Statement statement = entry.getStatements().get(0);
            if (hasLeadingOwnComment(statement)) {
                entryDoc = Doc.concat(
                        label,
                        guard,
                        Doc.text(" ->"),
                        Doc.indent(Doc.concat(Doc.HARD_LINE, statement(statement))));
            } else {
                entryDoc = Doc.concat(label, guard, Doc.text(" -> "), switchEntryBody(statement));
            }
        }
        entryDoc = trailingComment == Doc.EMPTY ? entryDoc : Doc.concat(entryDoc, Doc.text(" "), trailingComment);
        return Doc.concat(leadingComment, entryDoc);
    }

    private Doc switchEntryLabel(SwitchEntry entry) {
        if (entry.isDefault()) {
            return Doc.text(defaultSwitchEntryLabel(entry));
        }
        String flatLabels = entry.getLabels().stream().map(this::switchLabelText).reduce((left, right) -> left + ", " + right).orElse("");
        String flat = "case " + flatLabels;
        if (entry.getLabels().size() == 1 && !switchLabelBreaks(entry.getLabels().get(0))
                || currentIndentedWidth(flat + " -> {}") <= options.lineWidth()) {
            return Doc.text(flat);
        }
        if (entry.getLabels().size() == 1) {
            return Doc.concat(Doc.text("case "), switchLabel(entry.getLabels().get(0)));
        }
        return Doc.concat(
                Doc.text("case"),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        Doc.join(
                                Doc.concat(Doc.text(","), Doc.HARD_LINE),
                                entry.getLabels().stream().map(label -> Doc.text(switchLabelText(label))).toList()))));
    }

    private boolean switchLabelBreaks(Expression label) {
        return label instanceof RecordPatternExpr && currentIndentedWidth("case " + switchLabelText(label) + " -> {}") > options.lineWidth();
    }

    private Doc switchLabel(Expression label) {
        if (label instanceof RecordPatternExpr recordPattern && switchLabelBreaks(label)) {
            return recordPattern(recordPattern);
        }
        return Doc.text(switchLabelText(label));
    }

    private String switchLabelText(Expression label) {
        if (label instanceof TypePatternExpr) {
            return rawSource.normalizeWhitespace(label.toString());
        }
        if (label instanceof RecordPatternExpr) {
            return rawSource.normalizeWhitespace(label.toString());
        }
        return compact(label);
    }

    private String defaultSwitchEntryLabel(SwitchEntry entry) {
        String raw = rawSource.raw(entry);
        int colon = raw.indexOf(':');
        if (colon < 0) {
            return "default";
        }
        String label = CommentedTokenText.tokenLine(CommentedTokenText.tokens(raw.substring(0, colon)));
        return label.isEmpty() ? "default" : label;
    }

    private Doc recordPattern(RecordPatternExpr pattern) {
        return Doc.concat(
                Doc.text(modifiers(pattern) + compactTypeLike(pattern.getType()) + "("),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        Doc.join(Doc.concat(Doc.text(","), Doc.HARD_LINE), pattern.getPatternList().stream()
                                .map(this::recordPatternComponent)
                                .toList()))),
                Doc.HARD_LINE,
                Doc.text(")"));
    }

    private Doc recordPatternComponent(Expression pattern) {
        if (pattern instanceof RecordPatternExpr recordPattern && switchLabelBreaks(pattern)) {
            return recordPattern(recordPattern);
        }
        return Doc.text(switchLabelText(pattern));
    }

    private Doc switchEntryGuard(SwitchEntry entry) {
        if (entry.getGuard().isEmpty()) {
            return Doc.EMPTY;
        }
        Expression guard = entry.getGuard().orElseThrow();
        String flat = " when " + compact(guard);
        if (!switchGuardBreaks(entry, guard, flat)) {
            return Doc.text(flat);
        }
        Expression guardedExpression = guard instanceof EnclosedExpr enclosedExpr ? enclosedExpr.getInner() : guard;
        return Doc.concat(
                Doc.text(" when ("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, binaryExpressionLines(guardedExpression))),
                Doc.HARD_LINE,
                Doc.text(")"));
    }

    private boolean switchGuardBreaks(SwitchEntry entry, Expression guard, String flat) {
        String label = "case " + entry.getLabels().stream().map(this::switchLabelText).reduce((left, right) -> left + ", " + right).orElse("");
        return guard instanceof EnclosedExpr
                || switchEntryWidth(label + flat + " -> {}") >= options.lineWidth()
                        && !rawSingleLineSwitchEntry(entry).isPresent();
    }

    private int switchEntryWidth(String text) {
        return (options.indentUnit().length() * 3) + text.length();
    }

    private Doc switchStatementGroupEntry(Doc label, Doc guard, NodeList<Statement> statements) {
        if (statements.size() == 1 && statements.get(0).isBlockStmt()) {
            return Doc.concat(label, guard, Doc.text(": "), switchStatementGroupBlock(statements.get(0).asBlockStmt()));
        }
        return Doc.concat(label, guard, Doc.text(":"), switchEntryStatements(statements));
    }

    private Doc switchStatementGroupBlock(BlockStmt block) {
        if (block.getStatements().isEmpty() && block.getOrphanComments().isEmpty()) {
            return Doc.concat(Doc.text("{"), Doc.HARD_LINE, Doc.text("}"));
        }
        return block(block);
    }

    private boolean hasLeadingOwnComment(Statement statement) {
        return statement.getComment()
                .filter(comment -> comment.getRange()
                        .flatMap(commentRange -> statement.getRange()
                                .map(statementRange -> commentRange.begin.line < statementRange.begin.line))
                        .orElse(false))
                .isPresent();
    }

    private Optional<Doc> rawSingleLineSwitchEntry(SwitchEntry entry) {
        if (entry.getType() == SwitchEntry.Type.STATEMENT_GROUP) {
            return Optional.empty();
        }
        String raw = entry.getTokenRange().map(Object::toString).orElseGet(() -> rawSource.rawWithoutOwnComment(entry)).stripTrailing();
        if (!raw.contains("->") || raw.contains("\n")) {
            return Optional.empty();
        }
        boolean preservesSourceOnlySyntax = raw.contains("/*") || raw.contains("null, default");
        if (!preservesSourceOnlySyntax && currentIndentedWidth(raw) <= options.lineWidth()) {
            return Optional.empty();
        }
        if (!preservesSourceOnlySyntax) {
            return Optional.empty();
        }
        return Optional.of(Doc.text(raw));
    }

    private Doc switchEntryBody(Statement statement) {
        if (statement.isBlockStmt()) {
            return switchRuleBlock(statement.asBlockStmt());
        }
        if (statement instanceof ExpressionStmt expressionStmt) {
            return Doc.concat(expression(expressionStmt.getExpression()), Doc.text(";"));
        }
        return Doc.concat(statement(statement));
    }

    private Doc switchRuleBlock(BlockStmt block) {
        if (block.getStatements().isEmpty() && block.getOrphanComments().isEmpty()) {
            return Doc.concat(Doc.text("{"), Doc.HARD_LINE, Doc.text("}"));
        }
        return block(block);
    }

    private Doc switchEntryStatements(NodeList<Statement> statements) {
        if (statements.isEmpty()) {
            return Doc.EMPTY;
        }
        List<Doc> docs = new ArrayList<>();
        Statement previous = null;
        for (Statement current : statements) {
            if (previous != null) {
                docs.add(blocks.statementSeparator(previous, current));
            }
            docs.add(statement(current));
            previous = current;
        }
        return Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.concat(docs)));
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
                docs.add(Doc.group(Doc.concat(typeBody(type), Doc.text(" "), variables)));
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
        return typeCanBreak(type)
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

    private Doc ifStatement(IfStmt statement) {
        if (statement.getThenStmt().isEmptyStmt()) {
            return ifWithEmptyThenStatement(statement);
        }
        List<Doc> docs = new ArrayList<>();
        Doc thenTrailingLineComment = trailingLineComment(statement.getThenStmt());
        Doc betweenThenAndElseBlockComment = blockCommentBetweenThenAndElse(statement);
        docs.add(ifCondition(statement.getCondition()));
        docs.add(ifThenStatement(statement));
        statement.getElseStmt().ifPresent(elseStatement -> {
            if (elseStatement.isEmptyStmt()) {
                docs.add(emptyElseStatement(statement, elseStatement));
                return;
            }
            Doc elseLeadingLineComment = comments.ownComment(elseStatement, LineComment.class::isInstance);
            Doc elseLeadingBlockComment = ownSameLineBlockCommentBeforeNode(elseStatement);
            Doc elseTrailingLineComment = trailingLineComment(elseStatement);
            docs.add(elseChainSeparator(
                    statement,
                    elseStatement,
                    thenTrailingLineComment,
                    betweenThenAndElseBlockComment,
                    elseLeadingLineComment,
                    elseLeadingBlockComment));
            docs.add(elseStatement.isIfStmt() ? statement(elseStatement) : nestedStatement(elseStatement));
            if (elseTrailingLineComment != Doc.EMPTY) {
                docs.add(Doc.text(" "));
                docs.add(elseTrailingLineComment);
            }
        });
        if (statement.getElseStmt().isEmpty() && thenTrailingLineComment != Doc.EMPTY) {
            docs.add(Doc.text(" "));
            docs.add(thenTrailingLineComment);
        }
        return Doc.concat(docs);
    }

    private Doc ifWithEmptyThenStatement(IfStmt statement) {
        List<Doc> docs = new ArrayList<>();
        docs.add(Doc.text("if (" + ifEmptyThenCondition(statement) + ");"));
        statement.getElseStmt().ifPresent(elseStatement -> {
            docs.add(Doc.HARD_LINE);
            docs.add(elseStatement.isEmptyStmt()
                    ? Doc.text("else;" + trailingEmptyBodyBlockComment(elseStatement))
                    : Doc.concat(Doc.text("else "), nestedStatement(elseStatement)));
        });
        return Doc.concat(docs);
    }

    private String ifEmptyThenCondition(IfStmt statement) {
        List<String> parts = new ArrayList<>();
        parts.add(compact(statement.getCondition()));
        String thenComment = commentText(emptyBodyOwnBlockComment(statement.getThenStmt()));
        if (!thenComment.isEmpty()) {
            parts.add(thenComment);
        }
        String betweenThenAndElse = commentText(blockCommentBetweenThenAndElse(statement));
        if (!betweenThenAndElse.isEmpty()) {
            parts.add(betweenThenAndElse);
        }
        statement.getElseStmt()
                .filter(Statement::isEmptyStmt)
                .map(this::emptyBodyOwnBlockComment)
                .map(this::commentText)
                .filter(comment -> !comment.isEmpty())
                .ifPresent(parts::add);
        return String.join(" ", parts);
    }

    private Doc blockCommentBetweenThenAndElse(IfStmt statement) {
        if (statement.getElseStmt().isEmpty()) {
            return Doc.EMPTY;
        }
        Statement thenStatement = statement.getThenStmt();
        Statement elseStatement = statement.getElseStmt().orElseThrow();
        return statement.getAllContainedComments().stream()
                .filter(BlockComment.class::isInstance)
                .filter(comment -> comment.getRange()
                        .flatMap(commentRange -> thenStatement.getRange()
                                .flatMap(thenRange -> elseStatement.getRange()
                                        .map(elseRange -> commentRange.begin.line == thenRange.end.line
                                                && commentRange.begin.column > thenRange.end.column
                                                && commentRange.begin.column <= thenRange.end.column + 2
                                                && commentRange.begin.line == elseRange.begin.line
                                                && commentRange.begin.column < elseRange.begin.column)))
                        .orElse(false))
                .findFirst()
                .map(comments::comment)
                .orElse(Doc.EMPTY);
    }

    private Doc emptyElseStatement(IfStmt statement, Statement elseStatement) {
        String elseComment = commentText(emptyBodyOwnBlockComment(elseStatement));
        String prefix = elseComment.isEmpty() ? " else;" : " " + elseComment + " else;";
        return Doc.text(prefix + trailingEmptyBodyBlockComment(elseStatement));
    }

    private Doc ifCondition(Expression condition) {
        Doc commented = commentedIfCondition(condition);
        if (commented != Doc.EMPTY) {
            return commented;
        }
        String flat = compact(condition);
        if (currentIndentedWidth("if (" + flat + ") {}") <= options.lineWidth()) {
            if (expressionHasParenthesizedNestedBinary(condition)) {
                return Doc.concat(Doc.text("if ("), expression(condition), Doc.text(") "));
            }
            return Doc.text("if (" + flat + ") ");
        }
        return Doc.concat(
                Doc.text("if ("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, binaryExpressionLines(condition))),
                Doc.HARD_LINE,
                Doc.text(") "));
    }

    private Doc commentedIfCondition(Expression condition) {
        Optional<Comment> ownComment = condition.getComment();
        if (ownComment.filter(LineComment.class::isInstance).isPresent()) {
            Comment comment = ownComment.orElseThrow();
            Doc printedComment = comments.comment(comment);
            Doc conditionDoc = conditionCommentStartsBeforeExpression(condition, comment)
                    ? Doc.join(Doc.HARD_LINE, List.of(printedComment, Doc.text(compactWithoutOwnComment(condition))))
                    : Doc.text(compactWithoutOwnComment(condition) + " " + commentText(printedComment));
            return Doc.concat(
                    Doc.text("if ("),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, conditionDoc)),
                    Doc.HARD_LINE,
                    Doc.text(") "));
        }
        if (ownComment.filter(BlockComment.class::isInstance).isPresent()) {
            Comment comment = ownComment.orElseThrow();
            String text = commentText(comments.comment(comment));
            String expressionText = compactWithoutOwnComment(condition);
            String conditionText = conditionCommentStartsBeforeExpression(condition, comment)
                    ? text + " " + expressionText
                    : expressionText + " " + text;
            return Doc.text("if (" + conditionText + ") ");
        }
        Doc trailingBlock = trailingBlockCommentBeforeCloseParen(condition);
        if (trailingBlock != Doc.EMPTY) {
            return Doc.text("if (" + compact(condition) + " " + commentText(trailingBlock) + ") ");
        }
        return Doc.EMPTY;
    }

    private boolean conditionCommentStartsBeforeExpression(Expression condition, Comment comment) {
        return comment.getRange()
                .flatMap(commentRange -> condition.getRange()
                        .map(conditionRange -> startsBefore(commentRange, conditionRange)))
                .orElse(false);
    }

    private boolean startsBefore(com.github.javaparser.Range left, com.github.javaparser.Range right) {
        if (left.begin.line != right.begin.line) {
            return left.begin.line < right.begin.line;
        }
        return left.begin.column < right.begin.column;
    }

    private Doc trailingBlockCommentBeforeCloseParen(Expression condition) {
        return condition.getParentNode()
                .stream()
                .flatMap(parent -> parent.getAllContainedComments().stream())
                .filter(BlockComment.class::isInstance)
                .filter(comment -> comment.getCommentedNode()
                        .map(BlockStmt.class::isInstance)
                        .orElse(false))
                .filter(comment -> startsImmediatelyAfterNodeOnSameLine(condition, comment))
                .findFirst()
                .map(comments::comment)
                .orElse(Doc.EMPTY);
    }

    private boolean startsImmediatelyAfterNodeOnSameLine(Node node, Comment comment) {
        return node.getRange()
                .flatMap(nodeRange -> comment.getRange()
                        .map(commentRange -> commentRange.begin.line == nodeRange.end.line
                                && commentRange.begin.column == nodeRange.end.column + 1))
                .orElse(false);
    }

    private Doc ifThenStatement(IfStmt statement) {
        if (statement.getElseStmt().isEmpty()
                && statement.getThenStmt().isBlockStmt()
                && statement.getThenStmt().asBlockStmt().getStatements().isEmpty()
                && statement.getThenStmt().asBlockStmt().getOrphanComments().isEmpty()
                && compact(statement.getCondition()).contains("instanceof")) {
            return Doc.concat(Doc.text("{"), Doc.HARD_LINE, Doc.text("}"));
        }
        return nestedStatement(statement.getThenStmt());
    }

    private Doc elseChainSeparator(
            IfStmt statement,
            Statement elseStatement,
            Doc thenTrailingLineComment,
            Doc betweenThenAndElseBlockComment,
            Doc elseLeadingLineComment,
            Doc elseLeadingBlockComment) {
        if (thenTrailingLineComment != Doc.EMPTY) {
            return Doc.concat(Doc.HARD_LINE, thenTrailingLineComment, Doc.HARD_LINE, Doc.text("else "));
        }
        if (betweenThenAndElseBlockComment != Doc.EMPTY) {
            return Doc.concat(Doc.text(" "), betweenThenAndElseBlockComment, Doc.text(" else "));
        }
        if (elseLeadingLineComment != Doc.EMPTY) {
            return Doc.concat(Doc.HARD_LINE, elseLeadingLineComment, Doc.HARD_LINE, Doc.text("else "));
        }
        if (elseLeadingBlockComment != Doc.EMPTY) {
            return Doc.concat(Doc.text(" else "), elseLeadingBlockComment, Doc.text(" "));
        }
        if (elseStatement.isIfStmt() && !statement.getThenStmt().isBlockStmt()) {
            return Doc.concat(Doc.HARD_LINE, Doc.text("else "));
        }
        return Doc.text(" else ");
    }

    private Doc nestedStatement(Statement statement) {
        if (statement.isBlockStmt()
                && statement.asBlockStmt().getStatements().isEmpty()
                && statement.asBlockStmt().getOrphanComments().isEmpty()
                && statement.getParentNode().filter(IfStmt.class::isInstance).isPresent()) {
            return emptyControlBlock(statement.asBlockStmt());
        }
        if (statement.isBlockStmt()) {
            return statement(statement);
        }
        if (statement.isIfStmt()
                || statement.isForStmt()
                || statement.isForEachStmt()
                || statement.isWhileStmt()
                || statement.isDoStmt()) {
            return Doc.indent(Doc.concat(Doc.HARD_LINE, statement(statement)));
        }
        return statement(statement);
    }

    private Doc emptyControlBlock(BlockStmt block) {
        Doc inlineBlockComment = comments.ownComment(block, BlockComment.class::isInstance);
        if (inlineBlockComment != Doc.EMPTY) {
            return Doc.concat(inlineBlockComment, Doc.text(" {"), Doc.HARD_LINE, Doc.text("}"));
        }
        Doc leading = comments.leading(block);
        return Doc.concat(leading, Doc.text("{"), Doc.HARD_LINE, Doc.text("}"));
    }

    private Doc forEachStatement(ForEachStmt statement) {
        if (statement.getBody().isEmptyStmt()) {
            return Doc.text("for (" + compact(statement.getVariable()) + " : "
                    + emptyBodyHeaderExpression(statement.getIterable(), statement.getBody()) + ");"
                    + trailingEmptyBodyBlockComment(statement));
        }
        String header = "for (" + forEachVariable(statement) + " : " + compact(statement.getIterable()) + ")";
        Optional<Doc> lineComment = lineCommentBeforeNestedBody(statement);
        if (lineComment.isPresent() && !statement.getBody().isBlockStmt()) {
            return Doc.concat(
                    Doc.text(header + " "),
                    lineComment.orElseThrow(),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, statement(statement.getBody()))));
        }
        return Doc.concat(Doc.text(header + " "), nestedStatement(statement.getBody()));
    }

    private String forEachVariable(ForEachStmt statement) {
        String raw = rawSource.raw(statement);
        int open = raw.indexOf('(');
        int colon = raw.indexOf(':', open);
        if (open < 0 || colon < open) {
            return compact(statement.getVariable());
        }
        String variable = raw.substring(open + 1, colon);
        return variable.contains("/*")
                ? CommentedTokenText.tokenLine(CommentedTokenText.tokens(variable))
                : compact(statement.getVariable());
    }

    private Optional<Doc> lineCommentBeforeNestedBody(Statement statement) {
        String raw = statement.getTokenRange().map(Object::toString).orElse("");
        int commentStart = raw.indexOf("//");
        if (commentStart < 0) {
            return Optional.empty();
        }
        int lineEnd = raw.indexOf('\n', commentStart);
        if (lineEnd < 0) {
            return Optional.empty();
        }
        return Optional.of(Doc.text(raw.substring(commentStart, lineEnd).stripTrailing()));
    }

    private String forHeader(ForStmt statement) {
        String init = statement.getInitialization().stream()
                .map(this::forHeaderExpression)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        String compare = statement.getCompare().map(this::forHeaderExpression).orElse("");
        String update = compactJoin(statement.getUpdate());
        if (init.isEmpty() && compare.isEmpty() && update.isEmpty()) {
            return "for (;;)";
        }
        return "for (" + init + "; " + compare + "; " + update + ")";
    }

    private Doc forStatement(ForStmt statement) {
        if (statement.getBody().isEmptyStmt()) {
            return loopWithEmptyBody(forHeader(statement), statement);
        }
        if (statement.getBody() instanceof DoStmt) {
            return Doc.concat(Doc.text(forHeader(statement) + " "), statement(statement.getBody()));
        }
        return Doc.concat(Doc.text(forHeader(statement) + " "), nestedStatement(statement.getBody()));
    }

    private Doc whileStatement(WhileStmt statement) {
        if (statement.getBody().isEmptyStmt()) {
            return Doc.text("while (" + emptyBodyHeaderExpression(statement.getCondition(), statement.getBody()) + ");"
                    + trailingEmptyBodyBlockComment(statement));
        }
        Optional<Doc> commentedBody = commentedLoopBody(statement, statement.getBody());
        if (commentedBody.isPresent()) {
            return Doc.concat(Doc.text("while "), controlCondition(statement.getCondition()), commentedBody.orElseThrow());
        }
        return Doc.concat(Doc.text("while "), controlCondition(statement.getCondition()), Doc.text(" "), nestedStatement(statement.getBody()));
    }

    private Optional<Doc> commentedLoopBody(Node loop, Statement body) {
        if (body.isBlockStmt()) {
            return Optional.empty();
        }
        Doc comment = comments.ownComment(body, BlockComment.class::isInstance);
        if (comment == Doc.EMPTY) {
            return Optional.empty();
        }
        Doc commentedStatement = Doc.concat(comment, Doc.text(" "), statement(body));
        if (sameBeginLine(loop, body)) {
            return Optional.of(Doc.concat(Doc.text(" "), commentedStatement));
        }
        return Optional.of(Doc.indent(Doc.concat(Doc.HARD_LINE, commentedStatement)));
    }

    private boolean sameBeginLine(Node left, Node right) {
        return left.getRange()
                .flatMap(leftRange -> right.getRange()
                        .map(rightRange -> leftRange.begin.line == rightRange.begin.line))
                .orElse(false);
    }

    private Doc doStatement(DoStmt statement) {
        if (statement.getBody().isEmptyStmt()) {
            String condition = compact(statement.getCondition());
            Doc bodyComment = emptyBodyOwnBlockComment(statement.getBody());
            Doc conditionComment = comments.ownComment(statement.getCondition(), BlockComment.class::isInstance);
            if (bodyComment != Doc.EMPTY || conditionComment != Doc.EMPTY) {
                String comment = bodyComment != Doc.EMPTY ? commentText(bodyComment) : commentText(conditionComment);
                return Doc.text("do; while (" + comment + " " + condition + ");");
            }
            return Doc.text("do; while (" + condition + ");");
        }
        return Doc.concat(Doc.text("do "), doBody(statement.getBody()), doWhileTail(statement));
    }

    private Doc doBody(Statement body) {
        if (!body.isBlockStmt()) {
            return nestedStatement(body);
        }
        Doc leadingBlockComment = comments.ownComment(body, BlockComment.class::isInstance);
        if (leadingBlockComment == Doc.EMPTY) {
            return nestedStatement(body);
        }
        return Doc.concat(leadingBlockComment, Doc.text(" "), block(body.asBlockStmt()));
    }

    private Doc doWhileTail(DoStmt statement) {
        Optional<Comment> conditionComment = statement.getCondition().getComment().filter(BlockComment.class::isInstance);
        if (conditionComment.isPresent()
                && conditionCommentStartsBeforeExpression(statement.getCondition(), conditionComment.orElseThrow())) {
            Doc comment = comments.comment(conditionComment.orElseThrow());
            return Doc.text(" " + commentText(comment) + " while (" + compactWithoutOwnComment(statement.getCondition()) + ");");
        }
        return Doc.concat(Doc.text(" while "), controlCondition(statement.getCondition()), Doc.text(";"));
    }

    private Doc controlCondition(Expression expression) {
        String flat = compactWithOwnBlockComment(expression);
        if (currentIndentedWidth("(" + flat + ") {}") <= options.lineWidth()) {
            return Doc.text("(" + flat + ")");
        }
        return Doc.concat(
                Doc.text("("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, binaryExpressionLines(expression))),
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

    private Doc loopWithEmptyBody(String header, Node statement) {
        Doc bodyComment = statement instanceof ForStmt forStmt ? emptyBodyOwnBlockComment(forStmt.getBody()) : Doc.EMPTY;
        if (bodyComment == Doc.EMPTY) {
            return Doc.text(header + ";" + trailingEmptyBodyBlockComment(statement));
        }
        return Doc.concat(bodyComment, Doc.HARD_LINE, Doc.text(header + ";" + trailingEmptyBodyBlockComment(statement)));
    }

    private String emptyBodyHeaderExpression(Expression expression, Statement body) {
        Doc bodyComment = emptyBodyOwnBlockComment(body);
        if (bodyComment == Doc.EMPTY) {
            return compact(expression);
        }
        return compact(expression) + " " + commentText(bodyComment);
    }

    private Doc emptyBodyOwnBlockComment(Statement body) {
        return comments.ownComment(body, BlockComment.class::isInstance);
    }

    private String commentText(Doc comment) {
        if (comment instanceof Doc.Text text) {
            return text.value();
        }
        return "";
    }

    private Doc trailingLineComment(Node node) {
        Doc own = comments.trailingLineComment(node);
        if (own != Doc.EMPTY) {
            return own;
        }
        return unattachedTrailingLineComment(node);
    }

    private String trailingEmptyBodyBlockComment(Node node) {
        Doc unattached = unattachedTrailingBlockComment(node);
        if (unattached != Doc.EMPTY) {
            return " " + commentText(unattached);
        }
        String raw = rawSource.raw(node);
        int semicolon = raw.lastIndexOf(';');
        if (semicolon < 0 || semicolon + 1 >= raw.length()) {
            return "";
        }
        String trailing = raw.substring(semicolon + 1).strip();
        return trailing.startsWith("/*") ? " " + trailing : "";
    }

    private Doc unattachedTrailingLineComment(Node node) {
        Optional<Node> parent = node.getParentNode();
        while (parent.isPresent()) {
            Optional<Doc> trailing = parent.orElseThrow().getAllContainedComments().stream()
                    .filter(LineComment.class::isInstance)
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

    private boolean startsAfterNodeOnSameLine(Node node, Comment comment) {
        return node.getRange()
                .flatMap(nodeRange -> comment.getRange()
                        .map(commentRange -> commentRange.begin.line == nodeRange.end.line
                                && commentRange.begin.column > nodeRange.end.column))
                .orElse(false);
    }

    private String forHeaderExpression(Expression expression) {
        if (expression instanceof BinaryExpr binaryExpr) {
            return compact(binaryExpr.getLeft())
                    + " "
                    + binaryExpr.getOperator().asString()
                    + " "
                    + compactWithOwnBlockComment(binaryExpr.getRight());
        }
        if (expression instanceof VariableDeclarationExpr variableDeclaration
                && variableDeclaration.getVariables().size() == 1) {
            VariableDeclarator variable = variableDeclaration.getVariables().get(0);
            return compactTypeLike(variable.getType())
                    + " "
                    + variable.getNameAsString()
                    + variable.getInitializer().map(initializer -> " = " + compact(initializer)).orElse("");
        }
        return compact(expression);
    }

    private Doc rawDeclaration(BodyDeclaration<?> declaration) {
        return Doc.concat(comments.leading(declaration), Doc.text(compact(declaration)));
    }

    private <T extends Node> Optional<Doc> extendsTypes(NodeList<T> types) {
        if (types.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Doc.text(" extends " + compactJoinTypeLike(types)));
    }

    private Optional<Doc> implementsTypes(NodeList<ClassOrInterfaceType> types) {
        if (types.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Doc.text(" implements " + compactJoinTypeLike(types)));
    }

    private Optional<Doc> permitsTypes(NodeList<ClassOrInterfaceType> types) {
        if (types.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Doc.text(" permits " + compactJoinTypeLike(types)));
    }

    private <T extends Node> Optional<Doc> typeClause(String keyword, NodeList<T> types) {
        return typeClause(keyword, types, true);
    }

    private <T extends Node> Optional<Doc> typeClause(String keyword, NodeList<T> types, boolean breakBeforeClause) {
        if (types.isEmpty()) {
            return Optional.empty();
        }
        String flat = keyword + " " + compactJoinTypeLike(types);
        if (!breakBeforeClause) {
            if (types.size() == 1
                    && types.get(0) instanceof ClassOrInterfaceType type
                    && typeArgumentCount(type) > 2) {
                return Optional.of(Doc.concat(Doc.text(" " + keyword + " "), brokenClassOrInterfaceType(type)));
            }
            return Optional.of(Doc.text(" " + flat));
        }
        if (flat.length() + options.indentUnit().length() <= options.lineWidth()) {
            return Optional.of(Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(flat))));
        }
        if (types.size() == 1
                && types.get(0) instanceof ClassOrInterfaceType type
                && typeArgumentCount(type) > 2) {
            return Optional.of(Doc.indent(Doc.concat(
                    Doc.HARD_LINE,
                    Doc.text(keyword + " "),
                    brokenClassOrInterfaceType(type))));
        }
        return Optional.of(Doc.indent(Doc.concat(
                Doc.HARD_LINE,
                Doc.text(keyword),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        Doc.join(Doc.concat(Doc.text(","), Doc.HARD_LINE), types.stream()
                                .map(type -> Doc.text(compactTypeLike(type)))
                                .toList()))))));
    }

    private Doc brokenClassOrInterfaceType(ClassOrInterfaceType type) {
        return Doc.concat(
                Doc.text(typeNameWithoutArguments(type) + "<"),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        Doc.join(Doc.concat(Doc.text(","), Doc.HARD_LINE), type.getTypeArguments().stream()
                                .flatMap(NodeList::stream)
                                .map(this::typeBody)
                                .toList()))),
                Doc.HARD_LINE,
                Doc.text(">"));
    }

    private boolean typeCanBreak(Type type) {
        return type instanceof ClassOrInterfaceType classOrInterfaceType
                && classOrInterfaceType.getTypeArguments().isPresent();
    }

    private Doc typeBody(Type type) {
        if (type instanceof ClassOrInterfaceType classOrInterfaceType
                && classOrInterfaceType.getTypeArguments().isPresent()) {
            return classOrInterfaceTypeBody(classOrInterfaceType);
        }
        return Doc.text(compactTypeLike(type));
    }

    private Doc classOrInterfaceTypeBody(ClassOrInterfaceType type) {
        return Doc.concat(
                Doc.text(typeNameWithoutArguments(type) + "<"),
                Doc.indent(Doc.concat(
                        Doc.SOFT_LINE,
                        Doc.join(Doc.concat(Doc.text(","), Doc.LINE), type.getTypeArguments().stream()
                                .flatMap(NodeList::stream)
                                .map(this::typeBody)
                                .toList()))),
                Doc.SOFT_LINE,
                Doc.text(">"));
    }

    private String typeNameWithoutArguments(ClassOrInterfaceType type) {
        String text = compactTypeLike(type);
        int argumentsStart = text.indexOf('<');
        return argumentsStart < 0 ? text : text.substring(0, argumentsStart);
    }

    private <T extends Node> String flatTypeClause(String keyword, NodeList<T> types) {
        if (types.isEmpty()) {
            return "";
        }
        return " " + keyword + " " + compactJoinTypeLike(types);
    }

    private String flatTypeParameters(NodeList<TypeParameter> typeParameters) {
        if (typeParameters.isEmpty()) {
            return "";
        }
        return "<" + compactJoinTypeLike(typeParameters) + ">";
    }

    private boolean emptyMemberBlock(ClassOrInterfaceDeclaration declaration) {
        return declaration.getMembers().isEmpty() && declaration.getOrphanComments().isEmpty();
    }

    private int flatMemberBlockWidth(ClassOrInterfaceDeclaration declaration) {
        return emptyMemberBlock(declaration) ? "{}".length() : "{".length();
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

    private String compactJoinTypeLike(List<? extends Node> nodes) {
        return nodes.stream().map(this::compactTypeLike).reduce((left, right) -> left + ", " + right).orElse("");
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
                + expression.getTypeArguments().map(typeArguments -> "<" + compactJoinTypeLike(typeArguments) + ">").orElse("")
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

    private enum MethodCallMode {
        AUTO,
        BREAK
    }

}
