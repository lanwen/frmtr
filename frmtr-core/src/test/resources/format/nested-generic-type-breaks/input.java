package sample;

final class NestedGenericTypeBreaks {
    private ProcessorHarnessWithVeryLongFormatterFixtureName<FirstSignal, SignalResult<FirstSignal>, SignalState<FirstSignal>> runner;

    void connect(Source source) {
        final OperationWithVeryLongFormatterFixtureName<
            FirstSubject,
            SubjectCollection<FirstSubject>,
            SubjectResource<FirstSubject>
        > operation = source.operation();
        sink(operation);
    }

    private static OperationWithVeryLongFormatterFixtureName<
        FirstSubject,
        SubjectCollection<FirstSubject>,
        SubjectResource<FirstSubject>
    > resource() {
        return sourceOperation();
    }
}


public class GenericClass<ENTITY extends Comparable<ENTITY>> {
  private ENTITY entity;

  public GenericClass(ENTITY entity) {
    this.entity = entity;
  }

  public ENTITY setEntity(ENTITY entity) {
    this.entity = entity;
    return entity;
  }

  public <T extends Comparable<T>> T doSomething(T value) {
    return value;
  }

  public void addAll(final Collection<? extends E> collection) {
		for (final E element : collection) {
			add(element);
		}
  }

}

public abstract class AbstractGenericClass<Value extends AbstractValue, PreviousValue extends AbstractValue, CurrentValue extends AbstractValue, NextValue extends AbstractValue, ArchivedValue extends AbstractValue, PendingValue extends AbstractValue> {
    public Value getValue() {
        return new Value();
    }
}


public class GenericExtends<ENTITY extends Bean<?>> {}

public class Simple {

  public void converter(final Converter<?> converter) {}

}
