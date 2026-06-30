class CertificateSubjectAssembler {
    String subjectFromFlatSource() {
        String subject = MethodHandles.lookup().lookupClass().getCanonicalName() + " Root CA";
        return subject;
    }

    String subjectFromBrokenSource() {
        String subject = MethodHandles.lookup()
                .lookupClass()
                .getCanonicalName() + " Root CA";
        return subject;
    }

    String shortChainStaysFlat(SessionRegistry registry) {
        return registry.activeSessions().firstEntry() + " session";
    }
}
