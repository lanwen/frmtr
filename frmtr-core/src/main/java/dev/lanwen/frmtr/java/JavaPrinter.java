package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationDeclaration;
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
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

final class JavaPrinter {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final JavaFormatter.CommentTracker comments = new JavaFormatter.CommentTracker();

    JavaPrinter() {}

    Doc print(CompilationUnit unit) {
        List<Doc> parts = new ArrayList<>();
        Doc orphanComments = comments.orphanComments(unit);
        if (orphanComments != Doc.EMPTY) {
            parts.add(orphanComments);
        }
        unit.getPackageDeclaration().ifPresent(packageDeclaration -> {
            parts.add(comments.leading(packageDeclaration));
            parts.add(Doc.text("package " + packageDeclaration.getNameAsString() + ";"));
        });
        Optional<Doc> imports = imports(unit);
        if (imports.isPresent()) {
            if (!parts.isEmpty()) {
                parts.add(Doc.HARD_LINE);
                parts.add(Doc.HARD_LINE);
            }
            parts.add(imports.orElseThrow());
        }
        if (!unit.getTypes().isEmpty()) {
            if (!parts.isEmpty()) {
                parts.add(Doc.HARD_LINE);
                parts.add(Doc.HARD_LINE);
            }
            parts.add(Doc.join(Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE), unit.getTypes().stream()
                    .map(this::body)
                    .toList()));
        }
        return Doc.concat(parts);
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
        if (!normal.isEmpty()) {
            blocks.add(Doc.join(Doc.HARD_LINE, normal.stream().map(this::importDoc).toList()));
        }
        if (!normal.isEmpty() && !statics.isEmpty()) {
            blocks.add(Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE));
        }
        if (!statics.isEmpty()) {
            blocks.add(Doc.join(Doc.HARD_LINE, statics.stream().map(this::importDoc).toList()));
        }
        return blocks.isEmpty() ? Optional.empty() : Optional.of(Doc.concat(blocks));
    }

    private Doc importDoc(ImportDeclaration declaration) {
        String prefix = declaration.isStatic() ? "import static " : "import ";
        String suffix = declaration.isAsterisk() ? ".*" : "";
        return Doc.concat(comments.leading(declaration), Doc.text(prefix + declaration.getNameAsString() + suffix + ";"));
    }

    private Doc body(BodyDeclaration<?> declaration) {
        return switch (declaration) {
            case ClassOrInterfaceDeclaration classDeclaration -> classOrInterface(classDeclaration);
            case RecordDeclaration recordDeclaration -> record(recordDeclaration);
            case EnumDeclaration enumDeclaration -> enumDeclaration(enumDeclaration);
            case AnnotationDeclaration annotationDeclaration -> rawDeclaration(annotationDeclaration);
            case FieldDeclaration fieldDeclaration -> field(fieldDeclaration);
            case MethodDeclaration methodDeclaration -> method(methodDeclaration);
            case ConstructorDeclaration constructorDeclaration -> constructor(constructorDeclaration);
            case InitializerDeclaration initializerDeclaration -> initializer(initializerDeclaration);
            default -> rawDeclaration(declaration);
        };
    }

    private Doc classOrInterface(ClassOrInterfaceDeclaration declaration) {
        List<Doc> header = new ArrayList<>();
        header.add(comments.leading(declaration));
        header.add(annotations(declaration));
        header.add(Doc.text(modifiers(declaration)));
        header.add(Doc.text(declaration.isInterface() ? "interface " : "class "));
        header.add(Doc.text(declaration.getNameAsString()));
        if (!declaration.getTypeParameters().isEmpty()) {
            header.add(Doc.text("<" + compactJoin(declaration.getTypeParameters()) + ">"));
        }
        extendsTypes(declaration.getExtendedTypes()).ifPresent(header::add);
        implementsTypes(declaration.getImplementedTypes()).ifPresent(header::add);
        header.add(Doc.text(" "));
        header.add(memberBlock(declaration.getMembers()));
        return Doc.concat(header);
    }

    private Doc record(RecordDeclaration declaration) {
        List<Doc> header = new ArrayList<>();
        header.add(comments.leading(declaration));
        header.add(annotations(declaration));
        header.add(Doc.text(modifiers(declaration)));
        header.add(Doc.text("record " + declaration.getNameAsString()));
        if (!declaration.getTypeParameters().isEmpty()) {
            header.add(Doc.text("<" + compactJoin(declaration.getTypeParameters()) + ">"));
        }
        header.add(Doc.group(Doc.concat(
                Doc.text("("),
                Doc.indent(Doc.concat(Doc.SOFT_LINE, Doc.join(Doc.concat(Doc.text(","), Doc.LINE),
                        declaration.getParameters().stream().map(this::parameter).toList()))),
                Doc.SOFT_LINE,
                Doc.text(")"))));
        implementsTypes(declaration.getImplementedTypes()).ifPresent(header::add);
        header.add(Doc.text(" "));
        header.add(memberBlock(declaration.getMembers()));
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
            header.add(Doc.text(members.isEmpty() ? "" : ";"));
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
        docs.add(Doc.text(compact(declaration.getElementType()) + " "));
        docs.add(Doc.group(Doc.join(Doc.concat(Doc.text(","), Doc.LINE), declaration.getVariables().stream()
                .map(this::variable)
                .toList())));
        docs.add(Doc.text(";"));
        return Doc.concat(docs);
    }

    private Doc variable(VariableDeclarator variable) {
        String initializer = variable.getInitializer().map(expression -> " = " + compact(expression)).orElse("");
        return Doc.text(variable.getNameAsString() + initializer);
    }

    private Doc method(MethodDeclaration declaration) {
        List<Doc> docs = new ArrayList<>();
        docs.add(comments.leading(declaration));
        docs.add(annotations(declaration));
        docs.add(Doc.text(modifiers(declaration)));
        if (!declaration.getTypeParameters().isEmpty()) {
            docs.add(Doc.text("<" + compactJoin(declaration.getTypeParameters()) + "> "));
        }
        docs.add(Doc.text(compact(declaration.getType()) + " " + declaration.getNameAsString()));
        docs.add(parameters(declaration.getParameters()));
        if (!declaration.getThrownExceptions().isEmpty()) {
            docs.add(Doc.text(" throws " + compactJoin(declaration.getThrownExceptions())));
        }
        docs.add(declaration.getBody().map(body -> Doc.concat(Doc.text(" "), block(body))).orElse(Doc.text(";")));
        return Doc.concat(docs);
    }

    private Doc constructor(ConstructorDeclaration declaration) {
        List<Doc> docs = new ArrayList<>();
        docs.add(comments.leading(declaration));
        docs.add(annotations(declaration));
        docs.add(Doc.text(modifiers(declaration)));
        if (!declaration.getTypeParameters().isEmpty()) {
            docs.add(Doc.text("<" + compactJoin(declaration.getTypeParameters()) + "> "));
        }
        docs.add(Doc.text(declaration.getNameAsString()));
        docs.add(parameters(declaration.getParameters()));
        if (!declaration.getThrownExceptions().isEmpty()) {
            docs.add(Doc.text(" throws " + compactJoin(declaration.getThrownExceptions())));
        }
        docs.add(Doc.text(" "));
        docs.add(block(declaration.getBody()));
        return Doc.concat(docs);
    }

    private Doc initializer(InitializerDeclaration declaration) {
        return Doc.concat(
                comments.leading(declaration),
                declaration.isStatic() ? Doc.text("static ") : Doc.EMPTY,
                block(declaration.getBody()));
    }

    private Doc memberBlock(NodeList<BodyDeclaration<?>> members) {
        if (members.isEmpty()) {
            return Doc.text("{}");
        }
        List<Doc> memberDocs = members.stream().map(this::body).toList();
        return Doc.concat(
                Doc.text("{"),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.concat(Doc.HARD_LINE, Doc.HARD_LINE), memberDocs))),
                Doc.HARD_LINE,
                Doc.text("}"));
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

    private Doc parameter(Parameter parameter) {
        List<String> parts = new ArrayList<>();
        parameter.getAnnotations().stream().map(this::compact).forEach(parts::add);
        parameter.getModifiers().stream().map(this::modifier).forEach(parts::add);
        parts.add(compact(parameter.getType()) + (parameter.isVarArgs() ? "..." : ""));
        parts.add(parameter.getNameAsString());
        return Doc.text(String.join(" ", parts));
    }

    private Doc block(BlockStmt block) {
        if (block.getStatements().isEmpty() && block.getOrphanComments().isEmpty()) {
            return Doc.text("{}");
        }
        List<Doc> statements = new ArrayList<>();
        Doc orphanComments = comments.orphanComments(block);
        if (orphanComments != Doc.EMPTY) {
            statements.add(orphanComments);
        }
        block.getStatements().stream().map(this::statement).forEach(statements::add);
        return Doc.concat(
                Doc.text("{"),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, statements))),
                Doc.HARD_LINE,
                Doc.text("}"));
    }

    private Doc statement(Statement statement) {
        Doc leading = comments.leading(statement);
        Doc body = switch (statement) {
            case BlockStmt blockStmt -> block(blockStmt);
            case ReturnStmt returnStmt -> Doc.text(returnStmt.getExpression().map(expression -> "return " + compact(expression) + ";").orElse("return;"));
            case ThrowStmt throwStmt -> Doc.text("throw " + compact(throwStmt.getExpression()) + ";");
            case ExpressionStmt expressionStmt -> Doc.text(compact(expressionStmt.getExpression()) + ";");
            case EmptyStmt ignored -> Doc.text(";");
            case BreakStmt breakStmt -> Doc.text("break" + breakStmt.getLabel().map(label -> " " + label.asString()).orElse("") + ";");
            case ContinueStmt continueStmt -> Doc.text("continue" + continueStmt.getLabel().map(label -> " " + label.asString()).orElse("") + ";");
            case IfStmt ifStmt -> ifStatement(ifStmt);
            case WhileStmt whileStmt -> Doc.concat(Doc.text("while (" + compact(whileStmt.getCondition()) + ") "), nestedStatement(whileStmt.getBody()));
            case DoStmt doStmt -> Doc.concat(Doc.text("do "), nestedStatement(doStmt.getBody()), Doc.text(" while (" + compact(doStmt.getCondition()) + ");"));
            case ForStmt forStmt -> Doc.concat(Doc.text(forHeader(forStmt) + " "), nestedStatement(forStmt.getBody()));
            case ForEachStmt forEachStmt -> Doc.concat(
                    Doc.text("for (" + compact(forEachStmt.getVariable()) + " : " + compact(forEachStmt.getIterable()) + ") "),
                    nestedStatement(forEachStmt.getBody()));
            default -> Doc.text(compact(statement));
        };
        return Doc.concat(leading, body);
    }

    private Doc ifStatement(IfStmt statement) {
        List<Doc> docs = new ArrayList<>();
        docs.add(Doc.text("if (" + compact(statement.getCondition()) + ") "));
        docs.add(nestedStatement(statement.getThenStmt()));
        statement.getElseStmt().ifPresent(elseStatement -> {
            docs.add(Doc.text(" else "));
            docs.add(nestedStatement(elseStatement));
        });
        return Doc.concat(docs);
    }

    private Doc nestedStatement(Statement statement) {
        if (statement.isBlockStmt()) {
            return statement(statement);
        }
        return Doc.concat(Doc.text("{"), Doc.indent(Doc.concat(Doc.HARD_LINE, statement(statement))), Doc.HARD_LINE, Doc.text("}"));
    }

    private String forHeader(ForStmt statement) {
        String init = compactJoin(statement.getInitialization());
        String compare = statement.getCompare().map(this::compact).orElse("");
        String update = compactJoin(statement.getUpdate());
        return "for (" + init + "; " + compare + "; " + update + ")";
    }

    private Doc rawDeclaration(BodyDeclaration<?> declaration) {
        return Doc.concat(comments.leading(declaration), Doc.text(compact(declaration)));
    }

    private <T extends Node> Optional<Doc> extendsTypes(NodeList<T> types) {
        if (types.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Doc.text(" extends " + compactJoin(types)));
    }

    private Optional<Doc> implementsTypes(NodeList<ClassOrInterfaceType> types) {
        if (types.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Doc.text(" implements " + compactJoin(types)));
    }

    private Doc annotations(NodeWithAnnotations<?> node) {
        List<AnnotationExpr> annotations = node.getAnnotations();
        if (annotations.isEmpty()) {
            return Doc.EMPTY;
        }
        return Doc.concat(annotations.stream()
                .map(annotation -> Doc.concat(Doc.text(compact(annotation)), Doc.HARD_LINE))
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

    private String compact(Node node) {
        return node.getTokenRange()
                .map(Object::toString)
                .map(this::normalizeWhitespace)
                .orElseGet(() -> normalizeWhitespace(node.toString()));
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
