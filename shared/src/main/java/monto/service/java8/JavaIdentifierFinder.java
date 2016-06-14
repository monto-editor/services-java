package monto.service.java8;

import monto.service.MontoService;
import monto.service.ZMQConfiguration;
import monto.service.ast.ASTNode;
import monto.service.ast.ASTNodeVisitor;
import monto.service.configuration.BooleanOption;
import monto.service.configuration.Configuration;
import monto.service.configuration.Setting;
import monto.service.dependency.DynamicDependency;
import monto.service.dependency.RegisterDynamicDependencies;
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
import monto.service.types.Product;
import monto.service.types.Source;

import java.util.*;
import java.util.stream.Collectors;

public class JavaIdentifierFinder extends MontoService {

  protected static final String OPTION_ID_FILTER_OUT_KEYWORDS = "filterOutKeywords";
  protected static final String OPTION_ID_SORT_IDENTIFIERS = "sortIdentifiers";
  protected boolean filterOutKeywords = true;
  protected boolean sortIdentifiersAlphabetically = true;

  public static final Set<String> JAVA_KEYWORDS_AND_LITERALS =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  // keywords
                  "abstract",
                  "continue",
                  "for",
                  "new",
                  "switch",
                  "assert",
                  "default",
                  "package",
                  "synchronized",
                  "boolean",
                  "do",
                  "if",
                  "private",
                  "this",
                  "break",
                  "double",
                  "implements",
                  "protected",
                  "throw",
                  "byte",
                  "else",
                  "import",
                  "public",
                  "throws",
                  "case",
                  "enum",
                  "instanceof",
                  "return",
                  "transient",
                  "catch",
                  "extends",
                  "int",
                  "short",
                  "try",
                  "char",
                  "final",
                  "interface",
                  "static",
                  "void",
                  "class",
                  "finally",
                  "long",
                  "strictfp",
                  "volatile",
                  "float",
                  "native",
                  "super",
                  "while",
                  // unused keywords
                  "goto",
                  "const",
                  // literals
                  "true",
                  "false",
                  "null"
                  // taken from: https://docs.oracle.com/javase/tutorial/java/nutsandbolts/_keywords.html
                  )));

  public JavaIdentifierFinder(ZMQConfiguration zmqConfig) {
    super(
        zmqConfig,
        JavaServices.IDENTIFIER_FINDER,
        "Identifier Finder",
        "Tries to find identifiers from AST, but can also find codewords from source message, if AST is not available",
        Languages.JAVA,
        Products.IDENTIFIER,
        options(
            new BooleanOption(
                OPTION_ID_FILTER_OUT_KEYWORDS,
                "Filter out Java Keywords and numeric literals from codewords, when AST is not available",
                true),
            new BooleanOption(
                OPTION_ID_SORT_IDENTIFIERS, "Sort identifiers alphabetically", false)),
        dependencies(
            new SourceDependency(Languages.JAVA),
            new ProductDependency(JavaServices.JAVACC_PARSER, Products.AST, Languages.JAVA)));
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
    SourceMessage mainSourceMessage =
        request
            .getSourceMessage(request.getSource())
            .orElseThrow(() -> new IllegalArgumentException("No source message in request"));
    ProductMessage mainAstMessage =
        request
            .getProductMessage(request.getSource(), Products.AST, Languages.JAVA)
            .orElseThrow(() -> new IllegalArgumentException("No AST message in request"));

    long start = System.nanoTime();

    Collection<Identifier> identifiers;
    if (!mainAstMessage.isAvailable()) {
      // fallback to source message
      identifiers = getCodewordsFromSourceMessage(mainSourceMessage);
      if (filterOutKeywords) {
        identifiers =
            identifiers
                .stream()
                .filter(
                    identifier -> !JAVA_KEYWORDS_AND_LITERALS.contains(identifier.getIdentifier()))
                .collect(Collectors.toSet());
      }
    } else {
      String mainSourceCode = mainSourceMessage.getContents();
      ASTNode mainAstRoot = GsonMonto.fromJson(mainAstMessage, ASTNode.class);
      Set<String> importedFiles = getImportedFiles(mainSourceCode, mainAstRoot);

      if (containsAllAstAndSourceMessageProducts(request, importedFiles)) {
        // find identifiers from main and all imported files
        identifiers = getIdentifiersFromAST(mainSourceCode, mainAstRoot);
        for (String importedFile : importedFiles) {
          String importedSourceCode =
              request.getSourceMessage(new Source(importedFile)).get().getContents();
          ASTNode importedAstRoot =
              GsonMonto.fromJson(
                  request
                      .getProductMessage(new Source(importedFile), Products.AST, Languages.JAVA)
                      .get()
                      .getContents(),
                  ASTNode.class);
          identifiers.addAll(getIdentifiersFromAST(importedSourceCode, importedAstRoot));
        }
      } else {
        // re-request all source messages and ASTs for imported files
        Set<DynamicDependency> astDependencies =
            importedFiles
                .stream()
                .map(
                    importedFile
                        -> new DynamicDependency(
                            new Source(importedFile),
                            JavaServices.JAVACC_PARSER,
                            Products.AST,
                            Languages.JAVA))
                .collect(Collectors.toSet());
        Set<DynamicDependency> sourceDependencies =
            importedFiles
                .stream()
                .map(
                    importedFile
                        -> DynamicDependency.sourceDependency(
                            new Source(importedFile), Languages.JAVA))
                .collect(Collectors.toSet());

        Set<DynamicDependency> allDynDeps = new HashSet<>();
        allDynDeps.addAll(astDependencies);
        allDynDeps.addAll(sourceDependencies);

        RegisterDynamicDependencies dynamicDependencies =
            new RegisterDynamicDependencies(request.getSource(), getServiceId(), allDynDeps);
        System.out.println("requesting " + dynamicDependencies);
        registerDynamicDependencies(dynamicDependencies);
        return;
      }
    }
    if (sortIdentifiersAlphabetically) {
      identifiers = identifiers.stream().sorted().collect(Collectors.toList());
    }

    //        System.out.println(identifiers);

    long end = System.nanoTime();

    sendProductMessage(
        mainSourceMessage.getId(),
        mainSourceMessage.getSource(),
        Products.IDENTIFIER,
        Languages.JAVA,
        GsonMonto.toJsonTree(identifiers),
        mainAstMessage.getTime() + end - start // TODO incorporate DynDep AST messages
        );
  }

  private Set<String> getImportedFiles(String sourceCode, ASTNode root) {
    Set<String> imports = new HashSet<>();
    // root is always a CompilationUnit
    List<ASTNode> compilationUnitChildren = root.getChildren();
    compilationUnitChildren.forEach(
        child
            -> child.accept(
                node -> {
                  switch (node.getName()) {
                    case "ImportDeclaration":
                      // first child of ImportDeclaration is the namedExpr
                      imports.add(
                          getRightMostImportNameExpr(node.getChild(0)).extract(sourceCode)
                              + ".java");
                      break;
                  }
                }));
    return imports;
  }

  private boolean containsAllAstAndSourceMessageProducts(
      Request request, Set<String> importedFiles) {
    for (String importedFile : importedFiles) {
      if (!request
              .getProductMessage(new Source(importedFile), Products.AST, Languages.JAVA)
              .isPresent()
          || !request.getSourceMessage(new Source(importedFile)).isPresent()) {
        return false;
      }
    }
    return true;
  }

  private Set<Identifier> getIdentifiersFromAST(String sourceCode, ASTNode astRoot)
      throws ParseException {
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
          IRegion rightMostImportRegion = getRightMostImportNameExpr(importNameExpr);
          identifiers.add(new Identifier(rightMostImportRegion.extract(sourceCode), "import"));
          break;

        case "ClassDeclaration":
          identifiers.add(new Identifier(node.getChild(1).extract(sourceCode), "class"));
          traverseChildren(node);
          break;

        case "EnumDeclaration":
          String enumName = node.getChild(1).extract(sourceCode);
          identifiers.add(new Identifier(enumName, "enum"));
          node.getChildren()
              .stream()
              .filter(identifier -> identifier.getName().equals("EnumConstantDeclaration"))
              .forEach(
                  identifier
                      -> identifiers.add(
                          new Identifier(enumName + "." + identifier.extract(sourceCode), "enum")));
          break;

        case "InterfaceDeclaration":
          identifiers.add(new Identifier(node.getChild(1).extract(sourceCode), "interface"));
          traverseChildren(node);
          break;

        case "FieldDeclaration":
          fieldDeclaration = true;
          traverseChildren(node);
          fieldDeclaration = false;
          break;

        case "VariableDeclaratorId":
          if (fieldDeclaration) identifiers.add(new Identifier(node.extract(sourceCode), "field"));
          else identifiers.add(new Identifier(node.extract(sourceCode), "variable"));
          break;

        case "MethodDeclaration":
          identifiers.add(new Identifier(node.getChild(2).extract(sourceCode), "method"));
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

  private IRegion getRightMostImportNameExpr(ASTNode importNameExpr) {
    if (importNameExpr.getChildren().size() > 0) {
      IRegion nextHigherRegion = importNameExpr.getChild(0);
      int lengthDiff = importNameExpr.getEndOffset() - nextHigherRegion.getEndOffset();
      // + 1 to exclude separating .
      // - 1 to exclude ;
      return new Region(nextHigherRegion.getEndOffset() + 1, lengthDiff - 1);

    } else {
      return importNameExpr;
    }
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
