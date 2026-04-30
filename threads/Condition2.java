package nachos.threads;

import java.util.LinkedList;

import nachos.machine.*;

/**
 * An implementation of condition variables that disables interrupt()s for
 * synchronization.
 *
 * <p>
 * You must implement this.
 *
 * @see	nachos.threads.Condition
 */
public class Condition2 {
    
    private Lock conditionLock;
    private LinkedList<KThread> waitQueue;
    /**
     * Allocate a new condition variable.
     *
     * @param	conditionLock	the lock associated with this condition
     *				variable. The current thread must hold this
     *				lock whenever it uses <tt>sleep()</tt>,
     *				<tt>wake()</tt>, or <tt>wakeAll()</tt>.
     */
    public Condition2(Lock conditionLock) {
	    this.conditionLock = conditionLock;
        this.waitQueue = new LinkedList<KThread>();
    }

    /**
     * Atomically release the associated lock and go to sleep on this condition
     * variable until another thread wakes it using <tt>wake()</tt>. The
     * current thread must hold the associated lock. The thread will
     * automatically reacquire the lock before <tt>sleep()</tt> returns.
     */
    public void sleep() {
        // must hold lock to sleep
	    Lib.assertTrue(conditionLock.isHeldByCurrentThread());

        // disable interrupts, add to queue, release lock, block atomically
        boolean intStatus = Machine.interrupt().disable();
        waitQueue.add(KThread.currentThread());
        conditionLock.release();
        KThread.sleep();

        // restore interrupts to previous machine state
        conditionLock.acquire();
        Machine.interrupt().restore(intStatus);
    }

    /**
     * Wake up at most one thread sleeping on this condition variable. The
     * current thread must hold the associated lock.
     */
    public void wake() {
        // hold lock to wake
	    Lib.assertTrue(conditionLock.isHeldByCurrentThread());

        // atomically move first waiting thread bac k to ready queue
        boolean intStatus = Machine.interrupt().disable();
        if (!waitQueue.isEmpty()) {
            waitQueue.removeFirst().ready();
        }
        Machine.interrupt().restore(intStatus);
    }

    /**
     * Wake up all threads sleeping on this condition variable. The current
     * thread must hold the associated lock.
     */
    public void wakeAll() {
        // hold lock to wake
	    Lib.assertTrue(conditionLock.isHeldByCurrentThread());

        // disable interrupts once, wake all of the theads, then restore
        boolean intStatus = Machine.interrupt().disable();
        while(!waitQueue.isEmpty()) {
            waitQueue.removeFirst().ready();
        }
        Machine.interrupt().restore(intStatus);
    }

    public static void selfTest() {
        System.out.println("\n----- Running Condition2.selfTest() ------\n");

        // test case 1: basic sleep and wake, one thread sleeps on con, other wakes it up
        System.out.println("Test 1: Basic sleep and wake");
        final Lock lock1 = new Lock();
        final Condition2 cv1 = new Condition2(lock1);

        // create thread that acquires lock and sleeps on the condition
        // will block here until another thread calls the wake function
        KThread t1 = new KThread(new Runnable() {
            public void run() {
                lock1.acquire();
                System.out.println("Thread 1: sleeping on condition");
                cv1.sleep(); // blocks until wake is called
                System.out.println("Thread 1: woke up");
                lock1.release();
            }
        }).setName("Thread 1");

        t1.fork();
        KThread.yield(); // let thread 1 run and block before we make it

        // the main thread acquires the lock, wakes thread 1
        lock1.acquire();
        System.out.println("Main: waking up");
        cv1.wake();
        lock1.release();
        t1.join();

        System.out.println("Test 1 has been passed.");

        // test case 2: wakeAll: multiple threads sleeping on the asme condition, all should wake up
        System.out.println("Test 2: wakeAll");
        final Lock lock2 = new Lock();
        final Condition2 cv2 = new Condition2(lock2);

        // create 2 threads, both sleep on same condition var
        KThread t2 = new KThread(new Runnable(){
            public void run() {
                lock2.acquire();
                System.out.println("Thread 2: sleeping on condition");
                cv2.sleep(); // blocks until wakeAll() is called
                System.out.println("Thread 2: woke up");
                lock2.release();
            }
        }).setName("Thread 2");

        // create second kthread (im not going to do this in a loop, simpler to do it this way)
        KThread t3 = new KThread(new Runnable(){
            public void run() {
                lock2.acquire();
                System.out.println("Thread 3: sleeping on condition");
                cv2.sleep(); // blocks until wakeAll() is called
                System.out.println("Thread 3: woke up");
                lock2.release();
            }
        }).setName("Thread 3");

        // fork both threads
        t2.fork();
        t3.fork();
        KThread.yield(); // both threads run and block

        // now we wake all sleeping threads at the same time
        lock2.acquire();
        System.out.println("Main Thread: waking all");
        cv2.wakeAll(); // call wakeAll
        
        // release lock, then join
        lock2.release();
        t2.join();
        t3.join();

        System.out.println("Test 2 has been passed. \n");

        // test case 3: wake with an empty queue
        // if wake is called when no threads are sleeping, it should do nothing and not crash.

        System.out.println("Test 3: wake with an empty queue");
        final Lock lock3 = new Lock();
        final Condition2 cv3 = new Condition2(lock3);

        // acquire lock, wake, then release it, if it doesn't crash, we are good.
        lock3.acquire();
        cv3.wake();
        lock3.release();

        System.out.println("Test 3 has been passed. \n");

        System.out.println("----- Condition2.selfTest() Completed -----\n");

    }   
}
