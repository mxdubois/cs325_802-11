package wifi;

import java.util.HashMap;
import java.util.concurrent.BlockingQueue;

import rf.RF;

/**
 * The RecvThread class is responsible for monitoring the network and delivering
 * incoming packets to their appropriate destination within the Link Layer
 * @author Nathan Pastor and Michael DuBois
 *
 */
public class RecvTask implements Runnable {

	private static final String TAG = "RecvTask";
	
	private RF mRF;
	private short mHostAddr;
	private BlockingQueue<Packet> mRecvData;
	private BlockingQueue<Packet> mRecvAck;
	private BlockingQueue<Packet> mSendAckQueue;
	// Maps source addresses to last sequence number received
	private HashMap<Short, Short> mLastSeqs;
	private NSyncClock mClock;
		
	/**
	 * Instantiates a RecvTask capable of monitoring the network and delivering
	 * incoming packets to their appropriate destination within the Link Layer
	 * @param rf The phyiscal layer
	 * @param clock The synced clock
	 * @param sendAckQueue Outgoing ACK queue
	 * @param recvAck Incoming ACK queue
	 * @param recvData Incoming DATA queue
	 * @param hostAddr This client's MAC address
	 */
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
		Log.i(TAG, "RecvThread running");
		while(true) {
			byte[] recvTrans = mRF.receive(); // Block until we get a transmission
			long recvTime = mClock.time(); // Time transmission was received
			
			// Don't bother parsing the whole packet unless it's really for us.
			short packDest = Packet.parseDest(recvTrans);
			Log.d(TAG, "RecvThread got a transmission for " + packDest);
		   // Consume ACK and data packets that were sent to this host, and
			// beacons specified by their universal address
			if(packDest == mHostAddr || packDest == Packet.BEACON_MAC) {
				Packet packet = Packet.parse(recvTrans, mClock.time());
				// Packet is null if not valid (CRC's didn't match)
				if(packet == null)
					Log.i(TAG, "Throwing out a corrupted packet");
				else {
					int type = packet.getType();
					if(type == Packet.CTRL_ACK_CODE) {
						consumeAck(packet);
					} else if(type == Packet.CTRL_BEACON_CODE) {
						consumeBacon(packet, recvTime);
					} else if(type == Packet.CTRL_DATA_CODE) {
						consumeData(packet);
					}
				}
			}	
		}
	}
	
	/**
	 * Delivers an ACK packet
	 * @param ackPack The ACK packet
	 */
	private void consumeAck(Packet ackPack) {
		Log.i(TAG, "Consuming ACK packet");
		try {
			mRecvAck.put(ackPack);
			if(LinkLayer.layerMode == LinkLayer.MODE_ROUND_TRIP_TEST)
				mClock.logRecvAckTime(ackPack.getSequenceNumber());
		} catch (InterruptedException e) {
			Log.e(TAG, 
					"RecvTask interrupted while blocking on the received ACK queue");
			e.printStackTrace();
		}
	}
	
	/**
	 * Delivers a beacon packet to the clock
	 * @param beaconPacket The beacon packet
	 * @param recvTime The receive time of the packet
	 */
	private void consumeBacon(Packet beaconPacket, long recvTime) {
		// Don't consume beacons in RTT test mode
		if(LinkLayer.layerMode != LinkLayer.MODE_ROUND_TRIP_TEST) {
			Log.i(TAG, "Consuming BEACON packet");
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
			Log.e(TAG, 
				  "Incoming data packet queue is full, ignoring a new data packet");
			return;
		}
		
		Short lastSeqNum = mLastSeqs.get(dataPacket.getSrcAddr());
		if(lastSeqNum == null)
			lastSeqNum = -1;
		
		short packetSeqNum = dataPacket.getSequenceNumber();
		short packetSrcAddr = dataPacket.getSrcAddr();
		// Check if we've already received this packet. We ignore duplicate data, 
		// defined as any packet with a sequence number less than the value 
		// we're expecting from the host.
		if(lastSeqNum >= packetSeqNum) {
			Log.e(TAG, "Discarding a duplicate data packet from address " 
					+ packetSrcAddr +	", seq num " + packetSeqNum);
		} else {
			// Increment expected sequence number
			short nextSeqNum = (short) (lastSeqNum + 1);
			if(nextSeqNum > Packet.MAX_SEQ_NUM)
				nextSeqNum = 0;
			// Check if sender has retired a packet without this system ACKing
			// and moved on. This will be indicated by a gap in sequence numbers.
			// Log an error but queue anyway.
			if(nextSeqNum < packetSeqNum || 
					(nextSeqNum == 0 && packetSeqNum < Packet.MAX_SEQ_NUM)) {
				Log.e(TAG, "Gap in sequence numbers from host " + packetSrcAddr
						+ ". Expecting " + nextSeqNum + ", got " + packetSeqNum);
			}
			
			// Queue packet for delivery
			try {
				mRecvData.put(dataPacket);
			} catch (InterruptedException e) {
				Log.e(TAG, "Interrupted when blocking on the receive data queue");
			}
			
			// Update last sequence number
			mLastSeqs.put(packetSrcAddr, nextSeqNum);
		}
			
		try {
			// Prepare and queue ACK
			Packet ack = new Packet(Packet.CTRL_ACK_CODE, packetSrcAddr, 
					mHostAddr, new byte[0], 0, packetSeqNum, mClock.time());
			mSendAckQueue.put(ack);
			Log.d(TAG, "Queueing ack seq num " + packetSeqNum);
		} catch (InterruptedException e) {
			Log.e(TAG, "RecvTask interrupted when blocking on the send queue");
		}
	}

}
