package wifi;

import java.util.Queue;
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
	Queue<Short> mRecvAck;
	NSyncClock mClock;
		
	// TODO lots of parameters. Builder pattern?
	public RecvTask(RF rf, NSyncClock clock, Queue<Short> recvAck, BlockingQueue<Packet> recvData, short hostAddr) {
		mRF = rf;
		mClock = clock;
		mRecvData = recvData;
		mRecvAck = recvAck;
		mHostAddr = hostAddr;
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
			if(Packet.parseDest(recvTrans) == mHostAddr) { // Only consume packets sent to this host
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
		mRecvAck.add(ackPack.getSequenceNumber());
	}
	
	private void consumeBeacon(Packet beaconPacket) {
		Log.i(TAG, "Consuming B(E)ACON packet");
		mClock.consumeBacon(beaconPacket);
	}
	
	private void consumeData(Packet dataPacket) {
		Log.i(TAG, "Consuming DATA packet");
		try {
			// If space remains, add to end
			if(mRecvData.remainingCapacity() != 0) {
				mRecvData.put(dataPacket);
			// If queue is full, remove oldest element
			} else {
				mRecvData.take();
				mRecvData.put(dataPacket);
			}
		} catch(InterruptedException ex) {
			Log.e(TAG, "RevTask interrupted when blocking on the receive data queue");
		}
	}

}
