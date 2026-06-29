class LinkBuilder {

    void describe(String[] columns) {
        columns[3] = // external link
            resolveContext().resolvePath()
            + "/"
            + catalog.lookup("linkBuilder.helpReferenceFile");
        columns[5] = // external link
            resolveContext().resolvePath()
            + "/"
            + catalog.lookup("linkBuilder.helpReferenceFile");
    }

    void shorten(int seed, int offset, int stride) {
        int total;
        total = // accumulated weight
            seed
            + offset
            + stride;
    }
}
