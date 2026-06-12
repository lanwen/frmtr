public interface PropertyAvailability {
    boolean isAvailable(Object propertyId);

    public static final Method LOOKUP_METHOD = ReflectionMethods.findLookupMethod();
}

public interface RegionalAvailability extends PrimaryRegion, BackupRegion, ArchiveRegion, PreviewRegion {
    boolean isAvailable(Object propertyId);

    public static final Method LOOKUP_METHOD = ReflectionMethods.findLookupMethod();
}

public interface GlobalAvailability extends PrimaryRegion, BackupRegion, ArchiveRegion, PreviewRegion, CanaryRegion, AuditRegion, BillingRegion, SearchRegion {
    boolean isAvailable(Object propertyId);

    public static final Method LOOKUP_METHOD = ReflectionMethods.findLookupMethod();
}

interface AvailabilityContract {
    String PRIMARY_LABEL = "primary";
    String SECONDARY_LABEL = "secondary";

    class Token {}

    AvailabilityResult firstAvailabilityCheck(String propertyId);
    DetailedAvailabilityResult secondAvailabilityCheckWithAVeryLongName(String propertyId);

    interface NestedContract {}

    AvailabilityResult thirdAvailabilityCheck(String propertyId);

    @Annotation(annotationAttribute = CONSTANT_STRING)
    AvailabilityResult annotatedAvailabilityCheck(String propertyId);

    @Annotation(annotationAttribute = CONSTANT_STRING)
    AvailabilityResult otherAnnotatedAvailabilityCheck(String propertyId);

    AvailabilityResult fourthAvailabilityCheck(String propertyId);

    @Annotation(annotationAttribute = CONSTANT_STRING)
    String AUDIT_LABEL = "audit";

    String FALLBACK_LABEL = "fallback";
}

public interface EmptyInterface {}

public interface InterfaceWithSemicolon {
    String PRIMARY_LABEL = "primary";
}

@FunctionalInterface
public interface Filterer<Group, Element> {
    //
}

sealed class SingleStagePipeline<SourceRecord> extends PipelineNode permits CsvPipeline {

    void refresh() {}
}

sealed class DualStagePipeline<SourceRecord, TargetRecord> extends PipelineNode permits JsonPipeline {

    void refresh() {}
}

sealed class EmptyDualStagePipeline<SourceRecord, TargetRecord> extends PipelineNode permits JsonPipeline {}

class SimplePipeline<SourceRecord, TargetRecord> extends PipelineNode<ParsedRecord, OutputRecord> {

    void refresh() {}
}

class ExtendedPipeline<SourceRecord, TargetRecord> extends PipelineNode<ParsedRecord, OutputRecord, AuditRecord, PipelineFrame, HeaderBlock> {

    void refresh() {}
}

sealed class WidePipeline<SourceRecord, TargetRecord, ParserConfig, OutputSpec, AuditEvent, RetryPolicy>
    extends PipelineFrame<HeaderBlock, PayloadBlock>
    permits CsvPipeline, JsonPipeline
{

    void refresh() {}
}

sealed class EmptyWidePipeline<SourceRecord, TargetRecord, ParserConfig, OutputSpec, AuditEvent, RetryPolicy>
    extends PipelineFrame<HeaderBlock, PayloadBlock>
    permits CsvPipeline, JsonPipeline {}

sealed class VeryWidePipeline<SourceRecord, TargetRecord, ParserConfig, OutputSpec, AuditEvent, RetryPolicy>
    extends PipelineFrame<HeaderBlock, PayloadBlock, CsvPipeline, JsonPipeline, RetryWindow, FailureReport>
    permits XmlPipeline, YamlPipeline, AvroPipeline, ParquetPipeline, ArchivePipeline, StreamingPipeline, PreviewPipeline
{

    void refresh() {}
}

sealed class EmptyVeryWidePipeline<SourceRecord, TargetRecord, ParserConfig, OutputSpec, AuditEvent, RetryPolicy>
    extends PipelineFrame<HeaderBlock, PayloadBlock, CsvPipeline, JsonPipeline, RetryWindow, FailureReport>
    permits XmlPipeline, YamlPipeline, AvroPipeline, ParquetPipeline, ArchivePipeline, StreamingPipeline, PreviewPipeline {}


public sealed class Rectangle
    implements Shape
    permits Square {

    private final double length;
    private final double height;

    public Rectangle(double length, double height) {
        this.length = length;
        this.height = height;
    }

    @Override
    public double area() {
        return length * height;
    }

}

public non-sealed class RightTriangle implements Triangle {

    private final double adjacent;
    private final double opposite;

    public RightTriangle(double adjacent, double opposite) {
        this.adjacent = adjacent;
        this.opposite = opposite;
    }

    @Override
    public double area() {
        interface People { String name(); }
        record Person(String name) implements People { }
        record Persons(String... names) { }

        People person = new Person("John Doe");

        return adjacent * opposite / 2;
    }

}

public sealed interface Shape
    permits Circle, Rectangle, Triangle, Unicorn {

    double area();

    default Shape rotate(double angle) {
        return this;
    }

    default String areaMessage() {
        if (this instanceof Circle)
            return "Circle: " + area();
        else if (this instanceof Rectangle)
            return "Rectangle: " + area();
        else if (this instanceof RightTriangle)
            return "Triangle: " + area();
        // :(
        throw new IllegalArgumentException();
    }

}

public non-sealed interface Triangle extends Shape {

}

public sealed interface Shape permits ExpandedCircle, ExpandedRectangle, ExpandedTriangle, ExpandedUnicorn {}
public sealed interface Shape extends AbstractShape permits ExpandedCircle, ExpandedRectangle, ExpandedTriangle, ExpandedUnicorn {}
public sealed class Shape permits ExpandedCircle, ExpandedRectangle, ExpandedTriangle, ExpandedUnicorn {}
public sealed class Shape extends AbstractShape permits ExpandedCircle, ExpandedRectangle, ExpandedTriangle, ExpandedUnicorn {}

public class NestedSealedClasses {
    public static sealed abstract class SealedParent permits SealedChild {}

    final static class SealedChild extends SealedParent {}
}

public class NestedNonSealedClasses {
    public static non-sealed abstract class NonSealedParent {}

    final static class SealedChild extends NonSealedParent {}
}

public interface Test {
    sealed interface Inner {}

    public static sealed abstract class SealedParent {}

    non-sealed interface Inner {}

    public static non-sealed abstract class SealedParent {}

    final static class SealedChild extends SealedParent {}
}
