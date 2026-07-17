package demo;

class MagicOffsetPicker {
    long firstOffsetFor(int toMagic) {
        long firstOffset;
        if (toMagic == RecordBatch.MAGIC_VALUE_V0)
            firstOffset = 11L; // v1 record
        else
            firstOffset = 17; // v2 record
        return firstOffset;
    }
}
