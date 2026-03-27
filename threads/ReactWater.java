package nachos.threads;

import nachos.machine.*;

/**
 * Objective 4:
 *
 * Manages the chemical reaction of Hydrogen (H) and Oxygen (O) atoms to form Water (H2O).
 * This class ensures that exactly two H atoms and one O atom are present before
 * allowing a reaction to occur, preventing starvation and busy-waiting using
 * Mesa-style condition variables.
 */
public class ReactWater {
  private Lock lock;
  private Condition hCond;
  private Condition oCond;
  
  private int waitingH = 0;
  private int waitingO = 0;
  
  private int reactingH = 0;
  private int reactingO = 0;
  
  /**
   * Initializes a new ReactWater controller with a synchronization lock
   * and condition variables for both atom types.
   */
  public ReactWater() {
    lock = new Lock();
    hCond = new Condition(lock);
    oCond = new Condition(lock);
  }
  
  /**
   * Called by a Hydrogen atom thread. If there are enough atoms (2H, 1O),
   * it initiates the reaction, wakes partner atoms, and calls makeWater.
   * Otherwise, it blocks until a reaction is ready.
   */
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
  
  /**
   * Called by an Oxygen atom thread. If there are enough atoms (2H, 1O),
   * it initiates the reaction, wakes two partner H atoms, and calls makeWater.
   * Otherwise, it blocks until a reaction is ready.
   */
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
  
  /**
   * Internal method used to signal that a water molecule has been successfully
   * formed. Prints a confirmation message to the console.
   */
  private void makeWater() {
    System.out.println("Water was made!");
  }
  
  /**
   * Basic test case that forks 4 Hydrogen threads and 2 Oxygen threads.
   * Successful execution should result in exactly 2 water molecules formed.
   */
  public static void selfTest() {
    System.out.println("\n\n--- Starting ReactWater Test ---\n");
    final ReactWater rw = new ReactWater();
    
    for (int i = 1; i <= 4; i++) {
      final int id = i;
      new KThread(new Runnable() {
        public void run() {
          System.out.println("H atom " + id + " ready.");
          rw.hReady();
        }
      }).setName("H_" + i).fork();
    }
    
    for (int i = 1; i <= 2; i++) {
      final int id = i;
      new KThread(new Runnable() {
        public void run() {
          System.out.println("O atom " + id + " ready.");
          rw.oReady();
        }
      }).setName("O_" + i).fork();
    }
    
    KThread.yield();
    KThread.yield();
    
    ReactWater.testImbalance();
  }
  
  /**
   * Stress test that creates an imbalance (10H and 2O).
   * Verifies that only 2 molecules are made and excess atoms stay blocked
   * without incorrectly triggering reactions or crashing.
   */
  public static void testImbalance() {
    System.out.println("\n--- Starting ReactWater Imbalance Test (10H, 2O) ---");
    final ReactWater rw = new ReactWater();
    
    for (int i = 1; i <= 10; i++) {
      final int id = i;
      new KThread(new Runnable() {
        public void run() {
          rw.hReady();
          System.out.println("H atom " + id + " finished reaction.");
        }
      }).setName("H_" + id).fork();
    }
    
    for (int i = 1; i <= 2; i++) {
      final int id = i;
      new KThread(new Runnable() {
        public void run() {
          rw.oReady();
          System.out.println("O atom " + id + " finished reaction.");
        }
      }).setName("O_" + id).fork();
    }
    
    for(int i = 0; i < 20; i++) {
      KThread.yield();
    }
    System.out.println("--- Imbalance Test Yielding Finished ---");
  }
}