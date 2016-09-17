Kubernetes Letsencrypt Controller
=================================

This implements a Kubernetes controller that automatically requests and refreshes 
Letsencrypt certificates based on service annotations.

This controller currently only supports _Amazon Route 53_ as the DNS target.

## Setup

Launch the controller into your cluster using

```
kubectl apply -f letsencrypt-controller.yaml
```

This will use a release or snapshot version (depending on your git checkout) hosted on my Docker Hub
account.

The pod must run with the permissions required for updating DNS records in the hosted zones that you
maintain. Consider using a project such as [kube2iam][] to grant
permissions to individual pods.

Please refer to the 'Building' section for using your own image.

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
and store it as `www-yourdomain-com-tls`.

The certificate secret will contain three files named `certificate.pem`, `chain.pem` and `key.pem`.
You can [mount these][] into whatever application you use to terminate TLS.

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
