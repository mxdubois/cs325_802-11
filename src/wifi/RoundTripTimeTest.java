package wifi;

public class RoundTripTimeTest {
	

	// Destination MAC for round trip tests
	public static final short RTT_TEST_DEST_MAC = 602;
	// Number of packets to send for round trip tests
	public static final int NUM_RTT_PACKETS = 5;
	
	private static final short RTT_ORIGIN_MAC = 601;
	

	public static void main(String[] args) {
		LinkLayer ll = new LinkLayer(RTT_ORIGIN_MAC, null, 
				LinkLayer.MODE_ROUND_TRIP_TEST);
	}
}
