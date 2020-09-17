FROM ubuntu:20.04

ENV DEBIAN_FRONTEND noninteractive

# Install prerequisites
RUN apt-get update && \
    apt-get install -y \
        curl \
        git \
        gnupg2 \
        locales \
        nodejs \
        npm \
        # xvfb is needed to run VSCode in headless mode
        xvfb \
        # shared libraries that VSCode depend on:
        libgdk-pixbuf2.0-0 \
        libgtk-3-0 \
        libgbm1 \
        libxss1 \
        libnss3 \
        libasound2 \
        libsecret-1-0 \
    && \
    rm -rf /var/lib/apt/lists/*

# Set up locale
RUN locale-gen en_US.UTF-8
ENV LANG en_US.UTF-8
ENV LANGUAGE en_US:en
ENV LC_ALL en_US.UTF-8

# Install Java
RUN apt-get update && \
    apt-get install -y openjdk-11-jdk && \
    rm -rf /var/lib/apt/lists/*

# Install sbt
RUN echo "deb https://dl.bintray.com/sbt/debian /" | tee -a /etc/apt/sources.list.d/sbt.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
    apt-get update && \
    apt-get install -y sbt && \
    rm -rf /var/lib/apt/lists/*

# Install Z3
RUN apt-get update && \
    apt-get install -y z3 && \
    rm -rf /var/lib/apt/lists/*
# executing `which z3` and setting Z3_EXE based on its result is unfortunately not possible, therefore it is hardcoded
ENV Z3_EXE /usr/bin/z3

# create a non-root user "node" which can be used to run npm as a non-root user
RUN useradd --create-home node
# do not change the user to "node" as we'll need "root" for some commands and only run a few commands as "node"

WORKDIR /home/node
