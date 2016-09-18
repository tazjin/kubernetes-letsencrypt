Kubernetes Letsencrypt Controller
=================================

[![Build Status](https://travis-ci.org/tazjin/kubernetes-letsencrypt.svg?branch=master)](https://travis-ci.org/tazjin/kubernetes-letsencrypt)

This implements a Kubernetes controller that automatically requests and refreshes Letsencrypt
certificates based on service annotations.

This controller currently supports _Amazon Route 53_ and _Google Cloud DNS_ as the DNS targets.



## Setup

Launch the controller into your cluster using

```
kubectl apply -f letsencrypt-controller.yaml
```

This will use a release or snapshot version (depending on your git checkout) hosted on my Docker Hub
account.

The pod must run with the permissions required for updating records in the DNS zones that you
maintain.

On AWS, consider using a project such as [kube2iam][] to grant permissions to individual pods.

Please refer to the 'Building' section for using your own image.

## Configuration

The controller currently supports two configuration options via environment variables:

* `ACME_URL`: This can be set to an alternative ACME directory URL, for example the Letsencrypt
  staging server if you only want to test out the controller.
* `CLOUD_PLATFORM`: This can be set to either `GCP` or `AWS` to override the automatic platform
  detection. You can use this to for example use Route53 as the DNS backend with a cluster running
  on Google's Cloud Platform.
  If you override this option you must provide credentials for the DNS backend, for example via the
  environment variables for the [Google Cloud Java SDK][] or the [AWS Java SDK][]

## Usage

Simply add an annotation to your services, for example:

```
---
apiVersion: v1
kind: Service
metadata:
  name: my-app
  labels:
    app: my-app
  annotations:
    acme/certificate: www.yourdomain.com
spec:
  type: LoadBalancer
  selector:
    app: my-app
  ports:
    - port: 443
      name: https
```

The controller will notice this and, assuming you have a matching hosted zone, create a certificate
and store it as a secret named `www-yourdomain-com-tls`.

The certificate secret will contain three files named `certificate.pem`, `chain.pem` and `key.pem`.
You can [mount these][] into whatever application you use to terminate TLS.

The secret will always be created in the same namespace as your service. Removing the annotation
will **never** remove a secret.

## Certificate renewals

Every secret will be annotated with the certificate expiry date. The controller will refresh the
certificate and update the secret once the expiry date is close.

Currently this update happens within 1-2 days of expiry. The reason for the short time-interval is
that Letsencrypt has a long-term desire to reduce the certificate lifespans so I am trying to be
future-proof here.

## Overview

The controller first attempts to find a secret in the Kubernetes `kube-system` namespace with the
name `letsencrypt-keypair`. This secret is expected to contain the key pair used for authentication
with the Letsencrypt service.

If no such key pair is found the controller will create one and store it as a secret.

On startup the controller will check all existing services for an annotation

## Building

All build lifecycle steps are handled in Maven. After determining your desired image name, you can
build and push a new image with:

```
# Builds, tests and creates shaded JAR
mvn package

# Creates and pushes Docker image
mvn -Ddocker.imageName='your/image-name' docker:build docker:push
```

[kube2iam]: https://github.com/jtblin/kube2iam
[mount these]: http://kubernetes.io/docs/user-guide/secrets/#using-secrets-as-files-from-a-pod
[Google Cloud Java SDK]: https://github.com/GoogleCloudPlatform/google-cloud-java#authentication
[AWS Java SDK]: https://docs.aws.amazon.com/java-sdk/latest/developer-guide/credentials.html