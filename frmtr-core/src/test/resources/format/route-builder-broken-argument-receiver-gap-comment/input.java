class WebsocketBroadcastRouteTest {

    void configure(int port) {
        from("undertow:ws://localhost:" + port + "/broadcast") //
                .to("undertow:ws://localhost:" + port + "/broadcast?sendToAll=true");
    }
}
