package dev.example;

class Demo {

    int value;

    int value() {
        return value;
    }
}

public class HelloWorld {

    public static void main(String[] args) {
        System.out.println("Hello, World");
    }
}

class IndentationFixture {

    void assertIndentedChains() {
        assertThat(
            customerAccountPreferenceMapperWithExternalAuditSnapshot,
            customerAccountPreferenceMapperWithExternalAuditSnapshot,
            customerAccountPreferenceMapperWithExternalAuditSnapshot
        );

        assertThat(
            customerAccountPreferenceMapperWithExternalAuditSnapshot,
            customerAccountPreferenceMapperWithExternalAuditSnapshot,
            customerAccountPreferenceMapperWithExternalAuditSnapshot
        ).isEqualTo();

        assertThat(
            customerAccountPreferenceMapperWithExternalAuditSnapshot,
            customerAccountPreferenceMapperWithExternalAuditSnapshot,
            customerAccountPreferenceMapperWithExternalAuditSnapshot
        )
            .isEqualTo()
            .anotherInvocation(
                customerAccountPreferenceMapperWithExternalAuditSnapshot,
                customerAccountPreferenceMapperWithExternalAuditSnapshot,
                customerAccountPreferenceMapperWithExternalAuditSnapshot
            );

        assertionCollector
            .assertThat(customerAccountPreferenceMapperWithExternalAuditSnapshot)
            .isEqualTo();
    }
}
