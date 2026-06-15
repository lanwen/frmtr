package dev.lanwen.frmtr.gradle;

import javax.inject.Inject;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

public abstract class FrmtrPrintExtension {

    private final Property<Boolean> diffs;

    @Inject
    public FrmtrPrintExtension(ObjectFactory objects) {
        this.diffs = objects.property(Boolean.class).convention(true);
    }

    public Property<Boolean> getDiffs() {
        return diffs;
    }
}
