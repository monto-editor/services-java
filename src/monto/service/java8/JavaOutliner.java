package monto.service.java8;

import monto.service.MontoService;
import monto.service.ast.*;
import monto.service.message.*;
import monto.service.outline.Outline;
import monto.service.outline.Outlines;
import org.zeromq.ZContext;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public class JavaOutliner extends MontoService {

    private static final Product AST = new Product("ast");
    private static final Product OUTLINE = new Product("outline");
    private static final Language JAVA = new Language("java");

    public JavaOutliner(ZContext context, String address, String registrationAddress, String serviceID) {
        super(context, address, registrationAddress, serviceID, "Outline service for Java", "An outline service for Java", OUTLINE, JAVA, new String[]{"Source", "ast/java"});
    }


    @Override
    public ProductMessage onVersionMessage(List<Message> messages) throws ParseException {
        VersionMessage version = Messages.getVersionMessage(messages);
        if (!version.getLanguage().equals(JAVA)) {
            throw new IllegalArgumentException("wrong language in version message");
        }
        ProductMessage ast = Messages.getProductMessage(messages, AST, JAVA);
        if (!ast.getLanguage().equals(JAVA)) {
            throw new IllegalArgumentException("wrong language in ast product message");
        }
        NonTerminal root = (NonTerminal) ASTs.decode(ast);

        OutlineTrimmer trimmer = new OutlineTrimmer();
        root.accept(trimmer);

        return new ProductMessage(
                version.getVersionId(),
                new LongKey(1),
                version.getSource(),
                OUTLINE,
                JAVA,
                Outlines.encode(trimmer.getConverted()),
                new ProductDependency(ast));

    }

    @Override
    public void onConfigurationMessage(List<Message> list) throws Exception {

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
