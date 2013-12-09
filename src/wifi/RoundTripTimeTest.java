package wifi;

public class RoundTripTimeTest {
	
	private static final short RTT_ORIGIN_MAC = 601;

	public static void main(String[] args) {
		LinkLayer ll = new LinkLayer(RTT_ORIGIN_MAC, null, 
				LinkLayer.MODE_ROUND_TRIP_TEST);
	}
}
