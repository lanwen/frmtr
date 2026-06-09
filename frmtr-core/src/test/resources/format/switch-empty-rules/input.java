class SwitchEmptyRulesSample {
    void routeUnknownEntry(TaskCommand command) {
        switch (command) {
            case TaskCommand.Approve approve -> {
                replyUnknown(approve.replyTo());
            }
            case TaskCommand.RefreshMarker _ -> {}
            case TaskCommand.Complete _ -> {}
            case TaskCommand.UpdateStatus _ -> {}
            case TaskCommand.InternalCommand _ -> {}
        }
    }
}
