package auth;

class ImpersonationSupport {
    GoogleCredentials impersonate(GoogleCredentials sourceCredentials, String targetServiceAccount, List<String> scopeList) {
        return ImpersonatedCredentials.create(sourceCredentials, targetServiceAccount, null, // delegates
                scopeList, 3600); // lifetime in seconds (1 hour)
    }
}
