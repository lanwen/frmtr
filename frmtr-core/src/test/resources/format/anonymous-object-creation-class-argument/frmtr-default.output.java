class AnonymousObjectCreationClassArgumentSample {

    Behavior<String> attach(Behavior<String> behavior) {
        return Factory.intercept(() -> {
            return new Interceptor<Object, String>(Object.class) {
                @Override
                String receive(Context<Object> ctx, Object msg, Target<String> target) {
                    return target.apply(ctx, (String) msg);
                }
            };
        }, behavior).narrow();
    }
}
