package monto.service.benchmark;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import monto.ide.IDESource;
import monto.ide.Sink;
import monto.service.java8.JavaServices;
import monto.service.product.ProductMessage;
import monto.service.source.SourceMessage;
import monto.service.types.Languages;
import monto.service.types.LongKey;
import monto.service.types.ServiceId;
import monto.service.types.Source;

import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Context;

public class IntegrationBenchmark extends Benchmark {

    private final Path brokerPath;
    private final Path servicesPath;
    private final List<String> modes;
    private ServiceId serviceId;
    private Process broker, services;
    private LongKey id = new LongKey(0);
    private Context context;
    private IDESource source;
    private Sink sink;

    public IntegrationBenchmark(Path brokerPath, Path servicesPath, ServiceId serviceId, String... modes) {
        this.brokerPath = brokerPath;
        this.servicesPath = servicesPath;
        this.serviceId = serviceId;
        this.modes = Arrays.asList(modes);
        this.fileType = "java";
        this.context = ZMQ.context(1);
    }

    @Override
    protected synchronized void setup() throws Exception {
        System.out.printf("startup broker: %s\n", brokerPath);
        broker = new ProcessBuilder(
                brokerPath.toString(),
                "--source", "tcp://*:5000",
                "--sink", "tcp://*:5001",
                "--registration", "tcp://*:5002",
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
        command.add("tcp://*:5002");
        command.add("-resources");
        command.add("8080");
        services = new ProcessBuilder(command)
                .redirectOutput(new File("services.stdout"))
               .redirectError(new File("services.stderr"))
                .start();
        Thread.sleep(1000);
        System.out.println("setup connection");
        source = new IDESource(context, "tcp://localhost:5000");
        source.connect();
        sink = new Sink(context, "tcp://localhost:5001");
        sink.connect();
        Thread.sleep(1000);
        System.out.println("ready");
    }

    @Override
    protected synchronized void tearDown() throws Exception {
        System.out.println("Tear Down");
        try {
            services.destroy();
            services.waitFor();
            broker.destroy();
            broker.waitFor();
            source.close();
            sink.close();
//            context.close();
        } catch (Throwable e) {
            System.err.println(e);
        }
    }

    @Override
    protected long measure(Source src, String contents) throws Exception {
        SourceMessage srcMsg = new SourceMessage(id, src, Languages.JAVA, contents);
        id = id.freshId();
        source.sendSource(srcMsg);
        while(true) {
	        ProductMessage prod = sink.<ProductMessage,RuntimeException>receive(
	        		p -> p,
	        		disc -> { throw new RuntimeException("Unexpected discovery response"); });
	        if(prod.getServiceId().equals(serviceId))
	        	return prod.getTime();
        }
    }

    public static void main(String[] args) throws Exception {
        Path corpus = Paths.get(System.getProperty("corpus.location"));
        Path csvOutputDir = Paths.get(System.getProperty("csv.output.directory"));
        Path brokerPath = Paths.get(System.getProperty("broker"));
        Path servicesJar = Paths.get(System.getProperty("services.jar"));
        for (ServiceId service : Arrays.asList(JavaServices.JAVA_HIGHLIGHTER, JavaServices.JAVA_JAVACC_PARSER, JavaServices.JAVA_OUTLINER)) {
            Path csvOutput = csvOutputDir.resolve(service.toString() + ".csv");
            IntegrationBenchmark bench = new IntegrationBenchmark(brokerPath, servicesJar, service, "-highlighter", "-javaccparser", "-outline");
            bench.runBenchmark(corpus, csvOutput, 10, 20, 3);
        }
    }
}
