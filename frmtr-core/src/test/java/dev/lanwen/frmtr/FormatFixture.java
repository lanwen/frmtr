package dev.lanwen.frmtr;

record FormatFixture(String name, String source, String expected, FormatterOptions options) {
    @Override
    public String toString() {
        return name;
    }
}
