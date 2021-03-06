package monto.service.java8;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseException;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.ModifierSet;
import com.github.javaparser.ast.body.TypeDeclaration;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import monto.service.MontoService;
import monto.service.ZMQConfiguration;
import monto.service.ast.ASTNode;
import monto.service.gson.GsonMonto;
import monto.service.product.Products;
import monto.service.region.IRegion;
import monto.service.region.Region;
import monto.service.registration.ProductDescription;
import monto.service.registration.SourceDependency;
import monto.service.request.Request;
import monto.service.source.SourceMessage;
import monto.service.types.Languages;

public class JavaJavaCCParser extends MontoService {

  public JavaJavaCCParser(ZMQConfiguration zmqConfig) {
    super(
        zmqConfig,
        JavaServices.JAVACC_PARSER,
        "JavaCC Parser",
        "A parser that produces an AST for Java using JavaCC",
        productDescriptions(new ProductDescription(Products.AST, Languages.JAVA)),
        options(),
        dependencies(new SourceDependency(Languages.JAVA)),
        commands());
  }

  @Override
  public void onRequest(Request request) throws IOException {
    SourceMessage sourceMessage =
        request
            .getSourceMessage()
            .orElseThrow(() -> new IllegalArgumentException("No version message in request"));
    long start = System.nanoTime();

    // Remove all tabs to correct source locations of JavaCC Parser
    String contents = sourceMessage.getContents().replaceAll("\\t", " ");

    try {
      Node root = JavaParser.parse(new StringReader(contents), true);
      ASTNode convertedRoot = encode(Region.getLineOffsets(sourceMessage.getContents()), root);
      sendProductMessage(
          sourceMessage.getId(),
          sourceMessage.getSource(),
          Products.AST,
          Languages.JAVA,
          GsonMonto.toJsonTree(convertedRoot),
          System.nanoTime() - start);
    } catch (ParseException e) {
      sendProductMessageNotAvailable(
          sourceMessage.getId(),
          sourceMessage.getSource(),
          Products.AST,
          Languages.JAVA,
          e,
          System.nanoTime() - start);
    }
  }

  private ASTNode astNode(String name, List<ASTNode> children, IRegion region) {
    return new ASTNode(name, region.getStartOffset(), region.getLength(), children);
  }

  private ASTNode encode(int[] offsets, Node node) {
    String name = node.getClass().getSimpleName();
    IRegion region = region(offsets, node);
    List<ASTNode> children =
        node.getChildrenNodes()
            .stream()
            .map(child -> encode(offsets, child))
            .collect(Collectors.toList());

    // Not all parts of the AST appear in node.getChildrenNodes, so we have to
    // include these parts manually.
    switch (name) {
      case "ClassOrInterfaceDeclaration":
        {
          ClassOrInterfaceDeclaration decl = (ClassOrInterfaceDeclaration) node;
          if (decl.isInterface()) name = "InterfaceDeclaration";
          else name = "ClassDeclaration";
          children.add(0, makeModifierObject(offsets, decl.getModifiers(), decl));
          children.add(1, makeIdentifierObject(offsets, decl.getNameExpr()));
        }
        break;
      case "EmptyTypeDeclaration":
      case "EnumDeclaration":
        {
          TypeDeclaration enumN = (TypeDeclaration) node;
          children.add(0, makeModifierObject(offsets, enumN.getModifiers(), enumN));
          children.add(1, makeIdentifierObject(offsets, enumN.getNameExpr()));
        }
        break;
      case "MethodDeclaration":
        {
          MethodDeclaration decl = (MethodDeclaration) node;
          children.add(0, makeModifierObject(offsets, decl.getModifiers(), decl));
          children.add(2, makeIdentifierObject(offsets, decl.getNameExpr()));
        }
        break;
      case "FieldDeclaration":
        {
          FieldDeclaration decl = (FieldDeclaration) node;
          children.add(0, makeModifierObject(offsets, decl.getModifiers(), decl));
        }
        break;
      case "ConstructorDeclaration":
        {
          ConstructorDeclaration decl = (ConstructorDeclaration) node;
          children.add(0, makeModifierObject(offsets, decl.getModifiers(), decl));
          children.add(1, makeIdentifierObject(offsets, decl.getNameExpr()));
          List<ASTNode> paramsChildren =
              decl.getParameters()
                  .stream()
                  .map(param -> encode(offsets, param))
                  .collect(Collectors.toList());
          children.add(2, astNode("Parameters", paramsChildren, region(offsets, decl)));
        }
        break;
      case "EnumConstantDeclaration":
        {
          EnumConstantDeclaration decl = (EnumConstantDeclaration) node;
          ASTNode idObj =
              astNode(
                  "Identifier",
                  new ArrayList<>(),
                  Region.fromLineNumberColumn(
                      offsets,
                      decl.getBeginLine(),
                      decl.getBeginColumn(),
                      decl.getEndLine(),
                      decl.getEndColumn() + decl.getName().length()));
          children.add(0, idObj);
        }
        break;
      default:
        break;
    }

    return new ASTNode(name, region.getStartOffset(), region.getLength(), children);
  }

  private ASTNode makeIdentifierObject(int[] offsets, Node name) {
    return astNode("Identifier", new ArrayList<>(), region(offsets, name));
  }

  private ASTNode makeModifierObject(int[] offsets, int modifiers, Node n) {
    IRegion modRegion =
        Region.fromLineNumberColumn(
            offsets, n.getBeginLine(), n.getBeginColumn(), n.getEndLine(), n.getEndColumn());
    List<String> mods = modifiers(modifiers);
    List<ASTNode> modChildrenArray = new ArrayList<>();
    for (String mod : mods) {
      modChildrenArray.add(astNode(mod, new ArrayList<>(), modRegion));
    }
    return astNode("Modifiers", modChildrenArray, modRegion);
  }

  private Region region(int[] offsets, Node node) {
    return Region.fromLineNumberColumn(
        offsets,
        node.getBeginLine(),
        node.getBeginColumn(),
        node.getEndLine(),
        node.getEndColumn());
  }

  private List<String> modifiers(int modifier) {
    List<String> modifiers = new ArrayList<>();
    if (ModifierSet.isAbstract(modifier)) modifiers.add("abstract");
    if (ModifierSet.isFinal(modifier)) modifiers.add("final");
    if (ModifierSet.isNative(modifier)) modifiers.add("native");
    if (ModifierSet.isPrivate(modifier)) modifiers.add("private");
    if (ModifierSet.isProtected(modifier)) modifiers.add("protected");
    if (ModifierSet.isPublic(modifier)) modifiers.add("public");
    if (ModifierSet.isStatic(modifier)) modifiers.add("static");
    if (ModifierSet.isStrictfp(modifier)) modifiers.add("strictfp");
    if (ModifierSet.isSynchronized(modifier)) modifiers.add("synchronized");
    if (ModifierSet.isTransient(modifier)) modifiers.add("transient");
    if (ModifierSet.isVolatile(modifier)) modifiers.add("volatile");
    return modifiers;
  }
}
