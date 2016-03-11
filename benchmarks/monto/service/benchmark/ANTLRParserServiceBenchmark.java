package monto.service.benchmark;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import org.zeromq.ZContext;

import monto.service.ZMQConfiguration;
import monto.service.java8.ANTLRJavaParser;
import monto.service.request.Request;
import monto.service.source.SourceMessage;
import monto.service.types.Languages;
import monto.service.types.LongKey;
import monto.service.types.Source;

public class ANTLRParserServiceBenchmark extends Benchmark {
	private ANTLRJavaParser parser;
	private LongKey id = new LongKey(0);
	private ZContext context;
	
	@Override
	protected void setup() {
		context = new ZContext(1);
		ZMQConfiguration zmqConfig = new ZMQConfiguration(
        		context,
        		"tcp://*",
        		"tcp://*:5004",
        		"tcp://*:5007",
        		8080);
		parser = new ANTLRJavaParser(zmqConfig);
	}
	
	@Override
	protected void tearDown() throws Exception {
		context.destroy();
	}

	@Override
	protected void measure(Source source, String contents) {
		Request request = new Request(source, Arrays.asList(new SourceMessage(id, source, Languages.JAVA, contents)));
		id = id.freshId();
		try {
			parser.onRequest(request);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static void main(String[] args) throws Exception {
		Path corpus = Paths.get(System.getProperty("corpus.location"));
		Path csvOutput = Paths.get(System.getProperty("csv.output"));
		ANTLRParserServiceBenchmark bench = new ANTLRParserServiceBenchmark();
		bench.runBenchmark(corpus, csvOutput, 100, 10);
	}

}
