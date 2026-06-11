package sample;

final class TextBlockRawMethodCall {
    void combine(Source source) {
        var merged = source.alpha(
            source.beta(
                """
                        # keep this literal column
                        item.first = yes
                        item.second = no
                        """
            )
        );
        sink(merged);
    }

    void format(String sshPublicKey) {
        String payload = """
            #cloud-config
            content: "%s"
            """.formatted(
                Base64.getEncoder().encodeToString(
                    (sshPublicKey.strip() + System.lineSeparator()).getBytes(StandardCharsets.UTF_8)
                )
        );
        sink(payload);
    }

    void compare(String csv) {
        assertThat(csv)
            .isNotNull()
            .isEqualTo(
                """
                "day","version","total"
                """
            );
    }
}
