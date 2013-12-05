package wifi;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import rf.RF;

public class NSyncClock {
	
	private static final boolean DEBUG = LinkLayer.debugLevel > 0;
	private static final String TAG = "NSyncClock";
	
	public static final short BEACON_ADDR = (short) 0xFFFF;
	
	public static final long NANO_PER_MILLIS = 1000000L;
	public static final long NANO_PER_MICRO = 1000L;
	
	public static final long NANO_PER_CLOCK_UNIT = NANO_PER_MILLIS;
	public static final long CLOCK_UNIT_PER_MILLIS = 1;
	
	public static final long A_SLOT_TIME = RF.aSlotTime * CLOCK_UNIT_PER_MILLIS;
	public static final long CW_MIN = RF.aCWmin * CLOCK_UNIT_PER_MILLIS;
	public static final long CW_MAX = RF.aCWmax * CLOCK_UNIT_PER_MILLIS;
	
	/**
	 * Returns the slot length in nano seconds
	 * @return
	 */
	public static long getSlotTimeNano() {
		return A_SLOT_TIME * NANO_PER_CLOCK_UNIT;
	}
	
	//-------------------------------------------------------------------------
	// INSTANCE STUFF
	//-------------------------------------------------------------------------
	
	private final short mOurMAC;
	// Just in case another thread tries to play with these
	private AtomicLong mBeaconInterval;
	private AtomicLong mOffsetNano; // offset in nanoseconds
	
	public NSyncClock(short macDonalds) {
		mOurMAC = macDonalds;
		mBeaconInterval = new AtomicLong(-1L);
		mOffsetNano.set(0L);
	}
	
	/**
	 * Returns the time we should be using to compute elapsed time
	 * @return
	 */
	public long time() {
		return nanoTime();
	}
	
	/**
	 * Mimicking the System.nanoTime() call but adds timer offset
	 * @return
	 */
	public long nanoTime() {
		return mOffsetNano.get() + System.nanoTime();
	}
	
	/**
	 * Mimicking the System.currentTimeMillis() call but adds timer offset
	 * @return
	 */
	public long currentTimeMillis() {
		return nanoTime() / NANO_PER_MILLIS;
	}
	
	public long getBeaconInterval() {
		return mBeaconInterval.get();
	}
	
	public long getBeaconIntervalNano() {
		return mBeaconInterval.get() * NANO_PER_CLOCK_UNIT;
	}
	
	public void setBeaconInterval(long clockUnits) {
		mBeaconInterval.set(clockUnits);
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
		synchronized(mOffsetNano) {
			long ourTime = time(); // capture as soon as possible
			if(p.isBeacon()) {
				byte[] data = p.getData();
				long time = ByteBuffer.wrap(data).getLong();
				// TODO add estimated transfer time
				long difference = time - ourTime;
				// If their time is ahead of ours, roll ours forward to match
				if( difference > 0) {
					mOffsetNano.set(mOffsetNano.get() + difference); 
				}
			}
		}
	}
	
	public long ackWaitEst() {
		// TODO implement meeeeee!
		return 20L;
	}
	
	//--------------------------------------------------------------------------
	// REALLY REALLY IMPORTANT METHODS
	//--------------------------------------------------------------------------
	
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
