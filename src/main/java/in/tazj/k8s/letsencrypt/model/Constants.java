package in.tazj.k8s.letsencrypt.model;

/**
 * Names of annotations used by the controller.
 */
final public class Constants {
  final public static String REQUEST_ANNOTATION = "acme/certificate";
  final public static String EXPIRY_ANNOTATION = "acme/expiryDate";
  final public static String SYSTEM_NAMESPACE = "kube-system";
}
