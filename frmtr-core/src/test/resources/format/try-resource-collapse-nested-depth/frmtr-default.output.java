class DataPipeline {

    void copyAtBlockDepth() {
        try (
            InputStream encryptedSource = openEncryptedSource();
            OutputStream decryptedTarget = openDecryptedTarget()
        ) {
            pump(encryptedSource, decryptedTarget);
        }
    }

    void copyWhenNestedDeeper(boolean shouldCopy) {
        if (shouldCopy) {
            try (
                InputStream archivedSource = openArchivedSource();
                OutputStream restoredTarget = openRestoredTarget()
            ) {
                pump(archivedSource, restoredTarget);
            }
        }
    }
}
