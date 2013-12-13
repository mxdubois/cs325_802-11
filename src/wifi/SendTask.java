package wifi;

import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import rf.RF;

/**
 * An infinitely looping task that transmits queued packets over the given
 * RF physical layer according to the ground-breaking 802.11~ spec.
 * @author Michael DuBois & Nathan Pastor
 *
 */
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
	private BlockingQueue<Packet> mSendDataQueue;
	private BlockingQueue<Packet> mRecvAckQueue;

	private BlockingQueue<Packet> mSendAckQueue;

	private NSyncClock mClock;
	private AtomicInteger mHostStatus;
	private Random mRandom;
	
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

	private static final long EPSILON = 2;
	
	/**
	 * Creates a SendTask capable of transmitting packets according to 802.11~
	 * @param rf - The RF physical layer upon which we should transmit
	 * @param nSyncClock - the synced clock
	 * @param hostStatus - the atomic integer linkLayer status
	 * @param sendQueue - a queue from which we should poll data packets
	 * @param sendAckQueue - a queue from which we should poll outgoing acks
	 * @param recvAckQueue - a queue from which we should poll recvd acks
	 * @param mac - the mac address of this machine
	 */
	public SendTask(
			RF rf,
			NSyncClock nSyncClock,
			AtomicInteger hostStatus,
			BlockingQueue<Packet> sendDataQueue, 
			BlockingQueue<Packet> sendAckQueue,
			BlockingQueue<Packet> recvAckQueue,
			short mac) 
	{		
		mRF = rf;
		mSendDataQueue = sendDataQueue;
		mSendAckQueue = sendAckQueue;
		mRecvAckQueue = recvAckQueue;
		mClock = nSyncClock;
		mHostStatus = hostStatus;
		mLastSeqs = new HashMap<Short,Short>();
		if(LinkLayer.layerMode == LinkLayer.MODE_ROUND_TRIP_TEST)
			mAckWait = mClock.ackWaitRttTest();
		else
			mAckWait = mClock.ackWaitEst();
		
		Log.d(TAG, TAG + " initialized!");
		// This is the only reason we need the mac
		// TODO We could just ask for a Random to be passed in
		mRandom = new Random(mac); 
		setState(WAITING_FOR_DATA);
	}
	
	@Override
	public void run() {
		boolean done = false;
		while(!Thread.interrupted() && !done) {
			
			// 802.11 spec, Section 9.3.2.8:
			// After a successful reception of a frame requiring
			// acknowledgment, transmission of the ACK frame shall 
			// commence after a SIFS period, without regard to the
			// busy/idle state of the medium.
			processAckQueue();
			
			long elapsed = mClock.time() - mLastEvent;
			switch(mState) {
			
			// Our big switch statement of task states
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
						mPacket = mSendDataQueue.poll(nanoWait, TimeUnit.NANOSECONDS);
					}
					
					// If we got a packet either way
					if(mPacket != null) {
						// Only set sequence number for data, ACK sequence numbers 
						// are already set by the RecvTask
						if(mPacket.getType() == Packet.CTRL_DATA_CODE) {
							Short nextSeq = getNextSeqNum(mPacket.getDestAddr());
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
				
				// ...do the usual routine:
				if(mRF.inUse() || mRF.getIdleTime() < elapsed) {
					setState(WAITING_FOR_OPEN_CHANNEL);
					
				// Done waiting this packet's IFS
				} else if(timeLeft <= 0) {
					long time = mClock.time();
					// if waited long enough && within EPSILON of 50 units
					if(time % 50 > EPSILON) {
						// Busy wait until the next 50 unit boundary
						break;
					}
					Log.d(TAG, "done waiting IFS at " + time);
					setState(WAITING_BACKOFF);
				} 
				else {
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
					// Someone jumped on
					setState(WAITING_FOR_OPEN_CHANNEL);
					
					// Else if waited long enough && within EPSILON of 50 units
					// we might use an EPSILON if we didn't trust the OS to
					// wake us exactly on a 50 unit increment.
				} else if(timeLeft <=0) {
					long time = mClock.time();
					if(time % 50 > EPSILON) {
						// Busy wait until the next 50 unit boundary
						break;
					}
					Log.d(TAG, "Done waiting backoff at " + mClock.time());
					if(mPacket.isBeacon()) {
						// update time to the latest
						mClock.updateBeacon(mPacket);
						// Check to see if some jerkface jumped on our channel
						if(mRF.inUse()) {
							// mClock!!! You were too slow!!!
							mBackoff = mClock.time() - mLastEvent;
							setState(WAITING_FOR_OPEN_CHANNEL);
							break;
						}
					}
					
					// Fire away!
					int bytesSent = transmit(mPacket);
					if(mPacket.isBeacon()) 
						mClock.onBeaconTransmit();
					// Log transmit time if we're in RTT test mode
					if(LinkLayer.layerMode == LinkLayer.MODE_ROUND_TRIP_TEST)
						mClock.logTransmitTime(mPacket.getSequenceNumber());
					mTryCount++;
					
					if(bytesSent < mPacket.size()) {
						// We take a semi-naive approach if the RF failed to
						// send the whole packet. We treat it like a collision
						// but since we know the packet didn't get out, 
						// we can skip WAITIING_FOR_ACK, -- we won't get one.
						prepareForRetry();
						setState(WAITING_FOR_OPEN_CHANNEL);
					} else if(mPacket.getType() == Packet.CTRL_DATA_CODE) {
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
						Log.d(TAG, "Sender received packet " + 
								mPacket.getSequenceNumber());
						mHostStatus.set(LinkLayer.TX_DELIVERED);
						NSyncClock.dance();
						
						// If in RTT mode, check if the RTT test is done
						if(LinkLayer.layerMode == LinkLayer.MODE_ROUND_TRIP_TEST &&
								mPacket.getSequenceNumber() == 
								RoundTripTimeTest.NUM_RTT_PACKETS - 1)
							mClock.processRTTResults();							
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
			}
		}
		
		Log.e(TAG, "Interrupted!");
	}
	
	/**
	 * Sets the state and logs the state change event with current clock time
	 * @param newState This task's new state
	 */
	private void setState(int newState) {
		setState(newState, mClock.time());
	}
	
	/**
	 * Sets the state and logs state change with specified time
	 * @param newState This task's new state
	 * @param time The time of the state change
	 */
	private void setState(int newState, long time) {
		// align frame start to 50 unit increment
		mLastEvent = time + (time % 50); 
		mState = newState;
		switch(newState) {
		case WAITING_FOR_DATA :
			Log.d(TAG, "Waiting for data.");
			break;
		case WAITING_FOR_OPEN_CHANNEL:
			Log.d(TAG, "Waiting for open channel. Try count: " + mTryCount);
			break;
		case WAITING_PACKET_IFS:
			Log.d(TAG, "Waiting packet priority: " + mPacket.getIFS());
			break;
		case WAITING_BACKOFF:
			Log.d(TAG, "Waiting backoff: " + mBackoff);
			break;
		case WAITING_FOR_ACK:
			Log.d(TAG, "Waiting " + mClock.ackWaitEst() + "ms for ACK.");
			break;
		}
	}
	
	/**
	 * Retires a packet from transmission candidacy
	 */
	private void retirePacket() {
		mPacket = null;
	}
	
	/**
	 * Prepares packet/vars for retry transmission
	 */
	private void prepareForRetry() {
		mPacket.setRetry(true);
		setBackoff(mTryCount, mPacket.getType());
	}
	
	/**
	 * Have we received an ack for this packet?
	 * @param p - the outgoing packet for which we should have received ack
	 * @return - true if we've received an ack, false if not
	 */
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
			mBackoff = Utilities.nextLong(mRandom, mCW + 1L) * A_SLOT_TIME;
			
			// If slot selection override
			if(mSlotSelectionPolicy != 0) {
				// Instead, take mCW as backoff
				mBackoff = mCW * A_SLOT_TIME;
			}
		}
	}
	
	/**
	 * Transmits a packet
	 * @param p - Packet to transmit
	 * @return Number of bytes transmitted
	 */
	private int transmit(Packet p) {
		Log.i(TAG, "Transmitting packet type " + p.getType()
				+ ", seq num " + p.getSequenceNumber() 
				+ ", to " + p.getDestAddr() + ". try " + mTryCount);
		return mRF.transmit(p.getBytes());
	}
	
	/**
	 * Set the slot selection policy
	 * @param policy - int, 0 always MAX_CW, non-zero choose slots at random
	 */
	protected void setSlotSelectionPolicy(int policy) {
		mSlotSelectionPolicy = policy;
	}
	
	/**
	 * Get the slot selection policy
	 * @return
	 */
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
	
	/**
	 * Sends outgoing acks in ack queue if SIFS has expired since they were born
	 */
	private void processAckQueue() {
		Packet ack = mSendAckQueue.peek();
		if(ack != null) {
			long ackElapsed = mClock.time() - ack.getTimeInstantiated();
			// Send if SIFS has elapsed and we're on a % 50 boundary
			if(ackElapsed >= Packet.SIFS && mClock.time() % 50 <= EPSILON) {
				Log.d(TAG, "Sending ack, seq num " + ack.getSequenceNumber());
				mRF.transmit(ack.getBytes());
				try {
					mSendAckQueue.take();
				} catch (InterruptedException e) {
					Log.e(TAG, e.getMessage());
					e.printStackTrace();
				}
			}			
		}
	}
}
