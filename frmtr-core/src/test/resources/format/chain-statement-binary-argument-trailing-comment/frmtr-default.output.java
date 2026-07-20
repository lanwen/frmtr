class AsyncEndpointRoute {

    void configure(int port) {
        from("direct:send")
                .log("forwarding request")
                .to("cxfrs:http://localhost:" + port + "/rest/helloservice/sayHello?synchronous=false"); // switching to true would make it synchronous
    }
}
