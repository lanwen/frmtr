class PayloadRouter {

    int dispatch(Object payload) {
        if (payload instanceof int[]) {
            return forIntArray();
        }
        // Switch on the runtime array kind to reach the matching handler
        // This keeps multi dimensional arrays of equal depth together
        else if (payload instanceof long[]) {
            return forLongArray();
        }
        // Fall back when the payload is not a primitive array at all
        // The boxed handler walks the elements one by one
        else {
            return forBoxedArray();
        }
    }

    int forIntArray() {
        return 1;
    }

    int forLongArray() {
        return 2;
    }

    int forBoxedArray() {
        return 3;
    }
}
