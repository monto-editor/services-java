package monto.service.java8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import monto.service.MontoService;
import monto.service.ZMQConfiguration;
import monto.service.command.CommandMessage;
import monto.service.dependency.DynamicDependency;
import monto.service.dependency.RegisterCommandMessageDependencies;
import monto.service.java8.launching.CompileUtils;
import monto.service.java8.launching.InputStreamProductThread;
import monto.service.java8.launching.ProcessTerminationThread;
import monto.service.launching.LaunchConfiguration;
import monto.service.launching.StreamOutput;
import monto.service.launching.TerminateProcess;
import monto.service.product.Products;
import monto.service.registration.ProductDescription;
import monto.service.source.SourceMessage;
import monto.service.types.Languages;

public class JavaRunner extends MontoService {
  private Map<Integer, ProcessTerminationThread> processThreadMap;

  public JavaRunner(ZMQConfiguration zmqConfig) {
    super(
        zmqConfig,
        JavaServices.RUNNER,
        "Java runtime service",
        "Compiles and runs sources via CommandMessages and reports back stdout and stderr",
        Arrays.asList(
            new ProductDescription(Products.STREAM_OUTPUT, Languages.JAVA),
            new ProductDescription(Products.PROCESS_TERMINATED, Languages.JAVA)),
        options(),
        dependencies());

    processThreadMap = new HashMap<>();
  }

  @Override
  public void onCommandMessage(CommandMessage commandMessage) {
    try {
      if (commandMessage.getTag().equals(LaunchConfiguration.TAG)) {
        handleLaunchCommandMessage(commandMessage);
      } else if (commandMessage.getTag().equals(TerminateProcess.TAG)) {
        handleTerminationCommandMessage(commandMessage);
      }
    } catch (IOException e) {
      sendExceptionErrorProduct(e);
    }
  }

  private void handleLaunchCommandMessage(CommandMessage commandMessage) throws IOException {
    LaunchConfiguration launchConfiguration =
        LaunchConfiguration.fromCommandMessage(commandMessage);
    Optional<SourceMessage> maybeMainClassSourceMessage =
        commandMessage.getSourceMessage(launchConfiguration.getMainClassSource());
    // TODO: declare dependencies on imported files
    if (launchConfiguration.getMode().equals("run")) {
      if (maybeMainClassSourceMessage.isPresent()) {
        SourceMessage mainClassSourceMessage = maybeMainClassSourceMessage.get();

        if (!mainClassSourceMessage.getSource().getLogicalName().isPresent()) {
          // TODO: send error product instead
          System.err.println(
              mainClassSourceMessage
                  + " doesn't have a logical name.\n"
                  + "JavaRunner needs that to run the class");
        } else {
          Path compileDirectory = Files.createTempDirectory(null);

          CompileUtils.compileJavaClass(
              mainClassSourceMessage.getSource().getPhysicalName(),
              mainClassSourceMessage.getContents(),
              compileDirectory.toAbsolutePath().toString());

          Process process =
              Runtime.getRuntime()
                  .exec(
                      "java " + mainClassSourceMessage.getSource().getLogicalName().get(),
                      new String[0],
                      compileDirectory.toFile());

          InputStreamProductThread stdoutThread =
              new InputStreamProductThread(
                  StreamOutput.SourceStream.OUT,
                  "run",
                  commandMessage.getSession(),
                  process.getInputStream(),
                  getServiceId(),
                  this::sendProductMessage);
          InputStreamProductThread stderrThread =
              new InputStreamProductThread(
                  StreamOutput.SourceStream.ERR,
                  "run",
                  commandMessage.getSession(),
                  process.getErrorStream(),
                  getServiceId(),
                  this::sendProductMessage);

          stdoutThread.start();
          stderrThread.start();

          ProcessTerminationThread processTerminationThread =
              new ProcessTerminationThread(
                  process,
                  "run",
                  commandMessage.getSession(),
                  stdoutThread,
                  stderrThread,
                  compileDirectory,
                  getServiceId(),
                  this::sendProductMessage);

          processThreadMap.put(commandMessage.getSession(), processTerminationThread);

          processTerminationThread.start();
        }
      } else {
        Set<DynamicDependency> dependencies = new HashSet<>();
        dependencies.add(
            DynamicDependency.sourceDependency(
                launchConfiguration.getMainClassSource(), Languages.JAVA));
        registerCommandMessageDependencies(
            new RegisterCommandMessageDependencies(commandMessage, dependencies));
      }
    }
  }

  private void handleTerminationCommandMessage(CommandMessage commandMessage) {
    // CommandMessage doesn't need to be parsed into content, because no additional information is
    // needed for termination
    ProcessTerminationThread processTerminationThread =
        processThreadMap.get(commandMessage.getSession());
    if (processTerminationThread != null) {
      processTerminationThread.interrupt();
    }
  }

  protected void sendExceptionErrorProduct(Throwable t) {
    System.err.println(t.getMessage());
    t.printStackTrace();
  }
}
