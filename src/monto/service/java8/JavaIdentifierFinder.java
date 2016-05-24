package monto.service.java8;

import monto.service.MontoService;
import monto.service.ZMQConfiguration;
import monto.service.ast.ASTNode;
import monto.service.ast.ASTNodeVisitor;
import monto.service.configuration.BooleanOption;
import monto.service.configuration.Configuration;
import monto.service.configuration.Setting;
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

    protected static final String OPTION_ID_FILTER_OUT_KEYWORDS = "filterOutKeywords";
    protected static final String OPTION_ID_SORT_IDENTIFIERS = "sortIdentifiers";
    protected boolean filterOutKeywords = true;
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
                options(
                        new BooleanOption(OPTION_ID_FILTER_OUT_KEYWORDS, "Filter out Java Keywords and numeric literals from codewords, when AST is not available", true),
                        new BooleanOption(OPTION_ID_SORT_IDENTIFIERS, "Sort identifiers alphabetically", false)
                ),
                dependencies(
                        new SourceDependency(Languages.JAVA),
                        new ProductDependency(JavaServices.JAVA_JAVACC_PARSER, Products.AST, Languages.JAVA)
                )
        );
    }

    @Override
    public void onConfigurationMessage(Configuration message) throws Exception {
        for (Setting setting : message.getSettings()) {
            if (setting.getOptionId().equals(OPTION_ID_FILTER_OUT_KEYWORDS)) {
                filterOutKeywords = (boolean) setting.getValue();
            }
            if (setting.getOptionId().equals(OPTION_ID_SORT_IDENTIFIERS)) {
                sortIdentifiersAlphabetically = (boolean) setting.getValue();
            }
        }
    }

    @Override
    public void onRequest(Request request) throws Exception {
        SourceMessage sourceMessage = request.getSourceMessage()
                .orElseThrow(() -> new IllegalArgumentException("No source message in request"));
        ProductMessage astMessage = request.getProductMessage(Products.AST, Languages.JAVA)
                .orElseThrow(() -> new IllegalArgumentException("No AST message in request"));

        long start = System.nanoTime();


        Collection<Identifier> identifiers;
        if (!astMessage.isAvailable()) {
            // fallback to source message
            identifiers = getCodewordsFromSourceMessage(sourceMessage);
            if (filterOutKeywords) {
                identifiers = identifiers.stream()
                        .filter(identifier -> !JAVA_KEYWORDS_AND_LITERALS.contains(identifier.getIdentifier()))
                        .collect(Collectors.toSet());
            }
        } else {
            ASTNode astRoot = GsonMonto.fromJson(astMessage, ASTNode.class);
            identifiers = getIdentifiersFromAST(sourceMessage.getContents(), astRoot);
        }
        if (sortIdentifiersAlphabetically) {
            identifiers = identifiers.stream()
                    .sorted()
                    .collect(Collectors.toList());
        }

//        System.out.println(identifiers);

        long end = System.nanoTime();


        sendProductMessage(
                sourceMessage.getId(),
                sourceMessage.getSource(),
                Products.IDENTIFIER,
                Languages.JAVA,
                GsonMonto.toJsonTree(identifiers),
                astMessage.getTime() + end - start
        );
    }

    private Set<Identifier> getIdentifiersFromAST(String sourceCode, ASTNode astRoot) throws ParseException {
        AllIdentifiers completionVisitor = new AllIdentifiers(sourceCode);
        astRoot.accept(completionVisitor);
        return completionVisitor.getIdentifiers();
    }

    private class AllIdentifiers implements ASTNodeVisitor {
        private Set<Identifier> identifiers = new HashSet<>();
        private String sourceCode;
        private boolean fieldDeclaration = false;

        public AllIdentifiers(String sourceCode) {
            this.sourceCode = sourceCode;
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
                    identifiers.add(new Identifier(extract(sourceCode, rightMostImportRegion), "import"));
                    break;

                case "ClassDeclaration":
                    identifiers.add(new Identifier(extract(sourceCode, node.getChild(1)), "class"));
                    traverseChildren(node);
                    break;

                case "EnumDeclaration":
                    String enumName = extract(sourceCode, node.getChild(1));
                    identifiers.add(new Identifier(enumName, "enum"));
                    node.getChildren().stream()
                            .filter(identifier -> identifier.getName().equals("EnumConstantDeclaration"))
                            .forEach(identifier -> identifiers.add(
                                    new Identifier(enumName + "." + extract(sourceCode, identifier), "enum")
                            ));
                    break;

                case "InterfaceDeclaration":
                    identifiers.add(new Identifier(extract(sourceCode, node.getChild(1)), "interface"));
                    traverseChildren(node);
                    break;

                case "FieldDeclaration":
                    fieldDeclaration = true;
                    traverseChildren(node);
                    fieldDeclaration = false;
                    break;

                case "VariableDeclaratorId":
                    if (fieldDeclaration)
                        identifiers.add(new Identifier(extract(sourceCode, node), "field"));
                    else
                        identifiers.add(new Identifier(extract(sourceCode, node), "variable"));
                    break;

                case "MethodDeclaration":
                    identifiers.add(new Identifier(extract(sourceCode, node.getChild(2)), "method"));
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

        // remove strings and chars
        content = content.replaceAll("(\".*\"|'.*')", "");
        // replace everything non alphanumeric with one space
        content = content.replaceAll("\\W+", " ");

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
                .map((String identifier) -> new Identifier(identifier, "generic"))
                .collect(Collectors.toSet());
    }
}
