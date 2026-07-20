class RouteDiagramLayoutEngine {

    private int baseNodeHeight;

    RouteDiagramLayoutEngine(int fontSize) {
        this.baseNodeHeight = Math.max(DEFAULT_NODE_HEIGHT, fontSize * DEFAULT_NODE_HEIGHT / DEFAULT_FONT_SIZE) * SCALE;
    }
}
