package monto.service.java8.launching;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import monto.service.gson.GsonMonto;
import monto.service.java8.JavaServices;
import monto.service.launching.debug.Breakpoint;
import monto.service.launching.debug.BreakpointNotAvailableException;
import monto.service.launching.debug.HitBreakpoint;
import monto.service.launching.debug.StackFrame;
import monto.service.launching.debug.StepRequest;
import monto.service.launching.debug.Thread;
import monto.service.launching.debug.ThreadNotFoundException;
import monto.service.launching.debug.Variable;
import monto.service.product.ProductMessage;
import monto.service.product.Products;
import monto.service.source.LogicalNameAbsentException;
import monto.service.types.Languages;
import monto.service.types.LongKey;
import monto.service.types.Source;

public class JavaDebugSession {
  private final int sessionId;
  private final LongKey versionId;
  private final Source sessionSource;
  private final VirtualMachine vm;
  private final ProcessTerminationThread terminationThread;
  private final EventQueueReaderThread eventQueueReaderThread;
  private final List<Source> sources;

  private final Consumer<ProductMessage> onProductMessage;
  private final Consumer<Exception> asyncExceptionHandler;

  private final List<Breakpoint> deferredBreakpoints;
  private final Map<BreakpointRequest, Breakpoint> installedBreakpoints;
  private final Map<Breakpoint, BreakpointRequest> reverseInstalledBreakpoints;

  public JavaDebugSession(
      int sessionId,
      VirtualMachine vm,
      ProcessTerminationThread terminationThread,
      EventQueueReaderThread eventQueueReaderThread,
      List<Source> sources,
      Consumer<ProductMessage> onProductMessage,
      Consumer<Exception> asyncExceptionHandler) {
    this.sessionId = sessionId;
    this.versionId = new LongKey(-1);
    this.sessionSource = new Source("session:" + sessionId);
    this.vm = vm;
    this.terminationThread = terminationThread;
    this.eventQueueReaderThread = eventQueueReaderThread;
    this.sources = sources;

    this.onProductMessage = onProductMessage;
    this.asyncExceptionHandler = asyncExceptionHandler;

    eventQueueReaderThread.addClassPrepareEventListener(this::onClassPrepareEvent);
    eventQueueReaderThread.addBreakpointEventListener(this::onBreakpointHit);
    eventQueueReaderThread.addStepListener(this::onStep);
    deferredBreakpoints = new ArrayList<>();
    installedBreakpoints = new HashMap<>();
    reverseInstalledBreakpoints = new HashMap<>();
  }

  public int getSessionId() {
    return sessionId;
  }

  public VirtualMachine getVm() {
    return vm;
  }

  public Process getProcess() {
    return vm.process();
  }

  public EventRequestManager getEventRequestManager() {
    return vm.eventRequestManager();
  }

  public ProcessTerminationThread getTerminationThread() {
    return terminationThread;
  }

  public EventQueueReaderThread getEventQueueReaderThread() {
    return eventQueueReaderThread;
  }

  public void resume() {
    vm.resume();
    onProductMessage.accept(
        new ProductMessage(
            versionId,
            sessionSource,
            JavaServices.DEBUGGER,
            Products.THREADS_RESUMED,
            Languages.JAVA,
            null,
            0));
  }

  public void addBreakpoint(Breakpoint breakpoint)
      throws LogicalNameAbsentException, AbsentInformationException,
      BreakpointNotAvailableException {
    if (!breakpoint.getSource().getLogicalName().isPresent()) {
      throw new LogicalNameAbsentException(breakpoint.getSource());
    }
    List<ReferenceType> referenceTypes =
        vm.classesByName(breakpoint.getSource().getLogicalName().get());
    if (referenceTypes.size() == 0) {
      synchronized (this) {
        deferredBreakpoints.add(breakpoint);
      }
    } else {
      installBreakpoint(breakpoint, referenceTypes.get(0));
    }
  }

  public void removeBreakpoint(Breakpoint breakpoint) {
    BreakpointRequest breakpointRequest = reverseInstalledBreakpoints.get(breakpoint);
    if (breakpointRequest != null) {
      vm.eventRequestManager().deleteEventRequest(breakpointRequest);
      synchronized (this) {
        installedBreakpoints.remove(breakpointRequest);
        reverseInstalledBreakpoints.remove(breakpoint);
      }
    }
  }

  private void onBreakpointHit(BreakpointEvent breakpointEvent) {
    try {

      ThreadReference jdiHitThread = breakpointEvent.thread();

      List<Thread> otherThreads = new ArrayList<>();

      for (ThreadReference jdiOtherThread : vm.allThreads()) {
        if (!jdiHitThread.equals(jdiOtherThread)) {
          otherThreads.add(convertJdiThreadTreeToMontoThreadTree(jdiOtherThread, null));
        }
      }

      Breakpoint hitBreakpoint = installedBreakpoints.get(breakpointEvent.request());
      if (hitBreakpoint == null) {
        throw new BreakpointNotAvailableException(
            String.format(
                "A unexpected breakpoint was hit at %s:%s",
                breakpointEvent.location().sourceName(),
                breakpointEvent.location().lineNumber()));
      }
      HitBreakpoint hitBreakpointProduct =
          new HitBreakpoint(
              convertJdiThreadTreeToMontoThreadTree(jdiHitThread, hitBreakpoint), otherThreads);

      onProductMessage.accept(
          new ProductMessage(
              versionId,
              sessionSource,
              JavaServices.DEBUGGER,
              Products.HIT_BREAKPOINT,
              Languages.JAVA,
              GsonMonto.toJsonTree(hitBreakpointProduct),
              0));
    } catch (
        IncompatibleThreadStateException | AbsentInformationException
            | BreakpointNotAvailableException
            e) {
      asyncExceptionHandler.accept(e);
    }
  }

  private Thread convertJdiThreadTreeToMontoThreadTree(
      ThreadReference threadReference, Breakpoint hitBreakpoint)
      throws IncompatibleThreadStateException, AbsentInformationException {
    List<StackFrame> stackFrames = new ArrayList<>();
    for (com.sun.jdi.StackFrame jdiStackFrame : threadReference.frames()) {
      List<Variable> stackVariables = new ArrayList<>();
      ObjectReference jdiThisReference = jdiStackFrame.thisObject();

      Method method = jdiStackFrame.location().method();
      if (!method.isNative()) {
        try {
          List<LocalVariable> jdiArguments = method.arguments();
          List<LocalVariable> jdiLocalVariables = jdiStackFrame.visibleVariables();
          jdiLocalVariables.removeAll(jdiArguments);

          Map<LocalVariable, Value> jdiLocalValues = jdiStackFrame.getValues(jdiLocalVariables);
          Map<LocalVariable, Value> jdiArgumentValues = jdiStackFrame.getValues(jdiArguments);

          List<Variable> arguments =
              jdiArgumentValues
                  .entrySet()
                  .stream()
                  .map(
                      localValue
                          -> new Variable(
                          localValue.getKey().name(),
                          localValue.getKey().typeName(),
                          localValue.getValue().toString(),
                          Variable.KIND_ARGUMENT))
                  .collect(Collectors.toList());

          List<Variable> locals =
              jdiLocalValues
                  .entrySet()
                  .stream()
                  .map(
                      localValue
                          -> new Variable(
                          localValue.getKey().name(),
                          localValue.getKey().typeName(),
                          localValue.getValue().toString(),
                          Variable.KIND_LOCAL))
                  .collect(Collectors.toList());

          stackVariables.addAll(arguments);
          stackVariables.addAll(locals);
        } catch (AbsentInformationException e) {
          // Locals and arguments can't be extracted, because debugging information is missing.
          // There is no possibility to make sure this information is available from the JDI API,
          // so try catch is necessary. This exception is thrown, when threads are currently in
          // Java API classes, such as java.lang.*.
        }
      }

      if (jdiThisReference != null) {
        Variable thiss =
            new Variable(
                "this",
                jdiThisReference.referenceType().name(),
                jdiThisReference.toString(),
                Variable.KIND_THIS);
        stackVariables.add(thiss);
      }

      stackFrames.add(new StackFrame(getSourceForLocation(jdiStackFrame.location()).orElse(null),
          jdiStackFrame.location().lineNumber(), stackVariables));
    }

    return new Thread(threadReference.uniqueID(), threadReference.name(), stackFrames,
        hitBreakpoint);
  }

  private Optional<Source> getSourceForLocation(Location location) {
    try {
      String logicalSourceName = location.sourcePath().replace(".java", "").replaceAll("/", ".");
      Optional<Source> sourceOptional = sources.stream().filter(
          source -> source.getLogicalName().isPresent() && source.getLogicalName().get()
              .equals(logicalSourceName))
          .findFirst();
      if (sourceOptional.isPresent()) {
        return sourceOptional;
      } else {
        return Optional.of(new Source(location.sourcePath() + ":" + location.lineNumber()));
      }
    } catch (AbsentInformationException ignored) {
    }
    return Optional.empty();
  }

  private synchronized void onClassPrepareEvent(ClassPrepareEvent classPrepareEvent) {
    // register deferred breakpoints
    ReferenceType referenceType = classPrepareEvent.referenceType();

    for (Iterator<Breakpoint> iterator = deferredBreakpoints.iterator(); iterator.hasNext(); ) {
      Breakpoint deferredBreakpoint = iterator.next();
      Optional<String> mayBeLogicalName = deferredBreakpoint.getSource().getLogicalName();
      if (mayBeLogicalName.isPresent() && referenceType.name().equals(mayBeLogicalName.get())) {
        try {
          installBreakpoint(deferredBreakpoint, referenceType);
          iterator.remove();
        } catch (AbsentInformationException | BreakpointNotAvailableException e) {
          asyncExceptionHandler.accept(e);
        }
      }
    }
  }

  private void installBreakpoint(Breakpoint breakpoint, ReferenceType referenceType)
      throws AbsentInformationException, BreakpointNotAvailableException {
    BreakpointRequest previousBreakpointRequest = reverseInstalledBreakpoints.get(breakpoint);
    if (previousBreakpointRequest != null) {
      if (previousBreakpointRequest.isEnabled()) {
        System.out.println("Breakpoint already installed and enabled");
      } else {
        System.out.println("Breakpoint already installed, but disabled. Activating");
        previousBreakpointRequest.enable();
      }
    } else {
      List<Location> locationsOfLine = referenceType.locationsOfLine(breakpoint.getLineNumber());
      if (locationsOfLine.size() == 0) {
        throw new BreakpointNotAvailableException(breakpoint);
      } else {
        BreakpointRequest breakpointRequest =
            getEventRequestManager().createBreakpointRequest(locationsOfLine.get(0));
        breakpointRequest.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        breakpointRequest.enable();
        synchronized (this) {
          installedBreakpoints.put(breakpointRequest, breakpoint);
          reverseInstalledBreakpoints.put(breakpoint, breakpointRequest);
        }
      }
    }
  }

  private ThreadReference getThreadReference(long threadUniqueId) throws ThreadNotFoundException {
    List<ThreadReference>
        threads =
        vm.allThreads().stream().filter(thread -> thread.uniqueID() == threadUniqueId)
            .collect(Collectors.toList());
    if (threads.size() == 1) {
      return threads.get(0);
    } else {
      throw new ThreadNotFoundException(threadUniqueId);
    }
  }

  public void step(StepRequest request) throws ThreadNotFoundException {
    ThreadReference threadReference = getThreadReference(request.getThread().getId());
    int depth = com.sun.jdi.request.StepRequest.STEP_OVER;
    switch (request.getRange()) {
      case OVER:
        depth = com.sun.jdi.request.StepRequest.STEP_OVER;
        break;
      case INTO:
        depth = com.sun.jdi.request.StepRequest.STEP_INTO;
        break;
      case OUT:
        depth = com.sun.jdi.request.StepRequest.STEP_OUT;
        break;
    }
    com.sun.jdi.request.StepRequest jdiStepRequest = getEventRequestManager()
        .createStepRequest(threadReference, com.sun.jdi.request.StepRequest.STEP_LINE, depth);
    jdiStepRequest.addCountFilter(1);
    jdiStepRequest.enable();
    threadReference.resume();
  }

  private void onStep(StepEvent stepEvent) {
    try {
      // There can only be one StepRequest per ThreadReference.
      // Delete triggering request, so that there is no outstanding StepRequest.
      getEventRequestManager().deleteEventRequest(stepEvent.request());
      Thread thread = convertJdiThreadTreeToMontoThreadTree(stepEvent.thread(),
          null /* TODO: should not be null, but original suspending breakpoint */);
      onProductMessage.accept(
          new ProductMessage(versionId,
              sessionSource,
              JavaServices.DEBUGGER,
              Products.THREAD_STEPPED,
              Languages.JAVA,
              GsonMonto.toJsonTree(thread),
              0));
    } catch (IncompatibleThreadStateException | AbsentInformationException e) {
      asyncExceptionHandler.accept(e);
    }
  }
}
