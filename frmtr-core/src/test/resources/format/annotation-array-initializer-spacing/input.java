package dev.example;
@TypeBindings({Demo.Inner.class})
@SuppressWarnings({ "rawtypes", "unchecked" })
@EmptyBindings({})
class Demo{
@MemberBindings(types={Demo.Inner.class}, moreTypes = { Demo.Other.class, Demo.Inner.class })
void method(){}
static class Inner{}
static class Other{}
}
