package in.tazj.k8s.letsencrypt.model;

import lombok.Builder;
import lombok.Value;

/**
 * A request for a signed certificate based upon service annotations.
 */
@Value
@Builder
public class CertificateRequest {
  /** The name of the Kubernetes secret to store the certificate in. */
  String secretName;

  /** The certificate subject name. */
  String certificateName;

  /** Whether the certificate needs to be renewed. */
  Boolean renew;
}
