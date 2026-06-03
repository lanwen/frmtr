package dev.lanwen.frmtr.tooling;

import java.util.List;
import java.util.Optional;

public record FormatRunResult(List<FormatFileResult> results) {
    public FormatRunResult {
        results = List.copyOf(results);
    }

    public boolean hasChanges() {
        return results.stream().anyMatch(FormatFileResult::changed);
    }

    public boolean hasFailures() {
        return results.stream().anyMatch(FormatFileResult::failed);
    }

    public long changedCount() {
        return changedResults().size();
    }

    public long failureCount() {
        return failedResults().size();
    }

    public List<FormatFileResult> changedResults() {
        return results.stream().filter(FormatFileResult::changed).toList();
    }

    public List<FormatFileResult> failedResults() {
        return results.stream().filter(FormatFileResult::failed).toList();
    }

    public Optional<Exception> firstFailure() {
        return failedResults().stream()
                .flatMap(result -> result.failureException().stream())
                .findFirst();
    }
}
