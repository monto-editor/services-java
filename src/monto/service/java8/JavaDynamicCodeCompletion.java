package monto.service.java8;


import monto.service.MontoService;
import monto.service.ZMQConfiguration;
import monto.service.ast.*;
import monto.service.completion.Completion;
import monto.service.completion.Completions;
import monto.service.dependency.DynamicDependency;
import monto.service.dependency.RegisterDynamicDependencies;
import monto.service.product.ProductMessage;
import monto.service.product.Products;
import monto.service.region.IRegion;
import monto.service.registration.ProductDependency;
import monto.service.registration.SourceDependency;
import monto.service.request.Request;
import monto.service.source.SourceMessage;
import monto.service.token.Token;
import monto.service.token.TokenCategory;
import monto.service.token.Tokens;
import monto.service.types.*;

import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

public class JavaDynamicCodeCompletion extends MontoService {

    private Map<Source, RegisterDynamicDependencies> registerDynamicDependencies = new HashMap<>();

    public JavaDynamicCodeCompletion(ZMQConfiguration zmqConfig) {
        super(zmqConfig,
                JavaServices.JAVA_CODE_COMPLETION,
                "Dynamic Code Completion",
                "A code completion service for Java with file dependencies",
                Languages.JAVA,
                Products.COMPLETIONS,
                options(),
                dependencies(
                        new SourceDependency(Languages.JAVA),
                        new ProductDependency(JavaServices.JAVA_ANTLR_PARSER, Products.AST, Languages.JAVA),
                        new ProductDependency(JavaServices.JAVA_TOKENIZER, Products.TOKENS, Languages.JAVA)
                ));
    }

    @Override
    public ProductMessage onRequest(Request request) throws IOException, ParseException {
        SourceMessage source = request.getSourceMessage(request.getSource())
                .orElseThrow(() -> new IllegalArgumentException("No Source message in request"));

        if (source.getSelection().isPresent()) {
            AST ast = ASTs.decode(request.getProductMessage(Products.AST, Languages.JAVA)
                    .orElseThrow(() -> new IllegalArgumentException("No AST message in request")));
            ProductMessage tokens = request.getProductMessage(request.getSource(), Products.TOKENS, Languages.JAVA)
                    .orElseThrow(() -> new IllegalArgumentException("No Tokens message in request"));

            Set<Source> requiredSources = new HashSet<>();
            Set<DynamicDependency> dynDeps = findDynamicDependencies(tokens, source, requiredSources);

            sendMissingRequirements(source, dynDeps);

            List<Tuple> msgs = getDynamicDependencyMessages(request, requiredSources);
            msgs.add(new Tuple(ast, source));

            List<AST> selectedPath = selectedPath(ast, source.getSelection().get());
            if (selectedPath.size() > 0 && last(selectedPath) instanceof Terminal) {
                String toBeCompleted = extract(source.getContent(), last(selectedPath));

                List<Completion> relevantCompletions = new ArrayList<>();
                for (Tuple msg : msgs) {
                    relevantCompletions.addAll(
                            allCompletions(msg.src.getContent(), msg.ast)
                                    .stream()
                                    .filter(comp -> comp.getReplacement().startsWith(toBeCompleted))
                                    .map(comp -> new Completion(
                                            msg.src.getSource() + " - " + comp.getDescription() + ": " + comp.getReplacement(),
                                            comp.getReplacement(),
                                            source.getSelection().get().getStartOffset(),
                                            null))
                                    .collect(Collectors.toList()));
                }
                System.out.printf("Relevant: %s\n", relevantCompletions);

                return productMessage(
                        source.getId(),
                        source.getSource(),
                        Products.COMPLETIONS,
                        Languages.JAVA,
                        Completions.encode(relevantCompletions));
            }
            throw new IllegalArgumentException(
                    String.format("Last token in selection path is not a terminal: %s", selectedPath));
        }
        throw new IllegalArgumentException("Code completion needs selection");
    }

    private Set<DynamicDependency> findDynamicDependencies(ProductMessage tokens, SourceMessage source, Set<Source> requiredSources) throws ParseException {
        Set<DynamicDependency> dynDeps = new HashSet<>();
        int begin = 0;
        boolean foundImport = false;
        for (Token t : Tokens.decodeTokenMessage(tokens)) {
            if (foundImport && t.getCategory() == TokenCategory.DELIMITER && source.getContent().substring(t.getStartOffset(), t.getEndOffset()).equals(";")) {
                String str = source.getContent().substring(begin + 1, t.getStartOffset()).replace(".", "/").trim() + ".java";
                if (str.split("/")[0].equals("java")) {
                    continue;
                }
                Source s = new Source(str);
                requiredSources.add(s);
                dynDeps.add(new DynamicDependency(s, JavaServices.JAVA_ANTLR_PARSER, Products.AST, Languages.JAVA));
                dynDeps.add(new DynamicDependency(s, new ServiceID("source"), new Product("source"), Languages.JAVA));
                foundImport = false;
            } else if (t.getCategory() == TokenCategory.KEYWORD && source.getContent().substring(t.getStartOffset(), t.getEndOffset()).equals("import")) {
                foundImport = true;
                begin = t.getEndOffset();
            }
        }
        return dynDeps;
    }

    private List<Tuple> getDynamicDependencyMessages(Request request, Set<Source> requiredSources) throws ParseException {
        List<Tuple> msgs = new ArrayList<>();
        for (Source s : requiredSources) {
            msgs.add(new Tuple(
                    ASTs.decode(request.getProductMessage(s, Products.AST, Languages.JAVA)
                            .orElseThrow(() -> new IllegalArgumentException("No AST message in request for " + s))),
                    request.getSourceMessage(s)
                            .orElseThrow(() -> new IllegalArgumentException("No Source message in request for " + s))
            ));
        }
        return msgs;
    }

    private void sendMissingRequirements(SourceMessage source, Set<DynamicDependency> dynDeps) {
        RegisterDynamicDependencies regDynDep = new RegisterDynamicDependencies(
                source.getSource(),
                JavaServices.JAVA_CODE_COMPLETION,
                dynDeps
        );
        if (!regDynDep.equals(registerDynamicDependencies.get(source.getSource()))) {
            super.registerDynamicDependencies(regDynDep);
            registerDynamicDependencies.put(source.getSource(), regDynDep);
        }
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
                            extract(content, packageIdentifier),
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
            completions.add(new Completion(name, extract(content, structureIdent), icon));
            node.getChildren().forEach(child -> child.accept(this));
        }

        private void leaf(NonTerminal node, String name, URL url) {
            AST ident = node
                    .getChildren()
                    .stream()
                    .filter(ast -> ast instanceof Terminal)
                    .findFirst().get();
            completions.add(new Completion(name, extract(content, ident), url));
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
        return str.substring(indent.getStartOffset(), indent.getStartOffset() + indent.getLength());
    }

    private static <A> A last(List<A> list) {
        return list.get(list.size() - 1);
    }

    private class Tuple {
        public final AST ast;
        public final SourceMessage src;

        public Tuple(AST ast, SourceMessage src) {
            this.ast = ast;
            this.src = src;
        }
    }
}
