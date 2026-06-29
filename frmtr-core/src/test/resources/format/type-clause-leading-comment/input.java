package com.example.catalog;

public class CatalogEntry/* legacy marker */ implements java.io.Serializable {
    int revision = 1;
}

class CatalogIndex/* needs review */ extends AbstractIndex {
    int size = 0;
}

sealed class CatalogNode/* see ticket-42 */ permits LeafNode, BranchNode {
    int depth = 0;
}

final class LeafNode extends CatalogNode {}

final class BranchNode extends CatalogNode {}
