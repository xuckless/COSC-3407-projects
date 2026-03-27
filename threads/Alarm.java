package nachos.threads;

import nachos.machine.*;

import java.util.LinkedList;
import java.util.Iterator;
/**
 * Uses the hardware timer to provide preemption, and to allow threads to sleep
 * until a certain time.
 */
public class Alarm {
  
  
  private static class SleepingThread {
    KThread thread;
    long wakeTime;
    
    SleepingThread(KThread t, long time) {
      thread = t;
      wakeTime = time;
    }
  }
  
  private LinkedList<SleepingThread> sleepQueue;
  
  
  
  /**
   * Allocate a new Alarm. Set the machine's timer interrupt handler to this
   * alarm's callback.
   *
   * <p><b>Note</b>: Nachos will not function correctly with more than one
   * alarm.
   */
  public Alarm() {
    
    sleepQueue = new LinkedList<>();
    
    Machine.timer().setInterruptHandler(new Runnable() {
      public void run() { timerInterrupt(); }
    });
  }
  
  /**
   * The timer interrupt handler. This is called by the machine's timer
   * periodically (approximately every 500 clock ticks). Causes the current
   * thread to yield, forcing a context switch if there is another thread
   * that should be run.
   */
  public void timerInterrupt() {
    boolean intStatus = Machine.interrupt().disable();
    
    long now = Machine.timer().getTime();
    
    
    Iterator<SleepingThread> it = sleepQueue.iterator();
    while (it.hasNext()) {
      SleepingThread st = it.next();
      if (st.wakeTime <= now) {
        it.remove();
        st.thread.ready();
      }
    }
    
    Machine.interrupt().restore(intStatus);
    KThread.currentThread().yield();
  }
  
  /**
   * Put the current thread to sleep for at least <i>x</i> ticks,
   * waking it up in the timer interrupt handler. The thread must be
   * woken up (placed in the scheduler ready set) during the first timer
   * interrupt where
   *
   * <p><blockquote>
   * (current time) >= (WaitUntil called time)+(x)
   * </blockquote>
   *
   * @param	x	the minimum number of clock ticks to wait.
   *
   * @see	nachos.machine.Timer#getTime()
   */
  
  public void waitUntil(long x) {
    // for now, cheat just to get something working (busy waiting is bad)
    
    if (x<=0){return;}
    
    boolean intStatus = Machine.interrupt().disable();
    
    long wakeTime = Machine.timer().getTime() + x;
    SleepingThread st = new SleepingThread(KThread.currentThread(), wakeTime);
    
    
    sleepQueue.add(st);
    KThread.currentThread().sleep();
    Machine.interrupt().restore(intStatus);
  }
  public static void selfTest() {
    System.out.println("\n\n--- Starting Alarm Test ---\n");
    
    Alarm alarm = new Alarm();
    
    class TestThread implements Runnable {
      private String name;
      private long waitTime;
      
      TestThread(String name, long waitTime) {
        this.name = name;
        this.waitTime = waitTime;
      }
      
      public void run() {
        long start = Machine.timer().getTime();
        System.out.println(name + " sleeping at time " + start);
        
        alarm.waitUntil(waitTime);
        
        long wake = Machine.timer().getTime();
        System.out.println(name + " woke up at time " + wake +
          " (slept for " + (wake - start) + " ticks)");
      }
    }
    
    KThread t1 = new KThread(new TestThread("Thread-1000", 1000));
    KThread t2 = new KThread(new TestThread("Thread-2000", 2000));
    KThread t3 = new KThread(new TestThread("Thread-3000", 3000));
    
    t1.fork();
    t2.fork();
    t3.fork();
    
    // Yield repeatedly to allow threads and timer to run
    for (int i = 0; i < 3000; i++) {
      KThread.yield();
    }
    
    System.out.println("Alarm selfTest finished (check wake order above).");
  }
}


