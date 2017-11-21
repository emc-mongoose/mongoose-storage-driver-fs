[![master](https://img.shields.io/travis/emc-mongoose/mongoose-storage-driver-fs/master.svg)](https://travis-ci.org/emc-mongoose/mongoose-storage-driver-fs)
[![downloads](https://img.shields.io/github/downloads/emc-mongoose/mongoose-storage-driver-fs/total.svg)](https://github.com/emc-mongoose/mongoose-storage-driver-fs/releases)
[![release](https://img.shields.io/github/release/emc-mongoose/mongoose-storage-driver-fs.svg)]()
[![Docker Pulls](https://img.shields.io/docker/pulls/emcmongoose/mongoose-storage-driver-fs.svg)](https://hub.docker.com/r/emcmongoose/mongoose-storage-driver-fs/)

[Mongoose](https://github.com/emc-mongoose/mongoose-base)'s filesystem
storage driver

# Introduction

The storage driver implementation may be used to perform file
non-blocking I/O. The storage driver extends the Mongoose's
[Abstract NIO Storage Driver](https://github.com/emc-mongoose/mongoose-base/wiki/v3.6-Extensions#22-nio-storage-driver).

Typically used to test CIFS/HDFS/NFS shares mounted locally. However,
testing distributed filesystems using generic filesystem storage drivers
may be not accurate due to additional VFS layer. The measured
rates may be:
* Inadequately low due to frequent system calls
* Higher than network bandwidth due to local caching by VFS

# Features

# Usage

Latest stable pre-built jar file is available at:
https://github.com/emc-mongoose/mongoose-storage-driver-fs/releases/download/latest/mongoose-storage-driver-fs.jar
This jar file may be downloaded manually and placed into the `ext`
directory of Mongoose to be automatically loaded into the runtime.

```bash
java -jar mongoose-<VERSION>/mongoose.jar \
    --storage-driver-type=fs \
    ...
```

## Docker

### Standalone

```bash
docker run \
    --network host \
    --entrypoint mongoose \
    emcmongoose/mongoose-storage-driver-fs \
    -jar /opt/mongoose/mongoose.jar \
    --storage-type=fs \
    ...
```

### Distributed

#### Drivers

```bash
docker run \
    --network host \
    --expose 1099 \
    emcmongoose/mongoose-storage-driver-service-fs
```

#### Controller

```bash
docker run \
    --network host \
    --entrypoint mongoose \
    emcmongoose/mongoose-base \
    -jar /opt/mongoose/mongoose.jar \
    --storage-driver-remote \
    --storage-driver-addrs=<ADDR1,ADDR2,...> \
    --storage-driver-type=fs \
    ...
```

## Advanced

### Sources

```bash
git clone https://github.com/emc-mongoose/mongoose-storage-driver-fs.git
cd mongoose-storage-driver-fs
```

### Test

```
./gradlew clean test
```

### Build

```bash

./gradlew clean jar
```

### Embedding

```groovy
compile group: 'com.github.emc-mongoose', name: 'mongoose-storage-driver-fs', version: '<VERSION>'
```
