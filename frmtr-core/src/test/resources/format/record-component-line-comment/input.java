class RecordComponentLineCommentSample {
    record Sample(
        String alpha, // keep alpha source note
        // keep gap source note
        // keep adjacent gap source note
        @Required String beta,
        String gamma // keep gamma source note
    ) {}
}
