package dev.lanwen.frmtr.nativeimage;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.metamodel.BaseNodeMetaModel;
import com.github.javaparser.metamodel.JavaParserMetaModel;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

final class JavaParserAstReflectionTest {

    @Test
    void coversEveryJavaParserNodeMetaModelType() {
        assertThat(JavaParserAstReflection.nodeTypesFromMetaModel()).containsExactlyElementsOf(
            JavaParserMetaModel.getNodeMetaModels()
                    .stream()
                    .map(BaseNodeMetaModel::getType)
                    .toList()
        );
    }

    @Test
    void exposesDeclaredFieldsForNodeMetaModelTypes() {
        assertThat(JavaParserAstReflection.nodeTypesFromMetaModel()).allSatisfy(nodeType -> assertThat(JavaParserAstReflection.declaredFields(nodeType)).containsExactly(
                nodeType.getDeclaredFields()
        ));
    }

    @Test
    void includesKnownVariableNodeListFields() throws ReflectiveOperationException {
        assertThat(JavaParserAstReflection.nodeTypesFromMetaModel()).contains(
            FieldDeclaration.class,
            VariableDeclarationExpr.class
        );
        assertThat(JavaParserAstReflection.declaredFields(FieldDeclaration.class)).contains(
            declaredField(FieldDeclaration.class, "variables")
        );
        assertThat(JavaParserAstReflection.declaredFields(VariableDeclarationExpr.class)).contains(
            declaredField(VariableDeclarationExpr.class, "variables")
        );
    }

    private static Field declaredField(Class<?> type, String name) throws ReflectiveOperationException {
        return type.getDeclaredField(name);
    }
}
