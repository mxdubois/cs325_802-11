package wifi;

import java.util.Random;

public class Utilities {

	public static final Random random = new Random();
	
	private Utilities() {
		// no instance for you!
	}
	
	/**
	 * Returns a random long between zero and n
	 * Taken from: http://stackoverflow.com/questions/2546078/java-random-long-number-in-0-x-n-range
	 * @param n - long upper bound exclusive
	 * @return
	 */
	public static long nextLong(long n) {
		// error checking and 2^x checking removed for simplicity.
		long bits, val;
		do {
			bits = (random.nextLong() << 1) >>> 1;
			val = bits % n;
		} while (bits-val+(n-1) < 0L);
			return val;
		}
	}
