package wifi;

/**
 * Executes a round trip time experiment.
 * @author Nathan P
 */
public class RoundTripTimeTest {	

	// Destination MAC for RTT tests
	public static final short RTT_TEST_DEST_MAC = 602;
	// Origin MAC for RTT tests (this machine)
	private static final short RTT_ORIGIN_MAC = 601;
	// Number of packets to send for RTT tests
	public static final int NUM_RTT_PACKETS = 5;

	public static void main(String[] args) {
		// All we need to do is instantiate the link layer in the correct mode.
		// It will do the rest. Ideally we could specify mode from command line 
		// in the WifiClient main method, but I wanted to modify Brad's code as
		// little as possible.
		new LinkLayer(RTT_ORIGIN_MAC, null, LinkLayer.MODE_ROUND_TRIP_TEST);
	}
}
