package nachos.threads;

import nachos.machine.*;

public class ReactWater {
  private Lock lock;
  private Condition hCond;
  private Condition oCond;
  
  private int waitingH = 0;
  private int waitingO = 0;
  
  private int reactingH = 0;
  private int reactingO = 0;
  
  public ReactWater() {
    lock = new Lock();
    hCond = new Condition(lock);
    oCond = new Condition(lock);
  }
  
  public void hReady() {
    lock.acquire();
    waitingH++;
    
    if (waitingH >= 2 && waitingO >= 1) {
      waitingH -= 2;
      waitingO -= 1;
      reactingH += 2;
      reactingO += 1;
      
      hCond.wake();
      oCond.wake();
      
      makeWater();
      reactingH--;
    } else {
      while (reactingH == 0) {
        hCond.sleep();
      }
      reactingH--;
    }
    
    lock.release();
  }
  
  public void oReady() {
    lock.acquire();
    waitingO++;
    
    if (waitingH >= 2 && waitingO >= 1) {
      waitingH -= 2;
      waitingO -= 1;
      reactingH += 2;
      reactingO += 1;
      
      hCond.wake();
      hCond.wake();
      
      makeWater();
      reactingO--;
    } else {
      while (reactingO == 0) {
        oCond.sleep();
      }
      reactingO--;
    }
    
    lock.release();
  }
  
  private void makeWater() {
    System.out.println("Water was made!");
  }
  
  /**
   * Tests the ReactWater class by creating 4 H atoms and 2 O atoms.
   * This should result in exactly 2 "Water was made!" prints and no deadlocks.
   */
  public static void selfTest() {
    System.out.println("\n\n--- Starting ReactWater Test ---\n");
    final ReactWater rw = new ReactWater();
    
    // Fork 4 Hydrogen threads
    for (int i = 1; i <= 4; i++) {
      final int id = i;
      new KThread(new Runnable() {
        public void run() {
          System.out.println("H atom " + id + " ready.");
          rw.hReady();
        }
      }).setName("H_" + i).fork();
    }
    
    // Fork 2 Oxygen threads
    for (int i = 1; i <= 2; i++) {
      final int id = i;
      new KThread(new Runnable() {
        public void run() {
          System.out.println("O atom " + id + " ready.");
          rw.oReady();
        }
      }).setName("O_" + i).fork();
    }
    
    // Yield to allow the child threads to schedule and run
    KThread.yield();
    KThread.yield();
  }
}