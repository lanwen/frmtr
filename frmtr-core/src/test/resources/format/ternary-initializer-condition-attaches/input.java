class SubscriptionPlanSelector {

    Plan resolvePlan(Customer customer) {
        Plan selectedPlan = customer.hasActiveSubscription() && customer.isInGoodStanding() ? Plan.PREMIUM : Plan.STANDARD;
        return selectedPlan;
    }
}
