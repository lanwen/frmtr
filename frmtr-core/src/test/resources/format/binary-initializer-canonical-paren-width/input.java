class BinaryInitializerCanonicalParenWidth {

    int[] assignShares(double desiredSharing, int numTargetPartitions, int numSubscribedMembers) {
        double preciseDesiredAssignmentCount = desiredSharing * numTargetPartitions / (double) numSubscribedMembers;
        return distribute(preciseDesiredAssignmentCount);
    }
}
