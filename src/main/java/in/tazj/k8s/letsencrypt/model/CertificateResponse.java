package in.tazj.k8s.letsencrypt.model;

import java.util.Date;
import java.util.List;
import java.util.Map;

import lombok.Value;

/**
 * A signed certificate as returned by the ACME server.
 */
@Value
public class CertificateResponse {
  List<String> domains;
  Map<String, String> certificateFiles;
  Date expiryDate;
  String ca;
}