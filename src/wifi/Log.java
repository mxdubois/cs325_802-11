package wifi;

/**
 * Utility class for printing to console
 * @author Nathan P
 *
 */
public class Log {

	/**
	 * For logging information
	 * @param tag Usually caller's class name
	 * @param message Information message
	 */
	public static void i(String tag, String message) {
		System.out.println(tag + " : " + message);
	}
	
	/**
	 * For logging errors
	 * @param tag Usually caller's class name
	 * @param message Error message
	 */
	public static void e(String tag, String message) {
		System.err.println(tag + " : " + message);
	}
} 
