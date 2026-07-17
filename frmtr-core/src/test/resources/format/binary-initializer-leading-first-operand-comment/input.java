package demo;

class LeaveHeartbeatPolicy {
    boolean shouldSendLeaveHeartbeat(GroupMembershipOperation leaveGroupOperation, java.util.Optional<String> groupInstanceId) {
        boolean hasLeaveOperation =
            // Default operation: both static and dynamic members will send a leave heartbeat
            GroupMembershipOperation.DEFAULT == leaveGroupOperation
            // Leave group operation: both static and dynamic members will send a leave heartbeat
            || GroupMembershipOperation.LEAVE_GROUP == leaveGroupOperation
            // Remain in group: static members will send a leave heartbeat with the reserved epoch
            || groupInstanceId.isPresent();
        return hasLeaveOperation;
    }
}
