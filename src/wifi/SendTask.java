package wifi;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import rf.RF;

public class SendTask implements Runnable {
	
	private static final String TAG = "SendThread";
	private static final boolean DEBUG = LinkLayer.debugLevel > 0;
	
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
	private PriorityBlockingQueue<Short> mRecvAckQueue;
	private NSyncClock mClock;
	private AtomicInteger mHostStatus;
	
	// CURRENT STATE
	private Packet mPacket;
	private int mState = INITIALIZED;
	private int mSlotSelectionPolicy;
	private short mSequenceNumber = 0;
	private long mLastEvent;
	private long mLastBeaconEvent;
	
	// COUNTS
	private static final int MAX_RETRIES = RF.dot11RetryLimit;
	// We really track tries, so we'll check against retries + initial attempt.
	private static final int MAX_TRY_COUNT = MAX_RETRIES + 1;
	private int mTryCount = 0;
	
	// INTERVALS
	private static final long NANO_SEC_PER_MS = NSyncClock.NANO_SEC_PER_MS;
	private long mAckWaitNanoSec = 20L * NANO_SEC_PER_MS;
	private long mBeaconInterval = 40L * NANO_SEC_PER_MS;
	private long mBackoff = 0L;
	// Contention window
	private static final long CW_MIN = RF.aCWmin; //TODO proper value
	private static final long CW_MAX = RF.aCWmax; // TODO proper value
	private long mCW = CW_MIN;
	private static final long A_SLOT_TIME_NANO = RF.aSlotTime * NANO_SEC_PER_MS;
	
	public SendTask(
			RF rf,
			NSyncClock nSyncClock,
			AtomicInteger hostStatus,
			PriorityBlockingQueue sendQueue, 
			PriorityBlockingQueue<Short> recvAckQueue) 
	{		
		mRF = rf;
		mSendQueue = sendQueue;
		mRecvAckQueue = recvAckQueue;
		mClock = nSyncClock;
		mHostStatus = hostStatus;
		mAckWaitNanoSec = mClock.nanoAckWaitEst();
		if(DEBUG) 
			Log.i(TAG, TAG + " initialized!");
		setState(WAITING_FOR_DATA);
	}
	
	@Override
	public void run() {
		while(!Thread.interrupted()) { 
			
			switch(mState) {
			
			case WAITING_FOR_DATA:
				try {
					if(System.nanoTime() - mLastBeaconEvent >= mBeaconInterval) {
						// we need to send a beacon
						mPacket = mClock.generateBeacon();
						mLastBeaconEvent = System.nanoTime();
					} else {
						mPacket = mSendQueue.take();
					}
					mPacket.setSequenceNumber(mSequenceNumber);
					mTryCount = 0;
					setBackoff(mTryCount, mPacket.getType());
					setState(WAITING_FOR_OPEN_CHANNEL);
				} catch (InterruptedException e) {
					Log.e(TAG, e.getMessage());
				}
				break;
			
			case WAITING_FOR_OPEN_CHANNEL:
				if(!mRF.inUse()) {
					setState(WAITING_PACKET_IFS);
				} else; // TODO sleep? for how long?
				break;
			
			case WAITING_PACKET_IFS:
				long waitFor = mPacket.getIFS();
				if(mRF.inUse()) {
					setState(WAITING_FOR_OPEN_CHANNEL);
					//TODO use mRf.getIdleTime() ?
				} else if(System.nanoTime() - mLastEvent >= waitFor) {
					setState(WAITING_BACKOFF);
				} else {
					// TODO sleep? for how long?
				}
				break;
				
			case WAITING_BACKOFF:
				long elapsed = System.nanoTime() - mLastEvent;
				if(mRF.inUse()) {
					mBackoff = mBackoff - elapsed;
					setState(WAITING_FOR_OPEN_CHANNEL);
				} else if(elapsed >= mBackoff) {
					if(mPacket.isBeacon()) {
						// update time to the latest
						mClock.updateBeacon(mPacket);
					}
					// TODO handle problems with transmit
					transmit(mPacket);
					mTryCount++;
					setState(WAITING_FOR_ACK);
				}
				break;
				
			case WAITING_FOR_ACK:
				if(mTryCount >= MAX_TRY_COUNT || receivedAckFor(mPacket)) {
					if(mTryCount >= MAX_TRY_COUNT) {
						// we give up
						if(DEBUG) {
							Log.i(TAG, "Giving up on packet " 
									+ mPacket.getSequenceNumber());
						}
						mHostStatus.set(LinkLayer.TX_FAILED);
					} else {
						// success!!
						if(DEBUG) {
							Log.i(TAG, "Sender received packet" 
									+ mPacket.getSequenceNumber());
						}
						mHostStatus.set(LinkLayer.TX_DELIVERED);
						NSyncClock.dance();
					}
					mPacket = null;
					setState(WAITING_FOR_DATA);
				} else if(System.nanoTime() - mLastEvent >= mAckWaitNanoSec) {
					// No ack, resend.
					if(DEBUG)
						Log.i(TAG, "No ACK received.");
					mPacket.setRetry(true);
					setBackoff(mTryCount, mPacket.getType());
					setState(WAITING_FOR_OPEN_CHANNEL);
				} else {
					// TODO sleep? for how long?
				}
				break;
				
			} // End switch
			
		} // End while
		
		if(DEBUG) 
			Log.e(TAG, "Interrupted!");
		// TODO cleanup? Idk.. this is Java, prolly doesn't matter.
	}
	
	private void setState(int newState) {
		mLastEvent = System.nanoTime();
		mState = newState;
		switch(newState) {
		case WAITING_FOR_DATA :
			if(DEBUG) 
				Log.i(TAG, "Waiting for data.");
			break;
		case WAITING_FOR_OPEN_CHANNEL:
			if(DEBUG) {
				Log.i(TAG, "Waiting for open channel. Try count: " + mTryCount);
			}
			break;
		case WAITING_PACKET_IFS:
			long p = mPacket.getPriority();
			if(DEBUG) 
				Log.i(TAG, "Waiting packet priority: " + p);
			break;
		case WAITING_BACKOFF:
			if(DEBUG) 
				Log.i(TAG, "Waiting backoff: " + mBackoff);
			break;
		case WAITING_FOR_ACK:
			if(DEBUG) {
				double ms = mAckWaitNanoSec / NANO_SEC_PER_MS;
				Log.i(TAG, "Waiting " + ms + "ms for ACK.");
			}
			break;
		}
	}
	
	private boolean receivedAckFor(Packet p) {
		boolean recvdAck = false;
		// synchronized block b/c otherwise other threads might screw us up
		// we want to process the snapshot of the queue for this moment
		synchronized(mRecvAckQueue) {
			if(mRecvAckQueue.peek() != null) {
				short seqNum = p.getSequenceNumber();
				short recievedSeqNum = mRecvAckQueue.peek();
				// Discard seqNums less than current 
				// and duplicates equal to current
				while(mRecvAckQueue.peek() != null 
						&& mRecvAckQueue.peek() <= seqNum) {
					recievedSeqNum = mRecvAckQueue.poll();
				}
				// If current seqNum was recvd, it will remain in receivedSeqNum
				recvdAck = (recievedSeqNum == seqNum);
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
		} else if(mSlotSelectionPolicy != 0) {
			// Override specification for debugging
			mBackoff = CW_MAX * A_SLOT_TIME_NANO;
		} else {
			// Reset mCW if tries == 0
			// Double mCW and add one after every retry
			long newCW = (tryCount > 0) ? (mCW * 2 + 1L) : CW_MIN;
			// but clamp it to our specified range
			mCW = Math.max(CW_MIN, Math.min(newCW, CW_MAX));
			// Get a random backoff in the range [0,mCW]
			mBackoff = Utilities.nextLong(mCW + 1L) * A_SLOT_TIME_NANO;
		}
		Log.i(TAG, "tryCount: " + mTryCount);
		Log.i(TAG, "mCW: " + mCW);
		Log.i(TAG, "backoff: " + mBackoff);
	}
	
	private int transmit(Packet p) {
		if(DEBUG) {
			Log.i(TAG, "Transmitting the following packet: \n" + p.toString());
		}
		return mRF.transmit(p.getBytes());
	}
	
	
	// PACKAGE PRIVATE / PROTECTED STUFF
	protected void setBeaconInterval(long interval) {
		mBeaconInterval = interval;
	}
	
	protected long getBeaconInterval() {
		return mBeaconInterval;
	}
	
	/**
	 * Set the slut selection policy
	 * @param policy - int, 0 always MAX_CW, non-zero choose sluts at random
	 */
	protected void setSlotSelectionPolicy(int policy) {
		mSlotSelectionPolicy = policy;
	}
	
	protected int getSlotSelectionPolicy() {
		return mSlotSelectionPolicy;
	}
	
	
}
