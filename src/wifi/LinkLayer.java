package wifi;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import rf.RF;

/**
 * Use this layer as a starting point for your project code.  See {@link Dot11Interface} for more
 * details on these routines.
 * @author richards
 */
public class LinkLayer implements Dot11Interface {

	// This layer's mode
	public static final int MODE_STANDARD = 0;
	public static final int MODE_ROUND_TRIP_TEST = 1;
	public static int layerMode;
	
	private static final String TAG = "LinkLayer";
	public static int debugLevel = 1;

	public static final int MAX_BYTE_VAL = 255;
	public static final int MAX_MAC = 65535;

	public static final int SUCCESS = 1;
	public static final int UNSPECIFIED_ERROR = 2;
	public static final int RF_INIT_FAILED = 3;
	public static final int TX_DELIVERED = 4;
	public static final int TX_FAILED = 5;
	public static final int BAD_BUF_SIZE = 6;
	private static final int BAD_ADDRESS = 7;
	public static final int BAD_MAC_ADDRESS = 8;
	public static final int ILLEGAL_ARGUMENT = 9;
	public static final int INSUFFICIENT_BUFFER_SPACE = 10;

	public static final int RECV_DATA_BUFFER_SIZE = 4;
	public static final int RECV_ACK_BUFFER_SIZE = 4;
	public static final int  SEND_ACK_BUFFER_SIZE = 4;
	private static final int MAX_OUT_DATA_PACKETS = 4;


	private RF mRF;   // The physical layer
	private short mMac; // Our MAC address

	private BlockingQueue<Packet> mRecvData;
	private BlockingQueue<Packet> mRecvAck;
	private BlockingQueue<Packet> mSendAckQueue;
	private PriorityBlockingQueue<Packet> mSendQueue;
	

	private Thread mRecvThread;
	private RecvTask mRecvTask;
	private Thread mSendThread;
	private SendTask mSendTask;

	private int mLastRecvDataOffset;
	private Packet mLastRecvData;

	private NSyncClock mClock;
	private AtomicInteger mStatus;


	// STATUS CODES TO IMPLEMENT
	// TODO UNSPECIFIED_ERRORs
	// TODO RF_INIT_FAILED
	// TODO BAD_MAC_ADDRESS ??
	// TODO INSUFFICIENT_BUFFER_SPACE

	/**
	 * Constructor takes a MAC address and the PrintWriter to which our output will
	 * be written. This launches the layer in standard mode
	 * @param ourMAC  MAC address
	 * @param output  Output stream associated with GUI
	 */
	public LinkLayer(short ourMAC, PrintWriter output) {
		this(ourMAC, output, MODE_STANDARD);
	}

	/**
	 * Constructor takes a MAC address, output stream and the layer's mode
	 * @param ourMAC  MAC address
	 * @param output  Output stream associated with GUI
	 * @param mode    Mode to run the layer in, either MODE_STANDARD 
	 *                or MODE_ROUND_TRIP_TEST
	 */
	public LinkLayer(short ourMAC, PrintWriter output, int mode) {

		if(output != null)
			Log.setStream(output); // Write to the GUI if specified

		layerMode = mode;

		mStatus = new AtomicInteger();
		this.mMac = ourMAC;
		// TODO check if RF init failed?
		mRF = new RF(null, null);

		mRecvData = new ArrayBlockingQueue<Packet>(RECV_DATA_BUFFER_SIZE);
		mSendAckQueue = new ArrayBlockingQueue<Packet>(SEND_ACK_BUFFER_SIZE);
		mRecvAck = new ArrayBlockingQueue<Packet>(RECV_ACK_BUFFER_SIZE);
		mSendQueue = new PriorityBlockingQueue<Packet>();

		mClock = new NSyncClock(ourMAC);

		mRecvTask = new RecvTask(mRF, 
								mClock, 
								mSendAckQueue, 
								mRecvAck, 
								mRecvData, 
								ourMAC);
		mRecvThread = new Thread(mRecvTask);
		mRecvThread.start();

		mSendTask = new SendTask(mRF, 
								mClock, 
								mStatus, 
								mSendQueue, 
								mSendAckQueue, 
								mRecvAck, 
								ourMAC);

		mSendThread = new Thread(mSendTask);
		mSendThread.start();

		mLastRecvDataOffset = 0;
		mLastRecvData = null;

		// Queue packets if we're in RTT test mode
		if(layerMode == MODE_ROUND_TRIP_TEST) {
			queueRTTPackets();
		}
	}

	// TODO do we need an init method?

	/**
	 * Send method takes a destination, a buffer (array) of data, and the number
	 * of bytes to send.  See docs for full description.
	 */
	public int send(short dest, byte[] data, int len) {
		// If we're not in standard mode, don't accept any packets
		if(layerMode != MODE_STANDARD)
			return 0;
		
		// TODO figure out when BAD_ADDRESS should be set. With the 
		// address specified as a short, and all short values valid
		// addresses, I don't see how we'd ever get a bad address
		if(len < 0) {
			setStatus(BAD_BUF_SIZE);
			return -1;
		}

		// Protect ourselves against idiots
		if(data.length < len) {
			setStatus(ILLEGAL_ARGUMENT);
			return -1;
		}

		// Don't queue more than MAX_OUT_DATA_PACKETS
		if(mSendQueue.size() > MAX_OUT_DATA_PACKETS) {		
			Log.e(TAG, MAX_OUT_DATA_PACKETS + 
					" data packets already queued. Ignoring a new packet");
			return 0;
		}

		Log.d(TAG,"Queueing "+len+" bytes to "+dest);
		// In reality, we should only get data packets from the layer above.
		// To work with the GUI Bcast button though, we have to accept and
		// handle broadcast packets from above as well.
		int code = (dest == NSyncClock.BEACON_ADDR) ?
				Packet.CTRL_BEACON_CODE : Packet.CTRL_DATA_CODE;
		// We can only wrap Packet.MAX_DATA_BYTES per packet
		// So loop until we've wrapped all the data in packets
		int queued = 0;
		while(queued < len) {
			int toQueue = len - queued;
			toQueue = (int)Math.min(toQueue, Packet.MAX_DATA_BYTES);
			byte[] sendData  = new byte[toQueue];
			System.arraycopy(data, queued, sendData, 0, toQueue);
			Packet packet = new Packet(code, 
										dest, 
										mMac, 
										sendData, 
										len, 
										mClock.time());
			// Queue it for sending
			mSendQueue.offer(packet);
			queued = queued + toQueue;
		}
		return queued;
	}

	/**
	 * Recv method blocks until data arrives, then writes it an address info into
	 * the Transmission object.  See docs for full description.
	 */
	public int recv(Transmission t) {
		Log.i(TAG, "recv() called, waiting for queued data");

		// Only take a new packet if we've fully consumed the last packet
		if(mLastRecvData == null) {
			try {
				mLastRecvData = mRecvData.take();
			} catch (InterruptedException e) {
				Log.e(TAG, "recv() interrupted while blocking on take()");
				e.printStackTrace();
				return 0;
			}
		}

		// Put addresses in Transmission object
		t.setDestAddr(mLastRecvData.getDestAddr());
		t.setSourceAddr(mLastRecvData.getSrcAddr());

		int dataLength;
		int transBufLength = t.getBuf().length;
		byte[] data = mLastRecvData.getData();
		// Check if data will fit in the Transmission buffer
		if(data.length - mLastRecvDataOffset <= transBufLength) {
			t.setBuf(Arrays.copyOfRange(data, mLastRecvDataOffset, data.length));
			dataLength = data.length - mLastRecvDataOffset;

			// Reset offset and consume packet
			mLastRecvDataOffset = 0;
			mLastRecvData = null;
		} else {
			// If packet length exceeds buffer length, copy in as much as we can
			t.setBuf(Arrays.copyOfRange(data, mLastRecvDataOffset, transBufLength));
			dataLength = transBufLength;  

			// Set offset into packet data for next call, and don't consume packet
			mLastRecvDataOffset = mLastRecvDataOffset + transBufLength;
		}                
		return dataLength;
	}

	/**
	 * Returns a current status code.  See docs for full description.
	 */
	public int status() {
		return mStatus.get();
	}
	
	/**
	 * Passes command info to your link layer.  See docs for full description.
	 */
	public int command(int cmd, int val) {

		Log.i(TAG, "Sending command "+cmd+" with value "+val);
		switch(cmd) {
		case 0 : // Options and Settings
			Log.i(TAG, "LinkLayer: Current Settings: \n" + 
					"1. debugLevel: " + debugLevel + "\n" +
					"2. slotSelectionPolicy: " 
					+ mSendTask.getSlotSelectionPolicy() + "\n" +
					"3. beaconInterval: " + mClock.getBeaconInterval() + "\n");
			break;
		case 1: // Debug Level
			debugLevel = val;
			break;
		case 2: // Slot Selection
			mSendTask.setSlotSelectionPolicy(val);
			break;
		case 3: // Beacon Interval
			mClock.setBeaconInterval(val);
			break;		
		}
		return 0;
	}

	// PACKAGE PRIVATE / PROTECTED METHODS
	//-------------------------

	// PRIVATE METHODS
	//------------------
	private void setStatus(int code) {
		mStatus.set(code);
		if(debugLevel > 0) {
			switch(code) {
			case SUCCESS:
				Log.i(TAG, "Initial value if 802_init is successful");
				break;
			case UNSPECIFIED_ERROR:
				Log.e(TAG, "General error code");
				break;
			case RF_INIT_FAILED:
				Log.e(TAG, "Attempt to initialize RF layer failed");
				break;
			case TX_DELIVERED:
				Log.i(TAG, "Last transmission was acknowledged");
				break;
			case TX_FAILED:
				Log.e(TAG, "Last transmission was abandoned after unsuccessful delivery attempts");
				break;
			case BAD_BUF_SIZE:
				Log.e(TAG, "Buffer size was negative");
				break;
			case BAD_ADDRESS:
				Log.e(TAG, "Pointer to a buffer or address was NULL");
				break;
			case BAD_MAC_ADDRESS:
				Log.e(TAG, "Illegal MAC address was specified");
				break;
			case ILLEGAL_ARGUMENT:
				Log.e(TAG, "One or more arguments are invalid");
				break;
			case INSUFFICIENT_BUFFER_SPACE:
				Log.e(TAG, "Outgoing transmission rejected due to insufficient buffer space");
				break;
			}

		}
	}

	/**
	 * Queues up packets for a round trip time test
	 */
	private void queueRTTPackets() {
		for(int i = 0; i < RoundTripTimeTest.NUM_RTT_PACKETS; i++) {
			Packet dataPacket = new Packet(Packet.CTRL_DATA_CODE, 
					RoundTripTimeTest.RTT_TEST_DEST_MAC, mMac, 
					new byte[] {(byte)i}, 1, mClock.time());
			
			// Wait until there's room in the queue. Sleeping on this thread
			// shouldn't cause any problems - if we're in RTT mode there won't be
			// any other functionality to block
			while(mSendQueue.size() >= MAX_OUT_DATA_PACKETS) {
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					Log.e(TAG, e.getMessage());
				}
			}
			mSendQueue.offer(dataPacket);
		}
	}
}
