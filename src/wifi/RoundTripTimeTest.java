package wifi;

/**
 * Executes a round trip time experiment.
 * @author Nathan P
 */
public class RoundTripTimeTest {	
	
	private static final String TAG = "RoundTripTimeTest";

	// Destination MAC for RTT tests
	public static final short RTT_TEST_DEST_MAC = 602;
	// Origin MAC for RTT tests (testing machine)
	private static final short RTT_ORIGIN_MAC = 601;
	// Number of packets to send for RTT tests
	public static final int NUM_RTT_PACKETS = 25;

	public static void main(String[] args) {
		// All we do is instantiate the link layer in the correct mode, it will
		// do the rest. Ideally we could specify mode from command line 
		// in the WifiClient main method, but I wanted to modify Brad's code as
		// little as possible.
		new LinkLayer(RTT_ORIGIN_MAC, null, LinkLayer.MODE_ROUND_TRIP_TEST);
	}
}
