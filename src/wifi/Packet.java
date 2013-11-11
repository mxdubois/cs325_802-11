package wifi;

import java.nio.ByteBuffer;

import wifi.LinkLayer;

/**
 * Represents an 802.11~ packet.
 * Also provides methods for parsing a byte array into a packet
 * @author Nathan P
 *
 */
public class Packet {
	
	public static final int MAX_SEQ_NUM = 4095; // (2^12)-1

	// Packet type codes
	public static final int CTRL_DATA_CODE = 0;
	public static final int CTRL_ACK_CODE = 1;
	public static final int CTRL_BEACON_CODE = 2;

	private static final int HEADER_SIZE = 8;
	private static final int CRC_SIZE = 2;

	ByteBuffer mPacket;

	private int mPacketSize; // Total packet length

	public Packet(int type, short dest, byte[] data, int len) {
		// If len exceeds the size of the data buffer, add the entire buffer
		int dataSize = Math.min(len, data.length);

		mPacketSize = HEADER_SIZE + CRC_SIZE + dataSize;
		mPacket = ByteBuffer.allocate(mPacketSize);

		buildHeader(type, dest);

		// Insert data into packet buffer.
		for(int i = 0; i < dataSize; i++)
			mPacket.put(i + HEADER_SIZE, data[i]);

		computeAndSetCRC();
	}

	private Packet(byte[] packet) {
		mPacketSize = packet.length;
		mPacket = ByteBuffer.wrap(packet);
	}

	private void buildHeader(int type, short dest) {
		byte firstByte = mPacket.get(0);

		// Shift type 5 bits left and set
		int typeMask = type << 5;
		firstByte = (byte) (firstByte | typeMask);

		setRetry(false);
		setSequenceNum(0);
	}

	private void setSequenceNum(int seqNum) {		
		
		// TODO: determine behavior. overflow?
		if(seqNum > MAX_SEQ_NUM) 
			return;
		
		byte firstByte = mPacket.get(0);
		byte secondByte = mPacket.get(1);

		// Highest 4 bits of sequence number are in first byte
		int firstByteVal = seqNum / LinkLayer.MAX_BYTE_VAL; 
		// Lowest 8 bytes of sequence number are in second byte
		int secondByteVal = seqNum % LinkLayer.MAX_BYTE_VAL; 

		firstByte = (byte) (firstByte | firstByteVal);
		secondByte = (byte) secondByteVal;
		
		mPacket.put(0, firstByte);
		mPacket.put(1, secondByte);
	}

	public byte[] getBytes() {
		mPacket.position(0);

		// Copy bytes into a byte array
		byte[] packetBytes = new byte[mPacketSize];
		mPacket.get(packetBytes, 0, mPacketSize);

		return packetBytes;
	}

	public void setRetry(boolean isRetry) {
		// Retry is 4th bit in first byte
		// TODO: http://henkelmann.eu/2011/02/24/a_curse_on_java_bitwise_operators
		byte firstByte = (byte) (isRetry ? mPacket.get(0) | 0x10 : mPacket.get(0) & 0xEF);
		mPacket.put(0, firstByte);
	}

	private int computeAndSetCRC() {
		// TODO: for now, return all 1's. Implement CRC if we actually have time
		int crc = 0 | 0xFFFFFFFF;
		mPacket.putInt(mPacketSize-CRC_SIZE-1, crc);
		return crc;
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
}
