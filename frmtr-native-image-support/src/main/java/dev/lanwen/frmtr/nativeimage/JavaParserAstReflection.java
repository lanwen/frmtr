package dev.lanwen.frmtr.nativeimage;

import com.github.javaparser.ast.Node;
import com.github.javaparser.metamodel.BaseNodeMetaModel;
import com.github.javaparser.metamodel.JavaParserMetaModel;
import java.lang.reflect.Field;
import java.util.List;

final class JavaParserAstReflection {

    private JavaParserAstReflection() {}

    static List<Class<? extends Node>> nodeTypesFromMetaModel() {
        return JavaParserMetaModel.getNodeMetaModels()
                .stream()
                .map(BaseNodeMetaModel::getType)
                .toList();
    }

    static Field[] declaredFields(Class<?> nodeType) {
        return nodeType.getDeclaredFields();
    }
}
