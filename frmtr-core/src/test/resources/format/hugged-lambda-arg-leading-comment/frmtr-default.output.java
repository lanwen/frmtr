package sample.hug;

import java.util.Map;

class HuggedLambdaArgLeadingComment {

    void leadingCommentOnHuggedBlockLambda(Map<Integer, String> responses) {
        responses.forEach(
            // Map of partition id -> responses from api.
            (partitionId, responseFut) -> {
                System.out.println(partitionId);
            }
        );
    }

    void commentFreeBlockLambdaStillHugs(Map<Integer, String> responses) {
        responses.forEach((partitionId, responseFut) -> {
            System.out.println(partitionId);
        });
    }
}
