import java.util.concurrent.atomic.AtomicInteger;

public class LamportClock {
    private AtomicInteger time = new AtomicInteger(0);

    // Synchronized method to ensure only one thread can send and increment the clock at a time
    public synchronized int send() {
        return time.incrementAndGet(); // Atomically increments by 1 and returns the new value
    }

    // Get time is atomic, no need for synchronization here
    public int getTime() {
        return time.get(); 
    }

    // Synchronized method to handle incoming timestamps and ensure atomic updates
    public synchronized void receive(int receivedTimestamp) {
        // Atomically update the time to the maximum of the current and received time, plus one
        time.updateAndGet(currentTime -> Math.max(currentTime, receivedTimestamp) + 1);
    }

    @Override
    public String toString() {
        return "LamportClock [time=" + time + "]";
    }
}
