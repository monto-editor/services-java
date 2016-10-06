package monto.service.java8.launching;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;

import java.util.ArrayList;
import java.util.HashMap;
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
import monto.service.launching.debug.Thread;
import monto.service.launching.debug.Variable;
import monto.service.product.ProductMessage;
import monto.service.product.Products;
import monto.service.source.LogicalNameAbsentException;
import monto.service.types.Languages;
import monto.service.types.LongKey;
import monto.service.types.Source;

public class JavaDebugSession {
  private final int sessionId;
  private final VirtualMachine vm;
  private final ProcessTerminationThread terminationThread;
  private final EventQueueReaderThread eventQueueReaderThread;
  private final Consumer<ProductMessage> onProductMessage;
  private final Consumer<Exception> asyncExceptionHandler;
  private final List<Breakpoint> deferredBreakpoints;
  private final Map<BreakpointRequest, Breakpoint> installedBreakpoints;

  public JavaDebugSession(
      int sessionId,
      VirtualMachine vm,
      ProcessTerminationThread terminationThread,
      EventQueueReaderThread eventQueueReaderThread,
      Consumer<ProductMessage> onProductMessage,
      Consumer<Exception> asyncExceptionHandler) {
    this.sessionId = sessionId;
    this.vm = vm;
    this.terminationThread = terminationThread;
    this.eventQueueReaderThread = eventQueueReaderThread;
    this.onProductMessage = onProductMessage;
    this.asyncExceptionHandler = asyncExceptionHandler;

    eventQueueReaderThread.addClassPrepareEventListener(this::onClassPrepareEvent);
    eventQueueReaderThread.addBreakpointEventListener(this::onBreakpointHit);
    deferredBreakpoints = new ArrayList<>();
    installedBreakpoints = new HashMap<>();
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
      deferredBreakpoints.add(breakpoint);
    } else {
      installBreakpoint(breakpoint, referenceTypes.get(0));
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
              convertJdiThreadTreeToMontoThreadTree(jdiHitThread, hitBreakpoint),
              otherThreads);

      onProductMessage.accept(
          new ProductMessage(
              new LongKey(-1),
              new Source("debug:session:" + sessionId),
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
      List<LocalVariable> jdiArguments = jdiStackFrame.location().method().arguments();
      List<LocalVariable> jdiLocalVariables = jdiStackFrame.visibleVariables();
      jdiLocalVariables.removeAll(jdiArguments);

      Map<LocalVariable, Value> jdiLocalValues = jdiStackFrame.getValues(jdiLocalVariables);
      Map<LocalVariable, Value> jdiArgumentValues = jdiStackFrame.getValues(jdiArguments);
      ObjectReference jdiThisReference = jdiStackFrame.thisObject();

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

      List<Variable> stackVariables = new ArrayList<>();
      stackVariables.addAll(arguments);
      stackVariables.addAll(locals);

      if (jdiThisReference != null) {
        Variable thiss =
            new Variable(
                "this",
                jdiThisReference.referenceType().name(),
                jdiThisReference.toString(),
                Variable.KIND_THIS);
        stackVariables.add(thiss);
      }

      stackFrames.add(new StackFrame(jdiStackFrame.location().toString(), stackVariables));
    }

    return new Thread(threadReference.name(), stackFrames, hitBreakpoint);
  }

  private void onClassPrepareEvent(ClassPrepareEvent classPrepareEvent) {
    // register deferred breakpoints
    ReferenceType referenceType = classPrepareEvent.referenceType();

    for (Breakpoint deferredBreakpoint : deferredBreakpoints) {
      Optional<String> mayBeLogicalName = deferredBreakpoint.getSource().getLogicalName();
      if (mayBeLogicalName.isPresent() && referenceType.name().equals(mayBeLogicalName.get())) {
        try {
          installBreakpoint(deferredBreakpoint, referenceType);
          deferredBreakpoints.remove(deferredBreakpoint);
        } catch (AbsentInformationException | BreakpointNotAvailableException e) {
          asyncExceptionHandler.accept(e);
        }
      }
    }
  }

  private void installBreakpoint(Breakpoint breakpoint, ReferenceType referenceType)
      throws AbsentInformationException, BreakpointNotAvailableException {
    List<Location> locationsOfLine = referenceType.locationsOfLine(breakpoint.getLineNumber());
    if (locationsOfLine.size() == 0) {
      throw new BreakpointNotAvailableException(breakpoint);
    } else {
      BreakpointRequest breakpointRequest =
          getEventRequestManager().createBreakpointRequest(locationsOfLine.get(0));
      breakpointRequest.setSuspendPolicy(EventRequest.SUSPEND_ALL);
      breakpointRequest.enable();
      installedBreakpoints.put(breakpointRequest, breakpoint);
    }
  }
}
