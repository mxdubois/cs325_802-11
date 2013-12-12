package wifi;

import java.util.HashMap;
import java.util.concurrent.BlockingQueue;

import rf.RF;

/**
 * The RecvThread class is responsible for monitoring the network 
 * @author Nathan P
 *
 */
public class RecvTask implements Runnable {

	private static final String TAG = "RecvTask";
	
	RF mRF;
	short mHostAddr;
	BlockingQueue<Packet> mRecvData;
	BlockingQueue<Packet> mRecvAck;
	BlockingQueue<Packet> mSendAckQueue;
	HashMap<Short, Short> mLastSeqs; // Maps src addrs to last seq nums received
	NSyncClock mClock;
		
	// TODO lots of parameters. Builder pattern?
	public RecvTask(RF rf, NSyncClock clock, BlockingQueue<Packet> sendAckQueue,
			BlockingQueue<Packet> recvAck, BlockingQueue<Packet> recvData, short hostAddr) {
		mRF = rf;
		mClock = clock;
		mRecvData = recvData;
		mRecvAck = recvAck;
		mHostAddr = hostAddr;
		mSendAckQueue = sendAckQueue;
		mLastSeqs = new HashMap<Short, Short>();
		Log.i(TAG, TAG + " initialized");
	}
	
	@Override
	public void run() {
		// TODO: a stopping mechanism
		// http://stackoverflow.com/questions/10961714/how-to-properly-stop-the-thread-in-java
		Log.i(TAG, "RecvThread running");
		while(true) {
			byte[] recvTrans = mRF.receive();
			long recvTime = mClock.time();
			short packDest = Packet.parseDest(recvTrans);
			Log.i(TAG, "RecvThread got a transmission for " + packDest);
		   // Only consume beacons and data packets sent to this host
			if(packDest == mHostAddr || packDest == NSyncClock.BEACON_ADDR) {
				Packet packet = Packet.parse(recvTrans, mClock.time());
				// Packet is null if not valid (CRC's didn't match)
				if(packet == null)
					Log.i(TAG, "Throwing out a corrupted packet. \n ");
				else {
					int type = packet.getType();
					if(type == Packet.CTRL_ACK_CODE) {
						consumeAck(packet);
					} else if(type == Packet.CTRL_BEACON_CODE) {
						consumeBeacon(packet, recvTime);
					} else if(type == Packet.CTRL_DATA_CODE) {
						consumeData(packet);
					}
				}
			}	
		}
	}
	
	private void consumeAck(Packet ackPack) {
		Log.i(TAG, "Consuming ACK packet");
		try {
			mRecvAck.put(ackPack);
			if(LinkLayer.layerMode == LinkLayer.MODE_ROUND_TRIP_TEST)
				mClock.logRecvAckTime(ackPack.getSequenceNumber());
		} catch (InterruptedException e) {
			Log.e(TAG, "RecvTask interrupted while blocking on the received ACK queue");
			e.printStackTrace();
		}
	}
	
	private void consumeBeacon(Packet beaconPacket, long recvTime) {
		// Don't consume beacons in RTT test mode
		if(LinkLayer.layerMode != LinkLayer.MODE_ROUND_TRIP_TEST) {
			Log.i(TAG, "Consuming B(E)ACON packet");
			mClock.consumeBacon(beaconPacket, recvTime);
		}
	}
	
	/**
	 * Consumes a data packet, ensuring that the specified packet contains the 
	 * expected sequence number.
	 * @param dataPacket Data packet to deliver to above layer
	 */
	private void consumeData(Packet dataPacket) {
		Log.i(TAG, "Consuming DATA packet");
		
		// If buffer is full, ignore new packets
		if(mRecvData.remainingCapacity() == 0) {
			Log.e(TAG, "Incoming data packet queue is full, ignoring a new data packet");
			return;
		}
		
		Short lastSeqNum = mLastSeqs.get(dataPacket.getSrcAddr());
		if(lastSeqNum == null)
			lastSeqNum = -1;
		
		short packetSeqNum = dataPacket.getSequenceNumber();
		short packetSrcAddr = dataPacket.getSrcAddr();
		// Check if we've already received this packet. We ignore duplicate data, defined as
		// any packet with a sequence number less than the value we're expecting from the host.
		if(lastSeqNum >= packetSeqNum) {
			Log.e(TAG, "Discarding a duplicate data packet from address " + packetSrcAddr +
					", seq num " + packetSeqNum);
		} else {
			// Increment expected sequence number
			short nextSeqNum = (short) (lastSeqNum + 1);
			if(nextSeqNum > Packet.MAX_SEQ_NUM)
				nextSeqNum = 0;
			// Check if sender has retired a packet without this system ACKing
			// and moved on. This will be indicated by a gap in sequence numbers.
			// Log an error but queue anyway.
			if(nextSeqNum < packetSeqNum) {
				Log.e(TAG, "Gap in sequence numbers from host " + packetSrcAddr +
						". Expecting " + nextSeqNum + ", got " + packetSeqNum);
			}
			
			// Queue packet for delivery
			try {
				mRecvData.put(dataPacket);
				Log.d(TAG, "queued ack for sending");
			} catch (InterruptedException e) {
				Log.e(TAG, "Interrupted when blocking on the receive data queue");
			}
			
			// Update last sequence number
			mLastSeqs.put(packetSrcAddr, nextSeqNum);
		}
			
		// TODO do we ACK duplicate data?
		try {
			// Prepare and queue ACK
			Packet ack = new Packet(Packet.CTRL_ACK_CODE, packetSrcAddr, 
					mHostAddr, new byte[0], 0, packetSeqNum, mClock.time());
			mSendAckQueue.put(ack);
			Log.d(TAG, "Recvd data, queueing ack seq num " + packetSeqNum);
		} catch (InterruptedException e) {
			Log.e(TAG, "RecvTask interrupted when blocking on the send queue");
		}
	}

}
