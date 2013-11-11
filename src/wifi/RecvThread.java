package wifi;

import java.util.PriorityQueue;

import rf.RF;

/**
 * The RecvThread class is responsible for monitoring the network 
 * @author Nathan P
 *
 */
public class RecvThread implements Runnable {

	RF mRF;
	short mHostAddr;
	PriorityQueue<Packet> mRecvData;
	PriorityQueue<Packet> mRecvAck;
	NSyncClock mClock;
	
	
	
	// TODO lots of parameters. Builder pattern?
	public RecvThread(RF rf, NSyncClock clock, PriorityQueue<Packet> recvAck, PriorityQueue<Packet> recvData, short hostAddr) {
		mRF = rf;
		mClock = clock;
		mRecvData = recvData;
		mRecvAck = recvAck;
		mHostAddr = hostAddr;
	}
	
	@Override
	public void run() {
		// TODO: a stopping mechanism
		// http://stackoverflow.com/questions/10961714/how-to-properly-stop-the-thread-in-java
		while(true) {
			byte[] packet = mRF.receive();
			short dest = Packet.parseDest(packet);
			if(dest == mHostAddr) { // Only consume packets sent to this host
				int type = Packet.parseType(packet);
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
	
	private void consumeAck(byte[] ackPacket) {
		mRecvAck.add(Packet.parse(ackPacket));
	}
	
	private void consumeBeacon(byte[] beaconPacket) {
		mClock.consumeBacon(Packet.parse(beaconPacket));
	}
	
	private void consumeData(byte[] dataPacket) {
		mRecvData.add(Packet.parse(dataPacket));
	}

}
