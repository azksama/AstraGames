FROM ubuntu:22.04@sha256:076e7bf82319619f28dd0aa9c02ab34b055ad37100395b4f49a2ffba04fb5e11
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends build-essential cmake curl ca-certificates wget xxd ruby-full autoconf automake libtool pkg-config python3 git unzip bison && rm -rf /var/lib/apt/lists/*
RUN curl -fL --retry 3 https://dl.google.com/android/repository/android-ndk-r29-linux.zip -o /tmp/ndk.zip && echo '87e2bb7e9be5d6a1c6cdf5ec40dd4e0c6d07c30b  /tmp/ndk.zip' | sha1sum -c - && unzip -q /tmp/ndk.zip -d /opt && rm /tmp/ndk.zip
ENV ANDROID_NDK_HOME=/opt/android-ndk-r29 ARCH=linux-x86_64
ENV PATH=/opt/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH
WORKDIR /work
