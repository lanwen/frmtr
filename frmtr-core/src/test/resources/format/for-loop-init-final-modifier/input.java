public class ForInitModifier {

    void finalInit(java.util.Set<String> labels) {
        for (final java.util.Iterator<String> cursor = labels.iterator(); cursor.hasNext();) {
            cursor.next();
        }
    }

    void annotatedInit(java.util.Iterator<Object> source) {
        for (@SuppressWarnings("unchecked") final java.util.Iterator<Object> cursor = source; cursor.hasNext();) {
            cursor.next();
        }
    }

    void plainInit(int[] readings) {
        for (int index = 0; index < readings.length; index++) {
            consume(readings[index]);
        }
    }
}
