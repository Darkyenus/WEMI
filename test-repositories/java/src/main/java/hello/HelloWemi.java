package hello;

import com.esotericsoftware.minlog.Log;

/**
 * Class that greets the world from Wemi
 */
public class HelloWemi {

    /**
     * Main method, does the greeting.
     * @param args ignored
     */
    public static void main(String[] args){
        System.out.println("Hello from Wemi!");
        Log.info("Hello from MinLog!");
    }
}
