package wifi;

import java.nio.ByteBuffer;

public class NSyncClock {
	
	private static final boolean DEBUG = LinkLayer.debugLevel > 0;
	private static final String TAG = "NSyncClock";
	public final static short BEACON_ADDR = (short) 0xFFFF;
	private long mOffsetNano = 0L; // offset in nanoseconds
	private final short mOurMAC;
	
	public static final long NANO_PER_MILLIS = 1000000L;
	public static final long NANO_PER_MICRO = 1000L;
	
	public NSyncClock(short macDonalds) {
		mOurMAC = macDonalds;
	}
	
	/**
	 * Mimicking the System.nanoTime() call but adds timer offset
	 * @return
	 */
	public long nanoTime() {
		return mOffsetNano + System.nanoTime();
	}
	
	/**
	 * Mimicking the System.currentTimeMillis() call but adds timer offset
	 * @return
	 */
	public long currentTimeMillis() {
		return nanoTime() / NANO_PER_MILLIS;
	}
	
	public long getNanoOffset() {
		return mOffsetNano;
	}
	
	public Packet generateBeacon() {
		long time = nanoTime();
		byte[] data = ByteBuffer
						.allocate(Long.SIZE / 8)
						.putLong(time)
						.array();
		return new Packet(Packet.CTRL_BEACON_CODE, 
				Packet.BEACON_MAC, 
				mOurMAC, 
				data,
				data.length);
	}
	
	/**
	 * Updates the time in a beacon packet to the latest time.
	 * @param p
	 */
	public void updateBeacon(Packet p) {
		Log.i(TAG, "updating beacon packet");
		// TODO is this time in microseconds? milliseconds? nanoseconds?
		long time = nanoTime() / NANO_PER_MICRO;
		byte[] data = ByteBuffer
				.allocate(Long.SIZE / 8)
				.putLong(time)
				.array();
		p.setData(data);
	}
	
	public void consumeBacon(Packet p) {
		long ourNanoTime = nanoTime(); // capture as soon as possible
		if(p.isBeacon()) {
			byte[] data = p.getData();
			long time = ByteBuffer.wrap(data).getLong();
			// TODO is this time in microseconds? milliseconds? nanoseconds?
			// TODO add estimated transfer time
			long nanoTime = time * NANO_PER_MILLIS;
			long nanoOffset = nanoTime - ourNanoTime;
			// If their time is ahead of ours, roll ours forward to match
			if( nanoOffset > 0) {
				mOffsetNano += nanoOffset; 
			}
		}
	}
	
	public long nanoAckWaitEst() {
		// TODO implement meeeeee!
		return 20L;
	}
	
	// REALLY REALLY IMPORTANT METHODS
	// ------------------------
	
	public static void dance() {
		int randInt = Utilities.random.nextInt(3);
		switch(randInt) {
		case 0:
			Log.i(TAG, "I'm doin' this tonight, / " +
					"You're probably gonna start a fight. / " +
					"I know this can't be right. ");
			break;
		case 1:
			Log.i(TAG, "It's tearin' up my heart when I'm with you / " +
						"But when we are apart, I feel it too / " +
						"And no matter what I do, I feel the pain / " +
						"with or without you");
			break;
		case 2:
			Log.i(TAG, "Thank God it's Friday night and I / " +
						"just-just-just-just-juuuuuuust got paid / " +
						"yeah, ohh...");
			break;
			
		}
	}
}
