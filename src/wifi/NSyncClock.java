package wifi;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import rf.RF;

public class NSyncClock {
	
	private static final boolean DEBUG = LinkLayer.debugLevel > 0;
	private static final String TAG = "NSyncClock";
	
	public static final short BEACON_ADDR = (short) 0xFFFF;
	
	public static final long NANO_PER_MILLIS = 1000000L;
	public static final long NANO_PER_MICRO = 1000L;
	
	// Define clock unit conversions
	public static final long NANO_PER_CLOCK_UNIT = NANO_PER_MILLIS;
	public static final long CLOCK_UNIT_PER_MILLIS = 
										NANO_PER_CLOCK_UNIT / NANO_PER_MILLIS;
	
	// The time per slot in our clock units
	public static final long A_SIFS_TIME = RF.aSIFSTime * CLOCK_UNIT_PER_MILLIS;
	public static final long A_SLOT_TIME = RF.aSlotTime * CLOCK_UNIT_PER_MILLIS;
	// contention window parameters in slots
	public static final long CW_MIN = RF.aCWmin;
	public static final long CW_MAX = RF.aCWmax;
	
	// Number of beacon transmits to average to calculate fudge
	private static final int NUM_TRANSMITS_TO_AVG = 10;
	
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
	// Just in case another thread tries to play with these through setter
	// methods or consumeBacon
	private AtomicLong mBeaconInterval;
	private AtomicLong mOffset; // offset in nanoseconds
	
	// Track beacon events
	private long[] mTDiffArray;
	private int mTDiffArraySize; // how many slots have been filled
	private int mTDiffArrayIdx = 0; // the current index
	// transmit fudge to fix our outgoing bacon. Yum! Yum!
	private long mTransmitFudge = 0L; 
	// Remember the last time we packaged up some bacon? It was great.
	private long mLastBeaconEvent;

	
	public NSyncClock(short macDonalds) {
		mOurMAC = macDonalds;
		mBeaconInterval = new AtomicLong(-1L);
		mOffset = new AtomicLong(0L);
		mTDiffArray = new long[NUM_TRANSMITS_TO_AVG];
		mTDiffArraySize = 0;
	}
	
	/**
	 * Returns the time we should be using to compute elapsed time
	 * @return
	 */
	public long time() {
		return mOffset.get() + System.currentTimeMillis();
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
	
	/**
	 * Generates a beacon packet
	 * @return
	 */
	public Packet generateBeacon() {
		long time = time();
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
		mLastBeaconEvent = time();
		long time = time() + mTransmitFudge;
		byte[] data = ByteBuffer
				.allocate(Long.SIZE / 8)
				.putLong(time)
				.array();
		p.setData(data);
	}
	
	public void onBeaconTransmit() {
		long diff = time() -  mLastBeaconEvent;
		// Store this diff, replacing old if array is full
		mTDiffArray[mTDiffArrayIdx] = diff;
		// Increment size if necessary
		if(mTDiffArraySize < mTDiffArray.length)
			mTDiffArraySize = mTDiffArraySize + 1;
		// calc index for next time
		mTDiffArrayIdx = (mTDiffArrayIdx + 1) % mTDiffArray.length;
		
		// Calculate the new average
		double sum = 0L;
		double numItems = mTDiffArraySize;
		for(int i = 0; i < mTDiffArraySize; i++) {
			sum +=mTDiffArray[i];
		}
		mTransmitFudge = (long) (sum / numItems);
		Log.d(TAG, "New outgoing beacon fudge is " + mTransmitFudge);
	}
	
	public void consumeBacon(Packet p) {
		consumeBacon(p, time());
	}
	
	public void consumeBacon(Packet p, long timeRecvd) {
		synchronized(mOffset) {
			if(p.isBeacon()) {
				byte[] data = p.getData();
				long packetTime = ByteBuffer.wrap(data).getLong();
				long difference = packetTime - timeRecvd;
				
				Log.d(TAG, "Recvd beacon has time: " + packetTime);
				Log.d(TAG, "We have: " + timeRecvd);
				Log.d(TAG, "Diff: " + difference);
				
				// If their time is ahead of ours, roll ours forward to match
				if( difference > 0) {
					long newOffset = mOffset.get() + difference;
					mOffset.set(newOffset);
					Log.d(TAG, "new offset: " + newOffset);
				} else if ( difference == 0){
					Log.d(TAG, "We're NSYNC! Heyoooo!");
				} else {
					Log.d(TAG, "Them fools stuck in the past.");
				}
			}
		}
	}
	
	public long ackWaitEst() {
		// TODO implement meeeeee!
		return 20L;
	}
	
	public long getLastBeaconEvent() {
		return mLastBeaconEvent;
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
