package monto.service.java8;


import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import monto.service.MontoService;
import monto.service.ZMQConfiguration;
import monto.service.ast.AST;
import monto.service.ast.ASTVisitor;
import monto.service.ast.NonTerminal;
import monto.service.ast.Terminal;
import monto.service.completion.Completion;
import monto.service.dependency.DynamicDependency;
import monto.service.dependency.RegisterDynamicDependencies;
import monto.service.gson.GsonMonto;
import monto.service.product.ProductMessage;
import monto.service.product.Products;
import monto.service.registration.ProductDependency;
import monto.service.registration.SourceDependency;
import monto.service.request.Request;
import monto.service.source.SourceMessage;
import monto.service.types.Languages;
import monto.service.types.ParseException;
import monto.service.types.Source;

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
                        new ProductDependency(JavaServices.JAVA_ANTLR_PARSER, Products.AST, Languages.JAVA)
                ));
    }

    @Override
    public void onRequest(Request request) throws IOException, ParseException {
        SourceMessage source = request.getSourceMessage(request.getSource())
                .orElseThrow(() -> new IllegalArgumentException("No Source message in request"));

        ProductMessage astMessage = request.getProductMessage(Products.AST, Languages.JAVA)
                .orElseThrow(() -> new IllegalArgumentException("No AST message in request"));
        AST ast = GsonMonto.fromJson(astMessage, AST.class);

        Set<Source> requiredSources = new HashSet<>();
        Set<DynamicDependency> dynDeps = findDynamicDependencies(source, requiredSources);

        sendMissingRequirements(source, dynDeps);

        List<Tuple> msgs = getDynamicDependencyMessages(request, requiredSources);
        msgs.add(new Tuple(ast, source));


        List<Completion> relevantCompletions = new ArrayList<>();
        for (Tuple msg : msgs) {
            relevantCompletions.addAll(
                    allCompletions(msg.src.getContents(), msg.ast)
                            .stream()
                            .map(comp -> new Completion(
                                    msg.src.getSource() + " - " + comp.getDescription() + ": " + comp.getReplacement(),
                                    comp.getReplacement(),
                                    0,
                                    null))
                            .collect(Collectors.toList()));
        }
        System.out.printf("Relevant: %s\n", relevantCompletions);

        sendProductMessage(
                source.getId(),
                source.getSource(),
                Products.COMPLETIONS,
                Languages.JAVA,
                GsonMonto.toJsonTree(relevantCompletions)
        );
    }

    private Set<DynamicDependency> findDynamicDependencies(SourceMessage source, Set<Source> requiredSources) throws ParseException {
        Set<DynamicDependency> dynDeps = new HashSet<>();
        int begin = 0;
        boolean foundImport = false;

        // FIXME find another way to extract to extract import modules rather than from token categories.
        /*
        for (Token t : GsonMonto.fromJsonArray(tokens, Token[].class)) {
            if (foundImport && t.getCategory() == TokenCategory.DELIMITER && source.getContents().substring(t.getStartOffset(), t.getEndOffset()).equals(";")) {
                String str = source.getContents().substring(begin + 1, t.getStartOffset()).replace(".", "/").trim() + ".java";
                if (str.split("/")[0].equals("java")) {
                    continue;
                }
                Source s = new Source(str);
                requiredSources.add(s);
                dynDeps.add(new DynamicDependency(s, JavaServices.JAVA_ANTLR_PARSER, Products.AST, Languages.JAVA));
                dynDeps.add(new DynamicDependency(s, new ServiceId("source"), new Product("source"), Languages.JAVA));
                foundImport = false;
            } else if (t.getCategory() == TokenCategory.KEYWORD && source.getContents().substring(t.getStartOffset(), t.getEndOffset()).equals("import")) {
                foundImport = true;
                begin = t.getEndOffset();
            }
        }
        */
        return dynDeps;
    }

    private List<Tuple> getDynamicDependencyMessages(Request request, Set<Source> requiredSources) throws ParseException {
        List<Tuple> msgs = new ArrayList<>();
        for (Source s : requiredSources) {
            ProductMessage astMessage = request.getProductMessage(s, Products.AST, Languages.JAVA)
                    .orElseThrow(() -> new IllegalArgumentException("No AST message in request for " + s));
            msgs.add(new Tuple(
                    GsonMonto.fromJson(astMessage, AST.class),
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
                            packageIdentifier.extract(content),
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
            completions.add(new Completion(name, structureIdent.extract(content), icon));
            node.getChildren().forEach(child -> child.accept(this));
        }

        private void leaf(NonTerminal node, String name, URL url) {
            AST ident = node
                    .getChildren()
                    .stream()
                    .filter(ast -> ast instanceof Terminal)
                    .findFirst().get();
            completions.add(new Completion(name, ident.extract(content), url));
        }


        public List<Completion> getCompletions() {
            return completions;
        }
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
