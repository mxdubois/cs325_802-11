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
	private HashMap<Short, Short> mLastSeqs; // Maps dest addrs to last sequence nums
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
	private long mBeaconInterval = -1; //1000L * NANO_SEC_PER_MS;
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
			BlockingQueue<Packet> recvAckQueue) 
	{		
		mRF = rf;
		mSendQueue = sendQueue;
		mRecvAckQueue = recvAckQueue;
		mClock = nSyncClock;
		mHostStatus = hostStatus;
		mAckWaitNanoSec = mClock.nanoAckWaitEst();
		
		Log.d(TAG, TAG + " initialized!");
		setState(WAITING_FOR_DATA);
	}
	
	@Override
	public void run() {
		while(!Thread.interrupted()) { 
			
			switch(mState) {
			
			case WAITING_FOR_DATA:
				try {
					mPacket = null;
					
					long beaconElapsed = System.nanoTime() - mLastBeaconEvent;
					if(mBeaconInterval > -1 && beaconElapsed >= mBeaconInterval) {
						// we need to send a beacon
						mPacket = mClock.generateBeacon();
						mLastBeaconEvent = System.nanoTime();
					} else {
						mPacket = mSendQueue.poll(mBeaconInterval, 
												TimeUnit.NANOSECONDS);
					}
					if(mPacket != null) {
						Short nextSeq = getNextSeqNum(mPacket.getDestAddr());
						mPacket.setSequenceNumber(nextSeq);
						mTryCount = 0;
						setBackoff(mTryCount, mPacket.getType());
						setState(WAITING_FOR_OPEN_CHANNEL);
					}
				} catch (InterruptedException e) {
					Log.e(TAG, e.getMessage());
				}
				break;
			
			case WAITING_FOR_OPEN_CHANNEL:
				if(!mRF.inUse()) {
					setState(WAITING_PACKET_IFS);
				} else {
					try {
						Thread.sleep(0, (int)(A_SLOT_TIME_NANO/10)); // TODO sleep? for how long?
						
					} catch(InterruptedException e) {
						Log.e(TAG, e.getMessage());		
					}
				}
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
					int status = transmit(mPacket);
					mTryCount++;
					if(mPacket.isBeacon()) {
						// Don't bother with retries
						retirePacket();
						setState(WAITING_FOR_DATA);
					} else {
						// Wait for an ack
						setState(WAITING_FOR_ACK);
					}
				} else {
					// Sleep how long?
				}
				break;
				
			case WAITING_FOR_ACK:
				if(mTryCount >= MAX_TRY_COUNT || receivedAckFor(mPacket)) {
					if(mTryCount >= MAX_TRY_COUNT) {
						// we give up
						Log.d(TAG, "Giving up on packet " + mPacket.getSequenceNumber());

						mHostStatus.set(LinkLayer.TX_FAILED);
					} else {
						// success!!
						Log.d(TAG, "Sender received packet" + mPacket.getSequenceNumber());

						mHostStatus.set(LinkLayer.TX_DELIVERED);
						NSyncClock.dance();
					}
					// Moving on
					retirePacket();
					setState(WAITING_FOR_DATA);
				} else if(System.nanoTime() - mLastEvent >= mAckWaitNanoSec) {
					// No ack, resend.
					Log.d(TAG, "No ACK received. Collision has occured.");
					mPacket.setRetry(true);
					setBackoff(mTryCount, mPacket.getType());
					setState(WAITING_FOR_OPEN_CHANNEL);
				} else {
					// TODO sleep? for how long?
				}
				break;
				
			} // End switch
			
		} // End while
		
		Log.e(TAG, "Interrupted!");
		// TODO cleanup? Idk.. this is Java, prolly doesn't matter.
	}
	
	private void setState(int newState) {
		mLastEvent = System.nanoTime();
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
			double ms = mAckWaitNanoSec / NANO_SEC_PER_MS;
//			Log.d(TAG, "Waiting " + ms + "ms for ACK.");
			break;
		}
	}
	
	private void retirePacket() {
		mPacket = null;
//		mSequenceNumber++;
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
			mBackoff = Utilities.nextLong(mCW + 1L) * A_SLOT_TIME_NANO;
			
			// If slot selection override
			if(mSlotSelectionPolicy != 0) {
				// Instead, take mCW as backoff
				mBackoff = mCW * A_SLOT_TIME_NANO;
			}
		}
	}
	
	private int transmit(Packet p) {
		Log.i(TAG, "Transmitting the following packet, try " + mTryCount + "\n    " + p.toString());
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
