import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;

class PacketCodec {

    @Target(ElementType.TYPE_USE)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Trusted {}

    PacketCodec(byte payload[], int offset) {
        int size = payload.length + offset;
    }

    void write(final byte payload[]) {
        int size = payload.length;
    }

    void route(String channel[]) {
        int count = channel.length;
    }

    void copy(int source[], int target[], long checksum) {
        long total = source.length + target.length + checksum;
    }

    void blend(int rows[], String[] columns, long stride) {
        long total = rows.length + columns.length + stride;
    }

    void widen(long grid[][]) {
        int depth = grid.length;
    }

    void guard(byte buffer @Trusted []) {
        int size = buffer.length;
    }

    void bucket(Map<String, Integer> entries[]) {
        int count = entries.length;
    }

    void collect(int... counts) {
        int count = counts.length;
    }
}
