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
}
