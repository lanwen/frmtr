public @interface AnnotationInterfaceDeclaration {
    public String value() default "";
    @RandomAnnotation Integer[][] annotatedMatrix = (Integer[][]) new Object[4][2];
    @RandomBreakingAnnotation(one = "One", two = "Two", three = "Three", four = "Four", five = "Five")
    Integer[][] annotatedMatrix = (Integer[][]) new Object[4][2];
    @RandomAnnotationWithObject({"North", "South", "East", "West", "Central", "Remote", "Primary", "Backup", "Archive", "Audit"})
    V[][] annotatedGrid = (V[][]) new Object[rowList.size()][columnList.size()];
    record Config(String region, String tier) {}
}
