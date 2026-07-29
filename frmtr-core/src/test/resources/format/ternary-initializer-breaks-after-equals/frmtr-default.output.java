class SubscriptionPlanSelector {

    Plan resolveRecommendedSubscriptionPlanForCustomer(Customer customer) {
        Plan recommendedSubscriptionPlan =
            customer.hasActiveSubscription() && customer.isInGoodStanding() && customer.hasCompletedOnboarding()
                ? Plan.PREMIUM
                : Plan.STANDARD;
        return recommendedSubscriptionPlan;
    }
}
