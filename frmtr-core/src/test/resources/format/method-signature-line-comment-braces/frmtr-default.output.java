class CharClassTable {

    void markNameBody(int point, byte[] flags) // flags[point] |= (1 << 3) marks { the point as a name-body char }
    {
      flags[point] |= (byte) (1 << 3);
    }

    void markNameStart(int point, byte[] flags) {
        flags[point] |= (byte) (1 << 4);
        flags[point] |= (byte) (1 << 5);
    }
}
