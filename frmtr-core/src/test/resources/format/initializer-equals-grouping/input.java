package dev.example;
class InitializerEqualsGroupingSample {
private final FixtureApplicationContextRunner ctx = new FixtureApplicationContextRunner().withBean(Clock.class, Clock::systemUTC);
void configure(Registry registry) {
Consumer<Boolean> setup = enabled -> {
when(registry.flag(anyString(), any(), anyBoolean())).thenReturn(enabled);
};
Consumer<String> namedSetup = value -> {
when(registry.name(anyString(), any(), anyString())).thenReturn(value);
};
}
}
