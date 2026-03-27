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
}
