class CertificateAuthorityNames {

    void registerFlatMethodArgument(NameBuilder builder) {
        builder.addRDN(
            BCStyle.CN,
            MethodHandles.lookup()
                    .lookupClass()
                    .getCanonicalName()
                + " Root CA"
        );
    }

    void registerBrokenMethodArgument(NameBuilder builder) {
        builder.addRDN(
            BCStyle.CN,
            MethodHandles.lookup()
                    .lookupClass()
                    .getCanonicalName()
                + " Root CA"
        );
    }

    SubjectName buildFlatConstructorArgument() {
        return new SubjectName(
            MethodHandles.lookup()
                    .lookupClass()
                    .getCanonicalName()
                + " Intermediate CA"
        );
    }

    SubjectName buildBrokenConstructorArgument() {
        return new SubjectName(
            MethodHandles.lookup()
                    .lookupClass()
                    .getCanonicalName()
                + " Intermediate CA"
        );
    }
}
