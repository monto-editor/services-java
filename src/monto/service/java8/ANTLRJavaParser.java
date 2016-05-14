package monto.service.java8;

import monto.service.MontoService;
import monto.service.ZMQConfiguration;
import monto.service.ast.ASTNode;
import monto.service.ast.ASTs;
import monto.service.gson.GsonMonto;
import monto.service.java8.antlr.Java8Lexer;
import monto.service.java8.antlr.Java8Parser;
import monto.service.product.ProductMessage;
import monto.service.product.Products;
import monto.service.registration.SourceDependency;
import monto.service.request.Request;
import monto.service.source.SourceMessage;
import monto.service.types.Languages;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class ANTLRJavaParser extends MontoService {

    Java8Lexer lexer = new Java8Lexer(new ANTLRInputStream());
    Java8Parser parser = new Java8Parser(new CommonTokenStream(lexer));

    public ANTLRJavaParser(ZMQConfiguration zmqConfig) {
        super(zmqConfig,
                JavaServices.JAVA_ANTLR_PARSER,
                "ANTLR Parser",
                "A parser that produces an AST for Java using ANTLR",
                Languages.JAVA,
                Products.AST,
                options(),
                dependencies(
                        new SourceDependency(Languages.JAVA)
                ));
    }

    @Override
    public ProductMessage onRequest(Request request) throws IOException {
        SourceMessage version = request.getSourceMessage()
                .orElseThrow(() -> new IllegalArgumentException("No version message in request"));
        lexer.reset();
        parser.reset();
        lexer.setInputStream(new ANTLRInputStream(version.getContents()));
        parser.setTokenStream(new CommonTokenStream(lexer));
        ParserRuleContext root = parser.compilationUnit();
        ParseTreeWalker walker = new ParseTreeWalker();

        Converter converter = new Converter();
        walker.walk(converter, root);

        return productMessage(
                version.getId(),
                version.getSource(),
                Products.AST,
                Languages.JAVA,
                GsonMonto.toJsonTree(converter.getRoot()) // TODO Gson deserialization of this not tested
        );
    }

    private static class Converter implements ParseTreeListener {

        private Deque<ASTNode> nodes = new ArrayDeque<>();

        @Override
        public void enterEveryRule(ParserRuleContext context) {
            if (context.getChildCount() > 0) {
                String name = Java8Parser.ruleNames[context.getRuleIndex()];
                List<ASTNode> children = new ArrayList<>(context.getChildCount());
                Interval interval = context.getSourceInterval();

                ASTNode node = new ASTNode(name, interval.a, interval.length(), children);
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
//
//        @Override
//        public void visitErrorNode(ErrorNode err) {
//            org.antlr.v4.runtime.Token symbol = err.getSymbol();
//            addChild(new NonTerminal("error", new Terminal(symbol.getStartIndex(), symbol.getStopIndex() - symbol.getStartIndex() + 1)));
//        }
//
//        @Override
//        public void visitTerminal(TerminalNode terminal) {
//            org.antlr.v4.runtime.Token symbol = terminal.getSymbol();
//            Terminal token = new Terminal(symbol.getStartIndex(), symbol.getStopIndex() - symbol.getStartIndex() + 1);
//            if (nodes.size() == 0)
//                nodes.push(token);
//            else
//                addChild(token);
//        }

        private void addChild(ASTNode node) {
            if (nodes.isEmpty())
                nodes.add(node);
            else
                nodes.peek().addChild(node);
        }

        public ASTNode getRoot() {
            return nodes.peek();
        }

        @Override
        public void visitErrorNode(ErrorNode arg0) {
        }

        @Override
        public void visitTerminal(TerminalNode arg0) {
        }
    }
}
