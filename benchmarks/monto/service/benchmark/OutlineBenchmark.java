package monto.service.benchmark;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import org.zeromq.ZContext;

import monto.service.ZMQConfiguration;
import monto.service.java8.GithubJavaParser;
import monto.service.java8.JavaOutliner;
import monto.service.product.ProductMessage;
import monto.service.request.Request;
import monto.service.source.SourceMessage;
import monto.service.types.Languages;
import monto.service.types.LongKey;
import monto.service.types.Source;

public class OutlineBenchmark extends Benchmark {

	private GithubJavaParser parser;
	private JavaOutliner outliner;
	private ZContext context;
	private LongKey id = new LongKey(0);
	private ProductMessage parsingProduct;
	
	@Override
	protected void setup() {
		context = new ZContext(1);
		ZMQConfiguration zmqConfig = new ZMQConfiguration(
        		context,
        		"tcp://*",
        		"tcp://*:5004",
        		"tcp://*:5007",
        		8080);
		parser = new GithubJavaParser(zmqConfig);
		outliner = new JavaOutliner(zmqConfig);
	}

	protected void premeasure(Source source, String contents) throws Exception {
		Request request = new Request(source, Arrays.asList(new SourceMessage(id, source, Languages.JAVA, contents)));
		id = id.freshId();
		parsingProduct = parser.onRequest(request);
	}
	
	@Override
	protected void measure(Source source, String contents) throws Exception {
		Request request = new Request(source, Arrays.asList(new SourceMessage(id, source, Languages.JAVA, contents), parsingProduct));
		outliner.onRequest(request);
	}

	public static void main(String[] args) throws Exception {
		Path corpus = Paths.get(System.getProperty("corpus.location"));
		Path csvOutput = Paths.get(System.getProperty("csv.output"));
		OutlineBenchmark bench = new OutlineBenchmark();
		bench.runBenchmark(corpus,csvOutput, 100, 10);
	}
}
