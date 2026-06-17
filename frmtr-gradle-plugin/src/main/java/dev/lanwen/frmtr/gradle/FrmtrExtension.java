package dev.lanwen.frmtr.gradle;

import javax.inject.Inject;
import org.gradle.api.Action;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

public abstract class FrmtrExtension {

    private final Property<Boolean> enabled;

    private final FrmtrJavaExtension java;

    private final FrmtrCheckExtension check;

    @Inject
    public FrmtrExtension(ObjectFactory objects) {
        this.enabled = objects.property(Boolean.class).convention(true);
        this.java = objects.newInstance(FrmtrJavaExtension.class);
        this.check = objects.newInstance(FrmtrCheckExtension.class);
    }

    public Property<Boolean> getEnabled() {
        return enabled;
    }

    public FrmtrJavaExtension getJava() {
        return java;
    }

    public void java(Action<? super FrmtrJavaExtension> action) {
        action.execute(java);
    }

    public FrmtrCheckExtension getCheck() {
        return check;
    }

    public void check(Action<? super FrmtrCheckExtension> action) {
        action.execute(check);
    }
}
