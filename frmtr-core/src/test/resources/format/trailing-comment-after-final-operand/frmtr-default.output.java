class TrailingCommentAfterFinalOperand {

    int recordOverhead = 8 // key reference
        + 8 // value reference
        + 8
        // hash slot
    ;

    int entrySize() {
        return 16 // header bytes
        + 24 // payload bytes
        + 8
            // alignment padding
        ;
    }
}
