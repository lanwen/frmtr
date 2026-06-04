@AnnotationOne
@AnnotationTwo
@AnnotationThree
public interface InterfaceWithModifiers {

    @AnnotationOne
    static final public String INTERFACE_CONSTANT = "abc";

    @AnnotationOne
    @AnnotationTwo
    @AnnotationThree
    default public String defaultMethod() {
        return INTERFACE_CONSTANT;
    }

    @AnnotationOne
    @AnnotationTwo
    static public String staticMethod() {
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
abstract public class AbstractClassWithModifiers {

    @Annotation
    volatile private static String field;

    @AnnotationOne
    @AnnotationTwo
    @AnnotationThree
    abstract protected String method();

    @AnnotationOne
    @AnnotationTwo
    public void twoTrailingAnnotations() {}

    @AnnotationOne
    void onlyAnnotations() {}
}

@AnnotationOne
@AnnotationTwo
final public class ClassWithModifiers {

    @AnnotationOne
    @AnnotationTwo
    transient final private static String CONSTANT = "abc";

    @AnnotationOne
    @AnnotationTwo
    @AnnotationThree
    final static protected String CONSTANT_2 = "123";

    @AnnotationOne
    @AnnotationTwo
    static public String staticField;

    @AnnotationOne
    @AnnotationTwo
    public String twoTrailingAnnotations;

    @AnnotationOne
    String onlyAnnotations;

    @AnnotationOne
    @AnnotationTwo
    @AnnotationThree
    final static synchronized protected String method() {
        return CONSTANT;
    }

    @AnnotationOne
    @AnnotationTwo
    public void twoTrailingAnnotations() {}

    @AnnotationOne
    void onlyAnnotations() {}
}
