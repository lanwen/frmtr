class TabIndentedConditionWidth {
	void publishChangedMarker(Queue queue, CompletableFuture<Void> received) {
		subscriber.toAsync().publishes(MessagePublishFilter.ALL, publish -> {
			if (Arrays.equals(publish.getPayloadAsBytes(), "modified".getBytes(StandardCharsets.UTF_8))) {
				received.complete(null);
			} else {
				received.completeExceptionally(new IllegalStateException("unexpected"));
			}
		});
	}
}
