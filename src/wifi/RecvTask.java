package wifi;

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
	BlockingQueue<Packet> mSendQueue;
	NSyncClock mClock;
		
	// TODO lots of parameters. Builder pattern?
	public RecvTask(RF rf, NSyncClock clock, BlockingQueue<Packet> sendQueue,
			BlockingQueue<Packet> recvAck, BlockingQueue<Packet> recvData, short hostAddr) {
		mRF = rf;
		mClock = clock;
		mRecvData = recvData;
		mRecvAck = recvAck;
		mHostAddr = hostAddr;
		mSendQueue = sendQueue;
		Log.i(TAG, TAG + " initialized");
	}
	
	@Override
	public void run() {
		// TODO: a stopping mechanism
		// http://stackoverflow.com/questions/10961714/how-to-properly-stop-the-thread-in-java
		Log.i(TAG, "RecvThread running");
		while(true) {
			byte[] recvTrans = mRF.receive();
			Log.i(TAG, "RecvThread got a transmission");
			short packDest = Packet.parseDest(recvTrans);
		   // Only consume beacons and packets sent to this host
			if(packDest == mHostAddr || packDest == NSyncClock.BEACON_ADDR) {
				Packet packet = Packet.parse(recvTrans);
				int type = packet.getType();
				if(type == Packet.CTRL_ACK_CODE) {
					consumeAck(packet);
				} else if(type == Packet.CTRL_BEACON_CODE) {
					consumeBeacon(packet);
				} else if(type == Packet.CTRL_DATA_CODE) {
					consumeData(packet);
				}
			}	
		}
	}
	
	private void consumeAck(Packet ackPack) {
		Log.i(TAG, "Consuming ACK packet");
		try {
			mRecvAck.put(ackPack);
		} catch (InterruptedException e) {
			Log.e(TAG, "RecvTask interrupted while blocking on the received ACK queue");
			e.printStackTrace();
		}
	}
	
	private void consumeBeacon(Packet beaconPacket) {
		Log.i(TAG, "Consuming B(E)ACON packet");
		mClock.consumeBacon(beaconPacket);
	}
	
	private void consumeData(Packet dataPacket) {
		Log.i(TAG, "Consuming DATA packet");
		try {
			synchronized(mRecvData) {
				// If queue is full, remove oldest element
				if(mRecvData.remainingCapacity() == 0)
					mRecvData.take();
				
				// If we've already received this packet, log an error but queue anyway
				if(mRecvData.contains(dataPacket))
					Log.e(TAG, "Received duplicate data packet from address " + dataPacket.getSrcAddr() +
							", seq num " + dataPacket.getSequenceNumber());
				mRecvData.put(dataPacket);
			}
		} catch(InterruptedException ex) {
			Log.e(TAG, "RecvTask interrupted when blocking on the receive data queue");
		}
		
		try {
			// Prepare and queue ACK
			Packet ack = new Packet(Packet.CTRL_ACK_CODE, dataPacket.getDestAddr(), 
					dataPacket.getSrcAddr(), new byte[0], 0, dataPacket.getSequenceNumber());
			mSendQueue.put(ack);
		} catch (InterruptedException e) {
			Log.e(TAG, "RecvTask interrupted when blocking on the send queue");
		}
	}

}
