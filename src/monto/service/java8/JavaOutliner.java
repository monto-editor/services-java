package monto.service.java8;

import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import monto.service.MontoService;
import monto.service.ZMQConfiguration;
import monto.service.ast.AST;
import monto.service.ast.ASTVisitor;
import monto.service.ast.ASTs;
import monto.service.ast.NonTerminal;
import monto.service.ast.Terminal;
import monto.service.outline.Outline;
import monto.service.outline.Outlines;
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
    					new ProductDependency(JavaServices.JAVA_PARSER, Products.AST, Languages.JAVA)
    			));
    }

	@Override
    public ProductMessage onRequest(Request request) throws ParseException {
    	SourceMessage version = request.getSourceMessage()
    			.orElseThrow(() -> new IllegalArgumentException("No version message in request"));
        ProductMessage ast = request.getProductMessage(Products.AST, Languages.JAVA)
        		.orElseThrow(() -> new IllegalArgumentException("No AST message in request"));

        NonTerminal root = (NonTerminal) ASTs.decode(ast);

        OutlineTrimmer trimmer = new OutlineTrimmer(version);
        root.accept(trimmer);

        return productMessage(
                version.getId(),
                version.getSource(),
                Products.OUTLINE,
                Outlines.encode(trimmer.getConverted()));
    }

    /**
     * Traverses the AST and removes unneeded information.
     */
    private class OutlineTrimmer implements ASTVisitor {

        private Deque<Outline> converted = new ArrayDeque<>();
        private String document;

        public OutlineTrimmer(SourceMessage version) {
			this.document = version.getContent();
		}

		public Outline getConverted() {
            return converted.getFirst();
        }
		
		@Override
        public void visit(NonTerminal node) {
            switch (node.getName()) {
                case "compilationUnit": {
                    converted.push(new Outline("compilation-unit", node, null));
                    node.getChildren().forEach(child -> child.accept(this));
                    // compilation unit doesn't get poped from the stack
                    // to be available as a return value.
                }
                break;

                case "packageDeclaration": {
                	List<AST> children = node.getChildren();
                    Terminal packageIdentFirst = (Terminal) children.get(1);
                    Terminal packageIdentLast = (Terminal) children.get(children.size()-2);
                    IRegion packageIdentifier = new Region(
                    		packageIdentFirst.getStartOffset(),
                    		packageIdentLast.getEndOffset() - packageIdentFirst.getStartOffset());
                    addChild(new Outline(extract(packageIdentifier), packageIdentifier, getResource("package.png")));
                }
                break;

                case "normalClassDeclaration": {
                	List<String> modifiers = new ArrayList<>();
                	collectTerminals(node.getChild(0))
                		.forEach(t -> modifiers.add(extract(t)));
                	Terminal className = findTerminal(node.getChild(2));
                	URL classIcon = getIcon(modifiers, "class");
                    Outline klass = new Outline(extract(className), className, classIcon);
                	addChild(klass);
                    converted.push(klass);
                    node.getChild(3).accept(this);
                    converted.pop();
                }
                break;
                    
                case "constructorDeclaration": {
                	List<String> modifiers = new ArrayList<>();
                	List<FormalParameter> parameters = new ArrayList<>();
                	Terminal[] constructorName = new Terminal[1];
                	for(AST n : node.getChildren()) {
                		n.<Void>match(nonTerminal -> {
                			String name = nonTerminal.getName();
                			if(name.equals("constructorModifier"))
                				modifiers.add(extract(findTerminal(nonTerminal)));
                			if(name.equals("constructorDeclarator")) {
                				constructorName[0] = findTerminal(nonTerminal.getChild(0));
                				formalParameterList(nonTerminal.getChild(2))
                				.collect(Collectors.toCollection(() -> parameters));
                			}
                			return null;
                		},
                				terminal -> { return null; });
                	}
                	URL constructorIcon = getIcon(modifiers, "constructor");
                	String paramterList = parameters.stream()
                			.map(p -> extract(p.getType()))
                			.collect(Collectors.joining(", "));
                	String cname = String.format("%s(%s)", extract(constructorName[0]), paramterList);
                	addChild(new Outline(cname,constructorName[0],constructorIcon));
                }
                break;

                case "fieldDeclaration": {
                	List<String> modifiers = new ArrayList<>();
                	String[] type = new String[1];
                	List<Terminal> variables = new ArrayList<>();
                	for(AST n : node.getChildren()) {
                		n.<Void>match(
                			nonTerminal -> {
	                			String name = nonTerminal.getName();
	                			if(name.equals("fieldModifier"))
	                				modifiers.add(extract(nonTerminal.getChild(0)));
	                			if(name.toLowerCase().contains("type"))
	                				type[0] = extract(findTerminal(nonTerminal));
	                			if(name.equals("variableDeclaratorList")) {
	                				for(AST var : nonTerminal.getChildren())
	                					getVariableId(var).ifPresent(t->variables.add(t));
	                			}
	                			return null;
                			},
                			terminal -> { return null; });
                	}
                	for(Terminal variable : variables) {
                		URL fieldIcon = getIcon(modifiers, "field");
                		String name = String.format("%s : %s", extract(variable), type[0]);
                		addChild(new Outline(name, variable, fieldIcon));
                	}
                }
                break;

                case "methodDeclaration": {
                	List<String> modifiers = new ArrayList<>();
                	List<FormalParameter> parameters = new ArrayList<>();
                	Terminal[] returnType = new Terminal[1];
                	Terminal[] methodName = new Terminal[1];
                	for(AST n : node.getChildren()) {
                		n.<Void>match(nonTerminal -> {
                			String name = nonTerminal.getName();
                			if(name.equals("methodModifier"))
                				modifiers.add(extract(findTerminal(nonTerminal)));
                			if(name.equals("methodHeader")) {
                				returnType[0] = findTerminal(nonTerminal.getChild(0));
                				NonTerminal methodDeclarator = (NonTerminal) nonTerminal.getChild(1);
                				methodName[0] = findTerminal(methodDeclarator.getChild(0));
                				formalParameterList(methodDeclarator.getChild(2))
                					.collect(Collectors.toCollection(() -> parameters));
                			}
                			return null;
                		},
                		terminal -> { return null; });
                	}
                	URL methodIcon = getIcon(modifiers,"method");
                	String methodParams = parameters.stream()
                	  .map(p -> extract(p.getType()))
                	  .collect(Collectors.joining(", "));
                	String name = String.format("%s(%s) : %s", extract(methodName[0]), methodParams, extract(returnType[0]));
                	addChild(new Outline(name, methodName[0], methodIcon));
                }
                
                default:
                    node.getChildren().forEach(child -> child.accept(this));
            }
        }

		private Stream<FormalParameter> formalParameterList(AST parameterList) {
			return collectNonTerminals(parameterList, p -> p.getName().equals("formalParameter"))
				.map(param -> {
					Terminal typ = findTerminal(param.getChild(0));
					Terminal identifier = findTerminal(param.getChild(1));
					return new FormalParameter(identifier, typ);
				});
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

        private Optional<Terminal> getVariableId(AST var) {
        	try {
        		NonTerminal variableDeclarator = (NonTerminal) var;
        		if(!variableDeclarator.getName().equals("variableDeclarator"))
        			return Optional.empty();
        		NonTerminal variableDeclaratorId = (NonTerminal) variableDeclarator.getChild(0);
        		Terminal variableIdentifier = (Terminal) variableDeclaratorId.getChild(0);
        		return Optional.of(variableIdentifier);
        	} catch (Exception e) {
        		return Optional.empty();
        	}
		}

		@Override
        public void visit(Terminal token) {

        }

        private void addChild(Outline o) {
			converted.peek().addChild(o);
		}
        
        private String extract(IRegion region) {
        	return region.extract(document);
        }
    }

    private static Terminal findTerminal(AST ast) {
    	return ast.match(
    		nonTerminal -> findTerminal(nonTerminal.getChild(0)),
    		terminal -> terminal
    	);
    }
    
    private static Stream<Terminal> collectTerminals(AST node) {
    	return node.match(
    			nonTerminal -> nonTerminal.getChildren().stream().flatMap(n -> collectTerminals(n)),
    			terminal -> Stream.of(terminal)
    			);
    }
    
    private static Stream<NonTerminal> collectNonTerminals(AST node, Predicate<NonTerminal> p) {
    	return node.match(
    			nonTerminal ->
    				p.test(nonTerminal)
    				  ? Stream.of(nonTerminal)
    				  : nonTerminal.getChildren().stream().flatMap(n -> collectNonTerminals(n,p)),
    			terminal -> Stream.of());
    }
    
    public class FormalParameter {
    	private Terminal identifier;
		private Terminal type;

		public FormalParameter(Terminal identifier, Terminal type) {
			this.identifier = identifier;
    		this.type = type;
    	}

		public Terminal getIdentifier() {
			return identifier;
		}

		public Terminal getType() {
			return type;
		}
    }
    
    public static enum Visibility {
    	PUBLIC, PRIVATE, PROTECTED, DEFAULT;
    }
}
