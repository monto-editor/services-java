package monto.service.java8;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import monto.service.MontoService;
import monto.service.ZMQConfiguration;
import monto.service.ast.AST;
import monto.service.ast.ASTVisitor;
import monto.service.ast.ASTs;
import monto.service.ast.NonTerminal;
import monto.service.ast.Terminal;
import monto.service.completion.Completion;
import monto.service.completion.Completions;
import monto.service.filedependencies.ProductDependency;
import monto.service.product.ProductMessage;
import monto.service.product.Products;
import monto.service.region.IRegion;
import monto.service.registration.ServiceDependency;
import monto.service.registration.SourceDependency;
import monto.service.types.Languages;
import monto.service.types.Message;
import monto.service.types.Messages;
import monto.service.types.ParseException;
import monto.service.types.Selection;
import monto.service.version.VersionMessage;

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
        				new ServiceDependency(JavaServices.JAVA_CODE_COMPLETION)
        		));
    }

    @Override
    public ProductMessage onVersionMessage(List<Message> messages) throws IOException, ParseException {
        VersionMessage version = Messages.getVersionMessage(messages);
        if (!version.getLanguage().equals(Languages.JAVA)) {
            throw new IllegalArgumentException("wrong language in version message");
        }
        ProductMessage ast = Messages.getProductMessage(messages, Products.AST, Languages.JAVA);
        if (!ast.getLanguage().equals(Languages.JAVA)) {
            throw new IllegalArgumentException("wrong language in ast product message");
        }
        if (version.getSelections().size() > 0) {
            AST root = ASTs.decode(ast);
            List<Completion> allcompletions = allCompletions(version.getContent(), root);
            List<AST> selectedPath = selectedPath(root, version.getSelections().get(0));

            if (selectedPath.size() > 0 && last(selectedPath) instanceof Terminal) {
                Terminal terminalToBeCompleted = (Terminal) last(selectedPath);
                String text = extract(version.getContent(),terminalToBeCompleted);
                if (terminalToBeCompleted.getEndOffset() >= version.getSelections().get(0).getStartOffset() && terminalToBeCompleted.getStartOffset() <= version.getSelections().get(0).getStartOffset()) {
                    int vStart = version.getSelections().get(0).getStartOffset();
                    int tStart = terminalToBeCompleted.getStartOffset();
                    text = text.substring(0, vStart - tStart);
                }
                String toBeCompleted = text;
                Stream<Completion> relevant =
                        allcompletions
                                .stream()
                                .filter(comp -> comp.getReplacement().startsWith(toBeCompleted))
                                .map(comp -> new Completion(
                                        comp.getDescription() + ": " + comp.getReplacement(),
                                        comp.getReplacement().substring(toBeCompleted.length()),
                                        version.getSelections().get(0).getStartOffset(),
                                        comp.getIcon()));

                return productMessage(
                        version.getVersionId(),
                        version.getSource(),
                        Products.COMPLETIONS,
                        Completions.encode(relevant),
                        new ProductDependency(ast));
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
