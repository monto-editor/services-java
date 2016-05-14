package monto.service.java8;

import monto.service.MontoService;
import monto.service.ZMQConfiguration;
import monto.service.ast.ASTNode;
import monto.service.ast.ASTNodeVisitor;
import monto.service.gson.GsonMonto;
import monto.service.identifier.Identifier;
import monto.service.product.ProductMessage;
import monto.service.product.Products;
import monto.service.region.IRegion;
import monto.service.region.Region;
import monto.service.registration.ProductDependency;
import monto.service.registration.SourceDependency;
import monto.service.request.Request;
import monto.service.source.SourceMessage;
import monto.service.types.Languages;
import monto.service.types.ParseException;

import java.util.*;
import java.util.stream.Collectors;

public class JavaIdentifierFinder extends MontoService {

    protected boolean filterOutKeywordsAndLitetalFromCodewords = true;
    protected boolean sortIdentifiersAlphabetically = true;

    public static final Set<String> JAVA_KEYWORDS_AND_LITERALS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    // keywords
                    "abstract", "continue", "for", "new", "switch", "assert", "default", "package", "synchronized",
                    "boolean", "do", "if", "private", "this", "break", "double", "implements", "protected", "throw",
                    "byte", "else", "import", "public", "throws", "case", "enum", "instanceof", "return", "transient",
                    "catch", "extends", "int", "short", "try", "char", "final", "interface", "static", "void", "class",
                    "finally", "long", "strictfp", "volatile", "float", "native", "super", "while",
                    // unused keywords
                    "goto", "const",
                    // literals
                    "true", "false", "null"
                    // taken from: https://docs.oracle.com/javase/tutorial/java/nutsandbolts/_keywords.html
            )));

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

        ASTNode astRoot = GsonMonto.fromJson(astMessage, ASTNode.class);

        Collection<Identifier> identifiers;
        if (astRoot.getName().equals("NotAvailable")) {
            // fallback to source message
            identifiers = getCodewordsFromSourceMessage(sourceMessage);
            if (filterOutKeywordsAndLitetalFromCodewords) {
                identifiers = identifiers.stream()
                        .filter(identifier -> !JAVA_KEYWORDS_AND_LITERALS.contains(identifier.getIdentifier()))
                        .collect(Collectors.toSet());
            }
        } else {
            identifiers = getIdentifiersFromAST(sourceMessage.getContents(), astRoot);
        }
        if (sortIdentifiersAlphabetically) {
            identifiers = identifiers.stream()
                    .sorted()
                    .collect(Collectors.toList());
        }

        if (debug) {
            System.out.println(identifiers);
        }

        long end = System.nanoTime();


        return productMessage(
                sourceMessage.getId(),
                sourceMessage.getSource(),
                Products.IDENTIFIER,
                Languages.JAVA,
                GsonMonto.toJsonTree(identifiers),
                astMessage.getTime() + end - start
        );
    }

    private Set<Identifier> getIdentifiersFromAST(String contents, ASTNode astRoot) throws ParseException {
        AllIdentifiers completionVisitor = new AllIdentifiers(contents);
        astRoot.accept(completionVisitor);
        return completionVisitor.getIdentifiers();
    }

    private class AllIdentifiers implements ASTNodeVisitor {
        private Set<Identifier> identifiers = new HashSet<>();
        private String content;
        private boolean fieldDeclaration = false;

        public AllIdentifiers(String content) {
            this.content = content;
        }

        @Override
        public void visit(ASTNode node) {
            switch (node.getName()) {

                case "ImportDeclaration":
                    ASTNode importNameExpr = node.getChild(0);
                    IRegion rightMostImportRegion;
                    if (importNameExpr.getChildren().size() > 0) {
                        IRegion nextHigherRegion = importNameExpr.getChild(0);
                        int lengthDiff = importNameExpr.getEndOffset() - nextHigherRegion.getEndOffset();
                        // + 1 to exclude separating .
                        // - 1 to exclude ;
                        rightMostImportRegion = new Region(nextHigherRegion.getEndOffset() + 1, lengthDiff - 1);

                    } else {
                        rightMostImportRegion = importNameExpr;
                    }
                    identifiers.add(new Identifier(extract(content, rightMostImportRegion), Identifier.IdentifierType.IMPORT));
                    break;

                case "ClassDeclaration":
                    identifiers.add(new Identifier(extract(content, node.getChild(1)), Identifier.IdentifierType.CLASS));
                    traverseChildren(node);
                    break;

                case "EnumDeclaration":
                    String enumName = extract(content, node.getChild(1));
                    identifiers.add(new Identifier(enumName, Identifier.IdentifierType.ENUM));
                    node.getChildren().stream()
                            .filter(identifier -> identifier.getName().equals("EnumConstantDeclaration"))
                            .forEach(identifier -> identifiers.add(
                                    new Identifier(enumName + "." + extract(content, identifier), Identifier.IdentifierType.ENUM)
                            ));
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

        public Set<Identifier> getIdentifiers() {
            return identifiers;
        }
    }

    private static String extract(String str, IRegion indent) {
        return str.subSequence(indent.getStartOffset(), indent.getStartOffset() + indent.getLength()).toString();
    }


    private Set<Identifier> getCodewordsFromSourceMessage(SourceMessage sourceMessage) {
        String content = sourceMessage.getContents();
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

        /** second attempt: only alphanumerics */
        // remove strings and chars
        content = content.replaceAll("(\".*\"|'.*')", "");
        // replace everything non alphanumeric with one space
        content = content.replaceAll("\\W+", " ");
        /** end second attempt */

        // split at whitespaces
        return Arrays.stream(content.split("\\s+"))
                // remove numbers (including scientific notation, explicit double, float and long values and hexadecimals)
                // since Java 7 numeric literal can contain underscors to improve readability
                // https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html
                // also used document: http://web.itu.edu.tr/~bkurt/Courses/bte541/bte541b_mod03.pdf
                .filter((String str) -> !(str.matches("^[\\d_eE]+[dDfFlL]?$") || str.matches("^0[xX].*")))
                // create non-duplicate set
                .collect(Collectors.toSet())
                .stream()
                // convert strings to Identifier objetcs
                .map((String identifier) -> new Identifier(identifier, Identifier.IdentifierType.GENERIC))
                .collect(Collectors.toSet());
    }
}
