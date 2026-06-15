package dev.lanwen.frmtr.nativeimage;

import com.github.javaparser.ast.Node;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

public final class JavaParserReflectionFeature implements Feature {

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        for (Class<? extends Node> nodeType : JavaParserAstReflection.nodeTypesFromMetaModel()) {
            RuntimeReflection.register(JavaParserAstReflection.declaredFields(nodeType));
        }
    }
}
