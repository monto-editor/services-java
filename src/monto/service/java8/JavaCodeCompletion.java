package monto.service.java8;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import monto.service.MontoService;
import monto.service.ZMQConfiguration;
import monto.service.ast.AST;
import monto.service.ast.ASTVisitor;
import monto.service.ast.ASTs;
import monto.service.ast.NonTerminal;
import monto.service.ast.Terminal;
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
        				new ProductDependency(JavaServices.JAVA_ANTLR_PARSER, Products.AST, Languages.JAVA)
        		));
    }

    @Override
    public ProductMessage onRequest(Request request) throws IOException, ParseException {
    	SourceMessage version = request.getSourceMessage()
    			.orElseThrow(() -> new IllegalArgumentException("No version message in request"));
        ProductMessage ast = request.getProductMessage(Products.AST, Languages.JAVA)
        		.orElseThrow(() -> new IllegalArgumentException("No AST message in request"));
        
        if (version.getSelection().isPresent()) {
            AST root = ASTs.decode(ast);
            List<Completion> allcompletions = allCompletions(version.getContent(), root);
            List<AST> selectedPath = selectedPath(root, version.getSelection().get());

            if (selectedPath.size() > 0 && last(selectedPath) instanceof Terminal) {
                String toBeCompleted = extract(version.getContent(),last(selectedPath));
                List<Completion> relevant =
                        allcompletions
                                .stream()
                                .filter(comp -> comp.getReplacement().startsWith(toBeCompleted))
                                .map(comp -> new Completion(
                                        comp.getDescription() + ": " + comp.getReplacement(),
                                        comp.getReplacement(),
                                        version.getSelection().get().getStartOffset(),
                                        null))
                                .collect(Collectors.toList());
                
                System.out.printf("Relevant: %s\n", relevant);
                
                return productMessage(
                        version.getId(),
                        version.getSource(),
                        Products.COMPLETIONS,
                        Languages.JAVA,
                        Completions.encode(relevant));
            }
            throw new IllegalArgumentException(String.format("Last token in selection path is not a terminal: %s", selectedPath));
        }
        throw new IllegalArgumentException("Code completion needs selection");
    }

    private List<Completion> allCompletions(String contents, AST root) {
        AllCompletions completionVisitor = new AllCompletions(contents);
        root.accept(completionVisitor);
        return completionVisitor.getCompletions();
    }

    private class AllCompletions implements ASTVisitor {

        private List<Completion> completions = new ArrayList<>();
        private String content;
        private boolean fieldDeclaration;

        public AllCompletions(String content) {
            this.content = content;
        }

        @Override
        public void visit(NonTerminal node) {
            switch (node.getName()) {

                case "packageDeclaration":
                    AST packageIdentifier = node.getChildren().get(1);
                    completions.add(new Completion(
                            "package",
                            extract(content,packageIdentifier),
                            getResource("package.png")));
                    break;

                case "normalClassDeclaration":
                    structureDeclaration(node, "class", getResource("class.png"));
                    break;

                case "fieldDeclaration":
                    fieldDeclaration = true;
                    node.getChildren().forEach(child -> child.accept(this));
                    fieldDeclaration = false;

                case "variableDeclaratorId":
                    if (fieldDeclaration)
                        leaf(node, "field", getResource("private.png"));
                    break;

                case "methodDeclarator":
                    leaf(node, "method", getResource("public.png"));

                default:
                    node.getChildren().forEach(child -> child.accept(this));
            }
        }

        @Override
        public void visit(Terminal token) {

        }

        private void structureDeclaration(NonTerminal node, String name, URL icon) {
            Terminal structureIdent = (Terminal) node
                    .getChildren()
                    .stream()
                    .filter(ast -> ast instanceof Terminal)
                    .reduce((previous, current) -> current).get();
            completions.add(new Completion(name, extract(content,structureIdent), icon));
            node.getChildren().forEach(child -> child.accept(this));
        }

        private void leaf(NonTerminal node, String name, URL url) {
            AST ident = node
                    .getChildren()
                    .stream()
                    .filter(ast -> ast instanceof Terminal)
                    .findFirst().get();
            completions.add(new Completion(name, extract(content,ident), url));
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

    private static String extract(String str, AST indent) {
	return str.subSequence(indent.getStartOffset(), indent.getStartOffset()+indent.getLength()).toString();
    }

    private static <A> A last(List<A> list) {
        return list.get(list.size() - 1);
    }
}
