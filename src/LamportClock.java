import java.util.concurrent.atomic.AtomicInteger;

public class LamportClock {
    private AtomicInteger time = new AtomicInteger(0);  // using atomic integer to keep track of the clock

    // synchronized method to increment and return the current time
    public synchronized int send() {
        return time.incrementAndGet();  // increments the time and returns it
    }

    // gets the current time without modifying it
    public int getTime() {
        return time.get();  // returns the current time
    }

    // synchronized method to update the clock when receiving a timestamp
    public synchronized void receive(int receivedTimestamp) {
        // updates the time to be the max of the current time or the received timestamp, and then increments
        time.updateAndGet(currentTime -> Math.max(currentTime, receivedTimestamp) + 1);
    }

    // toString method to represent the clock as a string
    @Override
    public String toString() {
        return "LamportClock [time=" + time + "]";  // returns a string showing the current time
    }
}
