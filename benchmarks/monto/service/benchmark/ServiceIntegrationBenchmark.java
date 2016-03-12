package monto.service.benchmark;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Context;

import monto.connection.Publish;
import monto.connection.PublishSource;
import monto.connection.Sink;
import monto.connection.Subscribe;
import monto.service.java8.JavaServices;
import monto.service.source.SourceMessage;
import monto.service.types.Languages;
import monto.service.types.LongKey;
import monto.service.types.ServiceID;
import monto.service.types.Source;

public class ServiceIntegrationBenchmark extends Benchmark {
	
	private final Path brokerPath;
	private final Path servicesPath;
	private final List<String> modes;
	private ServiceID serviceID;
	private Process broker, services;
	private LongKey id = new LongKey(0);
	private Context context;
	private PublishSource publish;
	private Subscribe subscribe;
	private Sink sink;
	
	public ServiceIntegrationBenchmark(Path brokerPath, Path servicesPath, ServiceID serviceID, String ... modes) {
		this.brokerPath = brokerPath;
		this.servicesPath = servicesPath;
		this.serviceID = serviceID;
		this.modes = Arrays.asList(modes);
	}

	@Override
	protected void setup() throws Exception {
 		System.out.printf("startup broker: %s\n", brokerPath);
		broker = new ProcessBuilder(
				brokerPath.toString(),
				"--source", "tcp://*:5000",
				"--sink", "tcp://*:5001",
				"--registration", "tcp://*:5004",
				"--discovery", "tcp://*:5005",
				"--config", "tcp://*:5007",
				"--dyndep", "tcp://*:5009",
				"--topic", "[ServiceID]",
				"--servicesFrom", "Port 5010",
				"--servicesTo", "Port 5025")
				.redirectOutput(new File("broker.stdout"))
				.redirectError(new File("broker.stderr"))
				.start();
		Thread.sleep(1000);
		System.out.printf("startup services: %s\n", servicesPath);
		List<String> command = new ArrayList<>();
		command.add("java");
		command.add("-jar");
		command.add(servicesPath.toString());
		command.addAll(modes);
		command.add("-address");
		command.add("tcp://*");
		command.add("-registration");
		command.add("tcp://*:5004");
		command.add("-configuration");
		command.add("tcp://*:5007");
		command.add("-resources");
		command.add("8080");
		command.add("-dyndep");
		command.add("tcp://*:5009");
		services = new ProcessBuilder(command)
				.redirectOutput(new File("services.stdout"))
				.redirectError(new File("services.stderr"))
				.start();
		Thread.sleep(1000);
		System.out.println("setup connection");
		context = ZMQ.context(1);
		publish = new PublishSource(new Publish(context, "tcp://localhost:5000"));
		publish.connect();
		subscribe = new Subscribe(context, "tcp://localhost:5001");
//		subscribe.setReceivedTimeout(2000);
		sink = new Sink(subscribe, serviceID.toString());
		sink.connect();
		Thread.sleep(1000);
		System.out.println("ready");
	}

	@Override
	protected void tearDown() throws Exception {
		System.out.println("Tear Down");
		services.destroy();
		services.waitFor();
		broker.destroy();
		broker.waitFor();
		publish.close();
		sink.close();
		context.close();
	}
	
	@Override
	protected void measure(Source source, String contents) {
		SourceMessage srcMsg = new SourceMessage(id, source, Languages.JAVA, contents);
		id = id.freshId();
		publish.sendMessage(srcMsg);
		sink.receiveMessage().orElseThrow(() -> new RuntimeException("did not receive product"));
	}
	
	public static void main(String[] args) throws Exception {
		Path corpus = Paths.get(System.getProperty("corpus.location"));
		Path csvOutputDir = Paths.get(System.getProperty("csv.output.directory"));
		Path brokerPath = Paths.get(System.getProperty("broker"));
		Path servicesJar = Paths.get(System.getProperty("services.jar"));
		for(ServiceID service : Arrays.asList(JavaServices.JAVA_TOKENIZER, JavaServices.JAVA_JAVACC_PARSER, JavaServices.JAVA_OUTLINER)) {
			Path csvOutput = csvOutputDir.resolve(service.toString()+".csv");
			ServiceIntegrationBenchmark bench = new ServiceIntegrationBenchmark(brokerPath, servicesJar, service, "-tokenizer", "-javaccparser", "-outline");
			bench.runBenchmark(corpus, csvOutput, 100, 20);
		}
	}
}
