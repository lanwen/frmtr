package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.stmt.BlockStmt;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.function.Function;

/**
 * Wires declaration-formatting helpers behind the body-declaration rule envelope.
 *
 * <p>This composer owns the construction order for declaration prefixes, callable signatures, type declarations,
 * fields, local variables, module declarations, member blocks, whole-compilation-unit layout, and the body-declaration
 * dispatcher. The boundary exists so declaration wiring stays close to declaration policy instead of being interleaved
 * with expression and statement helper construction.
 *
 * <p>Callers still provide expression rendering, block rendering, shared type rendering, and the small comment-text
 * conversion used by raw module and constructor/comment paths. This composer leaves statement-body layout and
 * expression-specific initializer decisions with their existing owners.
 */
final class DeclarationPrinters {

    private final JavaFormatContext context;

    private final DeclarationPrefixPrinter declarationPrefixes;

    private final ModuleBlockPrinter moduleBlocks;

    private final ModuleDeclarationPrinter moduleDeclarations;

    private final MemberBlockPrinter memberBlocks;

    private final CallableSignaturePrinter callableSignatures;

    private final ThrowsClausePrinter throwsClauses;

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

    private final BodyDeclarationRuleEnvelope bodyDeclarations;

    DeclarationPrinters(
            JavaFormatContext context,
            TypePrinter types,
            ExpressionPrinters expressions,
            Function<BlockStmt, Doc> blockRenderer,
            Function<Doc, String> commentText
    ) {
        this.context = context;
        FormatterOptions options = context.options;
        CommentTracker comments = context.comments;
        JavaCommentPlacementPolicy commentPlacementPolicy = context.commentPlacementPolicy;
        FormatterPragmas formatterPragmas = context.formatterPragmas;
        RawSource rawSource = context.rawSource;
        RawPreservedSource rawPreservedSource = context.rawPreservedSource;
        CompactSourceText compactSource = context.compactSource;
        CommentPlacement commentPlacement = context.commentPlacement;
        this.declarationPrefixes = new DeclarationPrefixPrinter(
            comments,
            commentPlacementPolicy,
            (annotation, layout) -> expressions.annotation(annotation),
            expressions::annotationFlatText
        );
        this.moduleBlocks = new ModuleBlockPrinter(
            context,
            compactSource::compact,
            compactSource::compactJoin,
            declarationPrefixes::modifiers
        );
        this.moduleDeclarations = new ModuleDeclarationPrinter(
            comments,
            context.sourceShapePolicy,
            rawSource,
            rawPreservedSource,
            new CommentedModulePrinter(),
            declarationPrefixes::annotations,
            commentText,
            compactSource::compact,
            moduleBlocks::moduleBlock,
            moduleBlocks::canUseStructuredRecoveryForCommentedModule,
            context.recoverParseProblems
        );
        this.memberBlocks = new MemberBlockPrinter(
            context,
            declarationPrefixes::hasDeclarationAnnotations,
            formatterPragmas::hasPragma
        );
        this.commentedMethodSignatures = new CommentedMethodSignaturePrinter(options);
        PackageDeclarationPrinter packageDeclarations = new PackageDeclarationPrinter(
            comments,
            rawSource,
            options,
            declarationPrefixes::annotations
        );
        ImportDeclarationPrinter importDeclarations = new ImportDeclarationPrinter(comments);
        this.compilationUnits = new CompilationUnitPrinter(
            context,
            packageDeclarations,
            importDeclarations,
            (moduleDeclaration, layout) -> moduleDeclarations.moduleDeclaration(moduleDeclaration),
            (declaration, layout) -> body(declaration)
        );
        this.fields = new FieldDeclarationPrinter(
            context,
            declarationPrefixes::declarationAnnotations,
            declarationPrefixes::modifiers,
            declarationPrefixes::inlineAnnotations,
            types::typeBody,
            types::typeCanBreak,
            expressions::expression,
            expressions::expressionWithoutOwnComment,
            expressions::binaryHasLineComments,
            expressions::binaryLinesWithComments,
            expressions::suffixedEnclosedExpression,
            expressions::arrayAccessWithBrokenEnclosedName,
            expressions::shouldKeepCastDivisionContinuationFlat,
            expressions::binaryLines,
            expressions::parenthesizedBreak,
            expressions::methodCall,
            expressions::brokenMethodCall,
            expressions::mixedFieldMethodCallChain,
            expressions::initializerChain,
            expressions::canonicalFanChain,
            expressions::packedMethodCallChain,
            methodCall -> expressions.expressionWithTail(methodCall, ExpressionTail.SEMICOLON),
            expressions::mixedFieldMethodCallRoot,
            expressions::methodCallChainFirstLine,
            expressions::methodCallChainRootIsObjectCreation,
            expressions::methodCallChainIsSourceMultiline,
            expressions::methodCallChainInitializerShape,
            expressions::castType,
            conditional -> expressions.conditionalExpression(conditional, true),
            expressions::shouldBreakBeforeConditionalInitializer,
            expressions::arrayCreationTypeBreaks,
            expressions::arrayCreationPrefix,
            expressions::arrayInitializer,
            expressions::compactArrayInitializerWithSourceSpacing,
            expressions::objectCreationPrefix,
            types::typeNameWithoutArguments,
            types::brokenClassOrInterfaceType,
            expressions::shouldPrintScopeAsDoc,
            expressions::binaryFansChainOperand,
            expressions::methodCallPrefix,
            expressions::methodCallArgumentList,
            expressions::huggableBlockLambdaArguments,
            expressions::lambdaParameters,
            expressions::lambdaParametersShouldBreak,
            expressions::lambdaExpression
        );
        this.variableDeclarations = new VariableDeclarationPrinter(
            options,
            declarationPrefixes::annotations,
            declarationPrefixes::modifiers,
            compactSource::compactTypeLike,
            types::typeBody,
            types::typeCanBreak,
            fields::variable,
            fields::variableWithStatementTerminator,
            this::currentIndentedWidth
        );
        this.callableSignatures = new CallableSignaturePrinter(
            comments,
            rawSource,
            options,
            context.layoutWidth,
            compactSource::compact,
            compactSource::compactTypeLike,
            types::typeBody,
            (annotation, layout) -> expressions.annotationPreservingSourceBreaks(annotation, layout),
            expressions::annotationFlatText,
            declarationPrefixes::modifier,
            types::typeCanBreak,
            commentPlacement::unattachedTrailingBlockComment,
            commentText
        );
        this.throwsClauses = new ThrowsClausePrinter(
            options,
            compactSource::compact,
            compactSource::compactJoin,
            this::currentIndentedWidth,
            context.layoutWidth
        );
        this.classOrInterfaces = new ClassOrInterfaceDeclarationPrinter(
            comments,
            context.sourceShapePolicy,
            rawSource,
            rawPreservedSource,
            options,
            new CommentedInterfacePrinter(),
            callableSignatures,
            declarationPrefixes::annotations,
            declarationPrefixes::modifiers,
            types::extendsTypes,
            types::implementsTypes,
            types::permitsTypes,
            types::typeClause,
            types::flatTypeParameters,
            types::flatTypeClause,
            declaration -> memberBlocks.memberBlock(declaration.getMembers(), declaration, this::body)
        );
        this.constructors = new ConstructorDeclarationPrinter(
            callableSignatures,
            declarationPrefixes::annotations,
            declarationPrefixes::modifiers,
            compactSource::compactJoin,
            throwsClauses::throwsClause,
            blockRenderer
        );
        this.methods = new MethodDeclarationPrinter(
            comments,
            context.commentPlacementPolicy,
            context.sourceShapePolicy,
            rawSource,
            rawPreservedSource,
            commentedMethodSignatures,
            callableSignatures,
            declarationPrefixes::declarationAnnotations,
            declarationPrefixes::modifiers,
            types::flatTypeParameters,
            declarationPrefixes::inlineAnnotations,
            commentText,
            compactSource::compactTypeLike,
            types::typeBody,
            types::brokenClassOrInterfaceType,
            types::typeCanBreak,
            throwsClauses::throwsClause,
            blockRenderer
        );
        this.initializers = new InitializerDeclarationPrinter(blockRenderer);
        this.enums = new EnumDeclarationPrinter(
            context,
            declarationPrefixes::annotations,
            declarationPrefixes::modifiers,
            enumTypes -> types.typeClause("implements", enumTypes),
            types::implementsTypes,
            enumTypes -> types.flatTypeClause("implements", enumTypes),
            compactSource::compactJoin,
            expressions::expression,
            this::currentIndentedWidth,
            (members, owner) -> memberBlocks.memberBlock(members, owner, this::body),
            this::body
        );
        this.records = new RecordDeclarationPrinter(
            comments,
            commentPlacementPolicy,
            context.sourceShapePolicy,
            options,
            declarationPrefixes::annotations,
            declarationPrefixes::modifiers,
            callableSignatures::typeParameters,
            types::flatTypeParameters,
            compactSource::compact,
            compactSource::compactJoin,
            types::compactJoinTypeLike,
            compactSource::compactTypeLike,
            types::typeBody,
            (annotation, layout) -> expressions.annotationPreservingSourceBreaks(annotation, layout),
            expressions::annotationFlatText,
            this::currentIndentedWidth,
            declaration -> memberBlocks.memberBlock(declaration.getMembers(), declaration, this::body)
        );
        this.annotationDeclarations = new AnnotationDeclarationPrinter(
            context,
            declarationPrefixes::annotations,
            declarationPrefixes::modifiers,
            compactSource::compactTypeLike,
            types::typeBody,
            expressions::expression,
            this::body,
            (members, owner) -> memberBlocks.memberBlock(members, owner, this::body)
        );
        BodyDeclarationDispatcher bodyDeclarationDispatcher = new BodyDeclarationDispatcher(
            rawPreservedSource,
            compactSource::compact,
            (declaration, layout) -> classOrInterfaces.classOrInterface(declaration),
            (declaration, layout) -> records.record(declaration),
            (declaration, layout) -> enums.enumDeclaration(declaration),
            (declaration, layout) -> annotationDeclarations.annotationDeclaration(declaration),
            (declaration, layout) -> annotationDeclarations.annotationMember(declaration),
            (declaration, layout) -> fields.field(declaration),
            (declaration, layout) -> methods.method(declaration),
            (declaration, layout) -> constructors.compactConstructor(declaration),
            (declaration, layout) -> constructors.constructor(declaration),
            (declaration, layout) -> initializers.initializer(declaration)
        );
        this.bodyDeclarations = new BodyDeclarationRuleEnvelope(
            comments,
            formatterPragmas,
            rawPreservedSource,
            bodyDeclarationDispatcher::bodyContent
        );
    }

    Doc compilationUnit(CompilationUnit unit) {
        return compilationUnits.print(unit);
    }

    Doc body(BodyDeclaration<?> declaration) {
        return bodyDeclarations.body(declaration);
    }

    Doc variableDeclaration(VariableDeclarationExpr declaration) {
        return variableDeclarations.variableDeclaration(declaration);
    }

    Doc variableDeclarationStatement(VariableDeclarationExpr declaration) {
        return variableDeclarations.variableDeclarationStatement(declaration);
    }

    String modifiers(NodeWithModifiers<?> node) {
        return declarationPrefixes.modifiers(node);
    }

    String parameterText(Parameter parameter) {
        return callableSignatures.parameterFlat(parameter);
    }

    private int currentIndentedWidth(String text) {
        return context.layoutWidth.currentIndented(text);
    }
}
