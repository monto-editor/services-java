package monto.service.java8;

import java.net.URL;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import monto.service.MontoService;
import monto.service.ZMQConfiguration;
import monto.service.ast.AST;
import monto.service.ast.ASTVisitor;
import monto.service.ast.ASTs;
import monto.service.ast.NonTerminal;
import monto.service.ast.Terminal;
import monto.service.filedependencies.ProductDependency;
import monto.service.outline.Outline;
import monto.service.outline.Outlines;
import monto.service.product.ProductMessage;
import monto.service.product.Products;
import monto.service.registration.ServiceDependency;
import monto.service.registration.SourceDependency;
import monto.service.types.Languages;
import monto.service.types.Message;
import monto.service.types.Messages;
import monto.service.types.ParseException;
import monto.service.version.VersionMessage;

public class JavaOutliner extends MontoService {

	public JavaOutliner(ZMQConfiguration zmqConfig) {
    	super(zmqConfig,
    			JavaServices.JAVA_OUTLINER,
    			"Outline",
    			"An outline service for Java",
    			Languages.JAVA,
    			Products.OUTLINE,
    			options(),
    			dependencies(
    					new SourceDependency(Languages.JAVA),
    					new ServiceDependency(JavaServices.JAVA_PARSER)
    			));
    }

	@Override
    public ProductMessage onVersionMessage(List<Message> messages) throws ParseException {
        VersionMessage version = Messages.getVersionMessage(messages);
        if (!version.getLanguage().equals(Languages.JAVA)) {
            throw new IllegalArgumentException("wrong language in version message");
        }
        ProductMessage ast = Messages.getProductMessage(messages, Products.AST, Languages.JAVA);
        if (!ast.getLanguage().equals(Languages.JAVA)) {
            throw new IllegalArgumentException("wrong language in ast product message");
        }
        NonTerminal root = (NonTerminal) ASTs.decode(ast);

        OutlineTrimmer trimmer = new OutlineTrimmer();
        root.accept(trimmer);

        return productMessage(
                version.getVersionId(),
                version.getSource(),
                Products.OUTLINE,
                Outlines.encode(trimmer.getConverted()),
                new ProductDependency(ast));
    }

    /**
     * Traverses the AST and removes unneeded information.
     */
    private class OutlineTrimmer implements ASTVisitor {

        private Deque<Outline> converted = new ArrayDeque<>();
        private boolean fieldDeclaration = false;

        public Outline getConverted() {
            return converted.getFirst();
        }

        @Override
        public void visit(NonTerminal node) {
            switch (node.getName()) {
                case "compilationUnit":
                    converted.push(new Outline("compilationUnit", node, null));
                    node.getChildren().forEach(child -> child.accept(this));
                    // compilation unit doesn't get poped from the stack
                    // to be available as a return value.
                    break;

                case "packageDeclaration":
                    AST packageIdentifier = node.getChildren().get(1);
                    if (packageIdentifier instanceof Terminal)
                        converted.peek().addChild(new Outline("package", packageIdentifier, getResource("package.png")));
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
            node.getChildren()
                    .stream()
                    .filter(ast -> ast instanceof Terminal)
                    .limit(2)
                    .reduce((previous, current) -> current)
                    .ifPresent(ident -> {
                        Outline structure = new Outline(name, ident, icon);
                        converted.peek().addChild(structure);
                        converted.push(structure);
                        node.getChildren().forEach(child -> child.accept(this));
                        converted.pop();
                    });
        }

        private void leaf(NonTerminal node, String name, URL icon) {
            node.getChildren()
                    .stream()
                    .filter(ast -> ast instanceof Terminal)
                    .findFirst()
                    .ifPresent(ident -> converted.peek().addChild(new Outline(name, ident, icon)));
        }
    }

}
