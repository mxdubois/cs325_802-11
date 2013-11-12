package wifi;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.PriorityQueue;
import java.util.Queue;

import rf.RF;

/**
 * Use this layer as a starting point for your project code.  See {@link Dot11Interface} for more
 * details on these routines.
 * @author richards
 */
public class LinkLayer implements Dot11Interface {

	private static final String TAG = "LinkLayer";
	
	public static final int MAX_BYTE_VAL = 255;
	
	private static final int RECV_WAIT_MILLIS = 100;
	
	private RF theRF;           // You'll need one of these eventually
	private short ourMAC; // Our MAC address
	private PrintWriter output; // The output stream we'll write to
	
	private Queue<Packet> mRecvData;
	private Queue<Packet> mRecvAck;
	private PriorityQueue<Packet> mSendQueue;
	
	private Thread mRecvThread;
	private Thread mSendThread;
	
	private NSyncClock mClock;

	/**
	 * Constructor takes a MAC address and the PrintWriter to which our output will
	 * be written.
	 * @param ourMAC  MAC address
	 * @param output  Output stream associated with GUI
	 */
	public LinkLayer(short ourMAC, PrintWriter output) {
		this.ourMAC = ourMAC;
		this.output = output;      
		theRF = new RF(null, null);
		
		mRecvData = new LinkedList<Packet>();
		mRecvAck = new LinkedList<Packet>();
		mSendQueue = new PriorityQueue<Packet>();
		
		mClock = new NSyncClock();
		
		mRecvThread = new Thread(new RecvThread(theRF, mClock, mRecvAck, mRecvData, ourMAC));
		mRecvThread.start();
		
		output.println("LinkLayer: Constructor ran.");
	}

	/**
	 * Send method takes a destination, a buffer (array) of data, and the number
	 * of bytes to send.  See docs for full description.
	 */
	public int send(short dest, byte[] data, int len) {
		output.println("LinkLayer: Sending "+len+" bytes to "+dest);
		theRF.transmit(data);
		return len;
	}

	/**
	 * Recv method blocks until data arrives, then writes it an address info into
	 * the Transmission object.  See docs for full description.
	 */
	public int recv(Transmission t) {
		Log.i(TAG, "recv() called, waiting for queued data");
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
		output.println("LinkLayer: Faking a status() return value of 0");
		return 0;
	}

	/**
	 * Passes command info to your link layer.  See docs for full description.
	 */
	public int command(int cmd, int val) {
		output.println("LinkLayer: Sending command "+cmd+" with value "+val);
		return 0;
	}

	// PRIVATE METHODS
	//------------------
}
