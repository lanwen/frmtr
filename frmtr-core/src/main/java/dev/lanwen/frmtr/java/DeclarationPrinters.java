package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
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
            Function<Doc, String> commentText) {
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
                expressions::annotation,
                expressions::annotationFlatText);
        this.moduleBlocks = new ModuleBlockPrinter(
                context,
                compactSource::compact,
                compactSource::compactJoin,
                declarationPrefixes::modifiers);
        this.moduleDeclarations = new ModuleDeclarationPrinter(
                comments,
                rawSource,
                rawPreservedSource,
                new CommentedModulePrinter(),
                declarationPrefixes::annotations,
                commentText,
                compactSource::compact,
                moduleBlocks::moduleBlock,
                moduleBlocks::canUseStructuredRecoveryForCommentedModule,
                context.recoverParseProblems);
        this.memberBlocks = new MemberBlockPrinter(
                context,
                declarationPrefixes::hasDeclarationAnnotations,
                formatterPragmas::hasPragma);
        this.commentedMethodSignatures = new CommentedMethodSignaturePrinter(options);
        PackageDeclarationPrinter packageDeclarations = new PackageDeclarationPrinter(comments, rawSource, options);
        ImportDeclarationPrinter importDeclarations = new ImportDeclarationPrinter(comments);
        this.compilationUnits = new CompilationUnitPrinter(
                context,
                packageDeclarations,
                importDeclarations,
                moduleDeclarations::moduleDeclaration,
                this::body);
        this.fields = new FieldDeclarationPrinter(
                comments,
                rawSource,
                options,
                context.layoutWidth,
                declarationPrefixes::declarationAnnotations,
                declarationPrefixes::modifiers,
                declarationPrefixes::inlineAnnotations,
                compactSource::compactTypeLike,
                types::typeBody,
                types::typeCanBreak,
                compactSource::compact,
                compactSource::compactWithoutOwnComment,
                compactSource::compactJoin,
                expressions::expression,
                expressions::expressionWithoutOwnComment,
                expressions::binaryHasLineComments,
                expressions::binaryLinesWithComments,
                expressions::suffixedEnclosedExpression,
                expressions::arrayAccessWithBrokenEnclosedName,
                expressions::shouldKeepCastDivisionContinuationFlat,
                expressions::binaryLines,
                expressions::methodCall,
                expressions::brokenMethodCall,
                expressions::mixedFieldMethodCallChain,
                expressions::forcedMethodCallChain,
                expressions::forcedMethodCallChainWithSemicolon,
                expressions::methodCallChainHasFinalTrailingLineComment,
                expressions::mixedFieldMethodCallRoot,
                expressions::methodCallChainFirstLine,
                expressions::methodCallChainRootIsObjectCreation,
                expressions::methodCallChainIsSourceMultiline,
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
                expressions::methodCallPrefix,
                expressions::huggableBlockLambdaArguments,
                expressions::lambdaParameters,
                expressions::lambdaParametersShouldBreak,
                expressions::lambdaExpression);
        this.variableDeclarations = new VariableDeclarationPrinter(
                options,
                declarationPrefixes::annotations,
                declarationPrefixes::modifiers,
                compactSource::compactTypeLike,
                types::typeBody,
                types::typeCanBreak,
                fields::variable,
                fields::variableWithStatementTerminator,
                this::currentIndentedWidth);
        this.callableSignatures = new CallableSignaturePrinter(
                comments,
                rawSource,
                options,
                compactSource::compact,
                compactSource::compactTypeLike,
                types::typeBody,
                expressions::annotationPreservingSourceBreaks,
                expressions::annotationFlatText,
                declarationPrefixes::modifier,
                types::typeCanBreak,
                commentPlacement::unattachedTrailingBlockComment,
                commentText);
        this.throwsClauses = new ThrowsClausePrinter(
                options,
                compactSource::compact,
                compactSource::compactJoin,
                this::currentIndentedWidth);
        this.classOrInterfaces = new ClassOrInterfaceDeclarationPrinter(
                comments,
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
                declaration -> memberBlocks.memberBlock(declaration.getMembers(), declaration, this::body));
        this.constructors = new ConstructorDeclarationPrinter(
                callableSignatures,
                declarationPrefixes::annotations,
                declarationPrefixes::modifiers,
                compactSource::compactJoin,
                throwsClauses::throwsClause,
                blockRenderer);
        this.methods = new MethodDeclarationPrinter(
                rawSource,
                context.sourceShape,
                rawPreservedSource,
                commentedMethodSignatures,
                callableSignatures,
                declarationPrefixes::declarationAnnotations,
                declarationPrefixes::modifiers,
                types::flatTypeParameters,
                declarationPrefixes::inlineAnnotations,
                compactSource::compact,
                types::typeBody,
                types::brokenClassOrInterfaceType,
                types::typeCanBreak,
                throwsClauses::throwsClause,
                blockRenderer);
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
                this::body);
        this.records = new RecordDeclarationPrinter(
                comments,
                commentPlacementPolicy,
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
                expressions::annotationPreservingSourceBreaks,
                expressions::annotationFlatText,
                this::currentIndentedWidth,
                declaration -> memberBlocks.memberBlock(declaration.getMembers(), declaration, this::body));
        this.annotationDeclarations = new AnnotationDeclarationPrinter(
                context,
                declarationPrefixes::annotations,
                declarationPrefixes::modifiers,
                compactSource::compactTypeLike,
                expressions::expression,
                this::body);
        BodyDeclarationDispatcher bodyDeclarationDispatcher = new BodyDeclarationDispatcher(
                rawPreservedSource,
                compactSource::compact,
                classOrInterfaces::classOrInterface,
                records::record,
                enums::enumDeclaration,
                annotationDeclarations::annotationDeclaration,
                annotationDeclarations::annotationMember,
                fields::field,
                methods::method,
                constructors::compactConstructor,
                constructors::constructor,
                initializers::initializer);
        this.bodyDeclarations = new BodyDeclarationRuleEnvelope(
                comments,
                formatterPragmas,
                rawPreservedSource,
                bodyDeclarationDispatcher::bodyContent);
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

    private int currentIndentedWidth(String text) {
        return context.layoutWidth.currentIndented(text);
    }
}
