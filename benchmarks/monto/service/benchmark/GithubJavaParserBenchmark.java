package monto.service.benchmark;

import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseException;

import monto.service.types.Source;

public class GithubJavaParserBenchmark extends Benchmark {
	
	@Override
	protected void setup() {
	}

	@Override
	protected void measure(Source source, String contents) throws ParseException {
		JavaParser.parse(new StringReader(contents),true);
	}

	public static void main(String[] args) throws Exception {
		Path corpus = Paths.get(System.getProperty("corpus.location"));
		Path csvOutput = Paths.get(System.getProperty("csv.output"));
		GithubJavaParserBenchmark bench = new GithubJavaParserBenchmark();
		bench.runBenchmark(corpus,csvOutput, 100, 10);
	}
}
