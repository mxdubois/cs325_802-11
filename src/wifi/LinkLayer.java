package wifi;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import rf.RF;

/**
 * Use this layer as a starting point for your project code.  See {@link Dot11Interface} for more
 * details on these routines.
 * @author richards
 */
public class LinkLayer implements Dot11Interface {

	private static final String TAG = "LinkLayer";
	public static int debugLevel = 1;
	
	public static final int MAX_BYTE_VAL = 255;
	
	private static final int RECV_WAIT_MILLIS = 100;
	
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
	
	
	private RF theRF;           // You'll need one of these eventually
	private short ourMAC; // Our MAC address
	private PrintWriter output; // The output stream we'll write to
	
	private Queue<Packet> mRecvData;
	private PriorityBlockingQueue<Short> mRecvAck;
	private PriorityBlockingQueue<Packet> mSendQueue;
	
	private Thread mRecvThread;
	private RecvThread mRecvr;
	private Thread mSendThread;
	private SendThread mSender;
	
	private NSyncClock mClock;
	private AtomicInteger mStatus;
	
	// STATUS CODES TO IMPLEMENT
	// TODO UNSPECIFIED_ERRORs
	// TODO RF_INIT_FAILED
	// TODO BAD_MAC_ADDRESS ??
	// TODO INSUFFICIENT_BUFFER_SPACE

	/**
	 * Constructor takes a MAC address and the PrintWriter to which our output will
	 * be written.
	 * @param ourMAC  MAC address
	 * @param output  Output stream associated with GUI
	 */
	public LinkLayer(short ourMAC, PrintWriter output) {
		this.ourMAC = ourMAC;
		this.output = output;
		// TODO check if RF init failed?
		theRF = new RF(null, null);
		
		mRecvData = new LinkedList<Packet>();
		mRecvAck = new PriorityBlockingQueue<Short>();
		mSendQueue = new PriorityBlockingQueue<Packet>();
		
		mClock = new NSyncClock();
		
		mRecvr = new RecvThread(theRF, mClock, mRecvAck, mRecvData, ourMAC);
		mRecvThread = new Thread(mRecvr);
		mRecvThread.start();
		
		mSender = new SendThread(theRF, mClock, mStatus, mSendQueue, mRecvAck);
		mSendThread = new Thread(mSender);
		mSendThread.start();
		
		if(debugLevel > 0)
			Log.i(TAG, "Constructor ran.");
	}
	
	// TODO do we need an init method?

	/**
	 * Send method takes a destination, a buffer (array) of data, and the number
	 * of bytes to send.  See docs for full description.
	 */
	public int send(short dest, byte[] data, int len) {
		if(len < 0) {
			setStatus(BAD_BUF_SIZE);
			return -1;
		}
		if(data == null) {
			setStatus(BAD_ADDRESS);
			return -1;
		}
		// Protect ourselves against idiots
		if(data.length < len) {
			setStatus(ILLEGAL_ARGUMENT);
		}
		
		if(debugLevel > 0)
			Log.i(TAG,"Sending "+len+" bytes to "+dest);
		int queued = 0;
		int code = Packet.CTRL_DATA_CODE;
		// We can only wrap Packet.MAX_DATA_BYTES per packet
		// So loop until we've wrapped all the data in packets
		while(queued < len) {
			int toQueue = len - queued;
			toQueue = (int)Math.min(toQueue, Packet.MAX_DATA_BYTES);
			byte[] sendData  = new byte[toQueue];
			System.arraycopy(data, queued, sendData, 0, toQueue);
			Packet packet = new Packet(code, dest, ourMAC, sendData, len);
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
		if(debugLevel > 0)
			Log.i(TAG, "recv() called, waiting for queued data");
		// TODO use a blocking queue. Consider http://stackoverflow.com/a/18375862/1599617
		Packet recvData = null;
		while(recvData == null) {
			if(mRecvData.isEmpty()) {
				try {
					Thread.sleep(RECV_WAIT_MILLIS);
				} catch (InterruptedException e) {
					Log.e(TAG, "Thread interrupted while sleeping on recv()");
					e.printStackTrace();
				}
			} else {
				Log.i(TAG, "Data packet found in recvData queue, returning to caller");
				recvData = mRecvData.poll();
			}
		}
		// Put addresses in Transmission object
		t.setDestAddr(recvData.getDestAddr());
		t.setSourceAddr(recvData.getSrcAddr());
		
		// Ensure no buffer overflow
		// TODO: is this to spec? what do we do with packet data that doesn't fit 
		// in the transmission buffer?
		int dataLength;
		int transBufLength = t.getBuf().length;
		byte[] data = recvData.getData();
		if(data.length <= transBufLength) {
			t.setBuf(recvData.getData());
			dataLength = data.length;
		} else {
			t.setBuf(Arrays.copyOfRange(data, 0, transBufLength));
			dataLength = transBufLength;			
		}		
		return dataLength;
	}

	/**
	 * Returns a current status code.  See docs for full description.
	 */
	public int status() {
		if(debugLevel > 0)
			Log.i(TAG, "Faking a status() return value of 0");
		return mStatus.get();
	}

	/**
	 * Passes command info to your link layer.  See docs for full description.
	 */
	public int command(int cmd, int val) {
		if(debugLevel > 0)
			Log.i(TAG, "Sending command "+cmd+" with value "+val);
		switch(cmd) {
		case 0 : // Options and Settings
			output.println("LinkLayer: Current Settings: \n" + 
					"1. debugLevel: " + debugLevel + "\n" +
					"2. slotSelectionPolicy: " 
						+ mSender.getSlotSelectionPolicy() + "\n" +
					"3. beaconInterval: " + mSender.getBeaconInterval() + "\n");
			break;
		case 1: // Debug Level
			debugLevel = val;
			break;
		case 2: // Slot Selection
			mSender.setSlotSelectionPolicy(val);
			break;
		case 3: // Beacon Interval
			mSender.setBeaconInterval(val);
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
	
}
