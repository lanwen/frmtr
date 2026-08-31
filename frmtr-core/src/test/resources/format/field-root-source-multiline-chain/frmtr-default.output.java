package dev.example;

class Demo {

    void method() {
        context.sessionStore.persistence()
                .recordForReplay(historyId(context.id()), List.of(event(startedState()), changed(startedState())));
        context.sessionStore.persistence()
                .recordForReplay(
                    historyId(context.id()),
                    List.of(
                        event(element),
                        changed(element),
                        audited(element),
                        retained(element)
                    )
                );
        credentialBridge.request.builder.remoteEndpoint.scope(scope)
                .endpoint(CATALOG)
                .principal(AUTH.getPrincipal())
                .secret(AUTH.getSecret())
                .contact("ops@example.invalid")
                .run(REMOTE_ALIAS);
    }
}
