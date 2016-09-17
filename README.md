Kubernetes Letsencrypt Controller
=================================

This implements a Kubernetes controller that automatically requests and refreshes 
Letsencrypt certificates based on service annotations.

This controller currently only supports _Amazon Route 53_ as the DNS target.

The controller will create and store a Kubernetes secret containing the Letsencrypt
account used to retrieve the certificates.

## TODO

* Watch created secrets and renew automatically
* Use a third-party resource instead of service annotations
* Implement Google Cloud DNS support
* Detect cloud automatically
* Support different namespaces (currently everything is in default)