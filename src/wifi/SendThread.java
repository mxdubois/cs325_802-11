package wifi;

public class SendThread implements Runnable {
	
	// TODO lots of parameters needed here. Builder pattern?
	public SendThread() {
		
	}
	
	@Override
	public void run() {
		// TODO Auto-generated method stub

	}

	public void setBackoff(int tries) {
		
	}
	
	public void setBeaconInterval(long interval) {
		
	}
	
	public void setSlotSelectionPolicy(int policy) {
		
	}
	
	// TODO might neet to return something
	public void transmit(Packet p) {
		
	}
	
	public Packet buildBeacon() {
		// TODO fixme
		return new Packet(Packet.CTRL_BEACON_CODE, (short)0, (short)0, new byte[10], 1);
	}
	
	
	
}
