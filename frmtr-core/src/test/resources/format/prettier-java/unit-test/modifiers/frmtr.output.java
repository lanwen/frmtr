@AnnotationOne
@AnnotationTwo
@AnnotationThree
public interface InterfaceWithModifiers {
    @AnnotationOne
    public static final String INTERFACE_CONSTANT = "abc";

    @AnnotationOne
    @AnnotationTwo
    @AnnotationThree
    public default String defaultMethod() {
        return INTERFACE_CONSTANT;
    }

    @AnnotationOne
    @AnnotationTwo
    public static String staticMethod() {
        return INTERFACE_CONSTANT;
    }

    @AnnotationOne
    @AnnotationTwo
    public void twoTrailingAnnotations();

    @AnnotationOne
    void onlyAnnotations();
}

@AnnotationOne
@AnnotationTwo
public abstract class AbstractClassWithModifiers {

    @Annotation
    private static volatile String field;

    @AnnotationOne
    @AnnotationTwo
    @AnnotationThree
    protected abstract String method();

    @AnnotationOne
    @AnnotationTwo
    public void twoTrailingAnnotations() {}

    @AnnotationOne
    void onlyAnnotations() {}
}

@AnnotationOne
@AnnotationTwo
public final class ClassWithModifiers {

    @AnnotationOne
    @AnnotationTwo
    private static final transient String CONSTANT = "abc";

    @AnnotationOne
    @AnnotationTwo
    @AnnotationThree
    protected static final String CONSTANT_2 = "123";

    @AnnotationOne
    @AnnotationTwo
    public static String staticField;

    @AnnotationOne
    @AnnotationTwo
    public String twoTrailingAnnotations;

    @AnnotationOne
    String onlyAnnotations;

    @AnnotationOne
    @AnnotationTwo
    @AnnotationThree
    protected static final synchronized String method() {
        return CONSTANT;
    }

    @AnnotationOne
    @AnnotationTwo
    public void twoTrailingAnnotations() {}

    @AnnotationOne
    void onlyAnnotations() {}
}
