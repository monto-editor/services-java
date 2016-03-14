package monto.service.java8;

import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

import org.json.simple.JSONObject;

import monto.service.MontoService;
import monto.service.ZMQConfiguration;
import monto.service.ast.ASTNode;
import monto.service.ast.ASTNodeVisitor;
import monto.service.ast.ASTs;
import monto.service.outline.Outline;
import monto.service.outline.Outlines;
import monto.service.product.ProductMessage;
import monto.service.product.Products;
import monto.service.region.IRegion;
import monto.service.registration.ProductDependency;
import monto.service.registration.SourceDependency;
import monto.service.request.Request;
import monto.service.source.SourceMessage;
import monto.service.types.Languages;
import monto.service.types.ParseException;

public class JavaOutliner extends MontoService {

	public JavaOutliner(ZMQConfiguration zmqConfig) {
    	super(zmqConfig,
    			JavaServices.JAVA_OUTLINER,
    			"Outline",
    			"An outline service for Java",
    			Languages.JAVA,
    			Products.OUTLINE,
    			options(),
    			dependencies(
    					new SourceDependency(Languages.JAVA),
    					new ProductDependency(JavaServices.JAVA_JAVACC_PARSER, Products.AST, Languages.JAVA)
    			));
    }

	@Override
    public ProductMessage onRequest(Request request) throws ParseException {
    	SourceMessage version = request.getSourceMessage()
    			.orElseThrow(() -> new IllegalArgumentException("No version message in request"));
        ProductMessage ast = request.getProductMessage(Products.AST, Languages.JAVA)
        		.orElseThrow(() -> new IllegalArgumentException("No AST message in request"));

        ASTNode root = (ASTNode) ASTs.decodeASTNode((JSONObject) ast.getContents());

        OutlineTrimmer trimmer = new OutlineTrimmer(version);
        try {
        	root.accept(trimmer);
        } catch(Exception e) {
        	throw new RuntimeException(String.format("error while trimming the following AST:\n%s\nLength: %d",root.toString(),version.getContent().length()),e);
        }

        return productMessage(
                version.getId(),
                version.getSource(),
                Products.OUTLINE,
                Languages.JAVA,
                Outlines.encode(trimmer.getConverted()));
    }

    /**
     * Traverses the AST and removes unneeded information.
     */
    private class OutlineTrimmer implements ASTNodeVisitor {

        private Deque<Outline> converted = new ArrayDeque<>();
        private String document;

        public OutlineTrimmer(SourceMessage version) {
			this.document = version.getContent();
		}

		public Outline getConverted() {
            return converted.getFirst();
        }
		
		@Override
        public void visit(ASTNode node) {
            switch (node.getName()) {
                case "CompilationUnit": {
                    converted.push(new Outline("compilation-unit", node, null));
                    node.getChildren().forEach(child -> child.accept(this));
                    // compilation unit doesn't get poped from the stack
                    // to be available as a return value.
                }
                break;

                case "PackageDeclaration": {
                	ASTNode packageIdentifier = node.getChild(0);
                    addChild(new Outline(extract(packageIdentifier), packageIdentifier, getResource("package.png")));
                }
                break;

                case "ClassDeclaration":
                case "InterfaceDeclaration": {
	                List<String> modifiers = new ArrayList<>();
	            	for(ASTNode modifier: node.getChild(0).getChildren())
	            		modifiers.add(modifier.getName());
	            	ASTNode className = node.getChild(1);
	            	URL classIcon = getIcon(modifiers, "class");
	                Outline klass = new Outline(extract(className), className, classIcon);
	            	addChild(klass);
	                converted.push(klass);
	                for(int i = 2; i<node.getChildren().size(); i++)
	                	node.getChild(i).accept(this);
	                converted.pop();
                }
                break;
                
                case "ConstructorDeclaration": {
                	List<String> modifiers = new ArrayList<>();
                	for(ASTNode modifier : node.getChild(0).getChildren())
                		modifiers.add(modifier.getName());
                	
                	ASTNode constructorName = node.getChild(1);
                	
                	List<FormalParameter> parameters = new ArrayList<>();
                	for(ASTNode parameter: node.getChild(2).getChildren())
                		parameters.add(new FormalParameter(parameter.getChild(1), parameter.getChild(0)));
                	
                	URL constructorIcon = getIcon(modifiers, "constructor");
                	String paramterList = parameters.stream()
                			.map(p -> extract(p.getType()))
                			.collect(Collectors.joining(", "));
                	String cname = String.format("%s(%s)", extract(constructorName), paramterList);
                	addChild(new Outline(cname,constructorName,constructorIcon));
                }
                break;

                case "FieldDeclaration": {
                	List<String> modifiers = new ArrayList<>();
                	for(ASTNode modifier : node.getChild(0).getChildren())
                		modifiers.add(modifier.getName());
                	ASTNode type = node.getChild(1);
                	for(ASTNode variable : node.getChild(2).getChildren()) {
                		if(! variable.getName().equals("VariableDeclaratorId"))
                			continue;
                		URL fieldIcon = getIcon(modifiers, "field");
                		String name = String.format("%s : %s", extract(variable), extract(type));
                		addChild(new Outline(name, variable, fieldIcon));
                	}
                }
                break;

                case "MethodDeclaration": {
                	List<String> modifiers = new ArrayList<>();
                	for(ASTNode modifier : node.getChild(0).getChildren())
                		modifiers.add(modifier.getName());
                	
                	ASTNode returnType = node.getChild(1);
                	
                	ASTNode methodName = node.getChild(2);
                	
                	List<FormalParameter> parameters = new ArrayList<>();
                	for(int i = 3; i < node.getChildren().size() && node.getChild(i).getName().equals("Parameter"); i++) {
                		ASTNode parameter = node.getChild(i);
                		parameters.add(new FormalParameter(parameter.getChild(1), parameter.getChild(0)));
                	}
                	
                	URL methodIcon = getIcon(modifiers,"method");
                	String methodParams = parameters.stream()
                	  .map(p -> extract(p.getType()))
                	  .collect(Collectors.joining(", "));
                	String name = String.format("%s(%s) : %s", extract(methodName), methodParams, extract(returnType));
                	addChild(new Outline(name, methodName, methodIcon));
                }
                
                default:
                    node.getChildren().forEach(child -> child.accept(this));
            }
        }

		private Visibility visibility(List<String> modifiers) {
        	for(String modifier : modifiers) {
        		switch(modifier) {
    				case "public":
    					return Visibility.PUBLIC;
    				case "private":
    					return Visibility.PRIVATE;
    				case "protected":
    					return Visibility.PROTECTED;
        		}
        	}
    		return Visibility.DEFAULT;
		}
		
		private URL getIcon(List<String> modifiers, String type) {
        	switch(visibility(modifiers)) {
        	case PUBLIC: return getResource(type+"-public.png");
        	case PRIVATE: return getResource(type+"-private.png");
        	case PROTECTED: return getResource(type+"-protected.png");
        	default: return getResource(type+"-default.png");
        	}
		}

        private void addChild(Outline o) {
			converted.peek().addChild(o);
		}
        
        private String extract(IRegion region) {
        	return region.extract(document);
        }
    }
    
    public class FormalParameter {
    	private ASTNode identifier;
		private ASTNode type;

		public FormalParameter(ASTNode identifier2, ASTNode typ) {
			this.identifier = identifier2;
    		this.type = typ;
    	}

		public ASTNode getIdentifier() {
			return identifier;
		}

		public ASTNode getType() {
			return type;
		}
    }
    
    public static enum Visibility {
    	PUBLIC, PRIVATE, PROTECTED, DEFAULT;
    }
}
