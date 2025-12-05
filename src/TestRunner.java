import com.dosse.upnp.UPnP;

public class TestRunner {

    // We use a high port number to avoid conflicts with existing services
    private static final int TEST_PORT = 12392;

    public static void main(String[] args) {
        System.out.println("=== WaifUPnP (Java 11) Diagnostic Tool ===");
        System.out.println("Target Port: " + TEST_PORT);
        System.out.println("------------------------------------------");

        // 1. Initial Check
        System.out.println("[1/4] Checking status of port " + TEST_PORT + "...");
        boolean initiallyMapped = UPnP.isMappedTCP(TEST_PORT);
        
        if (initiallyMapped) {
            System.err.println("WARNING: Port " + TEST_PORT + " is ALREADY open/mapped.");
            System.err.println("Please change TEST_PORT in Main.java or close it manually on your router.");
            System.err.println("Aborting test to prevent overwriting existing settings.");
            return;
        } else {
            System.out.println("      Port is currently closed (Good).");
        }

        // 2. Open Port
        System.out.println("\n[2/4] Attempting to map TCP port " + TEST_PORT + "...");
        System.out.println("      (This may take a few seconds as we discover the gateway...)");
        
        boolean openSuccess = UPnP.openPortTCP(TEST_PORT, "Qortal-TestTool");
        
        if (openSuccess) {
            System.out.println("      SUCCESS: Library reported port opened.");
        } else {
            System.err.println("      FAILURE: Could not open port.");
            System.err.println("      - Check if UPnP is enabled on your router.");
            System.err.println("      - Check firewall settings.");
            return;
        }

        // 3. Verification
        System.out.println("\n[3/4] Verifying with Router...");
        boolean verified = UPnP.isMappedTCP(TEST_PORT);
        
        if (verified) {
            System.out.println("      SUCCESS: Router confirmed port is mapped.");
        } else {
            System.err.println("      FAILURE: Router says port is still closed.");
            // We continue to cleanup anyway, just in case
        }

        // 4. Cleanup
        System.out.println("\n[4/4] Cleaning up (Closing port)...");
        boolean closeSuccess = UPnP.closePortTCP(TEST_PORT);

        if (closeSuccess) {
            System.out.println("      SUCCESS: Port closed.");
        } else {
            System.err.println("      FAILURE: Could not close port. You may need to do this manually.");
        }

        System.out.println("\n------------------------------------------");
        System.out.println("Test Complete.");
    }
}