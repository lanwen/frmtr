package dev.lanwen.frmtr.gradle;

import javax.inject.Inject;
import org.gradle.api.Action;
import org.gradle.api.model.ObjectFactory;

public abstract class FrmtrCheckExtension {

    private final FrmtrPrintExtension print;

    @Inject
    public FrmtrCheckExtension(ObjectFactory objects) {
        this.print = objects.newInstance(FrmtrPrintExtension.class);
    }

    public FrmtrPrintExtension getPrint() {
        return print;
    }

    public void print(Action<? super FrmtrPrintExtension> action) {
        action.execute(print);
    }
}
