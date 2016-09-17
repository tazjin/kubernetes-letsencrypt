package in.tazj.k8s.letsencrypt.util;

/**
 * A runtime exception to be thrown if anything goes wrong during the Letsencrypt signing process.
 */
public class LetsencryptException extends RuntimeException {
  public LetsencryptException(String message) {
    super(message);
  }
}