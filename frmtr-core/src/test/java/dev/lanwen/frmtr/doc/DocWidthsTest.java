package dev.lanwen.frmtr.doc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class DocWidthsTest {

    @Test
    void doesNotCacheBoundedOverflowAsFlatWidth() {
        Doc shared = Doc.label("shared", Doc.concat(Doc.text("prefix"), Doc.text("-suffix")));
        Doc outer = Doc.concat(shared, Doc.text("-tail"));
        DocWidths.Measurement widths = DocWidths.measurement();

        assertThat(widths.fits(outer, 3)).isFalse();

        assertThat(widths.flatWidth(shared)).isEqualTo("prefix-suffix".length());
        assertThat(widths.fits(shared, "prefix-suffix".length())).isTrue();
    }

    @Test
    void reusesCompleteWidthFoundDuringBoundedFit() {
        Doc shared = Doc.label("shared", Doc.text("prefix-suffix"));
        DocWidths.Measurement widths = DocWidths.measurement();

        assertThat(widths.fits(shared, 3)).isFalse();

        assertThat(widths.flatWidth(shared)).isEqualTo("prefix-suffix".length());
        assertThat(widths.fits(shared, "prefix-suffix".length())).isTrue();
    }
}
