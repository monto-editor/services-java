package monto.service.java8.launching;

import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.StepEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class EventQueueReaderThread extends Thread {
  private final EventQueue eventQueue;

  private final List<Consumer<BreakpointEvent>> breakpointListeners;
  private final List<Consumer<ClassPrepareEvent>> classPrepareListeners;
  private final List<Consumer<StepEvent>> stepListeners;

  public EventQueueReaderThread(EventQueue eventQueue) {
    this.eventQueue = eventQueue;
    this.breakpointListeners = new ArrayList<>();
    this.classPrepareListeners = new ArrayList<>();
    this.stepListeners = new ArrayList<>();
  }

  @Override
  public void run() {
    while (!isInterrupted()) {
      try {
        EventSet events = eventQueue.remove();
        for (Event event : events) {
          if (event instanceof ClassPrepareEvent) {
            ClassPrepareEvent classPrepareEvent = (ClassPrepareEvent) event;
            for (Consumer<ClassPrepareEvent> consumer : classPrepareListeners) {
              consumer.accept(classPrepareEvent);
            }
            events.resume();
          } else if (event instanceof BreakpointEvent) {
            BreakpointEvent breakpointEvent = (BreakpointEvent) event;
            for (Consumer<BreakpointEvent> breakpointEventConsumer : breakpointListeners) {
              breakpointEventConsumer.accept(breakpointEvent);
            }
          } else if (event instanceof StepEvent) {
            StepEvent stepEvent = (StepEvent) event;
            for (Consumer<StepEvent> stepListener : stepListeners) {
              stepListener.accept(stepEvent);
            }
          }
        }
      } catch (InterruptedException e) {
        e.printStackTrace();
        interrupt();
      }
    }

    // clear all listeners
    classPrepareListeners.clear();
    breakpointListeners.clear();
    stepListeners.clear();
  }

  public void addClassPrepareEventListener(Consumer<ClassPrepareEvent> consumer) {
    classPrepareListeners.add(consumer);
  }

  public void addBreakpointEventListener(Consumer<BreakpointEvent> consumer) {
    breakpointListeners.add(consumer);
  }

  public void addStepListener(Consumer<StepEvent> consumer) {
    stepListeners.add(consumer);
  }
}
