package monto.service.java8;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import monto.service.java8.antlr.Java8Lexer;
import monto.service.java8.antlr.Java8Parser;
import monto.service.MontoService;
import monto.service.ast.AST;
import monto.service.ast.ASTs;
import monto.service.ast.NonTerminal;
import monto.service.ast.Terminal;
import monto.service.message.*;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.zeromq.ZMQ;

public class JavaParser extends MontoService {

    Java8Lexer lexer = new Java8Lexer(new ANTLRInputStream());
    CommonTokenStream tokens = new CommonTokenStream(lexer);
    Java8Parser parser = new Java8Parser(tokens);

    public JavaParser(String address, ZMQ.Context context) {
        super(address, context);
    }

    @Override
    public ProductMessage onMessage(List<Message> messages) throws IOException {
        VersionMessage version = Messages.getVersionMessage(messages);
        lexer.setInputStream(new ANTLRInputStream(version.getContent().getReader()));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        parser.setTokenStream(tokens);
        ParserRuleContext root = parser.compilationUnit();
        ParseTreeWalker walker = new ParseTreeWalker();

        Converter converter = new Converter();
        walker.walk(converter, root);

        Contents content = ASTs.encode(converter.getRoot());

        return new ProductMessage(
                version.getVersionId(),
                new LongKey(1),
                version.getSource(),
                JavaServices.AST,
                JavaServices.JSON,
                content);
    }

    private static class Converter implements ParseTreeListener {

        private Deque<AST> nodes = new ArrayDeque<>();

        @Override
        public void enterEveryRule(ParserRuleContext context) {
            if (context.getChildCount() > 0) {
                String name = Java8Parser.ruleNames[context.getRuleIndex()];
                List<AST> childs = new ArrayList<>(context.getChildCount());
                NonTerminal node = new NonTerminal(name, childs);
                addChild(node);
                nodes.push(node);
            }
        }

        @Override
        public void exitEveryRule(ParserRuleContext node) {
            // Keep the last node to return
            if (nodes.size() > 1)
                nodes.pop();
        }

        @Override
        public void visitErrorNode(ErrorNode err) {
            org.antlr.v4.runtime.Token symbol = err.getSymbol();
            addChild(new NonTerminal("error", new Terminal(symbol.getStartIndex(), symbol.getStopIndex() - symbol.getStartIndex() + 1)));
        }

        @Override
        public void visitTerminal(TerminalNode terminal) {
            org.antlr.v4.runtime.Token symbol = terminal.getSymbol();
            Terminal token = new Terminal(symbol.getStartIndex(), symbol.getStopIndex() - symbol.getStartIndex() + 1);
            if (nodes.size() == 0)
                nodes.push(token);
            else
                addChild(token);
        }

        private void addChild(AST node) {
            if (!nodes.isEmpty() && nodes.peek() instanceof NonTerminal)
                ((NonTerminal) nodes.peek()).addChild(node);
        }

        public AST getRoot() {
            return nodes.peek();
        }
    }

    /**
     * Checks if the given AST is complete, i.e. contains no error nodes.
     * The complexity of this method is O(n) where n is the number of elements
     * in the AST.
     */
//	public static boolean isComplete(AST node) {
//		Complete isComplete = new Complete();
//		node.accept(isComplete);
//		return isComplete.complete;
//	}
//	
//	private static class Complete implements ASTVisitor {
//
//		public boolean complete = true;
//
//		@Override
//		public void visit(NonTerminal node) {
//			if(node.getName().equals("error"))
//				complete = false;
//			for(AST child : node.getChildren())
//				child.accept(this);
//		}
//
//		@Override
//		public void visit(Terminal token) {}
//		
//	}
}
