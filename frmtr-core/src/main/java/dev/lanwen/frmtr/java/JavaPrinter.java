package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ArrayCreationLevel;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
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
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.modules.ModuleDeclaration;
import com.github.javaparser.ast.modules.ModuleDirective;
import com.github.javaparser.ast.modules.ModuleExportsDirective;
import com.github.javaparser.ast.modules.ModuleOpensDirective;
import com.github.javaparser.ast.modules.ModuleProvidesDirective;
import com.github.javaparser.ast.modules.ModuleRequiresDirective;
import com.github.javaparser.ast.modules.ModuleUsesDirective;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.stmt.YieldStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.TypeParameter;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

final class JavaPrinter {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final JavaFormatter.CommentTracker comments = new JavaFormatter.CommentTracker();
    private final FormatterOptions options;
    private boolean formattingDisabled;

    JavaPrinter(FormatterOptions options) {
        this.options = options;
    }

    Doc print(CompilationUnit unit) {
        List<Doc> parts = new ArrayList<>();
        boolean hasStructuralParts = false;
        int firstTypeLine = firstTypeLine(unit);
        Doc orphanComments = comments.orphanComments(unit, comment -> commentBeginLine(comment) < firstTypeLine);
        if (orphanComments != Doc.EMPTY) {
            parts.add(orphanComments);
        }
        unit.getPackageDeclaration().ifPresent(packageDeclaration -> {
            parts.add(comments.leading(packageDeclaration));
            parts.add(Doc.text("package " + packageDeclaration.getNameAsString() + ";"));
        });
        hasStructuralParts = unit.getPackageDeclaration().isPresent();
        Optional<Doc> imports = imports(unit);
        if (imports.isPresent()) {
            if (!parts.isEmpty()) {
                parts.add(Doc.HARD_LINE);
                parts.add(Doc.HARD_LINE);
            }
            parts.add(imports.orElseThrow());
            hasStructuralParts = true;
        }
        Optional<com.github.javaparser.ast.modules.ModuleDeclaration> module = unit.getModule();
        module.ifPresent(moduleDeclaration -> {
            if (!parts.isEmpty()) {
                parts.add(Doc.HARD_LINE);
                parts.add(Doc.HARD_LINE);
            }
            parts.add(moduleDeclaration(moduleDeclaration));
        });
        hasStructuralParts = hasStructuralParts || module.isPresent();
        if (!unit.getTypes().isEmpty()) {
            if (hasStructuralParts) {
                parts.add(Doc.HARD_LINE);
                parts.add(Doc.HARD_LINE);
            }
            parts.add(Doc.join(Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE), unit.getTypes().stream()
                    .map(this::body)
                    .toList()));
        }
        Doc trailingOrphanComments = comments.orphanComments(unit, comment -> commentBeginLine(comment) > lastTypeLine(unit));
        if (trailingOrphanComments != Doc.EMPTY) {
            if (!parts.isEmpty()) {
                parts.add(Doc.HARD_LINE);
            }
            parts.add(trailingOrphanComments);
        }
        return Doc.concat(parts);
    }

    private int firstTypeLine(CompilationUnit unit) {
        return unit.getTypes().stream()
                .flatMap(type -> type.getRange().stream())
                .mapToInt(range -> range.begin.line)
                .min()
                .orElse(Integer.MAX_VALUE);
    }

    private int lastTypeLine(CompilationUnit unit) {
        return unit.getTypes().stream()
                .flatMap(type -> type.getRange().stream())
                .mapToInt(range -> range.end.line)
                .max()
                .orElse(Integer.MAX_VALUE);
    }

    private int commentBeginLine(com.github.javaparser.ast.comments.Comment comment) {
        return comment.getRange().map(range -> range.begin.line).orElse(Integer.MAX_VALUE);
    }

    private Optional<Doc> imports(CompilationUnit unit) {
        List<ImportDeclaration> normal = unit.getImports().stream()
                .filter(importDeclaration -> !importDeclaration.isStatic())
                .sorted(Comparator.comparing(ImportDeclaration::getNameAsString))
                .toList();
        List<ImportDeclaration> statics = unit.getImports().stream()
                .filter(ImportDeclaration::isStatic)
                .sorted(Comparator.comparing(ImportDeclaration::getNameAsString))
                .toList();
        List<Doc> blocks = new ArrayList<>();
        if (!statics.isEmpty()) {
            blocks.add(Doc.join(Doc.HARD_LINE, statics.stream().map(this::importDoc).toList()));
        }
        if (!normal.isEmpty() && !statics.isEmpty()) {
            blocks.add(Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE));
        }
        if (!normal.isEmpty()) {
            blocks.add(Doc.join(Doc.HARD_LINE, normal.stream().map(this::importDoc).toList()));
        }
        return blocks.isEmpty() ? Optional.empty() : Optional.of(Doc.concat(blocks));
    }

    private Doc importDoc(ImportDeclaration declaration) {
        String prefix = declaration.isStatic() ? "import static " : "import ";
        String suffix = declaration.isAsterisk() ? ".*" : "";
        return Doc.concat(comments.leading(declaration), Doc.text(prefix + declaration.getNameAsString() + suffix + ";"));
    }

    private Doc moduleDeclaration(ModuleDeclaration declaration) {
        return Doc.concat(
                comments.leading(declaration),
                annotations(declaration),
                Doc.text((declaration.isOpen() ? "open " : "") + "module " + compact(declaration.getName()) + " "),
                moduleBlock(declaration));
    }

    private Doc moduleBlock(ModuleDeclaration declaration) {
        if (declaration.getDirectives().isEmpty()) {
            return Doc.text("{}");
        }
        return Doc.concat(
                Doc.text("{"),
                Doc.indent(Doc.concat(Doc.HARD_LINE, moduleContents(declaration.getDirectives()))),
                Doc.HARD_LINE,
                Doc.text("}"));
    }

    private Doc moduleContents(NodeList<ModuleDirective> directives) {
        List<Doc> contents = new ArrayList<>();
        for (int i = 0; i < directives.size(); i++) {
            if (i > 0) {
                contents.add(moduleDirectiveSeparator(directives.get(i - 1), directives.get(i)));
            }
            contents.add(moduleDirective(directives.get(i)));
        }
        return Doc.concat(contents);
    }

    private Doc moduleDirectiveSeparator(ModuleDirective previous, ModuleDirective current) {
        boolean hasBlankLineBetween = previous.getRange()
                .flatMap(previousRange -> current.getRange()
                        .map(currentRange -> currentRange.begin.line > previousRange.end.line + 1))
                .orElse(false);
        return hasBlankLineBetween ? Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE) : Doc.HARD_LINE;
    }

    private Doc moduleDirective(ModuleDirective directive) {
        Doc body = switch (directive) {
            case ModuleRequiresDirective requiresDirective -> moduleRequiresDirective(requiresDirective);
            case ModuleExportsDirective exportsDirective -> moduleAccessDirective(
                    "exports", exportsDirective.getName(), "to", exportsDirective.getModuleNames());
            case ModuleOpensDirective opensDirective -> moduleAccessDirective(
                    "opens", opensDirective.getName(), "to", opensDirective.getModuleNames());
            case ModuleUsesDirective usesDirective -> Doc.text("uses " + compact(usesDirective.getName()) + ";");
            case ModuleProvidesDirective providesDirective -> moduleAccessDirective(
                    "provides", providesDirective.getName(), "with", providesDirective.getWith());
            default -> Doc.text(compact(directive));
        };
        return Doc.concat(comments.leading(directive), body);
    }

    private Doc moduleRequiresDirective(ModuleRequiresDirective directive) {
        return Doc.text("requires " + modifiers(directive) + compact(directive.getName()) + ";");
    }

    private Doc moduleAccessDirective(
            String keyword,
            com.github.javaparser.ast.expr.Name name,
            String targetKeyword,
            NodeList<com.github.javaparser.ast.expr.Name> targets) {
        String prefix = keyword + " " + compact(name);
        if (targets.isEmpty()) {
            return Doc.text(prefix + ";");
        }
        String flatTargets = compactJoin(targets);
        String flat = prefix + " " + targetKeyword + " " + flatTargets + ";";
        if (currentIndentedWidth(flat) <= options.lineWidth()) {
            return Doc.text(flat);
        }
        String targetLine = targetKeyword + " " + flatTargets + ";";
        if (currentIndentedWidth(targetLine) <= options.lineWidth()) {
            return Doc.concat(Doc.text(prefix), Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(targetLine))));
        }
        return Doc.concat(
                Doc.text(prefix),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        Doc.text(targetKeyword),
                        Doc.indent(Doc.concat(
                                Doc.HARD_LINE,
                                Doc.join(
                                        Doc.concat(Doc.text(","), Doc.HARD_LINE),
                                        targets.stream().map(target -> Doc.text(compact(target))).toList()),
                                Doc.text(";"))))));
    }

    private Doc body(BodyDeclaration<?> declaration) {
        FormatterPragma formatterPragma = formatterPragma(declaration);
        if (formatterPragma == FormatterPragma.ON) {
            formattingDisabled = false;
            return Doc.concat(comments.leading(declaration), bodyContent(declaration));
        } else if (formatterPragma == FormatterPragma.END) {
            formattingDisabled = false;
            return Doc.concat(comments.leading(declaration), bodyContent(declaration));
        } else if (formatterPragma == FormatterPragma.OFF) {
            formattingDisabled = true;
            return Doc.concat(comments.leading(declaration), Doc.text(rawWithoutOwnComment(declaration)));
        } else if (formatterPragma == FormatterPragma.START) {
            formattingDisabled = true;
            return Doc.concat(comments.leading(declaration), Doc.text(rawWithoutOwnComment(declaration)));
        } else if (formatterPragma == FormatterPragma.IGNORE) {
            return Doc.concat(comments.leading(declaration), Doc.text(rawWithoutOwnComment(declaration)));
        }
        if (formattingDisabled) {
            return Doc.concat(comments.leading(declaration), Doc.text(rawWithoutOwnComment(declaration)));
        }
        return bodyContent(declaration);
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
            case ConstructorDeclaration constructorDeclaration -> constructor(constructorDeclaration);
            case InitializerDeclaration initializerDeclaration -> initializer(initializerDeclaration);
            default -> rawDeclaration(declaration);
        };
    }

    private Doc classOrInterface(ClassOrInterfaceDeclaration declaration) {
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
            header.add(typeParameters(declaration.getTypeParameters()));
        }
        extendsTypes(declaration.getExtendedTypes()).ifPresent(header::add);
        implementsTypes(declaration.getImplementedTypes()).ifPresent(header::add);
        header.add(Doc.text(" "));
        header.add(memberBlock(declaration.getMembers(), declaration));
        return Doc.concat(header);
    }

    private boolean shouldBreakClassOrInterfaceHeader(ClassOrInterfaceDeclaration declaration) {
        if (!declaration.getTypeParameters().isEmpty()) {
            return false;
        }
        if (declaration.getExtendedTypes().isEmpty() && declaration.getImplementedTypes().isEmpty()) {
            return false;
        }
        String flatHeader = modifiers(declaration)
                + (declaration.isInterface() ? "interface " : "class ")
                + declaration.getNameAsString()
                + flatTypeParameters(declaration.getTypeParameters())
                + flatTypeClause("extends", declaration.getExtendedTypes())
                + flatTypeClause("implements", declaration.getImplementedTypes());
        return flatHeader.length() + 1 + flatMemberBlockWidth(declaration) > options.lineWidth();
    }

    private Doc brokenClassOrInterface(ClassOrInterfaceDeclaration declaration) {
        List<Doc> header = new ArrayList<>();
        header.add(comments.leading(declaration));
        header.add(annotations(declaration));
        header.add(Doc.text(modifiers(declaration)));
        header.add(Doc.text(declaration.isInterface() ? "interface " : "class "));
        header.add(Doc.text(declaration.getNameAsString()));
        if (!declaration.getTypeParameters().isEmpty()) {
            header.add(typeParameters(declaration.getTypeParameters()));
        }
        typeClause("extends", declaration.getExtendedTypes()).ifPresent(header::add);
        typeClause("implements", declaration.getImplementedTypes()).ifPresent(header::add);
        header.add(emptyMemberBlock(declaration) ? Doc.text(" ") : Doc.HARD_LINE);
        header.add(memberBlock(declaration.getMembers(), declaration));
        return Doc.concat(header);
    }

    private Doc record(RecordDeclaration declaration) {
        List<Doc> header = new ArrayList<>();
        header.add(comments.leading(declaration));
        header.add(annotations(declaration));
        header.add(Doc.text(modifiers(declaration)));
        header.add(Doc.text("record " + declaration.getNameAsString()));
        if (!declaration.getTypeParameters().isEmpty()) {
            header.add(typeParameters(declaration.getTypeParameters()));
        }
        header.add(Doc.group(Doc.concat(
                Doc.text("("),
                Doc.indent(Doc.concat(Doc.SOFT_LINE, Doc.join(Doc.concat(Doc.text(","), Doc.LINE),
                        declaration.getParameters().stream().map(this::parameter).toList()))),
                Doc.SOFT_LINE,
                Doc.text(")"))));
        implementsTypes(declaration.getImplementedTypes()).ifPresent(header::add);
        header.add(Doc.text(" "));
        header.add(memberBlock(declaration.getMembers(), declaration));
        return Doc.concat(header);
    }

    private Doc enumDeclaration(EnumDeclaration declaration) {
        List<Doc> header = new ArrayList<>();
        header.add(comments.leading(declaration));
        header.add(annotations(declaration));
        header.add(Doc.text(modifiers(declaration)));
        header.add(Doc.text("enum " + declaration.getNameAsString()));
        implementsTypes(declaration.getImplementedTypes()).ifPresent(header::add);
        header.add(Doc.text(" {"));
        List<Doc> entries = declaration.getEntries().stream().map(this::enumConstant).toList();
        List<Doc> members = declaration.getMembers().stream().map(this::body).toList();
        if (!entries.isEmpty()) {
            header.add(Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.concat(Doc.text(","), Doc.LINE), entries))));
            header.add(Doc.text(members.isEmpty() ? "," : ";"));
        }
        if (!members.isEmpty()) {
            header.add(Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE, Doc.join(Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE), members))));
        }
        if (!entries.isEmpty() || !members.isEmpty()) {
            header.add(Doc.HARD_LINE);
        }
        header.add(Doc.text("}"));
        return Doc.concat(header);
    }

    private Doc annotationDeclaration(AnnotationDeclaration declaration) {
        List<Doc> header = new ArrayList<>();
        header.add(comments.leading(declaration));
        header.add(annotations(declaration));
        header.add(Doc.text(modifiers(declaration)));
        header.add(Doc.text("@interface " + declaration.getNameAsString() + " "));
        header.add(annotationMemberBlock(declaration));
        return Doc.concat(header);
    }

    private Doc annotationMemberBlock(AnnotationDeclaration declaration) {
        if (declaration.getMembers().isEmpty()) {
            return Doc.text("{}");
        }
        List<Doc> memberDocs = declaration.getMembers().stream().map(this::body).toList();
        return Doc.concat(
                Doc.text("{"),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE), memberDocs))),
                Doc.HARD_LINE,
                Doc.text("}"));
    }

    private Doc annotationMember(AnnotationMemberDeclaration declaration) {
        List<Doc> docs = new ArrayList<>();
        docs.add(comments.leading(declaration));
        docs.add(annotations(declaration));
        docs.add(Doc.text(modifiers(declaration)));
        docs.add(Doc.text(compactTypeLike(declaration.getType()) + " " + declaration.getNameAsString() + "()"));
        declaration.getDefaultValue()
                .ifPresent(defaultValue -> docs.add(Doc.concat(Doc.text(" default "), expression(defaultValue))));
        docs.add(Doc.text(";"));
        return Doc.concat(docs);
    }

    private Doc enumConstant(EnumConstantDeclaration declaration) {
        String arguments = declaration.getArguments().isEmpty()
                ? ""
                : "(" + compactJoin(declaration.getArguments()) + ")";
        return Doc.concat(comments.leading(declaration), Doc.text(declaration.getNameAsString() + arguments));
    }

    private Doc field(FieldDeclaration declaration) {
        List<Doc> docs = new ArrayList<>();
        docs.add(comments.leading(declaration));
        docs.add(annotations(declaration));
        docs.add(Doc.text(modifiers(declaration)));
        String declarationPrefix = modifiers(declaration);
        if (!declaration.getVariables().isEmpty()) {
            String type = compactTypeLike(declaration.getVariables().get(0).getType()) + " ";
            declarationPrefix += type;
            docs.add(Doc.text(type));
        }
        String variableDeclarationPrefix = declarationPrefix;
        docs.add(Doc.group(Doc.join(Doc.concat(Doc.text(","), Doc.LINE), declaration.getVariables().stream()
                .map(variable -> variable(variable, variableDeclarationPrefix))
                .toList())));
        docs.add(Doc.text(";"));
        return Doc.concat(docs);
    }

    private Doc variable(VariableDeclarator variable) {
        return variable(variable, "");
    }

    private Doc variable(VariableDeclarator variable, String declarationPrefix) {
        return variable.getInitializer()
                .map(expression -> variableWithInitializer(variable, expression, declarationPrefix))
                .orElseGet(() -> Doc.text(variable.getNameAsString()));
    }

    private Doc variableWithInitializer(
            VariableDeclarator variable,
            Expression initializer,
            String declarationPrefix) {
        String flat = declarationPrefix + variable.getNameAsString() + " = " + compact(initializer) + ";";
        if (currentIndentedWidth(flat) > options.lineWidth()
                && !(initializer instanceof StringLiteralExpr)
                && !initializerHasOwnBreak(initializer)) {
            return Doc.concat(
                    Doc.text(variable.getNameAsString() + " ="),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, expression(initializer))));
        }
        return Doc.concat(Doc.text(variable.getNameAsString() + " = "), expression(initializer));
    }

    private boolean initializerHasOwnBreak(Expression initializer) {
        if (initializer instanceof ArrayCreationExpr arrayCreationExpr) {
            return arrayCreationHasOwnBreak(arrayCreationExpr);
        }
        if (initializer instanceof ArrayAccessExpr) {
            return true;
        }
        if (initializer instanceof MethodCallExpr methodCallExpr) {
            return methodCallExpr.getScope()
                    .filter(ArrayCreationExpr.class::isInstance)
                    .map(ArrayCreationExpr.class::cast)
                    .map(this::arrayCreationHasOwnBreak)
                    .orElse(false);
        }
        return false;
    }

    private boolean arrayCreationHasOwnBreak(ArrayCreationExpr expression) {
        return expression.getInitializer().isPresent() || arrayCreationTypeBreaks(expression);
    }

    private Doc method(MethodDeclaration declaration) {
        List<Doc> docs = new ArrayList<>();
        docs.add(comments.leading(declaration));
        docs.add(annotations(declaration));
        String prefix = modifiers(declaration);
        docs.add(Doc.text(prefix));
        if (!declaration.getTypeParameters().isEmpty()) {
            String typeParameters = "<" + compactJoin(declaration.getTypeParameters()) + "> ";
            prefix += typeParameters;
            docs.add(Doc.text(typeParameters));
        }
        String signature = compact(declaration.getType()) + " " + declaration.getNameAsString();
        prefix += signature;
        docs.add(Doc.text(signature));
        docs.add(parameters(declaration.getParameters()));
        if (!declaration.getThrownExceptions().isEmpty()) {
            docs.add(throwsClause(
                    prefix,
                    declaration.getParameters(),
                    declaration.getThrownExceptions(),
                    declaration.getBody().isPresent() ? " {" : ";"));
        }
        docs.add(declaration.getBody().map(body -> Doc.concat(Doc.text(" "), block(body))).orElse(Doc.text(";")));
        return Doc.concat(docs);
    }

    private Doc constructor(ConstructorDeclaration declaration) {
        List<Doc> docs = new ArrayList<>();
        docs.add(comments.leading(declaration));
        docs.add(annotations(declaration));
        String prefix = modifiers(declaration);
        docs.add(Doc.text(prefix));
        if (!declaration.getTypeParameters().isEmpty()) {
            String typeParameters = "<" + compactJoin(declaration.getTypeParameters()) + "> ";
            prefix += typeParameters;
            docs.add(Doc.text(typeParameters));
        }
        prefix += declaration.getNameAsString();
        docs.add(Doc.text(declaration.getNameAsString()));
        docs.add(parameters(declaration.getParameters()));
        if (!declaration.getThrownExceptions().isEmpty()) {
            docs.add(throwsClause(prefix, declaration.getParameters(), declaration.getThrownExceptions(), " {"));
        }
        docs.add(Doc.text(" "));
        docs.add(block(declaration.getBody()));
        return Doc.concat(docs);
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

    private Doc initializer(InitializerDeclaration declaration) {
        return Doc.concat(
                comments.leading(declaration),
                declaration.isStatic() ? Doc.text("static ") : Doc.EMPTY,
                block(declaration.getBody()));
    }

    private Doc memberBlock(NodeList<BodyDeclaration<?>> members, Node owner) {
        List<Doc> memberDocs = new ArrayList<>(members.stream().map(this::body).toList());
        List<Doc> orphanComments = comments.orphanCommentStatements(owner);
        if (memberDocs.isEmpty()) {
            if (orphanComments.isEmpty()) {
                return Doc.text("{}");
            }
            return Doc.concat(
                    Doc.text("{"),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, orphanComments))),
                    Doc.HARD_LINE,
                    Doc.text("}"));
        }
        Doc contents = memberContents(members, memberDocs);
        if (!orphanComments.isEmpty()) {
            contents = Doc.concat(contents, Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, orphanComments));
        }
        return Doc.concat(
                Doc.text("{"),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE, contents)),
                Doc.HARD_LINE,
                Doc.text("}"));
    }

    private Doc memberContents(NodeList<BodyDeclaration<?>> members, List<Doc> memberDocs) {
        List<Doc> contents = new ArrayList<>();
        for (int i = 0; i < memberDocs.size(); i++) {
            if (i > 0) {
                contents.add(memberSeparator(members.get(i - 1), members.get(i)));
            }
            contents.add(memberDocs.get(i));
        }
        return Doc.concat(contents);
    }

    private Doc memberSeparator(BodyDeclaration<?> previous, BodyDeclaration<?> current) {
        if (!(previous instanceof FieldDeclaration) || !(current instanceof FieldDeclaration)) {
            return Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE);
        }
        boolean hasBlankLineBetween = previous.getRange()
                .flatMap(previousRange -> current.getRange()
                        .map(currentRange -> currentRange.begin.line > previousRange.end.line + 1))
                .orElse(true);
        return hasBlankLineBetween ? Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE) : Doc.HARD_LINE;
    }

    private Doc parameters(NodeList<Parameter> parameters) {
        return Doc.group(Doc.concat(
                Doc.text("("),
                Doc.indent(Doc.concat(
                        Doc.SOFT_LINE,
                        Doc.join(Doc.concat(Doc.text(","), Doc.LINE), parameters.stream().map(this::parameter).toList()))),
                Doc.SOFT_LINE,
                Doc.text(")")));
    }

    private Doc typeParameters(NodeList<TypeParameter> typeParameters) {
        return Doc.group(Doc.concat(
                Doc.text("<"),
                Doc.indent(Doc.concat(
                        Doc.SOFT_LINE,
                        Doc.join(Doc.concat(Doc.text(","), Doc.LINE), typeParameters.stream()
                                .map(typeParameter -> Doc.text(compactTypeLike(typeParameter)))
                                .toList()))),
                Doc.SOFT_LINE,
                Doc.text(">")));
    }

    private Doc parameter(Parameter parameter) {
        List<String> parts = new ArrayList<>();
        parameter.getAnnotations().stream().map(this::compact).forEach(parts::add);
        parameter.getModifiers().stream().map(this::modifier).forEach(parts::add);
        String type = compact(parameter.getType());
        if (parameter.isVarArgs()) {
            String varargsAnnotations = compactJoin(parameter.getVarArgsAnnotations());
            type += varargsAnnotations.isEmpty() ? "..." : " " + varargsAnnotations + "...";
        }
        parts.add(type);
        parts.add(parameter.getNameAsString());
        return Doc.text(String.join(" ", parts));
    }

    private Doc block(BlockStmt block) {
        if (block.getStatements().isEmpty() && block.getOrphanComments().isEmpty()) {
            return Doc.text("{}");
        }
        List<Doc> statements = new ArrayList<>();
        statements.addAll(comments.orphanCommentStatements(block));
        Statement previousStatement = null;
        for (Statement currentStatement : block.getStatements()) {
            if (currentStatement.isEmptyStmt() && previousStatement instanceof SwitchStmt) {
                continue;
            }
            if (!statements.isEmpty()) {
                statements.add(statementSeparator(previousStatement, currentStatement));
            }
            statements.add(statement(currentStatement));
            previousStatement = currentStatement;
        }
        return Doc.concat(
                Doc.text("{"),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.concat(statements))),
                Doc.HARD_LINE,
                Doc.text("}"));
    }

    private Doc statementSeparator(Statement previousStatement, Statement currentStatement) {
        if (previousStatement == null) {
            return Doc.HARD_LINE;
        }
        if (formatterPragma(previousStatement) != FormatterPragma.NONE
                || formatterPragma(currentStatement) != FormatterPragma.NONE) {
            return Doc.HARD_LINE;
        }
        boolean hasBlankLineBetween = previousStatement.getRange()
                .flatMap(previousRange -> currentStatement.getRange()
                        .map(currentRange -> currentRange.begin.line > previousRange.end.line + 1))
                .orElse(false);
        return hasBlankLineBetween ? Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE) : Doc.HARD_LINE;
    }

    private Doc statement(Statement statement) {
        FormatterPragma formatterPragma = formatterPragma(statement);
        Doc trailing = comments.trailingLineComment(statement);
        Doc leading = trailing == Doc.EMPTY ? comments.leading(statement) : Doc.EMPTY;
        if (formatterPragma == FormatterPragma.ON) {
            formattingDisabled = false;
        } else if (formatterPragma == FormatterPragma.END) {
            formattingDisabled = false;
        } else if (formatterPragma == FormatterPragma.OFF) {
            formattingDisabled = true;
        } else if (formatterPragma == FormatterPragma.START) {
            formattingDisabled = true;
        } else if (formatterPragma == FormatterPragma.IGNORE) {
            return Doc.concat(leading, Doc.text(rawWithoutOwnComment(statement)), Doc.HARD_LINE);
        }
        if (formattingDisabled) {
            return Doc.concat(leading, Doc.text(rawWithoutOwnComment(statement)));
        }
        Doc body = switch (statement) {
            case BlockStmt blockStmt -> block(blockStmt);
            case ReturnStmt returnStmt -> returnStatement(returnStmt);
            case ThrowStmt throwStmt -> Doc.concat(Doc.text("throw "), expression(throwStmt.getExpression()), Doc.text(";"));
            case YieldStmt yieldStmt -> yieldStatement(yieldStmt);
            case ExplicitConstructorInvocationStmt constructorInvocation -> Doc.concat(explicitConstructorInvocation(constructorInvocation), Doc.text(";"));
            case ExpressionStmt expressionStmt -> expressionStatement(expressionStmt);
            case EmptyStmt ignored -> Doc.text(";");
            case BreakStmt breakStmt -> Doc.text("break" + breakStmt.getLabel().map(label -> " " + label.asString()).orElse("") + ";");
            case ContinueStmt continueStmt -> Doc.text("continue" + continueStmt.getLabel().map(label -> " " + label.asString()).orElse("") + ";");
            case IfStmt ifStmt -> ifStatement(ifStmt);
            case WhileStmt whileStmt -> Doc.concat(Doc.text("while (" + compact(whileStmt.getCondition()) + ") "), nestedStatement(whileStmt.getBody()));
            case DoStmt doStmt -> Doc.concat(Doc.text("do "), nestedStatement(doStmt.getBody()), Doc.text(" while (" + compact(doStmt.getCondition()) + ");"));
            case SynchronizedStmt synchronizedStmt -> Doc.concat(Doc.text("synchronized (" + compact(synchronizedStmt.getExpression()) + ") "), block(synchronizedStmt.getBody()));
            case SwitchStmt switchStmt -> switchStatement(switchStmt);
            case ForStmt forStmt -> Doc.concat(Doc.text(forHeader(forStmt) + " "), nestedStatement(forStmt.getBody()));
            case ForEachStmt forEachStmt -> Doc.concat(
                    Doc.text("for (" + compact(forEachStmt.getVariable()) + " : " + compact(forEachStmt.getIterable()) + ") "),
                    nestedStatement(forEachStmt.getBody()));
            default -> Doc.text(compact(statement));
        };
        return Doc.concat(leading, body, trailing == Doc.EMPTY ? Doc.EMPTY : Doc.concat(Doc.text(" "), trailing));
    }

    private Doc returnStatement(ReturnStmt statement) {
        return statement.getExpression()
                .map(expression -> Doc.concat(Doc.text("return "), returnExpression(expression), Doc.text(";")))
                .orElse(Doc.text("return;"));
    }

    private Doc returnExpression(Expression expression) {
        String flatReturn = "return " + compact(expression) + ";";
        if (currentIndentedWidth(flatReturn) <= options.lineWidth()) {
            return expression(expression);
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
        return Doc.concat(
                Doc.text("("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, binaryExpressionLines(expression))),
                Doc.HARD_LINE,
                Doc.text(")"));
    }

    private Doc binaryExpressionLines(Expression expression) {
        if (!(expression instanceof BinaryExpr binaryExpr)) {
            return expression(expression);
        }
        if (parenthesizedInnerWidth(compact(binaryExpr)) <= options.lineWidth()) {
            return expression(binaryExpr);
        }
        List<Expression> operands = new ArrayList<>();
        flattenBinaryExpression(binaryExpr, binaryExpr.getOperator(), operands);
        List<Doc> lines = new ArrayList<>();
        for (int i = 0; i < operands.size(); i++) {
            Doc operand = expression(operands.get(i));
            if (i < operands.size() - 1) {
                operand = Doc.concat(operand, Doc.text(" " + binaryExpr.getOperator().asString()));
            }
            lines.add(operand);
        }
        return Doc.join(Doc.HARD_LINE, lines);
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

    private FormatterPragma formatterPragma(Node node) {
        return node.getComment()
                .map(comment -> {
                    String content = comment.getContent();
                    if (content.contains("@formatter:off")) {
                        return FormatterPragma.OFF;
                    }
                    if (content.contains("@formatter:on")) {
                        return FormatterPragma.ON;
                    }
                    if (content.contains("prettier-ignore-start")) {
                        return FormatterPragma.START;
                    }
                    if (content.contains("prettier-ignore-end")) {
                        return FormatterPragma.END;
                    }
                    if (content.contains("prettier-ignore")) {
                        return FormatterPragma.IGNORE;
                    }
                    return FormatterPragma.NONE;
                })
                .orElse(FormatterPragma.NONE);
    }

    private Doc expressionStatement(ExpressionStmt statement) {
        Expression expression = statement.getExpression();
        if (expression instanceof VariableDeclarationExpr variableDeclaration) {
            return Doc.concat(variableDeclaration(variableDeclaration), Doc.text(";"));
        }
        return Doc.concat(expression(expression), Doc.text(";"));
    }

    private Doc expression(Expression expression) {
        if (expression instanceof AssignExpr assignExpr) {
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
        if (expression instanceof MethodCallExpr methodCallExpr) {
            return methodCall(methodCallExpr);
        }
        if (expression instanceof ObjectCreationExpr objectCreationExpr) {
            return objectCreation(objectCreationExpr);
        }
        if (expression instanceof SwitchExpr switchExpr) {
            return switchExpression(switchExpr);
        }
        return Doc.text(compact(expression));
    }

    private Doc arrayAccess(ArrayAccessExpr expression) {
        return Doc.group(Doc.concat(
                expression(expression.getName()),
                Doc.text("["),
                Doc.indent(Doc.concat(Doc.SOFT_LINE, expression(expression.getIndex()))),
                Doc.SOFT_LINE,
                Doc.text("]")));
    }

    private Doc arrayCreation(ArrayCreationExpr expression) {
        Doc prefix = Doc.concat(
                Doc.text("new "),
                arrayCreationType(expression),
                Doc.text(compactJoinArrayLevels(expression.getLevels())));
        return expression.getInitializer()
                .map(initializer -> Doc.concat(prefix, Doc.text(" "), arrayInitializer(initializer)))
                .orElse(prefix);
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
        List<Doc> comments = JavaPrinter.this.comments.orphanCommentStatements(expression);
        if (expression.getValues().isEmpty() && comments.isEmpty()) {
            return Doc.text("{}");
        }
        List<Doc> values = new ArrayList<>(comments);
        values.addAll(expression.getValues().stream()
                .map(value -> Doc.concat(expression(value), Doc.text(",")))
                .toList());
        return Doc.concat(
                Doc.text("{"),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, values))),
                Doc.HARD_LINE,
                Doc.text("}"));
    }

    private Doc annotation(AnnotationExpr annotation) {
        if (annotation instanceof NormalAnnotationExpr normalAnnotation) {
            return normalAnnotation(normalAnnotation);
        }
        if (annotation instanceof SingleMemberAnnotationExpr singleMemberAnnotation) {
            return singleMemberAnnotation(singleMemberAnnotation);
        }
        return Doc.text("@" + compact(annotation.getName()));
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
        return Doc.concat(Doc.text(prefix + "("), annotationValue(annotation.getMemberValue()), Doc.text(")"));
    }

    private Doc annotationPair(MemberValuePair pair) {
        return Doc.concat(Doc.text(pair.getNameAsString() + " = "), annotationValue(pair.getValue()));
    }

    private Doc annotationValue(Expression value) {
        if (value instanceof ArrayInitializerExpr arrayInitializerExpr) {
            String flat = compactAnnotationArrayInitializer(arrayInitializerExpr);
            if (currentIndentedWidth(flat) <= options.lineWidth()) {
                return Doc.text(flat);
            }
            return annotationArrayInitializer(arrayInitializerExpr);
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
                    expression(expression.getLeft()),
                    Doc.text(" " + expression.getOperator().asString() + " "),
                    expression(expression.getRight()));
        }
        return Doc.concat(
                Doc.text(compactWithoutOwnComment(expression.getLeft()) + " " + expression.getOperator().asString() + " "),
                JavaFormatter.commentDoc(leftLineComment.orElseThrow()),
                Doc.indent(Doc.concat(Doc.HARD_LINE, expression(expression.getRight()))));
    }

    private Doc methodCall(MethodCallExpr expression) {
        if (expression.getScope().isEmpty()
                && expression.getNameAsString().equals("yield")
                && !expression.getArguments().isEmpty()) {
            return Doc.text("yield (" + compactJoin(expression.getArguments()) + ")");
        }
        Optional<Doc> chain = methodCallChain(expression);
        if (chain.isPresent()) {
            return chain.orElseThrow();
        }
        if (expression.getScope().filter(this::shouldPrintScopeAsDoc).isPresent()) {
            return Doc.concat(
                    expression(expression.getScope().orElseThrow()),
                    Doc.text("."),
                    methodCallWithoutScope(expression));
        }
        String prefix = expression.getScope().map(scope -> compact(scope) + ".").orElse("")
                + expression.getTypeArguments().map(typeArguments -> "<" + compactJoin(typeArguments) + ">").orElse("")
                + expression.getNameAsString();
        if (expression.getArguments().isEmpty()) {
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

    private boolean shouldPrintScopeAsDoc(Expression expression) {
        return expression instanceof ArrayCreationExpr || expression instanceof ArrayAccessExpr;
    }

    private Doc methodCallWithoutScope(MethodCallExpr expression) {
        String prefix = expression.getTypeArguments().map(typeArguments -> "<" + compactJoin(typeArguments) + ">").orElse("")
                + expression.getNameAsString();
        if (expression.getArguments().isEmpty()) {
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

    private Optional<Doc> methodCallChain(MethodCallExpr expression) {
        if (compact(expression).length() <= options.lineWidth() || expression.getScope().isEmpty()) {
            return Optional.empty();
        }
        List<MethodCallExpr> calls = new ArrayList<>();
        Expression root = methodCallChainRoot(expression, calls);
        if (calls.isEmpty() || (calls.size() < 2 && !(root instanceof MethodCallExpr))) {
            return Optional.empty();
        }
        if (calls.size() == 1 && root instanceof MethodCallExpr) {
            return Optional.of(Doc.concat(expression(root), methodCallChainSegment(calls.getFirst())));
        }
        return Optional.of(Doc.concat(
                expression(root),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, calls.stream()
                        .map(this::methodCallChainSegment)
                        .toList())))));
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
        String typeArguments = expression.getTypeArguments()
                .map(arguments -> "<" + compactJoinTypeLike(arguments) + ">")
                .orElse("");
        String prefix = "." + typeArguments + expression.getNameAsString();
        if (expression.getArguments().isEmpty()) {
            return Doc.text(prefix + "()");
        }
        return Doc.group(Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(Doc.SOFT_LINE, Doc.join(Doc.concat(Doc.text(","), Doc.LINE), expression.getArguments().stream()
                        .map(this::expression)
                        .toList()))),
                Doc.SOFT_LINE,
                Doc.text(")")));
    }

    private Doc objectCreation(ObjectCreationExpr expression) {
        if (expression.getAnonymousClassBody().isPresent()) {
            return Doc.text(compact(expression));
        }
        String prefix = expression.getScope().map(scope -> compact(scope) + ".").orElse("")
                + "new "
                + expression.getTypeArguments().map(typeArguments -> "<" + compactJoinTypeLike(typeArguments) + "> ").orElse("")
                + compactTypeLike(expression.getType());
        if (expression.getArguments().isEmpty()) {
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

    private Doc switchStatement(SwitchStmt statement) {
        return Doc.concat(
                Doc.text("switch (" + compact(statement.getSelector()) + ") "),
                switchBlock(statement.getEntries()));
    }

    private Doc switchExpression(SwitchExpr expression) {
        return Doc.concat(
                Doc.text("switch (" + compact(expression.getSelector()) + ") "),
                switchBlock(expression.getEntries()));
    }

    private Doc switchBlock(NodeList<SwitchEntry> entries) {
        if (entries.isEmpty()) {
            return Doc.text("{}");
        }
        return Doc.concat(
                Doc.text("{"),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, entries.stream()
                        .map(this::switchEntry)
                        .toList()))),
                Doc.HARD_LINE,
                Doc.text("}"));
    }

    private Doc switchEntry(SwitchEntry entry) {
        String label = entry.isDefault() ? "default" : "case " + compactJoin(entry.getLabels());
        String guard = entry.getGuard().map(expression -> " when " + compact(expression)).orElse("");
        if (entry.getType() == SwitchEntry.Type.STATEMENT_GROUP) {
            return Doc.concat(Doc.text(label + guard + ":"), switchEntryStatements(entry.getStatements()));
        }
        if (entry.getStatements().isEmpty()) {
            return Doc.text(label + guard + " ->");
        }
        Statement statement = entry.getStatements().get(0);
        return Doc.concat(Doc.text(label + guard + " -> "), switchEntryBody(statement));
    }

    private Doc switchEntryBody(Statement statement) {
        if (statement.isBlockStmt()) {
            return block(statement.asBlockStmt());
        }
        return Doc.concat(statement(statement));
    }

    private Doc switchEntryStatements(NodeList<Statement> statements) {
        if (statements.isEmpty()) {
            return Doc.EMPTY;
        }
        return Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, statements.stream()
                .map(this::statement)
                .toList())));
    }

    private Doc variableDeclaration(VariableDeclarationExpr declaration) {
        List<Doc> docs = new ArrayList<>();
        docs.add(annotations(declaration));
        docs.add(Doc.text(modifiers(declaration)));
        if (!declaration.getVariables().isEmpty()) {
            docs.add(Doc.text(compactTypeLike(declaration.getVariables().get(0).getType()) + " "));
        }
        docs.add(Doc.group(Doc.join(Doc.concat(Doc.text(","), Doc.LINE), declaration.getVariables().stream()
                .map(this::variable)
                .toList())));
        return Doc.concat(docs);
    }

    private Doc ifStatement(IfStmt statement) {
        List<Doc> docs = new ArrayList<>();
        docs.add(Doc.text("if (" + compact(statement.getCondition()) + ") "));
        docs.add(nestedStatement(statement.getThenStmt()));
        statement.getElseStmt().ifPresent(elseStatement -> {
            docs.add(Doc.text(" else "));
            docs.add(elseStatement.isIfStmt() ? statement(elseStatement) : nestedStatement(elseStatement));
        });
        return Doc.concat(docs);
    }

    private Doc nestedStatement(Statement statement) {
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

    private String forHeader(ForStmt statement) {
        String init = compactJoin(statement.getInitialization());
        String compare = statement.getCompare().map(this::compact).orElse("");
        String update = compactJoin(statement.getUpdate());
        if (init.isEmpty() && compare.isEmpty() && update.isEmpty()) {
            return "for (;;)";
        }
        return "for (" + init + "; " + compare + "; " + update + ")";
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

    private <T extends Node> Optional<Doc> typeClause(String keyword, NodeList<T> types) {
        if (types.isEmpty()) {
            return Optional.empty();
        }
        String flat = keyword + " " + compactJoinTypeLike(types);
        if (flat.length() + options.indentUnit().length() <= options.lineWidth()) {
            return Optional.of(Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(flat))));
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
        List<AnnotationExpr> annotations = node.getAnnotations();
        if (annotations.isEmpty()) {
            return Doc.EMPTY;
        }
        return Doc.concat(annotations.stream()
                .map(annotation -> Doc.concat(annotation(annotation), Doc.HARD_LINE))
                .toList());
    }

    private String modifiers(NodeWithModifiers<?> node) {
        if (node.getModifiers().isEmpty()) {
            return "";
        }
        return String.join(" ", node.getModifiers().stream().map(this::modifier).toList()) + " ";
    }

    private String modifier(Modifier modifier) {
        return modifier.getKeyword().asString();
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
        return node.getTokenRange()
                .map(Object::toString)
                .map(this::normalizeWhitespace)
                .orElseGet(() -> normalizeWhitespace(node.toString()));
    }

    private String compactTypeLike(Node node) {
        return compact(node)
                .replaceAll("<\\s+", "<")
                .replaceAll("\\s+>", ">");
    }

    private String compactWithoutOwnComment(Node node) {
        Node clone = node.clone();
        clone.removeComment();
        return compact(clone);
    }

    private String rawWithoutOwnComment(Node node) {
        Node clone = node.clone();
        clone.removeComment();
        String raw = clone.getTokenRange().map(Object::toString).orElseGet(clone::toString).strip();
        return options.preserveRawTrailingWhitespace() ? raw : stripTrailingHorizontalWhitespace(raw);
    }

    private String stripTrailingHorizontalWhitespace(String text) {
        return text.lines()
                .map(line -> line.stripTrailing())
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("");
    }

    private enum FormatterPragma {
        OFF,
        ON,
        START,
        END,
        IGNORE,
        NONE
    }

    private String normalizeWhitespace(String text) {
        String stripped = text.strip();
        if (stripped.isEmpty()) {
            return "";
        }
        String normalized = WHITESPACE.matcher(stripped).replaceAll(" ")
                .replaceAll("(?<![=!<>])\\s*=\\s*(?![=])", " = ");
        return WHITESPACE.matcher(normalized).replaceAll(" ");
    }
}
