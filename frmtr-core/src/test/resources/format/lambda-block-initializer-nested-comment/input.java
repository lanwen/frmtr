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

    void resolveRecipeVersions(RecipeDownloader recipeDownloader, VersionCollector versionCollector) {
        CompletableFuture<List<RecipeVersion>> springBootRecipeVersionsFuture = CompletableFuture.supplyAsync(() -> {
            List<RecipeVersion> springBootRecipeVersions = recipeDownloader.resolveAvailableVersions(UPGRADE_GROUP_ID);
            if (!springBootRecipeVersions.isEmpty()) {
                // qualified-receiver hug keeps the call on the assignment line, dropping this comment pre-fix
                versionCollector.addAll(recipeDownloader.resolveAvailableVersions(SPRING_BOOT_UPGRADE_GROUP_ID));
            }
            return springBootRecipeVersions;
        });
    }
}
