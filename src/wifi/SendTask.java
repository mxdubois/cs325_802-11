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

	private static final long EPSILON = 0;
	
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
			long elapsed = mClock.time() - mLastEvent;
			switch(mState) {
			
			case WAITING_FOR_DATA:
				try {
					mPacket = null;
					long beaconInterval = mClock.getBeaconInterval();
					long beaconElapsed = 
							mClock.time() - mClock.getLastBeaconEvent();
					boolean isBaconTime = beaconElapsed >= beaconInterval;
					// if it's time for bacon
					if(beaconInterval > -1 && isBaconTime) {
						// We generate a beacon packet here, but it's mostly
						// just a dummy that we can channel through the usual
						// sending logic. We update its time just before sending
						// to get a more accurate time
						// ( remember, we have no idea how long we'll have 
						// to wait for an open channel to send this sucker)
						mPacket = mClock.generateBeacon();
					} else {
						// otherwise block on poll for no more than
						// beaconInterval so that we don't miss the next
						// opportunity to fry up some bacon
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
				long ifs = mPacket.getIFS();
				long timeLeft = ifs - elapsed;
				if(mRF.inUse() || mRF.getIdleTime() < elapsed) {
					// Someone jumped on, determine how long we actually waited
					//  this is "wasted time", but we can travel back in time
					long wastedTime = elapsed - mRF.getIdleTime();
					long stateChangeTime = mClock.time() - wastedTime;
					setState(WAITING_FOR_OPEN_CHANNEL, stateChangeTime);
					
					// Else if waited long enough && within EPSILON of 50 units
					// we might use an EPSILON if we didn't trust the OS to
					// wake us exactly on a 50 unit increment.
				} else if(timeLeft <= 0) {
					long time = mClock.time();
					if(time % 50 > EPSILON) {
						// Busy wait until the next 50 unit boundary
						break;
					}
					Log.d(TAG, "done waiting IFS at " + time);
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
				timeLeft = mBackoff - elapsed;
				if(mRF.inUse() || mRF.getIdleTime() < elapsed) {
					// Someone jumped on, determine how long we actually waited
					// before they got on, subtract that from backoff
					// rest is "wasted time", but we can travel back in time
					long wastedTime = elapsed - mRF.getIdleTime();
					long stateChangeTime = mClock.time() - wastedTime;
					mBackoff = mBackoff - (elapsed - wastedTime);
					setState(WAITING_FOR_OPEN_CHANNEL, stateChangeTime);
					
					// Else if waited long enough && within EPSILON of 50 units
					// we might use an EPSILON if we didn't trust the OS to
					// wake us exactly on a 50 unit increment.
				} else if(timeLeft <=0) {
					long time = mClock.time();
					if(time % 50 > EPSILON) {
						// Busy wait until the next 50 unit boundary
						break;
					}
					Log.d(TAG,"done waiting backoff at " + mClock.time());
					if(mPacket.isBeacon()) {
						// update time to the latest
						mClock.updateBeacon(mPacket);
						// Check to see if some jerkface jumped on our channel
						if(mRF.inUse()) {
							// mClock!!! You were too slow!!!
							mBackoff = mClock.time() - mLastEvent;
							setState(WAITING_FOR_OPEN_CHANNEL);
						}
					}
					
					// Fire away!
					int bytesSent = transmit(mPacket);
					if(mPacket.isBeacon()) 
						mClock.onBeaconTransmit();
					mTryCount++;
					
					if(bytesSent > mPacket.size()) {
						// We take a semi-naive approach if the RF failed to
						// send the whole packet. We treat it like a collision
						// but since we know the packet didn't get out, 
						// we can skip WAITIING_FOR_ACK, -- we won't get one.
						prepareForRetry();
						setState(WAITING_FOR_OPEN_CHANNEL);
					} else if(mPacket.getType() == Packet.CTRL_DATA_CODE) {
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
				
			/* TODO 
			 * optimize. seems like we could be waiting for an open channel
			 * and waiting packet ifs while we wait for an ack
			 * we just wouldn't send unless ackWaitTime has elapsed
			 * it'd be more complicated though...
			 */
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
				} else if(elapsed >= mAckWait) {
					// No ack, resend.
					Log.d(TAG, "No ACK received. Collision has occured.");
					prepareForRetry();
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
		setState(newState, mClock.time());
	}
	
	private void setState(int newState, long time) {
		// align frame start to 50 unit increment
		mLastEvent = time + (time % 50); 
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
	
	private void prepareForRetry() {
		mPacket.setRetry(true);
		setBackoff(mTryCount, mPacket.getType());
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
