package dev.lanwen.frmtr.gradle;

import dev.lanwen.frmtr.FormatterOptions;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

public abstract class FrmtrJavaExtension {
    private final ListProperty<String> includes;
    private final ListProperty<String> excludes;
    private final Property<Integer> lineWidth;
    private final Property<FrmtrJavaLanguageLevel> languageLevel;

    @Inject
    public FrmtrJavaExtension(ObjectFactory objects) {
        this.includes = objects.listProperty(String.class).convention(List.of());
        this.excludes = objects.listProperty(String.class).convention(List.of());
        this.lineWidth = objects.property(Integer.class).convention(FormatterOptions.DEFAULT_LINE_WIDTH);
        this.languageLevel = objects.property(FrmtrJavaLanguageLevel.class).convention(FrmtrJavaLanguageLevel.AUTO);
    }

    public ListProperty<String> getIncludes() {
        return includes;
    }

    public ListProperty<String> getExcludes() {
        return excludes;
    }

    public Property<Integer> getLineWidth() {
        return lineWidth;
    }

    public Property<FrmtrJavaLanguageLevel> getLanguageLevel() {
        return languageLevel;
    }

    public void include(String... patterns) {
        includes.addAll(Arrays.asList(patterns));
    }

    public void exclude(String... patterns) {
        excludes.addAll(Arrays.asList(patterns));
    }
}
