package dev.example;
class Demo {
void method() {
for (State state : List.of(new State.Started(), create("available-1"), new State.Leased("lease-1", owner(), value("leased-1")), new State.Reserved("reservation-1", "lease-1", owner(), value("reserved-1")), new State.Configured("reservation-1", "lease-1", owner(), value("configured-1")), new State.Reusable(owner(), value("reuse-1")), new State.Terminating())) {
handle(state);
}
}
}
