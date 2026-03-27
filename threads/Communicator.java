package nachos.threads;
import nachos.machine.*;

/**
 * A <i>communicator</i> allows threads to synchronously exchange 32-bit
 * messages. Multiple threads can be waiting to <i>speak</i>,
 * and multiple threads can be waiting to <i>listen</i>. But there should never
 * be a time when both a speaker and a listener are waiting, because the two
 * threads can be paired off at this point.
 */
public class Communicator {
    /**
     * Allocate a new communicator.
     */

    private Lock lock;
    private Condition2 speakerCond;
    private Condition2 listenerCond;
    private int message;
    private boolean ready;
    private int waitingSpeakers;
    private int waitingListeners;


    public Communicator() {
        // constructor

        lock = new Lock();
        speakerCond = new Condition2(lock);
        listenerCond = new Condition2(lock);
        ready = false;
        waitingSpeakers = 0;
        waitingListeners = 0;

    }

    /**
     * Wait for a thread to listen through this communicator, and then transfer
     * <i>word</i> to the listener.
     *
     * <p>
     * Does not return until this thread is paired up with a listening thread.
     * Exactly one listener should receive <i>word</i>.
     *
     * @param	word	the integer to transfer.
     */
    public void speak(int word) {
        // acqure lock so we can enter the critcal section
        lock.acquire();
        waitingSpeakers++; // increment speakers

        // sleep if no listener waiting, or message is alr pending
        while (ready || waitingListeners == 0) {
            speakerCond.sleep();
        }

        // deposit message and signal listener
        waitingSpeakers--;
        message = word;
        ready = true;
        listenerCond.wake();

        // wait for listener to confirm it got message
        while (ready) {
            speakerCond.sleep();
        }

        lock.release();
    }

    /**
     * Wait for a thread to speak through this communicator, and then return
     * the <i>word</i> that thread passed to <tt>speak()</tt>.
     *
     * @return	the integer transferred.
     */    
    public int listen() {
        // acquire lock to enter critical section
        lock.acquire();
        waitingListeners++;

        // wake a speaker that can be waiting for a listener
        speakerCond.wake();

        // wait until message been deposited
        while(!ready) {
            listenerCond.sleep();
        }

        // pick up message
        waitingListeners--;
        int word = message;
        ready = false;

        // tell the speaker the message was recieved
        speakerCond.wake();

        // release the lock
        lock.release();
	    return word;
    }

    public static void selfTest() {
        System.out.println("\n----- Running Communicator.selfTest() ------\n");

        // test case 1: speak and listen
        System.out.println("Test 1: Basic speak and listen");
        final Communicator c1 = new Communicator();

        // new speaker thread, will try and send the word 42, block until listener is ready
        KThread speaker1 = new KThread(new Runnable() {
            public void run() {
                System.out.println("Speaker: speaking 42");
                c1.speak(42); // this is where it blocks until listen() is called
                System.out.println("Speaker: done");
            }
        }).setName("Speaker 1"); // setName is handy because it helps us identify the thread in debug

        // create a listener thread, wait for speaker to send word, will block inside listen until message is depositied.
        KThread listener1 = new KThread(new Runnable() {
            public void run() {
                int word = c1.listen(); // blocks until speaker calls speak()
                System.out.println("Listener: recieved " + word); // should print 42
            }
        }).setName("Listener 1");

        // fork both threads to start them running
        speaker1.fork();
        listener1.fork();

        // join both so test waits for them to finish before we move on.
        speaker1.join();
        listener1.join();

        System.out.println("Test 1 has been passed. \n");

        // test case 2: listener arrives before speaker

        System.out.println("Test 2: Listener arrives before speaker");
        final Communicator c2 = new Communicator();

         // create a listener thread that will arrive before any speaker exists, will block inside listen until speaker shows up
        KThread listener2 = new KThread(new Runnable() {
            public void run() {
                System.out.println("Listener 2: waiting for a speaker");
                int word = c2.listen(); // block here cause no speaker yet
                System.out.println("Listener 2: recieved " + word); // should print 99
            }
        }).setName("Listener 2");

        // create speaker thread that will arrive after listener is already waiting, since listener is ready, speak pairs immediately and returns

        KThread speaker2 = new KThread(new Runnable() {
            public void run() {
                System.out.println("Speaker 2: speaking 99");
                c2.speak(99); // this is where it blocks until listen() is called
                System.out.println("Speaker 2: done");
            }
        }).setName("Speaker 2");

        // fork listener first so it gets a head start, blocks before speaker starts
        listener2.fork();
        KThread.yield(); // we yield here so listener2 runs and blocks inside of listen()
        speaker2.fork(); // now we can start the speaker, will find listener waiting

        // join both threads so the test waits for them to finish
        listener2.join();
        speaker2.join();
        System.out.println("Test 2 has been passed. \n");


        // test case 3: multiple speakers, multiple listeners

        System.out.println("Test 3: Multiple speakers and listeners");
        final Communicator c3 = new Communicator();

        // we create 3 speaker threads using a loop, each speaks a different word
        KThread[] speakers3 = new KThread[3];
        for (int i = 0; i < 3; i++) {
            final int word = i + 1; // this is final so our runnable is able to access it
            speakers3[i] = new KThread(new Runnable() {
                public void run() {
                System.out.println("Speaker 3 - " + word + ": speaking " + word);
                c3.speak(word); // block until listener is paired
                System.out.println("Speaker 3 - " + word + ": done");
            }
            }).setName("Speaker 3 - " + word);
        }

        // create 3 listeners using a loop, much easier than writing them out 1 by 1.
        // each loop should get exactly 1 word
        KThread[] listeners3 = new KThread[3];
        for (int i = 0; i < 3; i++) {
            final int idx = i + 1; // accessable by the runnable since its final
            listeners3[i] = new KThread(new Runnable() {
                public void run() {
                    System.out.println("Listener 3 - " + idx + ": received " + c3.listen());
                }
            }).setName("Listener 3 - " + idx);
        }

        // fork all speakers and listeners, join all to wait for them to finish
        for (int i = 0; i < 3; i++) {
            speakers3[i].fork();
            listeners3[i].fork();
        }
        
        for (int i = 0; i < 3; i++) {
            speakers3[i].join();
            listeners3[i].join();
        }

        System.out.println("Test 3 has been passed. \n");

        // test case 4: stress test (10 speakers, 10 listeners)
        // if we get any deadlocks or duplicated messages, this will end up hanging or print wrong values
        System.out.println("Test 4: Stress test (10 speakers + 10 listeners");
        final Communicator c4 = new Communicator();

        // we create 10 speaker threads, each sending a diff word 1 - 10
        KThread[] speakers4 = new KThread[10];
        for (int i = 0; i < 10; i++) {
            final int word = i + 1; // this is final so our runnable is able to access it
            speakers4[i] = new KThread(new Runnable() {
                public void run() {
                System.out.println("Speaker 4 - " + word + ": speaking " + word);
                c4.speak(word); // block until listener is paired
                System.out.println("Speaker 4 - " + word + ": done");
            }
            }).setName("Speaker 4 - " + word);
        }

        // create 10 listeners, each should recieve exactly 1 word
        KThread[] listeners4 = new KThread[10];
        for (int i = 0; i < 10; i++) {
            final int idx = i + 1; // accessable by the runnable since its final
            listeners4[i] = new KThread(new Runnable() {
                public void run() {
                    System.out.println("Listener 4 - " + idx + ": received " + c4.listen());
                }
            }).setName("Listener 4 - " + idx);
        }

        // fork all speakers and listeners like test case 3, if any deadlock happens this will hang
        for (int i = 0; i < 10; i++) {
            speakers4[i].fork();
            listeners4[i].fork();
        }
        
        for (int i = 0; i < 10; i++) {
            speakers4[i].join();
            listeners4[i].join();
        }

        System.out.println("Test 4 has been passed. \n");
        System.out.println("----- Communicator.selfTest() Completed -----\n");
    }
}
