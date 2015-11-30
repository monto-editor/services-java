package monto.service.java8;

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
import monto.service.message.IconType;
import monto.service.message.Languages;
import monto.service.message.Message;
import monto.service.message.Messages;
import monto.service.message.ParseException;
import monto.service.message.ProductDependency;
import monto.service.message.ProductMessage;
import monto.service.message.Products;
import monto.service.message.VersionMessage;
import monto.service.outline.Outline;
import monto.service.outline.Outlines;
import monto.service.registration.ServiceDependency;
import monto.service.registration.SourceDependency;

public class JavaOutliner extends MontoService {

    public JavaOutliner(ZMQConfiguration zmqConfig) {
    	super(zmqConfig,
    			JavaServices.JAVA_OUTLINER,
    			"Outline",
    			"An outline service for Java",
    			Products.OUTLINE,
    			Languages.JAVA,
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
                Outlines.encode(trimmer.getConverted()),
                new ProductDependency(ast));
    }

    /**
     * Traverses the AST and removes unneeded information.
     */
    private static class OutlineTrimmer implements ASTVisitor {

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
                        converted.peek().addChild(new Outline("package", packageIdentifier, IconType.PACKAGE));
                    break;

                case "normalClassDeclaration":
                    structureDeclaration(node, "class", IconType.CLASS);
                    break;

                case "enumDeclaration":
                    structureDeclaration(node, "enum", IconType.ENUM);
                    break;

                case "enumConstant":
                    leaf(node, "constant", IconType.ENUM_DEFAULT);
                    break;

                case "fieldDeclaration":
                    fieldDeclaration = true;
                    node.getChildren().forEach(child -> child.accept(this));
                    fieldDeclaration = false;

                case "variableDeclaratorId":
                    if (fieldDeclaration)
                        leaf(node, "field", IconType.PRIVATE);
                    break;

                case "methodDeclarator":
                    leaf(node, "method", IconType.PUBLIC);

                default:
                    node.getChildren().forEach(child -> child.accept(this));
            }
        }

        @Override
        public void visit(Terminal token) {

        }

        private void structureDeclaration(NonTerminal node, String name, String icon) {
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

        private void leaf(NonTerminal node, String name, String icon) {
            node.getChildren()
                    .stream()
                    .filter(ast -> ast instanceof Terminal)
                    .findFirst()
                    .ifPresent(ident -> converted.peek().addChild(new Outline(name, ident, icon)));
        }
    }

}
