package wifi;

import java.util.Queue;

import rf.RF;

/**
 * The RecvThread class is responsible for monitoring the network 
 * @author Nathan P
 *
 */
public class RecvThread implements Runnable {

	private static final String TAG = "RecvThread";
	
	RF mRF;
	short mHostAddr;
	Queue<Packet> mRecvData;
	Queue<Packet> mRecvAck;
	NSyncClock mClock;
		
	// TODO lots of parameters. Builder pattern?
	public RecvThread(RF rf, NSyncClock clock, Queue<Packet> recvAck, Queue<Packet> recvData, short hostAddr) {
		mRF = rf;
		mClock = clock;
		mRecvData = recvData;
		mRecvAck = recvAck;
		mHostAddr = hostAddr;
		Log.i(TAG, "RecvThread initialized");
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
		mRecvAck.add(ackPack);
	}
	
	private void consumeBeacon(Packet beaconPacket) {
		Log.i(TAG, "Consuming B(E)ACON packet");
		mClock.consumeBacon(beaconPacket);
	}
	
	private void consumeData(Packet dataPacket) {
		Log.i(TAG, "Consuming DATA packet");
		mRecvData.add(dataPacket);
	}

}