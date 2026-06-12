class MethodChainRootArgumentsSample {

    void configure(Context ctx, Handler handler, Task task) {
        fallbackIfNeeded(
            "sample",
            task.id(),
            handler.prepare(task).thenReturn(done())
        ).as(status(ctx, task.replyTo(), call("sample", task.id())));
        Registry.lookup(
            List.of(Criteria.where("health").eq("READY").and("labels['sample']").eq("true"))
        ).toDebugString();
        registry.register(
            Builders.request().willReturn(
                Builders.response("ready", 100, flag, count)
            )
        );
        awaitMessage(broadcastProbe)
            .replyTo()
            .tell(
                new EntryAvailabilityUpdate(new ManagedSubject("entry-0", ManagedSubject.routeTo("localhost", 2200)))
            );
        this.mark("alpha")
            .withLabel("beta")
            .withPorts(2001, 2002)
            .complete();
        await().until(
            () -> {
                var entry = receive(next);
                if (entry instanceof Routed routed) {
                    return routed.command();
                }
                return null;
            },
            Result::ready
        );
        RequestGateway.<Message.Command, Message.Response>ask(
            sink,
            replyTo -> new Message.Command.Open(replyTo, request),
            timeouts.next()
        ).onErrorMap(TimeoutException.class, err -> new RoutingTimeoutFailure(err, request.owner(), request.target()));
        assertThat(
            Codec.derive(
                Codec.parse(
                    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghijklmnop"
                )
            )
        ).isEqualTo("public-key-public-key-public-key-public-key-public-key-public-key-public-key-public-key");
    }

    Result awaitRoutedResult(Receiver next) {
        return await().until(
            () -> {
                var entry = receive(next);
                if (entry instanceof Routed routed) {
                    return routed.command();
                }
                return null;
            },
            Result::ready
        );
    }
}
