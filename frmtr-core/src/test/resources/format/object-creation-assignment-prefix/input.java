package dev.example;
class Demo {
void method() {
var schedule = new Config.Schedule(Zone.of("Region/City"), List.of(new Config.Schedule.Shift("0 0 19 * * 5", 3, 10), // start compact mode
new Config.Schedule.Shift("0 0 19 * * 0", Config.UNSET, Config.UNSET)
// restore defaults
));
}
}
