package wifi;

import java.nio.ByteBuffer;

import rf.RF;

import wifi.LinkLayer;

/**
 * Represents an 802.11~ packet.
 * Also provides methods for parsing a byte array into a packet
 * @author Nathan P
 * TODO: http://henkelmann.eu/2011/02/24/a_curse_on_java_bitwise_operators
 */
public class Packet implements Comparable<Packet>{
	
	private static final String TAG = "Packet";
	
	// TODO wtf are correct values for these?
	public static final long SIFS = RF.aSIFSTime * NSyncClock.NANO_SEC_PER_MS;
	public static final long PIFS = 2*SIFS;
	public static final long DIFS = 4*SIFS;
	public static final long EIFS = 8*SIFS;
	
	public static final short MAX_SEQ_NUM = 4095; // 12 bits of data: (2^12)-1
	public static final int MAX_DATA_BYTES = 2038;
	public static final short BEACON_MAC = (short)(Math.pow(2,16)-1);
	// Packet type codes
	public static final int CTRL_DATA_CODE = 0;
	public static final int CTRL_ACK_CODE = 1;
	public static final int CTRL_BEACON_CODE = 2;
	
	public static final int INVALID_PACKET = -2;

	// Header sizes
	private static final int CONTROL_SIZE = 2;
	private static final int DEST_ADDR_SIZE = 2;
	private static final int SRC_ADDR_SIZE = 2;
	
	private static final int HEADER_SIZE = 6;
	private static final int CRC_SIZE = 4;
	
	private ByteBuffer mPacket;
	private int mPacketSize; // Total packet length

	// TODO CRC should be recomputed anytime a part of the packet is changed
	// Perhaps we can flip a boolean at the end of the constructor to turn this
	// behavior on.. it only needs to happen after the packet is constructed.
	
	public Packet(int type, short dest, short src, byte[] data, int len) {
		// If len exceeds the size of the data buffer, add the entire buffer
		int dataSize = Math.min(len, data.length);

		mPacketSize = HEADER_SIZE + CRC_SIZE + dataSize;
		mPacket = ByteBuffer.allocate(mPacketSize);

		buildHeader(type, dest, src);
		
		// Insert data into packet buffer.
		for(int i = 0; i < dataSize; i++)
			mPacket.put(i + HEADER_SIZE, data[i]);

		computeAndSetCRC();
	}

	/**
	 * Private constructor for building a packet from an array
	 * Use Packet.parse for similar functionality. This constructor does
	 * no error checking, it assumes argument constitutes a valid packet
	 * @param packet
	 */
	private Packet(byte[] packet) {
		mPacketSize = packet.length;
		mPacket = ByteBuffer.wrap(packet);
	}

	private void buildHeader(int type, short dest, short src) {
		byte firstByte = mPacket.get(0);

		// Set type. Shift type 5 bits left remove and trailing bits
		byte typeMask = (byte) ((type << 5) & 0xE0); // 11100000
		firstByte = (byte) (firstByte | typeMask);
		mPacket.put(0, firstByte);
		
		// Set destination
		mPacket.putShort(CONTROL_SIZE, dest);
		// Set source
		mPacket.putShort(CONTROL_SIZE+DEST_ADDR_SIZE, src);

		setRetry(false);
		setSequenceNumber((short)20);
	}

	public void setSequenceNumber(short seqNum) {	
		// TODO: determine behavior. overflow?
		if(seqNum > MAX_SEQ_NUM) 
			return;
		
		// Low can be assigned directly
		byte lowByte = (byte) (seqNum & 0xFF);
		
		byte highByte = mPacket.get(0);
		// Shift right 8 bits to get to the high byte, mask the upper 4 bits
		byte highVal = (byte) ((seqNum >> 8) & 0xFF); 
		highByte = (byte) (highByte | highVal);
		
		mPacket.put(0, highByte);
		mPacket.put(1, lowByte);
	}

	public void setRetry(boolean isRetry) {
		// Retry is 4th bit in first byte
		byte firstByte = (byte) (isRetry ? mPacket.get(0) | 0x10 : mPacket.get(0) & 0xEF);
		mPacket.put(0, firstByte);
	}

	private int computeAndSetCRC() {
		// TODO: for now, return all 1's. Implement CRC if we actually have time
		int crc = 0xFFFFFFFF;
		mPacket.putInt(mPacketSize-CRC_SIZE, crc);
		return crc;
	}
	
	public byte[] getBytes() {
		mPacket.position(0);

		// Copy bytes into a byte array
		byte[] packetBytes = new byte[mPacketSize];
		mPacket.get(packetBytes, 0, mPacketSize);

		return packetBytes;
	}
	
	public int getType() {
		byte firstByte = mPacket.get(0);
		int type = (firstByte >> 5) & 0x7;
		return type;
	}
	
	public boolean isBeacon() {
		return (getType() == CTRL_BEACON_CODE);
	}
	
	// TODO implement, seems like priority should be independent of IFS, idk
	public long getPriority() {
		return (long) getType();
	}
	
	// FIXME implement with window wait times RIFS, SIFS, PIFS, DIFS, AIFS, EIFS
	// we need to decide whether it makes sense to separate this from the packet
	// type (and thus packet class) or not. i.e. is it dependent on factors
	// other than the packet type? I'm esp curious about spec for EIFS here.
	public long getIFS() {
		long ifs = SIFS; // Default to ACK IFS
		if(getType() == CTRL_DATA_CODE) {
			ifs = DIFS;
		}
		return (long) getType();
	}
	
	public boolean isRetry() {
		byte firstByte = mPacket.get(0);
		// Shift 4 bits right and remove any leading bits
		int retry = (firstByte >> 4) & 0x1;
		return retry == 1;
	}
	
	public short getSrcAddr() {
		return mPacket.getShort(CONTROL_SIZE + DEST_ADDR_SIZE);
	}
	
	public short getDestAddr() {
		return mPacket.getShort(CONTROL_SIZE);
	}
	
	public int getDataLen() {
		return mPacketSize - HEADER_SIZE - CRC_SIZE;
	}
	
	public short getSequenceNumber() {
		byte highByte = mPacket.get(0);
		byte lowByte = mPacket.get(1);
		
		// Directly assign the low byte
		short seqNum = lowByte;

		// Shift the high byte up and mask the leading 4 control bits,
		// to get the high 4 bits
		short highVal = (short) ((highByte << 8) & 0xF00);
		
		// OR in the high 4 bits of the sequence number
		seqNum = (short) (seqNum | highVal);
		
		return seqNum;
	}
	
	public byte[] getData() {
		byte[] data;
		// Return empty lists if no data or packet is of insufficient size
		if(mPacketSize <= HEADER_SIZE + CRC_SIZE) {
			data = new byte[0];
		} else {
			data = new byte[getDataLen()];
			Log.i(TAG, "Buffer of length " + getDataLen() + ". Total length " + mPacket.limit());
			for (int i = 0; i < getDataLen(); i++)
	         data[i] = mPacket.get(i+HEADER_SIZE); 
		}
		return data;
	}
	
	public int getCRC() {
		return mPacket.getInt(mPacketSize-CRC_SIZE);
	}

	/**
	 * Parses a byte array into a packet
	 * @param packet The byte array to parse
	 * @return The packet object, or null if packet is invalid
	 */
	public static Packet parse(byte[] packet) {
		Packet newPacket;
		if(packet.length < HEADER_SIZE + CRC_SIZE)
			newPacket = null; // Packet is of insufficient length
		else {
			newPacket = new Packet(packet);
			// Check packet validity (CRC), return null if packet is not valid
			//			if(!newPacket.checkPacketValidity()) newPacket = null;
		}
		return newPacket;
	}
	
	/**
	 * Parses the destination address out of a packet byte array. This should be called
	 * on incoming packets so that we don't have to parse out the entire packet if its 
	 * destination is in fact elsewhere.
	 * @param packet The packet to parse
	 * @return The destination address, or INVALID_PACKET if packet was of insufficient length
	 */
	public static short parseDest(byte[] packet) {
		short dest = INVALID_PACKET;
		if(packet.length > CONTROL_SIZE + DEST_ADDR_SIZE)
			dest = ByteBuffer.wrap(packet, CONTROL_SIZE, DEST_ADDR_SIZE).getShort();
		return dest;
	}

	/**
	 * Compares the CRC against a newly re-computed CRC
	 * @return True if the existing CRC matches the new CRC, false otherwise
	 */
	private boolean checkPacketValidity() {
		boolean isValid = true;
		// The received CRC
		int recvCrc = mPacket.getInt(mPacketSize-CRC_SIZE-1);
		// Our calculated CRC
		int calcCrc = computeAndSetCRC();
		// Return false if these do not match up
		if(calcCrc != recvCrc) isValid = false;

		return isValid;
	}
	
	public String toString() {
		String str = "Packet. " +
					"Type: " + getType() +
					". Retry? " + isRetry() + 
					". Seq num: " + getSequenceNumber() +
					". Src addr: " + getSrcAddr() +
					". Dest addr: " + getDestAddr() +
					". Data length: " + getDataLen() + 
					". CRC: " + getCRC();
		
		return str;		
	}

	@Override
	public int compareTo(Packet p) {
		// Must return int, so can't just subtract long priorities
		int ret = 0;
//		if(getPriority() == p.getPriority()) {
//			// tie break with seq number
//			// TODO this probably doesn't work properly. We need a getIntSequenceNum function
//			ret = (getSequenceNumber() > p.getSequenceNumber()) ? 1 : -1;
//		} else 
		if(getPriority() != p.getPriority()) {
			ret = (getPriority() > p.getPriority()) ? 1 : -1;
		}
		return getType() - p.getType();
	}
}
