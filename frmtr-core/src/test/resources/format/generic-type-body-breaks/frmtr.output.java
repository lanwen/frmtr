class Demo {

    void method() {
        var adapter = (RedactedFormatterHarness<
            AlphaEnvelope.FirstPayload,
            BetaEnvelope.SecondPayload,
            GammaEnvelope.ThirdPayload
        >) (RedactedFormatterHarness<?, ?, ?>) create();
    }

    void longName() {
        var adapterIdentifierWithNoRoomForCastOpenerWhenTheGenericTypeNameNeedsToStartAfterEqualsSign =
            (RedactedFormatterHarness<
                AlphaEnvelope.FirstPayload,
                BetaEnvelope.SecondPayload,
                GammaEnvelope.ThirdPayload
            >) (RedactedFormatterHarness<?, ?, ?>) create();
    }

    record Capture(
        Context context,
        RedactedFormatterHarness<
            AlphaEnvelope.FirstPayload,
            BetaEnvelope.SecondPayload,
            GammaEnvelope.ThirdPayload
        > adapter
    ) {}
}

public class GenericClass<ENTITY> {

    private ENTITY entity;

    public GenericClass(ENTITY entity) {
        this.entity = entity;
    }

    public ENTITY setEntity(ENTITY entity) {
        this.entity = entity;
        return entity;
    }

    public <T> T doSomething(T value) {
        return value;
    }
}

public class ComplexGenericClass<
    ENTITY extends AbstractEntity & EntitySelector<ENTITYTYPE>,
    ENTITYTYPE,
    CONFIG extends EntityConfig<ENTITY, ENTITYTYPE, CONFIG>
> extends AbstractEntityConfig<ENTITY, CONFIG> {

    public <ENTITY> List<? super ENTITY> getEntity(final Class<ENTITY> entityClass) {
        return new ArrayList<>();
    }
}

public class SampleContainer<T> {

    public <U extends @NotNull T> void example(U value) {}

    public <U extends com.java.Any.@NotNull T> void example(U value) {}
}
