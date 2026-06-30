class CertificateSubjectFactory {
    String subjectFromFlatSource() {
        return MethodHandles.lookup().lookupClass().getCanonicalName() + " Root CA";
    }

    String subjectFromBrokenSource() {
        return MethodHandles.lookup()
                .lookupClass()
                .getCanonicalName() + " Root CA";
    }

    String bothOperandsFromFlatSource() {
        return MethodHandles.lookup().lookupClass().getCanonicalName() + MethodHandles.lookup().lookupClass().getSimpleName();
    }

    String bothOperandsFromBrokenSource() {
        return MethodHandles.lookup()
                .lookupClass()
                .getCanonicalName() + MethodHandles.lookup()
                .lookupClass()
                .getSimpleName();
    }
}
