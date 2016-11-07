package monto.service.java8;

import java.util.ArrayList;
import java.util.List;
import monto.service.MontoService;
import monto.service.ZMQConfiguration;
import monto.service.resources.ResourceServer;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.zeromq.ZContext;

public class Main {

  private static ResourceServer resourceServer;

  public static void main(String[] args) throws Exception {
    ZContext context = new ZContext(1);
    List<MontoService> services = new ArrayList<>();

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread() {
              @Override
              public void run() {
                System.out.println("terminating...");
                try {
                  for (MontoService service : services) {
                    service.stop();
                  }
                  resourceServer.stop();
                } catch (Exception e) {
                  e.printStackTrace();
                }
                context.destroy();
                System.out.println("everything terminated, good bye");
              }
            });

    Options options = new Options();
    options
        .addOption("highlighter", false, "enable Java syntax highlighter")
        .addOption("antlrparser", false, "enable Java ANTLR parser")
        .addOption("javaccparser", false, "enable JavaCC parser")
        .addOption("outliner", false, "enable Java outliner")
        .addOption("identifierfinder", false, "enable Java identifier finder")
        .addOption("codecompletioner", false, "enable Java code completioner")
        .addOption("runner", false, "enable Java runtime service")
        .addOption("debugger", false, "enable Java debugger service")
        .addOption("logicalnameextractor", false, "enable logical name extractor")
        .addOption("address", true, "address of services")
        .addOption("registration", true, "address of broker registration")
        .addOption("resources", true, "port for http resource server")
        .addOption("debug", false, "enable debugging output");

    CommandLineParser parser = new DefaultParser();
    CommandLine cmd = parser.parse(options, args);

    ZMQConfiguration zmqConfig =
        new ZMQConfiguration(
            context,
            cmd.getOptionValue("address"),
            cmd.getOptionValue("registration"),
            Integer.parseInt(cmd.getOptionValue("resources")));

    resourceServer =
        new ResourceServer(
            Main.class.getResource("/icons").toExternalForm(), zmqConfig.getResourcePort());
    try {
      resourceServer.start();
    } catch (Exception e) {
      e.printStackTrace();
    }

    if (cmd.hasOption("highlighter")) {
      services.add(new JavaHighlighter(zmqConfig));
    }
    if (cmd.hasOption("javaccparser")) {
      services.add(new JavaJavaCCParser(zmqConfig));
    }
    if (cmd.hasOption("antlrparser")) {
      services.add(new ANTLRJavaParser(zmqConfig));
    }
    if (cmd.hasOption("outliner")) {
      services.add(new JavaOutliner(zmqConfig));
    }
    if (cmd.hasOption("identifierfinder")) {
      services.add(new JavaIdentifierFinder(zmqConfig));
    }
    if (cmd.hasOption("codecompletioner")) {
      services.add(new JavaCodeCompletioner(zmqConfig));
    }
    if (cmd.hasOption("logicalnameextractor")) {
      services.add(new JavaLogicalNameExtractor(zmqConfig));
    }
    if (cmd.hasOption("runner")) {
      services.add(new JavaRunner(zmqConfig));
    }
    if (cmd.hasOption("debugger")) {
      services.add(new JavaDebugger(zmqConfig));
    }
    if (cmd.hasOption("debug")) {
      services.forEach(MontoService::enableDebugging);
    }

    for (MontoService service : services) {
      try {
        service.start();
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }
}
