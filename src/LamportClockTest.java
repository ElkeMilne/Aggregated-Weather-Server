import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class LamportClockTest {

    private LamportClock lamportClock;

    @BeforeEach
    public void setUp() {
        lamportClock = new LamportClock();
    }

    // Test initial state
    @Test
    public void testInitialState() {
        assertEquals(0, lamportClock.getTime());
    }

    // Test incrementing the clock
    @Test
    public void testClockIncrement() {
        lamportClock.send(); // Increment time
        assertEquals(1, lamportClock.getTime());
    }

    // Test receiving a higher timestamp
    @Test
    public void testReceiveHigherTimestamp() {
        lamportClock.receive(5); // Receive a higher timestamp
        assertEquals(6, lamportClock.getTime());
    }

    // Test receiving a lower timestamp
    @Test
    public void testReceiveLowerTimestamp() {
        lamportClock.send(); // Increment time to 1
        lamportClock.receive(0); // Receive a lower timestamp
        assertEquals(2, lamportClock.getTime()); // Clock should still increment
    }

    // Test LamportClock with multiple threads spamming read and write operations
    @Test
    public void testMultiThreadedLamportClock() throws InterruptedException {
        int numThreads = 100;
        int numOperations = 1000;

        // Executor service to manage the threads
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        // Submit tasks for read and write operations on LamportClock
        for (int i = 0; i < numOperations; i++) {
            executor.submit(() -> {
                // Randomly either send or receive
                if (Math.random() < 0.5) {
                    lamportClock.send(); // Increment clock
                } else {
                    int randomTimestamp = (int) (Math.random() * 100);
                    lamportClock.receive(randomTimestamp); // Receive a random timestamp
                }
            });
        }

        // Shut down executor and wait for tasks to finish
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);

        // After all threads are done, print the final clock time to ensure consistency
        System.out.println("Final Lamport Clock Time: " + lamportClock.getTime());

        // We cannot assert an exact value due to random operations, but we can ensure it is positive
        assertTrue(lamportClock.getTime() > 0);
    }
}
