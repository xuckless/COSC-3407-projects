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
   * Executes the complete suite of test cases for the ReactWater synchronization class.
   */
  public static void selfTest() {
    System.out.println("\n\n--- MakeWater.java tests ---\n");
    testPartA_Basics();
    testPartB_WaitingWithDelays();
    testPartC_Oversupply();
    testPartD_MultipleReactions();
    testPartE_RandomInterleaving();
    testPartF_StressTest();
  }
  
  /**
   * Helper method to dynamically spawn and fork a specified number of Hydrogen and Oxygen threads.
   *
   * @param rw The ReactWater instance to run the threads against.
   * @param h  The number of Hydrogen threads to spawn.
   * @param o  The number of Oxygen threads to spawn.
   */
  private static void spawn(final ReactWater rw, int h, int o) {
    for (int i = 0; i < h; i++) {
      new KThread(new Runnable() {
        public void run() {
          rw.hReady();
        }
      }).setName("H").fork();
    }
    for (int i = 0; i < o; i++) {
      new KThread(new Runnable() {
        public void run() {
          rw.oReady();
        }
      }).setName("O").fork();
    }
  }
  
  /**
   * Helper method to force the current thread to yield the CPU a specific number of times.
   *
   * @param times The number of times to yield.
   */
  private static void delayYield(int times) {
    for (int i = 0; i < times; i++) KThread.yield();
  }
  
  /**
   * Tests the basic formation of a single water molecule by providing exactly two Hydrogen and one Oxygen atoms.
   */
  public static void testPartA_Basics() {
    System.out.println("\n--- Part a: Verify basics (2H + 1O) ---");
    final ReactWater rw = new ReactWater();
    spawn(rw, 2, 1);
    delayYield(10);
  }
  
  /**
   * Tests synchronization when atoms arrive asynchronously with delays between their arrivals.
   */
  public static void testPartB_WaitingWithDelays() {
    System.out.println("\n--- Part b: Verify waiting with delays ---");
    final ReactWater rw = new ReactWater();
    
    new KThread(new Runnable() {
      public void run() {
        rw.hReady();
      }
    }).setName("H1").fork();
    delayYield(2);
    
    new KThread(new Runnable() {
      public void run() {
        rw.oReady();
      }
    }).setName("O1").fork();
    delayYield(2);
    
    new KThread(new Runnable() {
      public void run() {
        rw.hReady();
      }
    }).setName("H2").fork();
    delayYield(10);
  }
  
  /**
   * Tests system stability and blocked states when provided an excess of one type of atom across multiple intervals.
   */
  public static void testPartC_Oversupply() {
    System.out.println("\n--- Part c: Oversupply (3H + 2O then 1O + 2H) ---");
    final ReactWater rw = new ReactWater();
    
    System.out.println("Spawning 3H + 2O...");
    spawn(rw, 3, 2);
    delayYield(10);
    
    System.out.println("Spawning 1O + 2H...");
    spawn(rw, 2, 1);
    delayYield(10);
  }
  
  /**
   * Tests the correct formation of multiple water molecules initiated concurrently.
   */
  public static void testPartD_MultipleReactions() {
    System.out.println("\n--- Part d: Multiple reactions (4H + 2O) ---");
    final ReactWater rw = new ReactWater();
    spawn(rw, 4, 2);
    delayYield(10);
  }
  
  /**
   * Tests the robustness of the condition variables by spawning atoms in a randomized, interleaved order.
   */
  public static void testPartE_RandomInterleaving() {
    System.out.println("\n--- Part e: Random interleaving (Various order) ---");
    final ReactWater rw = new ReactWater();
    String[] seq = {"H", "O", "O", "H", "H", "H", "O", "H", "H"};
    
    for (int i = 0; i < seq.length; i++) {
      final String type = seq[i];
      new KThread(new Runnable() {
        public void run() {
          if (type.equals("H")) rw.hReady();
          else rw.oReady();
        }
      }).setName(type).fork();
      
      if (i % 2 == 0) KThread.yield();
    }
    delayYield(15);
  }
  
  /**
   * Performs a high-load stress test to ensure no deadlocks or race conditions occur under heavy concurrency.
   */
  public static void testPartF_StressTest() {
    System.out.println("\n--- Part f: Stress test (100H + 50O expecting 50 waters) ---");
    final ReactWater rw = new ReactWater();
    spawn(rw, 100, 50);
    delayYield(200);
  }
}