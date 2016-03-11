package monto.service.java8;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseException;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.ModifierSet;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;

import monto.service.MontoService;
import monto.service.ZMQConfiguration;
import monto.service.product.ProductMessage;
import monto.service.product.Products;
import monto.service.region.IRegion;
import monto.service.region.Region;
import monto.service.registration.SourceDependency;
import monto.service.request.Request;
import monto.service.source.SourceMessage;
import monto.service.types.Languages;

public class GithubJavaParser extends MontoService {

    public GithubJavaParser(ZMQConfiguration zmqConfig) {
        super(zmqConfig,
        		JavaServices.JAVA_PARSER,
        		"Parser",
        		"A parser that produces an AST for Java using ANTLR",
        		Languages.JAVA,
        		Products.AST,
        		options(),
        		dependencies(
        				new SourceDependency(Languages.JAVA)
        		));
    }

	@Override
    public ProductMessage onRequest(Request request) throws IOException, ParseException {
    	SourceMessage sourceMessage = request.getSourceMessage()
    			.orElseThrow(() -> new IllegalArgumentException("No version message in request"));

    	Node root = JavaParser.parse(new StringReader(sourceMessage.getContent()), true);
    	
    	JSONObject encoding =encode(offsets(sourceMessage.getContent()),root);    	
        return productMessage(
                sourceMessage.getId(),
                sourceMessage.getSource(),
                Products.AST,
                Languages.JAVA,
                encoding);
    }
	
	int[] offsets(String document) {
		String lines[] = document.split("\\r?\\n");
		int[] offsets = new int[lines.length+1];
		int offset = 0;
		offsets[0] = offset;
		int i = 1;
		for(String line : lines) {
			offset += line.length() + 1;
			offsets[i] = offset;
			i++;
		}
		return offsets;
	}
	
	@SuppressWarnings("unchecked")
	public static JSONObject astNode(String name, JSONArray children, IRegion region) {
		JSONObject obj = new JSONObject();
		obj.put("name", name);
		obj.put("offset", region.getStartOffset());
		obj.put("length", region.getLength());
		obj.put("children", children);
		return obj;
	}
	
	@SuppressWarnings("unchecked")
	public static JSONObject encode(int[] offsets, Node node) {
		String name = node.getClass().getSimpleName();
		IRegion region = region(offsets, node);
		JSONObject obj = new JSONObject();
		JSONArray children = new JSONArray();
		for(Node child: node.getChildrenNodes())
			children.add(encode(offsets,child));
		
		// Not all parts of the AST appear in node.getChildrenNodes, so we have to
		// include these parts manually.
		switch (name) {
		case "ClassOrInterfaceDeclaration": {
			ClassOrInterfaceDeclaration decl = (ClassOrInterfaceDeclaration) node;
			if (decl.isInterface())
				name = "InterfaceDeclaration";
			else
				name = "ClassDeclaration";
			children.add(0, makeModifierObject(offsets, decl.getModifiers(), decl));
			children.add(1, makeIdentifierObject(offsets, decl.getNameExpr()));
		} break;
		case "EmptyTypeDeclaration":
		case "EnumDeclaration": {
			TypeDeclaration enumN = (TypeDeclaration) node;
			children.add(0, makeModifierObject(offsets, enumN.getModifiers(), enumN));
			children.add(1, makeIdentifierObject(offsets, enumN.getNameExpr()));
		} break;
		case "MethodDeclaration": {
			MethodDeclaration decl = (MethodDeclaration) node;
			children.add(0, makeModifierObject(offsets, decl.getModifiers(), decl));
			children.add(2, makeIdentifierObject(offsets, decl.getNameExpr()));
		} break;
		case "FieldDeclaration": {
			FieldDeclaration decl = (FieldDeclaration) node;
			children.add(0, makeModifierObject(offsets, decl.getModifiers(), decl));
		} break;
		case "ConstructorDeclaration": {
			ConstructorDeclaration decl = (ConstructorDeclaration) node;
			children.add(0, makeModifierObject(offsets, decl.getModifiers(), decl));
			children.add(1, makeIdentifierObject(offsets, decl.getNameExpr()));
			JSONArray params = new JSONArray();
			for(Parameter param : decl.getParameters())
				params.add(encode(offsets,param));
			children.add(2, astNode("Parameters",params,region(offsets,decl)));
		} break;
		case "EnumConstantDeclaration": {
			EnumConstantDeclaration decl = (EnumConstantDeclaration) node;
			JSONObject idObj = astNode("Identifier", new JSONArray(), region(offsets, decl.getBeginLine(), decl.getBeginColumn(), decl.getEndLine(), decl.getEndColumn() + decl.getName().length()));
			children.add(0, idObj);
		} break;
		default:
			break;
		}
		
		obj.put("name", name);
		obj.put("offset", region.getStartOffset());
		obj.put("length", region.getLength());
		obj.put("children", children);
		return obj;
	}

	private static JSONObject makeIdentifierObject(int[] offsets, Node name) {
		return astNode("Identifier", new JSONArray(), region(offsets, name));
	}

	@SuppressWarnings("unchecked")
	private static JSONObject makeModifierObject(int[] offsets, int modifiers, Node n) {
		IRegion modRegion = region(offsets, n.getBeginLine(), n.getBeginColumn(), n.getBeginLine(), n.getBeginColumn());
		List<String> mods = modifiers(modifiers);
		JSONArray modArray = new JSONArray();
		for (String mod : mods) {
			modArray.add(astNode(mod, new JSONArray(), modRegion));
		}
		return astNode("Modifiers", modArray, modRegion);
	}
	
	static Region region(int[] offsets, Node node) {
		return region(offsets,node.getBeginLine(), node.getBeginColumn(), node.getEndLine(), node.getEndColumn());
	}
	
	static Region region(int[] offsets, int startLine, int startColumn, int endLine, int endColumn) {
		try {
			int startOffset = offsets[startLine - 1] + startColumn - 1;
			int endOffset;
			if(endLine - 1 >= offsets.length)
				endOffset = offsets[offsets.length-1];
			else
				endOffset = offsets[endLine - 1] + endColumn - 1;
			return new Region(startOffset, endOffset-startOffset+1);
		} catch (ArrayIndexOutOfBoundsException e) {
			throw new RuntimeException(String.format("%d, %s",offsets.length,Arrays.toString(offsets)),e);
		}
	}

	private static List<String> modifiers(int modifier) {
		List<String> modifiers = new ArrayList<>();
		if(ModifierSet.isAbstract(modifier))
			modifiers.add("abstract");
		if(ModifierSet.isFinal(modifier))
			modifiers.add("final");
		if(ModifierSet.isNative(modifier))
			modifiers.add("native");
		if(ModifierSet.isPrivate(modifier))
			modifiers.add("private");
		if(ModifierSet.isProtected(modifier))
			modifiers.add("protected");
		if(ModifierSet.isPublic(modifier))
			modifiers.add("public");
		if(ModifierSet.isStatic(modifier))
			modifiers.add("static");
		if(ModifierSet.isStrictfp(modifier))
			modifiers.add("strictfp");				
		if(ModifierSet.isSynchronized(modifier))
			modifiers.add("synchronized");				
		if(ModifierSet.isTransient(modifier))
			modifiers.add("transient");				
		if(ModifierSet.isVolatile(modifier))
			modifiers.add("volatile");
		return modifiers;
	}
}
