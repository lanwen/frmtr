class MyRouteEmptyUriTest {

    void configure() {
        from("direct:foo").to(""); // is empty on purpose
    }
}
