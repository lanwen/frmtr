class IfConditionMultiArgumentReflow {

    void collapsesSimpleArgumentsThatFit(AccessPolicy policy) {
        if (policy.permitsBothRolesForTargetResource(
            administratorRoleIdentifierKey,
            resourcePrimaryKeyReference
        )) {
            grantAccess();
        }
    }

    void keepsBreakForComplexArgumentsNearBoundary(AccessPolicy policy) {
        if (policy.permitsAssignedRoleForTargetResource(administratorRoleIdentifierKey, resource.primaryKeyValue())) {
            grantAccess();
        }
    }
}
