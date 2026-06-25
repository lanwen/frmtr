class LambdaBlockInitializerNestedComment {
    void scheduleWithNestedBlock(Scheduler workScheduler) {
        Handle handle = workScheduler.enqueue(context -> {
            {
                // first comment inside a nested block, dropped pre-fix
                runStage(context);
            }
            return false;
        });
    }

    void scheduleWithIfBody(Scheduler workScheduler) {
        Handle handle = workScheduler.enqueue(context -> {
            if (context.ready()) {
                // first comment inside an if body, dropped pre-fix
                runStage(context);
            }
        });
    }

    void scheduleAsExpressionStatement(Scheduler workScheduler) {
        workScheduler.enqueue(context -> {
            {
                // control: bare expression-statement call keeps the comment
                runStage(context);
            }
            return false;
        });
    }

    void scheduleWithDirectFirstComment(Scheduler workScheduler) {
        Handle handle = workScheduler.enqueue(context -> {
            // control: comment leading the first lambda-body statement directly
            runStage(context);
            return false;
        });
    }
}
