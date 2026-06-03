package dev.lanwen.frmtr.tooling;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffAlgorithm.SupportedAlgorithm;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;

public final class UnifiedDiffRenderer {
    private UnifiedDiffRenderer() {}

    public static String render(Path displayPath, String original, String formatted) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String path = displayPath.toString().replace('\\', '/');
        output.write(("diff --git a/" + path + " b/" + path + "\n").getBytes(StandardCharsets.UTF_8));
        output.write(("--- a/" + path + "\n").getBytes(StandardCharsets.UTF_8));
        output.write(("+++ b/" + path + "\n").getBytes(StandardCharsets.UTF_8));

        RawText oldText = new RawText(original.getBytes(StandardCharsets.UTF_8));
        RawText newText = new RawText(formatted.getBytes(StandardCharsets.UTF_8));
        EditList edits = DiffAlgorithm.getAlgorithm(SupportedAlgorithm.HISTOGRAM)
                .diff(RawTextComparator.DEFAULT, oldText, newText);
        try (DiffFormatter formatter = new DiffFormatter(output)) {
            formatter.setContext(3);
            formatter.setDiffComparator(RawTextComparator.DEFAULT);
            formatter.format(edits, oldText, newText);
            formatter.flush();
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
