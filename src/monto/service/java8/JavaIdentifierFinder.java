package monto.service.java8;

import monto.service.MontoService;
import monto.service.ZMQConfiguration;
import monto.service.ast.ASTNode;
import monto.service.ast.ASTNodeVisitor;
import monto.service.ast.ASTs;
import monto.service.identifier.Identifier;
import monto.service.identifier.Identifiers;
import monto.service.product.ProductMessage;
import monto.service.product.Products;
import monto.service.region.IRegion;
import monto.service.registration.ProductDependency;
import monto.service.registration.SourceDependency;
import monto.service.request.Request;
import monto.service.source.SourceMessage;
import monto.service.types.Languages;
import monto.service.types.ParseException;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class JavaIdentifierFinder extends MontoService {

    public JavaIdentifierFinder(ZMQConfiguration zmqConfig) {
        super(zmqConfig,
                JavaServices.JAVA_IDENTIFIER_FINDER,
                "Identifier Finder",
                "Tries to find identifiers from AST, but can also find codewords from source message, if AST is not available",
                Languages.JAVA,
                Products.IDENTIFIER,
                options(),
                dependencies(
                        new SourceDependency(Languages.JAVA),
                        new ProductDependency(JavaServices.JAVA_JAVACC_PARSER, Products.AST, Languages.JAVA)
                )
        );
    }

    @Override
    public ProductMessage onRequest(Request request) throws Exception {
        SourceMessage sourceMessage = request.getSourceMessage()
                .orElseThrow(() -> new IllegalArgumentException("No source message in request"));
        ProductMessage astMessage = request.getProductMessage(Products.AST, Languages.JAVA)
                .orElseThrow(() -> new IllegalArgumentException("No AST message in request"));

        long start = System.nanoTime();

        JSONObject jsonAst = (JSONObject) astMessage.getContents();

        List<Identifier> identifiers;
        if (jsonAst.get("notAvailable") != null) {
            // fallback to source message
            identifiers = getCodewordsFromSourceMessage(sourceMessage);
        } else {
            identifiers = getIdentifiersFromAST(sourceMessage.getContent(), jsonAst);
        }
        System.out.println(identifiers);

        JSONArray jsonArray = new JSONArray();
        //noinspection unchecked
        jsonArray.addAll(identifiers.stream().map(Identifiers::encode).collect(Collectors.toList()));
        long end = System.nanoTime();

        return productMessage(
                sourceMessage.getId(),
                sourceMessage.getSource(),
                Products.IDENTIFIER,
                Languages.JAVA,
                jsonArray,
                astMessage.getTime() + end - start
        );
    }

    private List<Identifier> getIdentifiersFromAST(String contents, JSONObject jsonAst) throws ParseException {
        ASTNode root = ASTs.decodeASTNode(jsonAst);

        AllIdentifiers completionVisitor = new AllIdentifiers(contents);
        root.accept(completionVisitor);
        return completionVisitor.getIdentifiers();
    }

    private class AllIdentifiers implements ASTNodeVisitor {
        private List<Identifier> identifiers = new ArrayList<>();
        private String content;
        private boolean fieldDeclaration = false;

        public AllIdentifiers(String content) {
            this.content = content;
        }

        @Override
        public void visit(ASTNode node) {
            switch (node.getName()) {

                case "PackageDeclaration":
                    identifiers.add(new Identifier(extract(content, node), Identifier.IdentifierType.PACKAGE));
                    break;

                case "ClassDeclaration":
                    identifiers.add(new Identifier(extract(content, node.getChild(1)), Identifier.IdentifierType.CLASS));
                    traverseChildren(node);
                    break;

                case "InterfaceDeclaration":
                    identifiers.add(new Identifier(extract(content, node.getChild(1)), Identifier.IdentifierType.INTERFACE));
                    traverseChildren(node);
                    break;

                case "FieldDeclaration":
                    fieldDeclaration = true;
                    traverseChildren(node);
                    fieldDeclaration = false;
                    break;

                case "VariableDeclaratorId":
                    if (fieldDeclaration)
                        identifiers.add(new Identifier(extract(content, node), Identifier.IdentifierType.FIELD));
                    else
                        identifiers.add(new Identifier(extract(content, node), Identifier.IdentifierType.VARIABLE));
                    break;

                case "MethodDeclaration":
                    identifiers.add(new Identifier(extract(content, node.getChild(2)), Identifier.IdentifierType.METHOD));
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

        public List<Identifier> getIdentifiers() {
            return identifiers;
        }
    }

    private static String extract(String str, IRegion indent) {
        return str.subSequence(indent.getStartOffset(), indent.getStartOffset() + indent.getLength()).toString();
    }


    private List<Identifier> getCodewordsFromSourceMessage(SourceMessage sourceMessage) {
        String content = sourceMessage.getContent();
        // cleanup source code by removing elements, that are not identifiers

        // remove comments
        content = content.replaceAll("(//.*|/\\*([\\S\\s]+?)\\*/)", " ");
        // [\S\s] is almost the same as ., except that it matches newlines too ([\W\w] or [\D\d] work too)
        // +? makes + ungreedy (doesn't match until last found instance, but next found instance (same for *?)

        /** first attempt: exclude everything unwanted */
        // remaining / not solved problem: digits in identifiers get cut out
//        // remove strings, chars, digits (including scientific notation) and these symbols: ,+-*/%|&!;()[]{}=?:
//        content = content.replaceAll("(\".*\"|'.*'|[eE]\\d+|[\\d,+\\-*/%\\|&!;()\\[\\]\\{\\}=:?])", "");
//        content = content.replaceAll("[<>]", " ");
//        // replace all whitespaces (includes newline), dots, < and > with only one space
//        content = content.replaceAll("[<>\\s.]+", " ");
//        // create non-duplicate set of identifiers by splitting at space
//        String[] identifiers = content.split(" ");
//        HashSet<String> identifierSet = new HashSet<>(Arrays.asList(identifiers));
        /** end first attempt */

        /** seconc attempt: only alphanumerics */
        // remove strings and chars
        content = content.replaceAll("(\".*\"|'.*')", "");
        // replace everything non alphanumeric with one space
        content = content.replaceAll("\\W+", " ");
        /** end seconc attempt */

        // split at whitespaces
        return Arrays.stream(content.split("\\s+"))
                // remove numbers (including scientific notation and hexadecimals)
                .filter((String str) -> !(str.matches("^[\\deE]+$") || str.matches("^0[xX].*")))
                // create non-duplicate set
                .collect(Collectors.toSet())
                // convert to stream again to sort or else order is arbitrary
                .stream()
                .sorted()
                // convert strings to Identifier objetcs
                .map((String identifier) -> new Identifier(identifier, Identifier.IdentifierType.GENERIC))
                .collect(Collectors.toList());
    }
}
