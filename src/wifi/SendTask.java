package wifi;

import java.util.HashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import rf.RF;

public class SendTask implements Runnable {
	
	private static final String TAG = "SendTask";
	
	// STATES
	private static final int INITIALIZED = 0;
	private static final int WAITING_FOR_DATA = 1;
	private static final int WAITING_FOR_OPEN_CHANNEL = 2;
	private static final int WAITING_PACKET_IFS = 3;
	private static final int WAITING_BACKOFF = 4;
	private static final int WAITING_FOR_ACK = 5;

	// ESSENTIALS
	private RF mRF;
	private PriorityBlockingQueue<Packet> mSendQueue;
	private BlockingQueue<Packet> mRecvAckQueue;
	private NSyncClock mClock;
	private AtomicInteger mHostStatus;
	
	// CURRENT STATE
	private Packet mPacket;
	private int mState = INITIALIZED;
	private int mSlotSelectionPolicy;
	// Map of last seq nums by dest address
	private HashMap<Short, Short> mLastSeqs; 
	private long mLastEvent;
	private long mLastBeaconEvent;
	
	// COUNTS
	private static final int MAX_RETRIES = RF.dot11RetryLimit;
	// We really track tries, so we'll check against retries + initial attempt.
	private static final int MAX_TRY_COUNT = MAX_RETRIES + 1;
	private int mTryCount = 0;
	
	// INTERVALS
	private static final long NANO_PER_MILLIS = NSyncClock.NANO_PER_MILLIS;
	private long mAckWait;
	private long mBackoff = 0L;
	
	// Contention window
	public static final long CW_MIN = NSyncClock.CW_MIN;
	public static final long CW_MAX = NSyncClock.CW_MAX;
	private long mCW = NSyncClock.CW_MIN;
	private static final long A_SLOT_TIME = NSyncClock.A_SLOT_TIME;
	
	public SendTask(
			RF rf,
			NSyncClock nSyncClock,
			AtomicInteger hostStatus,
			PriorityBlockingQueue<Packet> sendQueue, 
			BlockingQueue<Packet> recvAckQueue) 
	{		
		mRF = rf;
		mSendQueue = sendQueue;
		mRecvAckQueue = recvAckQueue;
		mClock = nSyncClock;
		mHostStatus = hostStatus;
		mAckWait = mClock.ackWaitEst();
		mLastSeqs = new HashMap<Short,Short>();
		Log.d(TAG, TAG + " initialized!");
		setState(WAITING_FOR_DATA);
	}
	
	@Override
	public void run() {
		boolean done = false;
		while(!Thread.interrupted() && !done) {
			
			switch(mState) {
			
			case WAITING_FOR_DATA:
				try {
					mPacket = null;
					long beaconInterval = mClock.getBeaconInterval();
					long beaconElapsed = mClock.time() - mLastBeaconEvent;
					boolean isBaconTime = beaconElapsed >= beaconInterval;
					// if it's time for bacon
					if(beaconInterval > -1 && isBaconTime) {
						mPacket = mClock.generateBeacon();
						mLastBeaconEvent = mClock.time();
					} else {
						// otherwise block on poll for beaconInterval
						long nanoWait = mClock.getBeaconIntervalNano();
						mPacket = mSendQueue.poll(nanoWait, 
												  TimeUnit.NANOSECONDS);
					}
					
					// If we got a packet either way
					if(mPacket != null) {
						// Only set seq number for data
						// ACK sequence numbers are already set by the RecvTask
						if(mPacket.getType() == Packet.CTRL_DATA_CODE) {
							Short nextSeq = 
									getNextSeqNum(mPacket.getDestAddr());
							mPacket.setSequenceNumber(nextSeq);
						}
						mTryCount = 0;
						setBackoff(mTryCount, mPacket.getType());
						setState(WAITING_FOR_OPEN_CHANNEL);
					}
				} catch (InterruptedException e) {
					done = true;
					Log.e(TAG, e.getMessage());
				}
				break;
			
			case WAITING_FOR_OPEN_CHANNEL:
				if(!mRF.inUse()) {
					setState(WAITING_PACKET_IFS);
				} else {
					try {
						sleepyTime();
					} catch(InterruptedException e) {
						done = true;
						Log.e(TAG, e.getMessage());		
					}
				}
				break;
			
			case WAITING_PACKET_IFS:
				long waitFor = mPacket.getIFS();
				if(mRF.inUse()) {
					setState(WAITING_FOR_OPEN_CHANNEL);
					//TODO use mRf.getIdleTime() ?
				} else if(mClock.nanoTime() - mLastEvent >= waitFor) {
					setState(WAITING_BACKOFF);
				} else {
					try {
						sleepyTime();
					} catch(InterruptedException e) {
						done = true;
						Log.e(TAG, e.getMessage());		
					}
				}
				break;
				
			case WAITING_BACKOFF:
				long elapsed = mClock.nanoTime() - mLastEvent;
				if(mRF.inUse()) {
					mBackoff = mBackoff - elapsed;
					setState(WAITING_FOR_OPEN_CHANNEL);
				} else if(elapsed >= mBackoff) {
					if(mPacket.isBeacon()) {
						// update time to the latest
						mClock.updateBeacon(mPacket);
					}
					// TODO in theory some jerkface could jump on the channel while we're updating the beacon
					// TODO handle problems with transmit
					int status = transmit(mPacket);
					mTryCount++;
					// Log tranmit time if we're in RTT test mode
					if(LinkLayer.layerMode == LinkLayer.MODE_ROUND_TRIP_TEST)
						mClock.logTransmitTime(mPacket.hashCode(), mRF.clock());
					if(mPacket.getType() == Packet.CTRL_DATA_CODE) {
						// Wait for an ack
						setState(WAITING_FOR_ACK);
					} else {
						// Don't bother with retries for ACKS and BEACONS
						retirePacket();
						setState(WAITING_FOR_DATA);
					}
				} else {
					try {
						sleepyTime();
					} catch(InterruptedException e) {
						done = true;
						Log.e(TAG, e.getMessage());		
					}
				}
				break;
				
			case WAITING_FOR_ACK:
				// If we're done with this packet
				if(mTryCount >= MAX_TRY_COUNT || receivedAckFor(mPacket)) {
					if(mTryCount >= MAX_TRY_COUNT) {
						// we're done because we give up
						Log.d(TAG, "Giving up on packet " + 
								mPacket.getSequenceNumber());

						mHostStatus.set(LinkLayer.TX_FAILED);
					} else {
						// success! we're done because we succeeded!!!
						Log.d(TAG, "Sender received packet" + 
								mPacket.getSequenceNumber());
						mHostStatus.set(LinkLayer.TX_DELIVERED);
						NSyncClock.dance();
					}
					
					// Moving on
					retirePacket();
					setState(WAITING_FOR_DATA);
				} else if(mClock.time() - mLastEvent >= mAckWait) {
					// No ack, resend.
					Log.d(TAG, "No ACK received. Collision has occured.");
					mPacket.setRetry(true);
					setBackoff(mTryCount, mPacket.getType());
					setState(WAITING_FOR_OPEN_CHANNEL);
				} else {
					// there's nothing to do yet, sleepytime
					try {
						sleepyTime();
					} catch(InterruptedException e) {
						done = true;
						Log.e(TAG, e.getMessage());		
					}
				}
				break;
				
			} // End switch
			
		} // End while
		
		Log.e(TAG, "Interrupted!");
	}
	
	private void setState(int newState) {
		mLastEvent = mClock.time();
		mState = newState;
		switch(newState) {
		case WAITING_FOR_DATA :
			Log.d(TAG, "Waiting for data.");
			break;
		case WAITING_FOR_OPEN_CHANNEL:
//			Log.d(TAG, "Waiting for open channel. Try count: " + mTryCount);
			break;
		case WAITING_PACKET_IFS:
			long p = mPacket.getPriority();
//			Log.d(TAG, "Waiting packet priority: " + p);
			break;
		case WAITING_BACKOFF:
//			Log.d(TAG, "Waiting backoff: " + mBackoff);
			break;
		case WAITING_FOR_ACK:
//			double ms = mAckWaitNanoSec / NANO_SEC_PER_MS;
//			Log.d(TAG, "Waiting " + ms + "ms for ACK.");
			break;
		}
	}
	
	private void retirePacket() {
		mPacket = null;
	}
	
	private boolean receivedAckFor(Packet p) {
		boolean recvdAck = false;
		// synchronized block b/c otherwise other threads might screw us up
		// we want to process the snapshot of the queue for this moment
		synchronized(mRecvAckQueue) {
			while(mRecvAckQueue.peek() != null) {
				Packet ack = mRecvAckQueue.poll();
				if(ack.getSequenceNumber() == p.getSequenceNumber() &&
						ack.getSrcAddr() == p.getDestAddr()) {
					recvdAck = true;
					break; // Found an ack, end the search
				}
			}
		}
		return recvdAck;
	}

	/**
	 * Set the backoff according to 802.11 specifications.
	 * See Sec 9.3.3 Random backoff time in the 2012 IEEE 802.11 spec.
	 * @param tryCount - int, number of tries including initial try
	 */
	private void setBackoff(int tryCount, int pType) {
		if(tryCount < 0) 
			throw new IllegalStateException(TAG + ": tryCount cannot be < 0");
		if(pType == Packet.CTRL_BEACON_CODE) {
			mBackoff = 0L;
		} else {
			// Reset mCW if tries == 0
			// else double mCW and add one after every retry
			long newCW = (tryCount > 0) ? (mCW * 2 + 1L) : CW_MIN;

			// but clamp it to our specified range
			mCW = Math.max(CW_MIN, Math.min(newCW, CW_MAX));
			// Get a random backoff in the range [0,mCW]
			mBackoff = Utilities.nextLong(mCW + 1L) * A_SLOT_TIME;
			
			// If slot selection override
			if(mSlotSelectionPolicy != 0) {
				// Instead, take mCW as backoff
				mBackoff = mCW * A_SLOT_TIME;
			}
		}
	}
	
	private int transmit(Packet p) {
		Log.i(TAG, "Transmitting the following packet, try " + 
				mTryCount + "\n    " + p.toString());
		return mRF.transmit(p.getBytes());
	}
	
	/**
	 * Set the slot selection policy
	 * @param policy - int, 0 always MAX_CW, non-zero choose slots at random
	 */
	protected void setSlotSelectionPolicy(int policy) {
		mSlotSelectionPolicy = policy;
	}
	
	protected int getSlotSelectionPolicy() {
		return mSlotSelectionPolicy;
	}
	
	/**
	 * Sleeps for the default time
	 * @throws InterruptedException if thread is interrupted.
	 */
	protected void sleepyTime() throws InterruptedException {
		int totalNanoWait = (int) (NSyncClock.getSlotTimeNano() / 10);
		sleepyTime(totalNanoWait);
	}
	
	/**
	 * Sleeps for the specified time
	 * @param ms
	 * @throws InterruptedException if thread is interrupted.
	 */
	protected void sleepyTime(long nano) throws InterruptedException {
		int millisWait = (int) (nano / NANO_PER_MILLIS);
        int nanoWait = (int) (nano % NANO_PER_MILLIS);
		//Log.d(TAG, "sleepyTime! " + nano + "ms");
		Thread.sleep(millisWait, nanoWait);
	}
	
	/**
	 * Gets the next sequence number for the specified destination address.
	 * A call to this method will increment the sequence number
	 * @param destAddr Destination address
	 * @return The next sequence number for specified destination address
	 */
	private Short getNextSeqNum(short destAddr) {
		Short curSeqNum = mLastSeqs.get(destAddr);
		if(curSeqNum == null || curSeqNum + 1 > Packet.MAX_SEQ_NUM) {
			// We have never sent to this address, or next seq num exceeds max
			curSeqNum = 0;
			mLastSeqs.put(destAddr, (short) 0);
		} else {
			mLastSeqs.put(destAddr, ++curSeqNum);
		}

		return curSeqNum;
	}
}
