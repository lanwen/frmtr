package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.Comment;
import java.util.List;

public record SyntaxNodeView(String kind, SourceSpan span, List<String> comments, List<SyntaxNodeView> children) {
    public static SyntaxNodeView from(Node node) {
        SourceSpan span = node.getRange()
                .map(range -> new SourceSpan(
                        range.begin.line,
                        range.begin.column,
                        range.end.line,
                        range.end.column))
                .orElseGet(SourceSpan::unknown);
        List<String> comments = node.getComment().stream().map(Comment::toString).toList();
        List<SyntaxNodeView> children = node.getChildNodes().stream().map(SyntaxNodeView::from).toList();
        return new SyntaxNodeView(node.getMetaModel().getTypeName(), span, comments, children);
    }
}
