package monto.service.benchmark;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;

import monto.service.java8.antlr.Java8Lexer;
import monto.service.java8.antlr.Java8Parser;
import monto.service.types.Source;

public class ANTLRParserBenchmark extends Benchmark {

	private Java8Lexer lexer;
	private CommonTokenStream tokens;
	private Java8Parser parser;
	
	@Override
	protected void setup() {
		lexer = new Java8Lexer(new ANTLRInputStream());
		tokens = new CommonTokenStream(lexer);
		parser = new Java8Parser(tokens);
	}

	@Override
	protected void measure(Source source, String contents) {
		lexer.setInputStream(new ANTLRInputStream(contents));
		parser.setTokenStream(new CommonTokenStream(lexer));
		parser.compilationUnit();
	}

	public static void main(String[] args) throws Exception {
		Path corpus = Paths.get(System.getProperty("corpus.location"));
		Path csvOutput = Paths.get(System.getProperty("csv.output"));
		ANTLRParserBenchmark bench = new ANTLRParserBenchmark();
		bench.runBenchmark(corpus,csvOutput, 100, 10);
	}
}
