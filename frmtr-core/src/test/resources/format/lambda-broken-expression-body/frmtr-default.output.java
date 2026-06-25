package sample;

import java.util.function.Supplier;

final class BrokenExpressionLambdaReturn {

    Supplier<Boolean> eligible(Account account, Policy policy, Snapshot snapshot, Region region) {
        return () ->
            account.isActiveSubscriber()
                && policy.allowsExtendedRegionalAccess(account, region)
                && snapshot.isVeryRecent();
    }
}
