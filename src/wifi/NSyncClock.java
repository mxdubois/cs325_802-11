package wifi;

public class NSyncClock {
	
	private static final boolean DEBUG = LinkLayer.debugLevel > 0;
	private static final String TAG = "NSyncClock";
	public final static short BEACON_ADDR = -1;
	private long mOffset = 0L; // offset in nanoseconds
	
	public static final long NANO_SEC_PER_MS = 1000000L;
	public NSyncClock() {
		
	}
	
	public long getOffset() {
		return mOffset;
	}
	
	public Packet generateBeacon() {
		return new Packet(Packet.CTRL_BEACON_CODE, 
				(short)0, 
				(short)0, 
				new byte[10],
				1);
	}
	
	/**
	 * Updates the time in a beacon packet to the latest time.
	 * @param p
	 */
	public void updateBeacon(Packet p) {
		Log.i(TAG, "updating beacon packet");
		// TODO plz implement me :(
		// Note that the Packet's CRC needs to be recomputed after this change.
	}
	
	public void consumeBacon(Packet p) {
		// TODO nom nom nom
	}
	
	public long nanoAckWaitEst() {
		// TODO implement meeeeee!
		return 20L * NANO_SEC_PER_MS;
	}
	
	// REALLY REALLY IMPORTANT METHODS
	// ------------------------
	
	public static void dance() {
		if(DEBUG) {
			int randInt = Utilities.random.nextInt(3);
			switch(randInt) {
			case 0:
				Log.i(TAG, "I'm doin' this tonight, " +
						"You're probably gonna start a fight. " +
						"I know this can't be right. ");
				break;
			case 1:
				Log.i(TAG, "It's tearin' up my heart when I'm with you " +
							"But when we are apart, I feel it too " +
							"And no matter what I do, I feel the pain " +
							"with or without you");
				break;
			case 2:
				Log.i(TAG, "Thank God it's Friday night and I " +
							"just-just-just-just-juuuuuuust got paid " +
							"yeah, ohh...");
				break;
				
			}
		}
	}
}
