`wolf-network-selfsigned.p12` is an authored, self-signed RSA certificate for
127.0.0.1/localhost, created solely for the Android loopback TLS test. Its public
test password is `wolf-fixture`; it is never used or trusted by the app.

The test runs a local TLS server and proves that the real download client rejects
an untrusted certificate even when Wolf's optional `VERIFY_TLS` marker is absent.
The HTTP, POST, cancellation and failure tests also use actual Android sockets.
No production credential, remote service or user game asset is included.
