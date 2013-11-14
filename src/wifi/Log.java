package wifi;

import java.io.PrintWriter;

/**
 * Utility class for printing to console
 * @author Nathan P
 *
 */
public class Log {

	private static PrintWriter output = null;
	
	public static void setStream(PrintWriter stream) {
		output = stream;
	}
	
	/**
	 * For logging information
	 * @param tag Usually caller's class name
	 * @param message Information message
	 */
	public static void i(String tag, String message) {
		String str = tag + " : " + message;
		if(output == null)
			System.out.println(str);
		else 
			output.println(str);
	}
	
	/**
	 * For logging errors
	 * @param tag Usually caller's class name
	 * @param message Error message
	 */
	public static void e(String tag, String message) {
		String str = tag + " : " + message;
		if(output == null)
			System.err.println(str);
		else 
			output.println(str);
	}
	
	/**
	 * For logging debug messages
	 * @param tag Usually caller's class name
	 * @param message Error message
	 */
	public static void d(String tag, String message) {
		if(LinkLayer.debugLevel > 0)
			i(tag, message);
	}
} 
