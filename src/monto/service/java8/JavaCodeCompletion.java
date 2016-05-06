package monto.service.java8;

import monto.service.MontoService;
import monto.service.ZMQConfiguration;
import monto.service.ast.*;
import monto.service.completion.Completion;
import monto.service.completion.Completions;
import monto.service.product.ProductMessage;
import monto.service.product.Products;
import monto.service.region.IRegion;
import monto.service.registration.ProductDependency;
import monto.service.registration.SourceDependency;
import monto.service.request.Request;
import monto.service.source.SourceMessage;
import monto.service.types.Languages;
import monto.service.types.ParseException;
import monto.service.types.Selection;
import org.json.simple.JSONObject;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class JavaCodeCompletion extends MontoService {

    public JavaCodeCompletion(ZMQConfiguration zmqConfig) {
        super(zmqConfig,
                JavaServices.JAVA_CODE_COMPLETION,
                "Code Completion",
                "A code completion service for Java",
                Languages.JAVA,
                Products.COMPLETIONS,
                options(),
                dependencies(
                        new SourceDependency(Languages.JAVA),
                        new ProductDependency(JavaServices.JAVA_JAVACC_PARSER, Products.AST, Languages.JAVA)
                ));
    }

    @Override
    public ProductMessage onRequest(Request request) throws IOException, ParseException {
        long start = System.nanoTime();

        SourceMessage version = request.getSourceMessage()
                .orElseThrow(() -> new IllegalArgumentException("No version message in request"));
        ProductMessage ast = request.getProductMessage(Products.AST, Languages.JAVA)
                .orElseThrow(() -> new IllegalArgumentException("No AST message in request"));

        ASTNode root = ASTs.decodeASTNode((JSONObject) ast.getContents());

        List<Completion> allcompletions = allCompletions(version.getContent(), root);
        List<Completion> relevant =
                allcompletions
                        .stream()
                        //.filter(comp -> comp.getReplacement().startsWith(toBeCompleted))
                        .map(comp -> new Completion(
                                comp.getDescription() + ": " + comp.getReplacement(),
                                comp.getReplacement(),
                                3, //version.getSelection().get().getStartOffset(),
                                null))
                        .collect(Collectors.toList());

        System.out.printf("Relevant: %s\n", relevant);
        long end = System.nanoTime();

        return productMessage(
                version.getId(),
                version.getSource(),
                Products.COMPLETIONS,
                Languages.JAVA,
                Completions.encode(relevant),
                ast.getTime() + end - start);
    }

    private List<Completion> allCompletions(String contents, ASTNode root) {
        AllCompletions completionVisitor = new AllCompletions(contents);
        root.accept(completionVisitor);
        return completionVisitor.getCompletions();
    }

    private class AllCompletions implements ASTNodeVisitor {

        private List<Completion> completions = new ArrayList<>();
        private String content;
        private boolean fieldDeclaration;

        public AllCompletions(String content) {
            this.content = content;
        }

        @Override
        public void visit(ASTNode node) {
            switch (node.getName()) {

                case "PackageDeclaration":
                    completions.add(new Completion(
                            "package",
                            extract(content, node),
                            getResource("package.png")));
                    break;

                case "ClassDeclaration":
                    completions.add(new Completion("class", extract(content, node.getChild(1)), getResource("class-public.png")));
                    traverseChildren(node);
                    break;

                case "FieldDeclaration":
                    fieldDeclaration = true;
                    traverseChildren(node);
                    fieldDeclaration = false;
                    break;

                case "VariableDeclaratorId":
                    if (fieldDeclaration)
                        completions.add(new Completion("field", extract(content, node), getResource("field-public.png")));
                    else
                        completions.add(new Completion("var", extract(content, node), getResource("field-default.png")));
                    break;

                case "MethodDeclaration":
                    completions.add(new Completion("method", extract(content, node.getChild(2)), getResource("method-public.png")));
                    traverseChildren(node);
                    break;

                default:
                    traverseChildren(node);
            }
        }

        private void traverseChildren(ASTNode node) {
            if (node.getChildren().size() > 0) {
                node.getChildren().forEach(child -> child.accept(this));
            }
        }

        public List<Completion> getCompletions() {
            return completions;
        }
    }

    private static List<AST> selectedPath(AST root, Selection sel) {
        SelectedPath finder = new SelectedPath(sel);
        root.accept(finder);
        return finder.getSelected();
    }

    private static class SelectedPath implements ASTVisitor {

        private Selection selection;
        private List<AST> selectedPath = new ArrayList<>();

        public SelectedPath(Selection selection) {
            this.selection = selection;
        }

        @Override
        public void visit(NonTerminal node) {
            if (selection.inRange(node) || rightBehind(selection, node))
                selectedPath.add(node);
            node.getChildren()
                    .stream()
                    .filter(child -> selection.inRange(child) || rightBehind(selection, child))
                    .forEach(child -> child.accept(this));
        }

        @Override
        public void visit(Terminal token) {
            if (rightBehind(selection, token))
                selectedPath.add(token);
        }

        public List<AST> getSelected() {
            return selectedPath;
        }

        private static boolean rightBehind(IRegion region1, IRegion region2) {
            try {
                return region1.getStartOffset() <= region2.getEndOffset() && region1.getStartOffset() >= region2.getStartOffset();
            } catch (Exception e) {
                return false;
            }
        }
    }

    private static String extract(String str, IRegion indent) {
        return str.subSequence(indent.getStartOffset(), indent.getStartOffset() + indent.getLength()).toString();
    }

    private static <A> A last(List<A> list) {
        return list.get(list.size() - 1);
    }
}
