package sample;

final class CompoundAssignmentInCondition {

    int decodeSignedByte(byte[] payload) {
        int accumulator = 0,
            cursor = 0;
        if ((accumulator ^= payload[cursor++] << 7) < 0) {
            return 1;
        }
        return accumulator;
    }

    int mergeFlagBits(int[] flags) {
        int mask = 0,
            index = 0;
        if ((mask |= flags[index++]) > 255) {
            return mask & 255;
        }
        return mask;
    }

    int shiftRemaining(int seed, int width) {
        int value = seed;
        if ((value >>= width) == 0) {
            return -1;
        }
        return value;
    }

    int foldChecksum(int initial, int delta) {
        int checksum = initial;
        checksum ^= delta;
        return checksum;
    }
}
