package monto.service.java8;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.connect.VMStartException;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import monto.service.MontoService;
import monto.service.ZMQConfiguration;
import monto.service.command.CommandMessage;
import monto.service.command.Commands;
import monto.service.dependency.DynamicDependency;
import monto.service.dependency.RegisterCommandMessageDependencies;
import monto.service.gson.GsonMonto;
import monto.service.java8.launching.CompileUtils;
import monto.service.java8.launching.EventQueueReaderThread;
import monto.service.java8.launching.InputStreamProductThread;
import monto.service.java8.launching.JavaDebugSession;
import monto.service.java8.launching.ProcessTerminationThread;
import monto.service.launching.DebugLaunchConfiguration;
import monto.service.launching.StreamOutput;
import monto.service.launching.debug.Breakpoint;
import monto.service.launching.debug.BreakpointNotAvailableException;
import monto.service.product.Products;
import monto.service.registration.CommandDescription;
import monto.service.registration.ProductDescription;
import monto.service.source.LogicalNameAbsentException;
import monto.service.source.SourceMessage;
import monto.service.types.Command;
import monto.service.types.Languages;

public class JavaDebugger extends MontoService {
  private final LaunchingConnector connector;
  private final Map<Integer, JavaDebugSession> debugSessionMap;

  public JavaDebugger(ZMQConfiguration zmqConfig) {
    super(
        zmqConfig,
        JavaServices.DEBUGGER,
        "Java debugger service",
        "Compiles and debugs sources via CommandMessages and reports output and events",
        productDescriptions(
            new ProductDescription(Products.STREAM_OUTPUT, Languages.JAVA),
            new ProductDescription(Products.PROCESS_TERMINATED, Languages.JAVA),
            new ProductDescription(Products.HIT_BREAKPOINT, Languages.JAVA)
            //TODO more products here
            ),
        options(),
        dependencies(),
        commands(
            new CommandDescription(Commands.DEBUG_LAUNCH_CONFIGURATION, Languages.JAVA),
            new CommandDescription(Commands.TERMINATE_PROCESS, Languages.JAVA),
            new CommandDescription(Commands.ADD_BREAKPOINT, Languages.JAVA),
            new CommandDescription(Commands.RESUME_DEBUGGING, Languages.JAVA)));

    connector = Bootstrap.virtualMachineManager().defaultConnector();
    debugSessionMap = new HashMap<>();
  }

  @Override
  public void onCommandMessage(CommandMessage commandMessage) {
    try {
      Command command = commandMessage.getCommand();
      if (command.equals(Commands.DEBUG_LAUNCH_CONFIGURATION)) {
        handleLaunchCommandMessage(commandMessage);
      } else {
        if (debugSessionMap.containsKey(commandMessage.getSession())) {
          if (command.equals(Commands.TERMINATE_PROCESS)) {
            // TODO // TODO TERMINATE_DEBUG_PROCESS?
            handleTerminationCommandMessage(commandMessage);

          } else if (command.equals(Commands.ADD_BREAKPOINT)) {
            handleAddBreakpoint(commandMessage);

          } else if (command.equals(Commands.RESUME_DEBUGGING)) {
            handleResumeDebugging(commandMessage);

          } else {
            System.out.println(
                "JavaDebugger received unexpected CommandMessage with command " + command);
          }
        }
      }
    } catch (
        IOException | BreakpointNotAvailableException | LogicalNameAbsentException
                | AbsentInformationException | VMStartException | IllegalConnectorArgumentsException
            e) {
      sendExceptionErrorProduct(e);
    }
  }

  private void handleResumeDebugging(CommandMessage commandMessage) {
    // TODO null checks
    debugSessionMap.get(commandMessage.getSession()).resume();
  }

  private void handleAddBreakpoint(CommandMessage commandMessage)
      throws AbsentInformationException, LogicalNameAbsentException,
          BreakpointNotAvailableException {
    // TODO null checks
    Breakpoint breakpoint = GsonMonto.fromJson(commandMessage.getContents(), Breakpoint.class);
    JavaDebugSession debugSession = debugSessionMap.get(commandMessage.getSession());
    debugSession.addBreakpoint(breakpoint);
  }

  private void handleLaunchCommandMessage(CommandMessage commandMessage)
      throws IllegalConnectorArgumentsException, VMStartException, IOException,
          AbsentInformationException, LogicalNameAbsentException, BreakpointNotAvailableException {

    DebugLaunchConfiguration debugLaunchConfiguration =
        GsonMonto.fromJson(commandMessage.getContents(), DebugLaunchConfiguration.class);

    // TODO: declare dependencies on imported files or project dependency
    Optional<SourceMessage> maybeMainClassSourceMessage =
        commandMessage.getSourceMessage(debugLaunchConfiguration.getMainClassSource());
    if (!maybeMainClassSourceMessage.isPresent()) {
      Set<DynamicDependency> dependencies = new HashSet<>();
      dependencies.add(
          DynamicDependency.sourceDependency(
              debugLaunchConfiguration.getMainClassSource(), Languages.JAVA));
      registerCommandMessageDependencies(
          new RegisterCommandMessageDependencies(commandMessage, dependencies));
    } else {
      SourceMessage mainClassSourceMessage = maybeMainClassSourceMessage.get();
      if (!mainClassSourceMessage.getSource().getLogicalName().isPresent()) {
        // TODO: send error product instead
        System.err.println(
            mainClassSourceMessage
                + " doesn't have a logical name.\n"
                + "JavaDebugger needs that to run the class");
      } else {
        Path compileDirectory = Files.createTempDirectory(null);
        Path workingDirectory = Files.createTempDirectory(null);

        CompileUtils.compileJavaClass(
            mainClassSourceMessage.getSource().getPhysicalName(),
            mainClassSourceMessage.getContents(),
            compileDirectory.toAbsolutePath().toString());

        Map<String, Connector.Argument> connectorArguments = connector.defaultArguments();

        // Arguments for SunCommandLineLauncher are documented at
        // http://docs.oracle.com/javase/8/docs/technotes/guides/jpda/conninv.html#sunlaunch

        Connector.Argument mainArgument = connectorArguments.get("main");
        mainArgument.setValue(mainClassSourceMessage.getSource().getLogicalName().get());

        Connector.Argument optionsArgument = connectorArguments.get("options");
        optionsArgument.setValue(
            "-classpath \""
                + compileDirectory.toAbsolutePath().toString()
                + "\" "
                + "-Duser.dir=\""
                + workingDirectory.toAbsolutePath().toString()
                + "\"");
        // TODO: user.dir for setting the working directory doesn't work in all cases
        // It does work when using the File class with relative paths, but FileOutputStream ignores
        // this setting. JDI (more specifically SunCommandLineLauncher) sadly doesn't allow
        // specification of the working directory. They internally use a ProcessBuilder, but the
        // working directory parameter is not settable.
        // Fix: Don't use SunCommandLineLauncher, but start own process and use an AttachingLauncher.

        // Suspend is true by default, but we still set it here, in case it ever changes
        // This suspends the vm just before the main class is loaded
        // This is useful, because all listeners can be attached, before the main class starts running
        // vm.resume() starts execution, once everything is ready
        Connector.Argument suspendArgument = connectorArguments.get("suspend");
        suspendArgument.setValue("true");

        VirtualMachine vm = connector.launch(connectorArguments);
        // Disable all prints to System.out and System.err on the Monto service vm,
        // not the just created vm
        vm.setDebugTraceMode(VirtualMachine.TRACE_NONE);

        EventRequestManager eventRequestManager = vm.eventRequestManager();
        ClassPrepareRequest classPrepareRequest = eventRequestManager.createClassPrepareRequest();
        classPrepareRequest.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        classPrepareRequest.enable();

        EventQueueReaderThread eventQueueReaderThread = new EventQueueReaderThread(vm.eventQueue());
        eventQueueReaderThread.start();

        Process process = vm.process();
        int sessionId = commandMessage.getSession();

        InputStreamProductThread stdoutThread =
            new InputStreamProductThread(
                StreamOutput.SourceStream.OUT,
                "debug",
                sessionId,
                process.getInputStream(),
                getServiceId(),
                this::sendProductMessage);
        InputStreamProductThread stderrThread =
            new InputStreamProductThread(
                StreamOutput.SourceStream.ERR,
                "debug",
                sessionId,
                process.getErrorStream(),
                getServiceId(),
                this::sendProductMessage);

        stdoutThread.start();
        stderrThread.start();

        ProcessTerminationThread processTerminationThread =
            new ProcessTerminationThread(
                process,
                "debug",
                sessionId,
                stdoutThread,
                stderrThread,
                workingDirectory,
                getServiceId(),
                this::sendProductMessage);

        processTerminationThread.start();

        JavaDebugSession debugSession =
            new JavaDebugSession(
                sessionId,
                vm,
                processTerminationThread,
                eventQueueReaderThread,
                this::sendProductMessage,
                this::sendExceptionErrorProduct);

        for (Breakpoint breakpoint : debugLaunchConfiguration.getBreakpoints()) {
          debugSession.addBreakpoint(breakpoint);
        }

        debugSessionMap.put(sessionId, debugSession);

        vm.resume();
      }
    }
  }

  private void handleTerminationCommandMessage(CommandMessage commandMessage) {
    // CommandMessage doesn't need to be parsed into content, because no additional information is
    // needed for termination
    // TODO
    debugSessionMap.get(commandMessage.getSession()).getVm().exit(1001);
  }

  protected void sendExceptionErrorProduct(Throwable t) {
    System.err.println(t.getMessage());
    t.printStackTrace();
  }
}
